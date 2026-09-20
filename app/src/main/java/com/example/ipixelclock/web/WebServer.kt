package com.example.ipixelclock.web

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.ipixelclock.render.PixelCanvas
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArraySet
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * The HTTP + WebSocket server behind the web interface.
 *
 * Structure follows `StreamRelay` in android-streaming-server — one accept loop,
 * a thread per connection, hand-rolled request parsing, and the same WebSocket
 * framing — because that code has been in service on a phone for a while and
 * pulling in a framework for eleven routes would be silly.
 *
 * **On the port.** An unrooted Android app cannot bind anything below 1024: the
 * kernel refuses privileged ports to unprivileged UIDs, and Android has no
 * setcap or authbind to grant an exception. So port 80 is not available, and the
 * server takes 8080 by preference, then 8000 and 8888, then whatever port it
 * last managed to get, then a random high one. Whatever it lands on is reported
 * back so the UI can show the real URL rather than a hopeful one.
 *
 * One `GET /ws` connection per open page carries preview frames and state
 * pushes; everything else is a plain request.
 */
class WebServer(
    private val context: Context,
    private val auth: Auth,
    private val api: Api
) {

    /** The bound port, 0 while down. */
    @Volatile
    var port = 0
        private set

    /** Port to try first; the last one that worked. */
    @Volatile
    var preferredPort = 8080

    /** Fired when the bound port changes, so it can be persisted and shown. */
    @Volatile
    var onPortChanged: ((Int) -> Unit)? = null

    private var server: ServerSocket? = null
    private val clients = CopyOnWriteArraySet<WsClient>()

    /** Cache of the gzipped static assets; they never change at runtime. */
    private val assetCache = HashMap<String, ByteArray>()

    // ------------------------------------------------------------- lifecycle

    fun start() {
        stop()
        thread(isDaemon = true, name = "ipixel-web-bind") {
            val ss = bind()
            if (ss == null) {
                Log.e(TAG, "could not bind any port")
                return@thread
            }
            server = ss
            port = ss.localPort
            Log.i(TAG, "listening on $port")
            onPortChanged?.invoke(port)
            accept(ss)
        }
    }

    fun stop() {
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
        port = 0
        for (c in clients) c.close()
        clients.clear()
    }

    private fun bind(): ServerSocket? {
        val candidates = ArrayList<Int>()
        if (preferredPort in 1024..65535) candidates.add(preferredPort)
        for (p in STANDARD_PORTS) if (p !in candidates) candidates.add(p)
        repeat(200) { candidates.add(Random.nextInt(PORT_MIN, PORT_MAX + 1)) }
        for (p in candidates) {
            try {
                return ServerSocket(p)
            } catch (_: Exception) {
                // Taken. Next.
            }
        }
        return null
    }

    private fun accept(ss: ServerSocket) {
        while (!ss.isClosed) {
            try {
                val socket = ss.accept()
                thread(isDaemon = true, name = "ipixel-web") {
                    try {
                        handle(socket)
                    } catch (e: Exception) {
                        Log.d(TAG, "connection ended: ${e.message}")
                    } finally {
                        try {
                            socket.close()
                        } catch (_: Exception) {
                        }
                    }
                }
            } catch (_: Exception) {
                // A closed server socket ends the loop; anything else retries.
                if (ss.isClosed) break
            }
        }
    }

    // ------------------------------------------------------------- the frames

    /**
     * Pushes a rendered frame to every open page.
     *
     * Wire format is a binary WebSocket message: `u8 type=1, u16 w, u16 h`, then
     * row-major RGB888. At 144x16 that is 6.9 kB, which is nothing on a LAN and
     * saves both ends the cost of PNG encoding thirty times a second.
     */
    fun broadcastFrame(canvas: PixelCanvas) {
        if (clients.isEmpty()) return
        val rgb = canvas.toRgb888()
        val msg = ByteArray(5 + rgb.size)
        msg[0] = 1
        msg[1] = (canvas.width shr 8).toByte()
        msg[2] = canvas.width.toByte()
        msg[3] = (canvas.height shr 8).toByte()
        msg[4] = canvas.height.toByte()
        System.arraycopy(rgb, 0, msg, 5, rgb.size)
        for (c in clients) c.sendBinary(msg)
    }

    /** Pushes a state change (connection, settings, weather) to every page. */
    fun broadcastState(json: JSONObject) {
        if (clients.isEmpty()) return
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        for (c in clients) c.sendText(bytes)
    }

    val viewerCount: Int get() = clients.size

    // ------------------------------------------------------------- the routes

    private fun handle(socket: Socket) {
        socket.soTimeout = 15000
        val pin = PushbackInputStream(socket.getInputStream(), 16)
        val ins = DataInputStream(pin)
        val out = socket.getOutputStream()

        val requestLine = readLine(ins, 4096) ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0].uppercase()
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "")

        val headers = HashMap<String, String>()
        while (true) {
            val h = readLine(ins, 8192) ?: return
            if (h.isEmpty()) break
            val i = h.indexOf(':')
            if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
        }

        val source = socket.inetAddress?.hostAddress ?: "?"
        val loopback = socket.inetAddress?.isLoopbackAddress == true
        val token = cookie(headers["cookie"], Auth.COOKIE)
        val authed = loopback || auth.isValidSession(token)

        // ---- the WebSocket upgrade
        if (path == "/ws") {
            if (!authed) {
                writeHttp(out, "401 Unauthorized", "text/plain", "login required".toByteArray())
                return
            }
            val wsKey = headers["sec-websocket-key"]
            if (wsKey == null) {
                writeHttp(out, "400 Bad Request", "text/plain", "not a websocket".toByteArray())
                return
            }
            serveWebSocket(socket, out, ins, wsKey)
            return
        }

        val body = readBody(ins, headers)

        // ---- routes that do not need a session
        when {
            // The stylesheet and the script hold no secrets, and the login and
            // locked pages need them to look like anything at all.
            path == "/app.css" -> {
                writeHttp(
                    out, "200 OK", "text/css; charset=utf-8",
                    asset("app.css") ?: ByteArray(0), gzip = acceptsGzip(headers)
                )
                return
            }
            path == "/app.js" -> {
                writeHttp(
                    out, "200 OK", "application/javascript; charset=utf-8",
                    asset("app.js") ?: ByteArray(0), gzip = acceptsGzip(headers)
                )
                return
            }
            path == "/favicon.ico" -> {
                writeHttp(out, "404 Not Found", "text/plain", ByteArray(0))
                return
            }
            path == "/api/login" && method == "POST" -> {
                serveLogin(out, source, body)
                return
            }
            path == "/api/logout" && method == "POST" -> {
                auth.dropSession(token)
                writeJson(out, "200 OK", JSONObject().put("ok", true), clearCookie = true)
                return
            }
            path == "/api/hello" -> {
                // Enough for the login page to render, and nothing more: whether
                // a password exists is not a secret, the password is.
                writeJson(
                    out, "200 OK", JSONObject()
                        .put("configured", auth.isConfigured)
                        .put("authed", authed)
                        .put("name", "iPixel Clock")
                )
                return
            }
        }

        // ---- everything else needs a session
        if (!authed) {
            if (!auth.isConfigured) {
                // Never fall open. An un-provisioned clock refuses the network
                // and says where to set the password.
                writeHttp(
                    out, "403 Forbidden", "text/html; charset=utf-8",
                    asset("locked.html") ?: LOCKED_FALLBACK.toByteArray()
                )
                return
            }
            if (path.startsWith("/api/")) {
                writeJson(out, "401 Unauthorized", JSONObject().put("error", "login required"))
            } else {
                writeHttp(
                    out, "200 OK", "text/html; charset=utf-8",
                    asset("login.html") ?: LOGIN_FALLBACK.toByteArray(),
                    gzip = acceptsGzip(headers)
                )
            }
            return
        }

        // ---- the application
        try {
            serveAuthed(method, path, query, body, headers, out)
        } catch (e: Exception) {
            Log.w(TAG, "$method $path failed", e)
            writeJson(
                out, "500 Internal Server Error",
                JSONObject().put("error", e.message ?: "failed")
            )
        }
    }

    private fun serveAuthed(
        method: String,
        path: String,
        query: String,
        body: ByteArray,
        headers: Map<String, String>,
        out: OutputStream
    ) {
        val gz = acceptsGzip(headers)
        when {
            path == "/" || path == "/index.html" ->
                writeHttp(out, "200 OK", "text/html; charset=utf-8",
                    asset("index.html") ?: NOT_BUILT.toByteArray(), gzip = gz)

            path == "/app.css" ->
                writeHttp(out, "200 OK", "text/css; charset=utf-8",
                    asset("app.css") ?: ByteArray(0), gzip = gz)

            path == "/app.js" ->
                writeHttp(out, "200 OK", "application/javascript; charset=utf-8",
                    asset("app.js") ?: ByteArray(0), gzip = gz)

            path == "/api/state" ->
                writeJson(out, "200 OK", api.state())

            path == "/api/schema" ->
                writeJson(out, "200 OK", api.schema())

            path == "/api/settings" && method == "POST" ->
                writeJson(out, "200 OK", api.patchSettings(jsonBody(body)))

            path == "/api/power" && method == "POST" ->
                writeJson(out, "200 OK", api.power(jsonBody(body)))

            path == "/api/brightness" && method == "POST" ->
                writeJson(out, "200 OK", api.brightness(jsonBody(body)))

            path == "/api/detect" && method == "POST" ->
                writeJson(out, "200 OK", api.detect(jsonBody(body)))

            path == "/api/message" && method == "POST" ->
                writeJson(out, "200 OK", api.message(jsonBody(body)))

            path == "/api/preview.png" -> {
                val png = api.previewPng()
                if (png == null) {
                    writeHttp(out, "503 Service Unavailable", "text/plain",
                        "no frame yet".toByteArray())
                } else {
                    writeHttp(out, "200 OK", "image/png", png)
                }
            }

            path == "/api/geocode" -> {
                val q = param(query, "q") ?: ""
                writeJson(out, "200 OK", api.geocode(q))
            }

            else ->
                writeHttp(out, "404 Not Found", "text/plain", "no such thing".toByteArray())
        }
    }

    private fun serveLogin(out: OutputStream, source: String, body: ByteArray) {
        if (!auth.isConfigured) {
            writeJson(
                out, "403 Forbidden",
                JSONObject().put("error", "No password is set. Set one in the app on the phone.")
            )
            return
        }
        val wait = auth.throttleRemainingMs(source)
        if (wait > 0) {
            writeJson(
                out, "429 Too Many Requests",
                JSONObject().put("error", "Too many attempts. Wait ${(wait / 1000) + 1}s.")
                    .put("retryMs", wait)
            )
            return
        }
        val password = jsonBody(body).optString("password", "")
        if (password.isNotEmpty() && auth.verify(password)) {
            auth.recordSuccess(source)
            val token = auth.newSession()
            writeJson(out, "200 OK", JSONObject().put("ok", true), setCookie = token)
        } else {
            auth.recordFailure(source)
            Log.w(TAG, "failed login from $source")
            writeJson(out, "401 Unauthorized", JSONObject().put("error", "Wrong password."))
        }
    }

    // ------------------------------------------------------------- WebSocket

    private inner class WsClient(private val socket: Socket, private val out: OutputStream) {

        @Volatile
        private var open = true

        fun sendText(payload: ByteArray) = send(0x81, payload)

        fun sendBinary(payload: ByteArray) = send(0x82, payload)

        private fun send(opcode: Int, payload: ByteArray) {
            if (!open) return
            try {
                val n = payload.size
                val header = when {
                    n < 126 -> byteArrayOf(opcode.toByte(), n.toByte())
                    n < 65536 -> byteArrayOf(opcode.toByte(), 126, (n ushr 8).toByte(), n.toByte())
                    else -> ByteArray(10).also {
                        it[0] = opcode.toByte(); it[1] = 127
                        for (i in 0..7) it[2 + i] = (n.toLong() ushr (56 - 8 * i)).toByte()
                    }
                }
                synchronized(out) {
                    out.write(header)
                    out.write(payload)
                    out.flush()
                }
            } catch (_: Exception) {
                close()
            }
        }

        fun pong(payload: ByteArray) {
            if (!open) return
            try {
                synchronized(out) {
                    out.write(0x8a)
                    out.write(payload.size)
                    out.write(payload)
                    out.flush()
                }
            } catch (_: Exception) {
                close()
            }
        }

        fun close() {
            open = false
            clients.remove(this)
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun serveWebSocket(
        socket: Socket,
        out: OutputStream,
        ins: DataInputStream,
        wsKey: String
    ) {
        val accept = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1").digest((wsKey + WS_MAGIC).toByteArray()),
            Base64.NO_WRAP
        )
        out.write(
            ("HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray()
        )
        out.flush()
        socket.soTimeout = 0
        socket.tcpNoDelay = true

        val client = WsClient(socket, out)
        clients.add(client)

        // Send the full picture straight away so a freshly opened page is
        // populated before the next frame tick.
        try {
            client.sendText(api.state().toString().toByteArray(Charsets.UTF_8))
        } catch (_: Exception) {
        }

        try {
            while (true) {
                val b0 = ins.read()
                if (b0 < 0) break
                val opcode = b0 and 0x0f
                val b1 = ins.read()
                if (b1 < 0) break
                var len = (b1 and 0x7f).toLong()
                if (len == 126L) {
                    len = ((ins.read() shl 8) or ins.read()).toLong()
                } else if (len == 127L) {
                    len = 0
                    repeat(8) { len = (len shl 8) or ins.read().toLong() }
                }
                val mask = if (b1 and 0x80 != 0) ByteArray(4).also { ins.readFully(it) } else null
                if (len > MAX_WS_FRAME) break
                val payload = ByteArray(len.toInt())
                ins.readFully(payload)
                mask?.let {
                    for (i in payload.indices) {
                        payload[i] = (payload[i].toInt() xor it[i % 4].toInt()).toByte()
                    }
                }
                when (opcode) {
                    8 -> break                      // close
                    9 -> client.pong(payload)       // ping
                    1 -> handleWsText(String(payload, Charsets.UTF_8))
                }
            }
        } catch (_: Exception) {
        } finally {
            client.close()
        }
    }

    /** Settings changes can arrive over the socket too, which keeps a dragged
     *  slider from opening a new TCP connection per pixel of travel. */
    private fun handleWsText(text: String) {
        try {
            val json = JSONObject(text)
            when (json.optString("op")) {
                "settings" -> api.patchSettings(json.optJSONObject("data") ?: JSONObject())
                "brightness" -> api.brightness(json.optJSONObject("data") ?: JSONObject())
                "power" -> api.power(json.optJSONObject("data") ?: JSONObject())
                "message" -> api.message(json.optJSONObject("data") ?: JSONObject())
            }
        } catch (e: Exception) {
            Log.d(TAG, "bad ws message", e)
        }
    }

    // ----------------------------------------------------------------- utils

    private fun asset(name: String): ByteArray? {
        assetCache[name]?.let { return it }
        return try {
            val bytes = context.assets.open("web/$name").use { it.readBytes() }
            assetCache[name] = bytes
            bytes
        } catch (_: Exception) {
            null
        }
    }

    private fun acceptsGzip(headers: Map<String, String>): Boolean =
        headers["accept-encoding"]?.contains("gzip", ignoreCase = true) == true

    private fun writeHttp(
        out: OutputStream,
        status: String,
        type: String,
        body: ByteArray,
        gzip: Boolean = false,
        setCookie: String? = null,
        clearCookie: Boolean = false
    ) {
        // Only worth compressing the text assets; a PNG or an RGB frame is
        // already dense and gzip just burns CPU on a phone.
        val compress = gzip && body.size > 512 &&
            (type.startsWith("text/") || type.contains("javascript") || type.contains("json"))
        val payload = if (compress) gzipped(body) else body

        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(status).append("\r\n")
        sb.append("Content-Type: ").append(type).append("\r\n")
        sb.append("Content-Length: ").append(payload.size).append("\r\n")
        if (compress) sb.append("Content-Encoding: gzip\r\n")
        sb.append("Cache-Control: no-store\r\n")
        // The UI is served from, and only talks to, this origin.
        sb.append("X-Content-Type-Options: nosniff\r\n")
        sb.append("Referrer-Policy: no-referrer\r\n")
        if (setCookie != null) {
            sb.append("Set-Cookie: ").append(Auth.COOKIE).append('=').append(setCookie)
                .append("; Path=/; HttpOnly; SameSite=Strict; Max-Age=604800\r\n")
        }
        if (clearCookie) {
            sb.append("Set-Cookie: ").append(Auth.COOKIE)
                .append("=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0\r\n")
        }
        sb.append("Connection: close\r\n\r\n")

        out.write(sb.toString().toByteArray())
        out.write(payload)
        out.flush()
    }

    private fun writeJson(
        out: OutputStream,
        status: String,
        json: JSONObject,
        setCookie: String? = null,
        clearCookie: Boolean = false
    ) = writeHttp(
        out, status, "application/json; charset=utf-8",
        json.toString().toByteArray(Charsets.UTF_8),
        setCookie = setCookie, clearCookie = clearCookie
    )

    private fun gzipped(body: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream(body.size / 2)
        GZIPOutputStream(bos).use { it.write(body) }
        return bos.toByteArray()
    }

    private fun readBody(ins: DataInputStream, headers: Map<String, String>): ByteArray {
        val len = headers["content-length"]?.toIntOrNull() ?: return ByteArray(0)
        if (len <= 0 || len > MAX_BODY) return ByteArray(0)
        val buf = ByteArray(len)
        ins.readFully(buf)
        return buf
    }

    private fun jsonBody(body: ByteArray): JSONObject = try {
        if (body.isEmpty()) JSONObject() else JSONObject(String(body, Charsets.UTF_8))
    } catch (_: Exception) {
        JSONObject()
    }

    private fun readLine(ins: DataInputStream, max: Int): String? {
        val sb = StringBuilder()
        while (sb.length < max) {
            val c = ins.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
        return null
    }

    private fun cookie(header: String?, name: String): String? {
        if (header == null) return null
        for (part in header.split(';')) {
            val kv = part.trim()
            val i = kv.indexOf('=')
            if (i > 0 && kv.substring(0, i) == name) return kv.substring(i + 1)
        }
        return null
    }

    private fun param(query: String, name: String): String? {
        for (part in query.split('&')) {
            val i = part.indexOf('=')
            if (i > 0 && part.substring(0, i) == name) {
                return try {
                    java.net.URLDecoder.decode(part.substring(i + 1), "UTF-8")
                } catch (_: Exception) {
                    null
                }
            }
        }
        return null
    }

    companion object {
        private const val TAG = "WebServer"
        private const val WS_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        /** Android forbids binding below 1024, so 80 is simply not an option. */
        private val STANDARD_PORTS = intArrayOf(8080, 8000, 8888)
        private const val PORT_MIN = 6500
        private const val PORT_MAX = 7500

        private const val MAX_BODY = 64 * 1024 * 1024 // media uploads, phase 4
        private const val MAX_WS_FRAME = 64 * 1024

        private const val NOT_BUILT =
            "<!doctype html><meta charset=utf-8><title>iPixel Clock</title>" +
                "<body style='background:#05060A;color:#00F0FF;font:14px monospace;padding:2em'>" +
                "<p>The web interface assets are missing from the APK.</p>"

        private const val LOGIN_FALLBACK =
            "<!doctype html><meta charset=utf-8><title>iPixel Clock</title>" +
                "<body style='background:#05060A;color:#00F0FF;font:14px monospace;padding:2em'>" +
                "<p>Login page missing.</p>"

        private const val LOCKED_FALLBACK =
            "<!doctype html><meta charset=utf-8><title>iPixel Clock - locked</title>" +
                "<body style='background:#05060A;color:#FF2BD1;font:14px monospace;padding:2em'>" +
                "<h1>LOCKED</h1><p>No password has been set yet. Open the iPixel Clock app " +
                "on the phone and set one; the web interface stays closed until you do.</p>"

        /** The device's LAN addresses, for the "open this on your desktop" line. */
        fun localIps(): List<String> = try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                .sortedByDescending { it.isSiteLocalAddress }
                .mapNotNull { it.hostAddress }
                .distinct()
                .toList()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
