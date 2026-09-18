package com.example.ipixelclock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import com.example.ipixelclock.led.IPixelHub
import com.example.ipixelclock.led.PanelTuning
import com.example.ipixelclock.led.PanelTuning.Companion.fmt1
import com.example.ipixelclock.led.PanelTuning.Companion.fmt2
import com.example.ipixelclock.render.FrameRenderer
import com.example.ipixelclock.render.LivePanel
import com.example.ipixelclock.render.PanelTarget
import com.example.ipixelclock.render.PixelCanvas
import com.example.ipixelclock.render.SimulatedPanel
import com.example.ipixelclock.settings.Settings
import com.example.ipixelclock.settings.SettingsStore
import com.example.ipixelclock.web.Api
import com.example.ipixelclock.web.Auth
import com.example.ipixelclock.web.WebServer
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * The clock itself.
 *
 * A foreground service because the whole point is a panel that keeps showing the
 * time with the phone's screen off and the app swiped away. It owns the BLE
 * driver, the render pipeline, the settings and the web server, and it is the
 * only thing that renders — the app's preview and every browser's preview are
 * both fed from the same frames it produces, so what you see while tweaking is
 * always what the panel is being sent.
 *
 * It starts and runs whether or not a panel exists. With no panel it renders to
 * a [SimulatedPanel] and Bluetooth is never touched, so the app is fully usable
 * on a phone with the radio off — you just need real hardware for real light.
 */
class ClockService : Service(), Api {

    // ------------------------------------------------------------ the pieces

    private lateinit var store: SettingsStore
    private lateinit var auth: Auth
    private lateinit var server: WebServer

    private val renderer = FrameRenderer()
    private val hub by lazy { IPixelHub(this) }
    private val livePanel by lazy { LivePanel(hub) }
    private val simulated = SimulatedPanel()

    /** Set while the user has asked for a real panel. */
    @Volatile
    private var panelRequested = false

    /** True when ipixel_lab.xml pins the frame mode; the auto-tune stands down. */
    @Volatile
    private var labForcesFrameMode = false

    /**
     * A *copy* of the latest frame, for `/api/preview.png`.
     *
     * Deliberately not a reference to the renderer's own canvas. That canvas is
     * reused and redrawn in place, and this is read from a web-server thread at
     * an arbitrary moment — which lands inside the window between `clear()` and
     * the face being drawn often enough to matter, returning a black frame about
     * three times in a hundred. Snapshotting under [frameLock] closes it.
     */
    private var frameSnapshot: PixelCanvas? = null
    private val frameLock = Any()

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private val settingsListener: (Settings) -> Unit = { s -> onSettingsChanged(s) }

    // ----------------------------------------------------------- the target

    /** Whichever panel the pipeline is currently driving. */
    private val target: PanelTarget
        get() = if (panelRequested && livePanel.isLive) livePanel else simulated

    // ---------------------------------------------------------- the lifecycle

    override fun onCreate() {
        super.onCreate()
        instance = this

        store = SettingsStore(this)
        auth = Auth(this)
        server = WebServer(this, auth, this)

        val s = store.current
        simulated.width = s.simulatedWidth
        simulated.height = s.simulatedHeight
        server.preferredPort = s.port
        server.onPortChanged = { p ->
            // Remember whatever port we actually got, so the next start is
            // likely to reuse the URL the user already bookmarked.
            store.update { it.copy(port = p) }
            pushState()
            updateNotification()
        }

        store.addListener(settingsListener)

        val t = HandlerThread("ipixel-clock").also { it.start() }
        thread = t
        handler = Handler(t.looper)

        startForegroundNotice()
        server.start()

        if (s.startOnBoot || s.displayOn) {
            // Rendering always runs; whether it reaches a panel is a separate
            // question answered by panelRequested.
            scheduleTick(0L)
        }
        Log.i(TAG, "service up, simulated ${simulated.width}x${simulated.height}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CONNECT -> connectPanel()
            ACTION_DISCONNECT -> disconnectPanel()
        }
        scheduleTick(0L)
        // The clock should come back by itself if Android reclaims the process.
        return START_STICKY
    }

    override fun onDestroy() {
        stopEverything()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopEverything() {
        store.removeListener(settingsListener)
        handler?.removeCallbacksAndMessages(null)
        server.stop()
        if (panelRequested) {
            // Leave the panel with a visible sign-off rather than the last frame
            // frozen on it, which is what the driver's goodbye sequence is for.
            hub.disableWithGoodbye()
        }
        thread?.quitSafely()
        thread = null
        handler = null
    }

    // ------------------------------------------------------------- the panel

    /** Starts scanning for a panel. Nothing touches Bluetooth before this. */
    fun connectPanel() {
        if (panelRequested) return
        panelRequested = true
        livePanel.lastStatus = "STARTING..."

        hub.manualW = store.current.manualWidth
        hub.manualH = store.current.manualHeight
        hub.setBrightness(renderer.effectiveBrightness(store.current, System.currentTimeMillis()))

        hub.onStateChanged = { s ->
            livePanel.lastStatus = s
            pushState()
            updateNotification()
        }
        hub.onResolution = { w, h ->
            Log.i(TAG, "panel resolved ${w}x$h")
            beginTuning(w, h)
            pushState()
            updateNotification()
        }
        // The driver pulls frames on its own thread at its own pace; this is the
        // single place a frame is produced for anything.
        hub.renderFrame = { produceFrame(hub.width, hub.height)?.toBitmap() }

        applyLabOverrides()
        applyFrameInterval(store.current)
        hub.enable()
        pushState()
        updateNotification()
    }

    /**
     * Field overrides for the driver's transport knobs, carried over from
     * yacht-compass. The compiled defaults are the configuration measured on a
     * 96x16 E15; a different panel generation may want something else, and this
     * is how you find out without a rebuild:
     *
     *   adb shell "run-as com.example.ipixelclock \
     *     sh -c 'cat > shared_prefs/ipixel_lab.xml'" < lab.xml
     *
     * Absent keys keep the compiled default. [labSummary] reports what is
     * actually in force so the web UI can show it.
     */
    private fun applyLabOverrides() {
        getSharedPreferences("ipixel_lab", MODE_PRIVATE).let { lab ->
            // A hand-set frame_mode is a deliberate override and outranks the
            // auto-tune, which would otherwise measure its way straight back
            // over the top of whatever is being investigated.
            labForcesFrameMode = lab.contains("frame_mode")
            hub.frameMode = lab.getInt("frame_mode", hub.frameMode)
            hub.writeWithResponse = lab.getBoolean("rsp", hub.writeWithResponse)
            hub.ackGating = lab.getBoolean("gate", hub.ackGating)
            hub.highPriority = lab.getBoolean("hi_prio", hub.highPriority)
            hub.chunkMax = lab.getInt("chunk", hub.chunkMax)
            hub.useDiyMode = lab.getBoolean("diy", hub.useDiyMode)
            hub.skipWipe = lab.getBoolean("skip_wipe", hub.skipWipe)
            hub.cameraFrameLen = lab.getInt("cam_len", hub.cameraFrameLen)
            hub.cameraAcks = lab.getBoolean("cam_acks", hub.cameraAcks)
            hub.skipUnchanged = lab.getBoolean("dedupe", hub.skipUnchanged)
            hub.minIntervalMs = lab.getInt("floor_ms", hub.minIntervalMs.toInt()).toLong()
            hub.phy2m = lab.getBoolean("phy2m", hub.phy2m)
            hub.autoFallback = lab.getBoolean("auto_fallback", hub.autoFallback)
        }
    }

    /** The transport knobs in force, for the web UI's diagnostics card. */
    private fun labSummary(): JSONObject = JSONObject().apply {
        put("frameMode", hub.frameMode)
        put("writeWithResponse", hub.writeWithResponse)
        put("ackGating", hub.ackGating)
        put("highPriority", hub.highPriority)
        put("chunkMax", hub.chunkMax)
        put("useDiyMode", hub.useDiyMode)
        put("skipWipe", hub.skipWipe)
        put("cameraFrameLen", hub.cameraFrameLen)
        put("cameraAcks", hub.cameraAcks)
        put("skipUnchanged", hub.skipUnchanged)
        put("minIntervalMs", hub.minIntervalMs)
        put("phy2m", hub.phy2m)
        put("autoFallback", hub.autoFallback)
    }

    fun disconnectPanel() {
        if (!panelRequested) return
        panelRequested = false
        hub.renderFrame = null
        hub.disableWithGoodbye()
        livePanel.lastStatus = ""
        pushState()
        updateNotification()
    }

    fun isPanelRequested(): Boolean = panelRequested

    fun btEnabled(): Boolean = try {
        hub.btEnabled()
    } catch (_: Exception) {
        false
    }

    // ------------------------------------------------------------ the frames

    private val tick = Runnable { onTick() }

    private fun scheduleTick(delayMs: Long) {
        handler?.removeCallbacks(tick)
        handler?.postDelayed(tick, delayMs)
    }

    /**
     * The simulated-panel frame clock.
     *
     * With a real panel the *driver* paces the frames — it is a single-frame
     * pipeline and pushing faster than it acknowledges just gets frames rejected
     * — so this loop only runs the preview path. It also keeps ticking while a
     * panel is connected but not yet resolved, so the previews do not freeze
     * during a scan.
     */
    private fun onTick() {
        val s = store.current
        val useLive = panelRequested && livePanel.isLive
        if (!useLive) {
            produceFrame(simulated.width, simulated.height)
        }
        // Idle panels do not need 12 fps. A static face on a black background
        // only has to redraw when the digits or the colon change.
        val interval = if (isAnimated(s)) s.frameIntervalMs.toLong() else 500L
        scheduleTick(interval)
    }

    private fun isAnimated(s: Settings): Boolean =
        s.background != "off" ||
            s.colorMode == "rainbow" || s.colorMode == "gradient-animated" ||
            s.visibility == "duty" || s.showSeconds || s.blinkColon

    /**
     * Renders one frame and fans it out. Returns the canvas, which the driver
     * turns into a bitmap and everything else reads for previews.
     */
    private fun produceFrame(w: Int, h: Int): PixelCanvas? = synchronized(frameLock) {
        // Synchronized because there are two callers: the driver pulls frames on
        // its own thread, and the tick loop drives the simulated panel. They do
        // not normally overlap, but they do during a connect or a disconnect,
        // and FrameRenderer keeps one canvas it draws into repeatedly.
        val s = store.current
        val canvas = renderer.render(s, w, h, System.currentTimeMillis())
            ?: return@synchronized null
        countFrame()

        // Previews get the scene as it will appear on the wall, not the panel's
        // physical strip — on a vertical mount those are not the same picture.
        val shown = renderer.scene ?: canvas
        val snap = frameSnapshot.let {
            if (it != null && it.width == shown.width && it.height == shown.height) it
            else PixelCanvas(shown.width, shown.height).also { c -> frameSnapshot = c }
        }
        snap.copyFrom(shown)
        try {
            // Raw RGB over the socket: cheaper for both ends than a PNG at a
            // dozen frames a second.
            server.broadcastFrame(shown)
        } catch (e: Exception) {
            Log.d(TAG, "preview broadcast failed", e)
        }
        previewListener?.let { l ->
            try {
                l(shown)
            } catch (_: Exception) {
            }
        }
        return@synchronized canvas
    }

    /** The in-app preview view subscribes here. */
    @Volatile
    var previewListener: ((PixelCanvas) -> Unit)? = null

    /**
     * Frames actually delivered per second, over a rolling window.
     *
     * Worth surfacing rather than assuming: with a real panel the *panel* sets
     * the pace, and different iPixel generations differ by an order of
     * magnitude on the same code — a 96x16 E15 takes a frame in about 10 ms, a
     * 144x16 on firmware 21.17 takes closer to 900 ms.
     */
    @Volatile
    private var measuredFps = 0.0
    private var frameTally = 0
    private var tallyStartMs = 0L

    private fun countFrame() {
        frameTally++
        val now = android.os.SystemClock.elapsedRealtime()
        if (tallyStartMs == 0L) {
            tallyStartMs = now
            return
        }
        val elapsed = now - tallyStartMs
        if (elapsed >= 3000L) {
            measuredFps = frameTally * 1000.0 / elapsed
            frameTally = 0
            tallyStartMs = now
        }
        if (tuningIndex >= 0) stepTuning(now)
    }

    // -------------------------------------------------------- panel tuning

    private val tuning by lazy { PanelTuning(this) }

    /** -1 = not probing; otherwise the candidate currently under test. */
    private var tuningIndex = -1
    private var tuningStartMs = 0L
    private var tuningFrames = 0
    private var tuningW = 0
    private var tuningH = 0
    private val tuningResults = ArrayList<Pair<PanelTuning.Config, Double>>()

    @Volatile
    private var tuningNote = ""

    /**
     * Applies the remembered encoding for this panel, or measures one.
     *
     * See [PanelTuning] for why this is measured rather than assumed: the same
     * driver runs at 12 fps on one panel generation and 1 fps on another with
     * the same settings, and the winner is not the one you would guess.
     */
    private fun beginTuning(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        if (labForcesFrameMode) {
            tuningNote = "forced by ipixel_lab.xml"
            Log.i(TAG, "tuning skipped: frame_mode is pinned in ipixel_lab.xml")
            return
        }

        val known = tuning.stored(w, h)
        if (known != null) {
            applyConfig(known)
            tuningNote = "${known.label}, measured ${fmt1(tuning.storedFps(w, h))} fps"
            Log.i(TAG, "panel ${w}x$h already tuned: $tuningNote")
            return
        }

        tuningW = w
        tuningH = h
        tuningResults.clear()
        tuningIndex = 0
        tuningFrames = 0
        tuningStartMs = android.os.SystemClock.elapsedRealtime()
        applyConfig(tuning.candidates[0])
        tuningNote = "measuring — ${tuning.candidates[0].label}"
        Log.i(TAG, "tuning ${w}x$h: trying ${tuning.candidates[0].label}")
        pushState()
    }

    /** Called per frame while a probe is running. */
    private fun stepTuning(now: Long) {
        tuningFrames++
        val elapsed = now - tuningStartMs
        if (elapsed < PanelTuning.PROBE_MS && tuningFrames < PanelTuning.PROBE_FRAMES) return

        val fps = tuningFrames * 1000.0 / elapsed.coerceAtLeast(1L)
        val config = tuning.candidates[tuningIndex]
        tuningResults.add(config to fps)
        Log.i(TAG, "tuning ${tuningW}x$tuningH: ${config.label} = ${fmt2(fps)} fps")

        // Already quick enough that the rest cannot matter: stop here rather
        // than making the panel flicker through two more probes for nothing.
        if (fps >= PanelTuning.GOOD_ENOUGH_FPS) {
            finishTuning()
            return
        }
        tuningIndex++
        if (tuningIndex >= tuning.candidates.size) {
            finishTuning()
            return
        }
        tuningFrames = 0
        tuningStartMs = now
        applyConfig(tuning.candidates[tuningIndex])
        tuningNote = "measuring — ${tuning.candidates[tuningIndex].label}"
        pushState()
    }

    private fun finishTuning() {
        tuningIndex = -1
        val best = tuningResults.maxByOrNull { it.second }
        if (best == null) {
            tuningNote = ""
            return
        }
        applyConfig(best.first)
        tuning.remember(tuningW, tuningH, best.first, best.second)
        tuningNote = "${best.first.label}, measured ${fmt1(best.second)} fps"
        pushState()
    }

    private fun applyConfig(config: PanelTuning.Config) {
        hub.frameMode = config.frameMode
        hub.writeWithResponse = config.writeWithResponse
    }

    /** Wipes the remembered tuning and probes again. */
    private fun retune() {
        if (livePanel.isLive) {
            tuning.forget(hub.width, hub.height)
            beginTuning(hub.width, hub.height)
        }
    }

    private fun applyFrameInterval(s: Settings) {
        hub.frameIntervalMs = s.frameIntervalMs.toLong()
    }

    // ---------------------------------------------------------- the settings

    private fun onSettingsChanged(s: Settings) {
        simulated.width = s.simulatedWidth
        simulated.height = s.simulatedHeight

        if (panelRequested) {
            hub.manualW = s.manualWidth
            hub.manualH = s.manualHeight
            hub.applyManualSize()
            applyFrameInterval(s)
            hub.setBrightness(renderer.effectiveBrightness(s, System.currentTimeMillis()))
            hub.requestFrame()
        }
        scheduleTick(0L)
        pushState()
        updateNotification()
    }

    // ------------------------------------------------------------------- Api

    override fun state(): JSONObject {
        val s = store.current
        val t = target
        return JSONObject().apply {
            put("settings", s.toJson())
            put("panel", JSONObject().apply {
                put("live", t.isLive)
                put("requested", panelRequested)
                put("width", t.panelWidth)
                put("height", t.panelHeight)
                put("status", if (panelRequested) livePanel.lastStatus else simulated.status)
                put("simulated", !t.isLive)
                put("bluetoothOn", btEnabled())
                // The standing reminder: a preview is not a panel.
                put(
                    "reminder",
                    if (t.isLive) ""
                    else "No iPixel panel connected — this is a preview. " +
                        "You need an iPixel BLE LED matrix (LED_96*16 and the like) for real output."
                )
            })
            put("server", JSONObject().apply {
                put("port", server.port)
                put("ips", org.json.JSONArray(WebServer.localIps()))
                put("viewers", server.viewerCount)
                put("passwordSet", auth.isConfigured)
            })
            put("transport", labSummary().put("tuning", tuningNote))
            put("fps", Math.round(measuredFps * 10.0) / 10.0)
            put("brightnessEffective", renderer.effectiveBrightness(s, System.currentTimeMillis()))
            put("sunriseMs", renderer.sunriseMs ?: JSONObject.NULL)
            put("sunsetMs", renderer.sunsetMs ?: JSONObject.NULL)
        }
    }

    override fun patchSettings(patch: JSONObject): JSONObject {
        store.patch(patch)
        return state()
    }

    override fun power(body: JSONObject): JSONObject {
        if (body.has("on")) {
            store.update { it.copy(displayOn = body.optBoolean("on", it.displayOn)) }
        }
        return state()
    }

    override fun brightness(body: JSONObject): JSONObject {
        store.update {
            it.copy(
                brightness = body.optInt("value", it.brightness).coerceIn(0, 100),
                brightnessAuto = body.optBoolean("auto", it.brightnessAuto),
                brightnessDay = body.optInt("day", it.brightnessDay).coerceIn(0, 100),
                brightnessNight = body.optInt("night", it.brightnessNight).coerceIn(0, 100)
            )
        }
        return state()
    }

    override fun detect(body: JSONObject): JSONObject {
        when (body.optString("action")) {
            "scan", "reconnect" -> {
                disconnectPanel()
                connectPanel()
            }
            "forget" -> disconnectPanel()
            "retune" -> retune()
            "simulate" -> {
                val w = body.optInt("w", simulated.width)
                val h = body.optInt("h", simulated.height)
                store.update { it.copy(simulatedWidth = w, simulatedHeight = h) }
            }
        }
        return state()
    }

    override fun previewPng(): ByteArray? {
        // Under the same lock the snapshot is written with, so a caller can
        // never observe a half-drawn frame.
        val bmp = synchronized(frameLock) { frameSnapshot?.toBitmap() } ?: return null
        return try {
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
            bmp.recycle()
            bos.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    override fun geocode(query: String): JSONObject {
        // Phase 5 wires this to Open-Meteo's geocoding endpoint.
        return JSONObject().put("results", org.json.JSONArray())
    }

    private fun pushState() {
        try {
            server.broadcastState(state())
        } catch (e: Exception) {
            Log.d(TAG, "state broadcast failed", e)
        }
    }

    // ------------------------------------------------------- the notification

    private fun startForegroundNotice() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_desc)
                setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // From Android 14 the connectedDevice type is refused unless a
            // Bluetooth runtime permission has been granted. MainActivity asks
            // for it before starting us, so this is the path where the user
            // said no: carry on unpromoted rather than crashing, and let the
            // clock be a preview and a web server until they change their mind.
            Log.w(TAG, "could not enter the foreground with the connectedDevice type", e)
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (e2: Exception) {
                Log.e(TAG, "foreground refused outright; the service may be killed early", e2)
            }
        }
    }

    private fun updateNotification() {
        try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            Log.d(TAG, "notification update failed", e)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            pendingFlags()
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ClockService::class.java).setAction(ACTION_STOP),
            pendingFlags()
        )

        val t = target
        val where = server.port.let { p ->
            if (p == 0) "web interface starting"
            else WebServer.localIps().firstOrNull()?.let { "http://$it:$p" } ?: "port $p"
        }
        val line = if (t.isLive) {
            "Panel ${t.panelWidth}×${t.panelHeight} · $where"
        } else {
            "No panel · preview only · $where"
        }

        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL)
        } else {
            Notification.Builder(this)
        }

        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(line)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentIntent(open)
            .setOngoing(true)
            .apply {
                if (Build.VERSION.SDK_INT >= 23) {
                    addAction(
                        Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Stop", stop).build()
                    )
                } else {
                    @Suppress("DEPRECATION")
                    addAction(0, "Stop", stop)
                }
                if (Build.VERSION.SDK_INT >= 21) setVisibility(Notification.VISIBILITY_PUBLIC)
            }
            .build()
    }

    private fun pendingFlags(): Int =
        if (Build.VERSION.SDK_INT >= 23) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    // ------------------------------------------------------------- the entry

    companion object {
        private const val TAG = "ClockService"
        private const val CHANNEL = "ipixel-clock"
        private const val NOTIFICATION_ID = 1

        const val ACTION_STOP = "com.example.ipixelclock.STOP"
        const val ACTION_CONNECT = "com.example.ipixelclock.CONNECT"
        const val ACTION_DISCONNECT = "com.example.ipixelclock.DISCONNECT"

        /** The running service, for the activity's preview and controls. */
        @Volatile
        var instance: ClockService? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, ClockService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ClockService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
