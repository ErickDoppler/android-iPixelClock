package com.example.ipixelclock.led

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.graphics.Bitmap
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.Calendar
import java.util.UUID
import java.util.zip.CRC32

/**
 * iPixel LED display driver ("LED_96*16"-style BLE RGB matrix panels).
 *
 * This file is kept byte-identical (except the package line) across all the
 * apps that drive a panel — edit it once, copy it everywhere.
 *
 * DIVERGENCE, 2026-09-18: this copy adds [brightness] and [setBrightness] so
 * the panel can be dimmed live from the UI, where the shared copy only sent a
 * fixed 80 once during onReady(). Purely additive — the command is the same
 * 0x8004 the setup already used. It still needs back-porting to
 * android-yacht-compass (and any other panel app) to restore the rule.
 *
 * Protocol (iPixel Color app family; cross-checked against the ESPHome
 * component, go-ipxl and the ipixel-ctrl traffic captures, then measured on
 * LED_BLE_FACDF2C6, 96x16, firmware MCU 18.21 / BLE 1.07):
 *  - GATT write characteristic 0xFA02, notifications on 0xFA03
 *  - commands: [len16 LE][opcode16 LE][data...], len covers the whole packet
 *  - nearly every command is answered on 0xFA03 with `05 00 <opcode> <state>`
 *  - opcode 0x0002 (PNG or raw RGB888 payload) is the stored-image path: the
 *    panel takes ~480 ms per frame whatever the payload; state 03 = ok,
 *    00 = CRC failed
 *  - opcode 0x0000 (raw RGB888, no CRC, after DIY mode 0x0104 = 1) is the
 *    app's live-canvas path: the panel is done in ~9 ms and acks with 01
 *  - the set-time reply `0B 00 01 80 <type> ...` carries the panel type at
 *    offset 4 — the authoritative size, used over the name and the picker
 *
 * Transport: one command in flight at a time, chunked, gated on the panel's
 * acknowledgement before the next one goes out. The panel is a single-frame
 * pipeline; bytes arriving while it is still busy get the frame rejected
 * and its ">LED<" splash shown.
 *
 * Measured configurations (2026-09-08, Note 10+, floor 0 unless noted):
 *   E15 default : 0x0000 + DIY, no-response chunks, ack-gated, LE 2M PHY  12.6 fps
 *   E14         : same on 2M with a 100 ms floor                            9.7 fps
 *   E9 / E10    : same on 1M PHY (what a panel without 2M gives you)        8.8 fps
 *   E1 fallback : PNG over 0x0002, response writes, ack-gated               1.9 fps
 * The public knobs select between them; the host may override them from a
 * prefs file for trying new panels. Degradation is automatic: a refused 2M
 * PHY just stays on 1M, and 0x0000 frames that stop acknowledging drop the
 * driver to the E1 path (see [autoFallback]).
 */
@SuppressLint("MissingPermission")
class IPixelHub(private val context: Context) {

    companion object {
        private val WRITE_UUID = UUID.fromString("0000fa02-0000-1000-8000-00805f9b34fb")
        private val NOTIFY_UUID = UUID.fromString("0000fa03-0000-1000-8000-00805f9b34fb")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val NAME_RES = Regex("(\\d{2,3})\\s*[*xX]\\s*(\\d{1,3})")
        private const val TAG = "IPixelHub"

        /** Device-type byte (set-time reply, offset 4) -> resolution. */
        private val TYPE_RES = mapOf(
            128 to (64 to 64), 129 to (32 to 32), 130 to (32 to 16),
            131 to (64 to 16), 132 to (96 to 16), 133 to (64 to 20),
            134 to (128 to 32), 135 to (144 to 16), 136 to (192 to 16),
            137 to (48 to 24), 138 to (64 to 32), 139 to (96 to 32),
            140 to (128 to 32), 141 to (96 to 32), 142 to (160 to 32),
            143 to (192 to 32), 144 to (256 to 32), 145 to (320 to 32),
            146 to (384 to 32), 147 to (448 to 32)
        )

        /** Frame encodings, see [frameMode]. */
        const val FRAME_PNG = 0     // opcode 0x0002, PNG payload (legacy / fallback)
        const val FRAME_RAW = 1     // opcode 0x0002, raw RGB888 payload
        const val FRAME_CAMERA = 2  // opcode 0x0000, raw RGB888 (app's live path)

        /** Quiet time after the ROM erase; 1.6 s proved too short. */
        private const val WIPE_HOLD_MS = 3000L

        /** A lost acknowledgement must never stall the pipeline. */
        private const val ACK_TIMEOUT_MS = 2000L

        /** Camera frames that time out in a row before falling back to PNG. */
        private const val FALLBACK_AFTER_TIMEOUTS = 3

        /** How long the orange "SCREEN OFF" notice stays up on shutdown. */
        private const val GOODBYE_HOLD_MS = 900L

        /** Settle time for the blanking frame before the link is dropped. */
        private const val BLANK_HOLD_MS = 300L

        private const val MAX_FRAME_RETRIES = 3
        private const val MAX_CHUNK_RETRIES = 5
    }

    // ---------------------------------------------------------------- knobs
    // Defaults are the measured-best configuration (E15). The host may
    // override any of them before enable() — see the ipixel_lab prefs block
    // in MainActivity — for trying other panels.

    @Volatile var frameMode = FRAME_CAMERA
    @Volatile var writeWithResponse = false // chunks pipelined; the ack is the pace
    @Volatile var ackGating = true
    @Volatile var highPriority = true
    @Volatile var chunkMax = 500            // ESPHome's proven cap
    @Volatile var useDiyMode = true         // 0x0104=1 during setup (0x0000 wants it)
    @Volatile var skipWipe = false          // experiment: no ROM erase at all
    @Volatile var cameraFrameLen = 1024     // 0x0000 header field; <=0 = payload size
    @Volatile var cameraAcks = true         // 0x0000 acknowledges with state 01
    @Volatile var skipUnchanged = false     // drop frames identical to the last one
    @Volatile var minIntervalMs = 0L        // >= 0 overrides frameIntervalMs as the floor
    @Volatile var phy2m = true              // request the LE 2M PHY after connect
    @Volatile var autoFallback = true       // camera frames unacked -> PNG path

    /** Status string for the settings page; any thread. */
    @Volatile var onStateChanged: ((String) -> Unit)? = null

    /** Resolution resolved (device / name / picker); any thread. */
    @Volatile var onResolution: ((Int, Int) -> Unit)? = null

    /** Host-provided page painter; called on the hub thread. */
    @Volatile var renderFrame: (() -> Bitmap?)? = null

    /** Host's floor between frame starts; overridden by [minIntervalMs] >= 0. */
    @Volatile var frameIntervalMs = 400L

    /** Panel brightness 0..100. [setBrightness] changes it live; [onReady]
     *  reapplies it after every reconnect, because the ROM wipe resets it. */
    @Volatile var brightness = 80
        private set

    /** Manual fallback size (0 = unknown); set from the settings picker. */
    @Volatile var manualW = 0
    @Volatile var manualH = 0

    @Volatile var isEnabled = false
        private set
    @Volatile var width = 0
        private set
    @Volatile var height = 0
        private set

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    @Volatile private var gen = 0
    @Volatile private var connected = false
    @Volatile private var autoResolved = false // size came from the device itself
    private var scanning = false
    private var mtu = 23

    // Traffic hold while the panel's ROM erase runs (see onReady).
    @Volatile private var holdUntil = 0L

    // Set while the goodbye sequence runs: keeps tick() off the panel.
    @Volatile private var shuttingDown = false

    private val tickRunnable = Runnable { tick(gen) }

    private fun scheduleTick(delayMs: Long) {
        handler?.removeCallbacks(tickRunnable)
        handler?.postDelayed(tickRunnable, delayMs)
    }

    private fun now() = SystemClock.elapsedRealtime()

    // ------------------------------------------------------- command queue
    // Hub thread only. One command is chunked out at a time; when its last
    // chunk is confirmed by the stack we wait for the panel's ack (if the
    // opcode has one) before dispatching the next.

    private class Cmd(
        val bytes: ByteArray,
        val label: String,
        val expectsAck: Boolean,
        val isFrame: Boolean = false,
        val thenDisable: Boolean = false,
        val crc: Long = 0L
    ) {
        var off = 0
        var lastChunk = 0
        var chunks = 0
        var retries = 0
        var chunkRetries = 0
        var tQueued = 0L
        var tFirstWrite = 0L
        var tLastWriteCb = 0L

        val isCameraFrame: Boolean
            get() = isFrame && bytes.size > 3 && bytes[2] == 0.toByte() && bytes[3] == 0.toByte()

        fun rewind() {
            off = 0; lastChunk = 0; chunks = 0; tFirstWrite = 0L; tLastWriteCb = 0L
        }
    }

    private val queue = ArrayDeque<Cmd>()
    private var current: Cmd? = null
    private var awaitingAck = false
    private var ackSeq = 0
    private var frameStartedAt = 0L
    private var lastFrameCrc = -1L
    private var cameraTimeouts = 0

    /** Last brightness actually written to the panel; -1 = none this session. */
    private var sentBrightness = -1

    // Last chunk write: a lost GATT callback would otherwise freeze the
    // pipeline forever with the panel stuck on one image.
    @Volatile private var lastWriteAt = 0L

    // Frame statistics for the log.
    private var frameCount = 0
    private var frameOk = 0
    private var frameCrcFail = 0
    private var frameTimeouts = 0
    private var statWindowStart = 0L

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE)
            as BluetoothManager).adapter

    fun btEnabled(): Boolean = adapter?.isEnabled == true

    // ------------------------------------------------------------- lifecycle

    /** Starts scanning/connecting; BT and runtime permissions must be OK. */
    fun enable() {
        disable()
        shuttingDown = false
        isEnabled = true
        val g = ++gen
        val t = HandlerThread("ipixel").also { it.start() }
        thread = t
        handler = Handler(t.looper)
        handler?.post { startScan(g) }
    }

    fun disable() {
        gen++
        isEnabled = false
        connected = false
        stopScanSafe()
        try {
            gatt?.disconnect()
        } catch (_: Exception) {
        }
        try {
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
        writeChar = null
        queue.clear()
        current = null
        awaitingAck = false
        width = 0
        height = 0
        autoResolved = false
        thread?.quitSafely()
        thread = null
        handler = null
        shuttingDown = false
    }

    /** Signs off on the panel before dropping the link: a short orange
     *  "SCREEN OFF" notice, then a blank frame, then the normal teardown.
     *  Without it the matrix keeps displaying whatever frame it held when
     *  the link went away. Falls straight through to [disable] when there
     *  is nothing connected to say goodbye to. */
    fun disableWithGoodbye() {
        val h = handler
        if (h == null || !connected || width == 0 || height == 0) {
            disable()
            return
        }
        val g = gen
        shuttingDown = true // tick() must not paint over the notice
        isEnabled = false   // and the scan/retry loops must not restart
        h.removeCallbacks(tickRunnable)
        h.post {
            if (g != gen) return@post
            queue.clear() // anything not yet started is abandoned
            enqueue(g, frameCmd(LedPages.screenOff(width, height), "goodbye"))
            h.postDelayed({
                if (g != gen) return@postDelayed
                enqueue(g, frameCmd(blankFrame(), "blank", thenDisable = true))
            }, GOODBYE_HOLD_MS)
        }
    }

    private fun blankFrame(): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            .also { it.eraseColor(0xFF000000.toInt()) }

    /** Re-run size resolution after the manual picker changed. Applies any
     *  time the size did not come from the device itself. */
    fun applyManualSize() {
        if (connected && !autoResolved && manualW > 0 && manualH > 0) {
            width = manualW
            height = manualH
            onResolution?.invoke(width, height)
            status("CONNECTED $width*$height")
            requestFrame()
        }
    }

    /** Sets the panel brightness (0..100) live. The command is queued AHEAD of
     *  any pending frame so a dragged slider tracks the hand instead of the
     *  frame rate. While nothing is connected this only stores the value —
     *  [onReady] sends it on the next connection. */
    fun setBrightness(pct: Int) {
        val v = pct.coerceIn(0, 100)
        brightness = v
        val h = handler ?: return
        val g = gen
        h.post {
            if (g != gen || !connected) return@post
            // Only when it actually moved. Hosts tend to reapply every setting
            // on every settings change, and because these jump the queue a
            // stream of no-op brightness commands would starve the frames.
            if (v == sentBrightness) return@post
            sentBrightness = v
            val cmd = Cmd(byteArrayOf(0x05, 0x00, 0x04, 0x80.toByte(), v.toByte()),
                "brightness", expectsAck = true)
            cmd.tQueued = now()
            queue.addFirst(cmd)
            dispatchNext(g)
        }
    }

    /** Pushes the next frame right away — settings changes apply instantly. */
    fun requestFrame() {
        handler?.post {
            if (current == null && !awaitingAck && queue.isEmpty()) {
                handler?.removeCallbacks(tickRunnable)
                tick(gen)
            }
        }
    }

    private fun status(s: String) {
        onStateChanged?.invoke(s)
    }

    // ------------------------------------------------------------------ scan

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device?.name ?: result.scanRecord?.deviceName ?: return
            if (!name.uppercase().startsWith("LED")) return
            stopScanSafe()
            status("CONNECTING $name...")
            handler?.post { connect(gen, name, result.device.address) }
        }
    }

    // KitKat scan path (BluetoothLeScanner is API 21+). device.name is the
    // GAP name cache and is usually null for a never-paired device, so the
    // advertised name must be parsed out of the raw scan record.
    @Suppress("DEPRECATION")
    private val legacyScanCallback = BluetoothAdapter.LeScanCallback { device, _, scanRecord ->
        val name = device?.name ?: parseAdvertisedName(scanRecord)
            ?: return@LeScanCallback
        if (!name.uppercase().startsWith("LED")) return@LeScanCallback
        stopScanSafe()
        status("CONNECTING $name...")
        handler?.post { connect(gen, name, device.address) }
    }

    /** Local name from raw AD structures ([len][type][data...]; 0x08/0x09). */
    private fun parseAdvertisedName(record: ByteArray?): String? {
        if (record == null) return null
        var i = 0
        while (i + 1 < record.size) {
            val len = record[i].toInt() and 0xff
            if (len == 0 || i + 1 + len > record.size) break
            val type = record[i + 1].toInt() and 0xff
            if (type == 0x08 || type == 0x09) { // shortened / complete name
                return String(record, i + 2, len - 1, Charsets.UTF_8)
            }
            i += 1 + len
        }
        return null
    }

    /** Android 6..11 withhold BLE scan results while location services are
     *  off: startScan() still succeeds and no result ever arrives, so this
     *  has to be checked up front or the scan just hangs forever. */
    private fun locationEnabled(): Boolean {
        val sdk = Build.VERSION.SDK_INT
        if (sdk < 23 || sdk > 30) return true // 12+: neverForLocation scan
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (sdk >= 28) {
                lm.isLocationEnabled
            } else {
                @Suppress("DEPRECATION")
                (lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
            }
        } catch (_: Exception) {
            true // unknown: let the scan try anyway
        }
    }

    private fun startScan(g: Int) {
        if (g != gen) return
        val a = adapter
        if (a == null || !a.isEnabled) {
            status("BLUETOOTH IS OFF")
            return
        }
        if (!locationEnabled()) {
            status("TURN LOCATION ON - BLE SCAN NEEDS IT")
            // Recheck so it starts by itself once the toggle is flipped.
            handler?.postDelayed({
                if (g == gen && isEnabled && !connected) startScan(g)
            }, 3000L)
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= 21) {
                a.bluetoothLeScanner?.startScan(scanCallback)
            } else {
                @Suppress("DEPRECATION")
                a.startLeScan(legacyScanCallback)
            }
            scanning = true
            status("SCANNING FOR LED DISPLAY...")
            // Rescan window: give up quietly and retry while enabled.
            handler?.postDelayed({
                if (g == gen && scanning) {
                    stopScanSafe()
                    startScan(g)
                }
            }, 20_000L)
        } catch (e: Exception) {
            status("SCAN FAILED (PERMISSIONS?)")
        }
    }

    private fun stopScanSafe() {
        if (!scanning) return
        scanning = false
        try {
            if (Build.VERSION.SDK_INT >= 21) {
                adapter?.bluetoothLeScanner?.stopScan(scanCallback)
            } else {
                @Suppress("DEPRECATION")
                adapter?.stopLeScan(legacyScanCallback)
            }
        } catch (_: Exception) {
        }
    }

    // --------------------------------------------------------------- connect

    private fun connect(g: Int, name: String, address: String) {
        if (g != gen) return
        // The advertised name usually carries the resolution: LED_96*16...
        // (the set-time reply will confirm or correct it once connected).
        NAME_RES.find(name)?.let {
            width = it.groupValues[1].toInt()
            height = it.groupValues[2].toInt()
        }
        try {
            val device = adapter?.getRemoteDevice(address)
            gatt = if (Build.VERSION.SDK_INT >= 23) {
                device?.connectGatt(context, false, gattCallback, 2 /* TRANSPORT_LE */)
            } else {
                device?.connectGatt(context, false, gattCallback)
            }
        } catch (_: Exception) {
            status("CONNECT FAILED")
            retry(g)
        }
    }

    private fun retry(g: Int) {
        handler?.postDelayed({
            if (g == gen && isEnabled && !connected) {
                try { gatt?.close() } catch (_: Exception) {}
                gatt = null
                startScan(g)
            }
        }, 3000L)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (Build.VERSION.SDK_INT >= 21) {
                    // Every chunk costs a connection interval; HIGH pulls that
                    // from ~40 ms down to ~11-15 ms.
                    if (highPriority) {
                        try {
                            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                        } catch (_: Exception) {
                        }
                    }
                    // LE 2M doubles air throughput when the panel radio has it;
                    // a refusal just leaves the link on 1M (see onPhyUpdate).
                    if (phy2m && Build.VERSION.SDK_INT >= 26) {
                        try {
                            g.setPreferredPhy(BluetoothDevice.PHY_LE_2M_MASK,
                                BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_OPTION_NO_PREFERRED)
                        } catch (_: Exception) {
                        }
                    }
                    g.requestMtu(512)
                } else {
                    mtu = 23 // KitKat: default MTU, no negotiation
                    g.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false
                Log.i(TAG, "disconnected status=$st")
                if (isEnabled) {
                    status("DISCONNECTED - RETRYING...")
                    retry(gen)
                }
            }
        }

        override fun onPhyUpdate(g: BluetoothGatt, txPhy: Int, rxPhy: Int, st: Int) {
            Log.i(TAG, "PHY update tx=$txPhy rx=$rxPhy status=$st (1=1M 2=2M 3=CODED)")
        }

        override fun onPhyRead(g: BluetoothGatt, txPhy: Int, rxPhy: Int, st: Int) {
            Log.i(TAG, "PHY now tx=$txPhy rx=$rxPhy status=$st (1=1M 2=2M 3=CODED)")
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, st: Int) {
            mtu = if (st == BluetoothGatt.GATT_SUCCESS) newMtu else 23
            Log.i(TAG, "mtu=$mtu status=$st")
            if (Build.VERSION.SDK_INT >= 26) {
                try { g.readPhy() } catch (_: Exception) {}
            }
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            var wc: BluetoothGattCharacteristic? = null
            var nc: BluetoothGattCharacteristic? = null
            for (svc in g.services) {
                for (c in svc.characteristics) {
                    when (c.uuid) {
                        WRITE_UUID -> wc = c
                        NOTIFY_UUID -> nc = c
                    }
                }
            }
            if (wc == null) {
                status("UNSUPPORTED DEVICE (NO FA02)")
                try { g.disconnect() } catch (_: Exception) {}
                return
            }
            writeChar = wc
            if (nc != null) {
                try {
                    g.setCharacteristicNotification(nc, true)
                    nc.getDescriptor(CCCD_UUID)?.let { d ->
                        @Suppress("DEPRECATION")
                        d.value = byteArrayOf(0x01, 0x00)
                        @Suppress("DEPRECATION")
                        g.writeDescriptor(d)
                    }
                } catch (_: Exception) {
                }
            }
            connected = true
            val gg = gen
            handler?.post { onReady(gg) }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            val data = c.value?.copyOf() ?: return
            val gg = gen
            handler?.post { onNotify(gg, data) }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int
        ) {
            val gg = gen
            handler?.post { onWriteDone(gg, st) }
        }
    }

    // ------------------------------------------------------------ the setup

    private fun onReady(g: Int) {
        if (g != gen) return
        frameCount = 0; frameOk = 0; frameCrcFail = 0; frameTimeouts = 0
        cameraTimeouts = 0
        statWindowStart = now()
        lastWriteAt = now()
        lastFrameCrc = -1L
        Log.i(TAG, "READY mode=$frameMode rsp=$writeWithResponse gate=$ackGating " +
            "hiPrio=$highPriority chunk=$chunkMax diy=$useDiyMode skipWipe=$skipWipe " +
            "camLen=$cameraFrameLen camAcks=$cameraAcks dedupe=$skipUnchanged " +
            "floor=$minIntervalMs phy2m=$phy2m fallback=$autoFallback mtu=$mtu")
        val cal = Calendar.getInstance()
        // The app's own order: set-time first (its reply carries the panel
        // type and it powers the panel on), then the firmware query.
        enqueue(g, Cmd(byteArrayOf(
            0x08, 0x00, 0x01, 0x80.toByte(),
            cal.get(Calendar.HOUR_OF_DAY).toByte(),
            cal.get(Calendar.MINUTE).toByte(),
            cal.get(Calendar.SECOND).toByte(), 0x00
        ), "time", expectsAck = true))
        enqueue(g, Cmd(byteArrayOf(0x04, 0x00, 0x05, 0x80.toByte()), "fw", expectsAck = true))
        enqueue(g, Cmd(byteArrayOf(0x05, 0x00, 0x07, 0x01, 0x01), "power", expectsAck = true))
        if (!skipWipe) {
            // Wipe the stored slots (opcode 0x8003): old animations in them are
            // what rolls through between live frames. It is a flash erase and
            // needs quiet time afterwards — see holdUntil in finishCommand.
            enqueue(g, Cmd(byteArrayOf(0x04, 0x00, 0x03, 0x80.toByte()), "wipe", expectsAck = true))
        }
        if (useDiyMode) {
            enqueue(g, Cmd(byteArrayOf(0x05, 0x00, 0x04, 0x01, 0x01), "diy", expectsAck = true))
        }
        // Brightness goes after the wipe: the wipe resets settings too.
        enqueue(g, Cmd(byteArrayOf(0x05, 0x00, 0x04, 0x80.toByte(), brightness.toByte()),
            "brightness", expectsAck = true))
        sentBrightness = brightness
        // Provisional size until the set-time reply arrives (~50 ms).
        if (width == 0 && manualW > 0 && manualH > 0) {
            width = manualW
            height = manualH
        }
        if (width != 0) {
            onResolution?.invoke(width, height)
            status("CONNECTED $width*$height")
        } else {
            status("CONNECTED - SIZE UNKNOWN, PICK IT BELOW")
        }
    }

    /** The panel's own word on its size, from the set-time reply. It wins
     *  over the advertised name and the manual picker: a stale picker value
     *  or a name without a resolution in it is exactly how a 96x16 panel
     *  used to end up driven as 64x64. */
    private fun applyDeviceType(type: Int) {
        val size = TYPE_RES[type]
        if (size == null) {
            Log.w(TAG, "panel type $type unknown; keeping ${width}x$height")
            return
        }
        val (w, h) = size
        autoResolved = true
        if (w == width && h == height) {
            Log.i(TAG, "panel reports ${w}x$h (type $type), confirmed")
            return
        }
        Log.i(TAG, "panel reports ${w}x$h (type $type), was ${width}x$height — using the panel's")
        width = w
        height = h
        onResolution?.invoke(w, h)
        status("CONNECTED $w*$h")
    }

    // ---------------------------------------------------------- the engine

    private fun enqueue(g: Int, cmd: Cmd) {
        if (g != gen) return
        cmd.tQueued = now()
        queue.addLast(cmd)
        dispatchNext(g)
    }

    private fun dispatchNext(g: Int) {
        if (g != gen || !connected) return
        if (current != null || awaitingAck) return
        val t = now()
        if (t < holdUntil) {
            handler?.postDelayed({ dispatchNext(g) }, holdUntil - t + 10L)
            return
        }
        val next = queue.pollFirst() ?: return
        next.rewind()
        current = next
        continueWrite(g)
    }

    /** Writes the next chunk; the write callback chains the rest. */
    private fun continueWrite(g: Int) {
        if (g != gen || !connected) {
            current = null
            queue.clear()
            return
        }
        val cur = current ?: return
        val c = writeChar ?: return
        val gt = gatt ?: return
        if (cur.tFirstWrite == 0L) cur.tFirstWrite = now()
        val cap = minOf(mtu - 3, chunkMax).coerceAtLeast(20)
        val n = (cur.bytes.size - cur.off).coerceAtMost(cap)
        val chunk = cur.bytes.copyOfRange(cur.off, cur.off + n)
        cur.off += n
        cur.lastChunk = n
        cur.chunks++
        lastWriteAt = now()
        try {
            @Suppress("DEPRECATION")
            c.writeType = if (writeWithResponse) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
            @Suppress("DEPRECATION")
            c.value = chunk
            @Suppress("DEPRECATION")
            if (!gt.writeCharacteristic(c)) {
                // Busy stack: try again shortly from where we left off.
                cur.off -= n
                cur.chunks--
                handler?.postDelayed({ continueWrite(g) }, 20L)
            }
        } catch (e: Exception) {
            Log.e(TAG, "write threw for ${cur.label}", e)
            finishCommand(g, cur, -2, null)
        }
    }

    private fun onWriteDone(g: Int, st: Int) {
        if (g != gen) return
        val cur = current ?: return
        lastWriteAt = now()
        if (st != BluetoothGatt.GATT_SUCCESS) {
            // The chunk did not land: resend it rather than skipping it, or
            // the frame arrives short and fails its CRC.
            if (cur.chunkRetries++ < MAX_CHUNK_RETRIES) {
                cur.off -= cur.lastChunk
                cur.chunks--
                Log.w(TAG, "write status $st on ${cur.label}, resending chunk")
                handler?.postDelayed({ continueWrite(g) }, 20L)
            } else {
                Log.e(TAG, "giving up on ${cur.label}: repeated write failures")
                finishCommand(g, cur, -2, null)
            }
            return
        }
        if (cur.off < cur.bytes.size) {
            continueWrite(g)
            return
        }
        // Every chunk confirmed by the stack.
        cur.tLastWriteCb = now()
        if (cur.expectsAck && ackGating) {
            awaitingAck = true
            val seq = ++ackSeq
            handler?.postDelayed({
                if (g == gen && awaitingAck && ackSeq == seq && current === cur) {
                    if (cur.isFrame) frameTimeouts++
                    Log.w(TAG, "ack timeout for ${cur.label}")
                    finishCommand(g, cur, -1, null)
                }
            }, ACK_TIMEOUT_MS)
        } else {
            finishCommand(g, cur, -3, null)
        }
    }

    private fun onNotify(g: Int, data: ByteArray) {
        if (g != gen || data.size < 5) return
        val cmdLo = data[2].toInt() and 0xff
        val cmdHi = data[3].toInt() and 0xff
        if (cmdLo == 0x01 && cmdHi == 0x80) {
            // Set-time reply: `0B 00 01 80 <type> ...`
            Log.i(TAG, "set-time reply ${hex(data)}")
            applyDeviceType(data[4].toInt() and 0xff)
        } else if (cmdLo == 0x05 && cmdHi == 0x80 && data.size >= 8) {
            Log.i(TAG, "firmware mcu=${data[4].toInt() and 0xff}.${"%02d".format(data[5].toInt() and 0xff)} " +
                "ble=${data[6].toInt() and 0xff}.${"%02d".format(data[7].toInt() and 0xff)}")
        }
        val cur = current
        val matches = cur != null && awaitingAck && cur.bytes.size >= 4 &&
            (cur.bytes[2].toInt() and 0xff) == cmdLo && (cur.bytes[3].toInt() and 0xff) == cmdHi
        if (matches) {
            finishCommand(g, cur!!, data[4].toInt() and 0xff, data)
        } else {
            Log.i(TAG, "notify ${hex(data)} (cur=${cur?.label} awaiting=$awaitingAck)")
        }
    }

    private fun finishCommand(g: Int, cmd: Cmd, state: Int, data: ByteArray?) {
        if (g != gen) return
        awaitingAck = false
        ackSeq++ // invalidates any pending timeout
        current = null
        val t = now()
        val transfer = if (cmd.tFirstWrite > 0 && cmd.tLastWriteCb > 0) cmd.tLastWriteCb - cmd.tFirstWrite else -1
        val panel = if (cmd.tLastWriteCb > 0 && state >= 0) t - cmd.tLastWriteCb else -1
        Log.i(TAG, "cmd=${cmd.label} bytes=${cmd.bytes.size} chunks=${cmd.chunks} " +
            "transfer=${transfer}ms panel=${panel}ms total=${t - cmd.tQueued}ms state=$state" +
            (if (data != null) " ack=${hex(data)}" else ""))
        if (cmd.label == "wipe") {
            // Quiet time starts once the erase command is in.
            holdUntil = t + WIPE_HOLD_MS
        }
        if (cmd.isFrame) {
            frameCount++
            val accepted = state == 0x03 || (cmd.isCameraFrame && state == 0x01)
            if (accepted) frameOk++
            if (state == 0x00 && cmd.expectsAck && !cmd.isCameraFrame) {
                frameCrcFail++
                if (cmd.retries++ < MAX_FRAME_RETRIES) {
                    Log.w(TAG, "CRC FAILED on ${cmd.label}, resending (${cmd.retries})")
                    queue.addFirst(cmd)
                    dispatchNext(g)
                    return
                }
            }
            if (cmd.isCameraFrame && cmd.expectsAck) {
                // Panels without the live path never answer 0x0000: after a
                // few silent frames switch to the proven PNG/0x0002 path.
                if (state == -1) {
                    cameraTimeouts++
                    if (autoFallback && cameraTimeouts >= FALLBACK_AFTER_TIMEOUTS &&
                        frameMode == FRAME_CAMERA) {
                        Log.w(TAG, "0x0000 frames unacknowledged $cameraTimeouts times — " +
                            "falling back to ack-gated PNG over 0x0002")
                        frameMode = FRAME_PNG
                        writeWithResponse = true
                        status("CONNECTED $width*$height (LEGACY MODE)")
                    }
                } else if (accepted) {
                    cameraTimeouts = 0
                }
            }
            if (frameCount % 10 == 0) {
                val el = (t - statWindowStart).coerceAtLeast(1L)
                Log.i(TAG, "STATS frames=$frameCount ok=$frameOk crcfail=$frameCrcFail " +
                    "timeouts=$frameTimeouts fps=${"%.2f".format(10_000.0 / el)} mode=$frameMode")
                statWindowStart = t
            }
            if (cmd.thenDisable) {
                handler?.postDelayed({ if (g == gen) disable() }, BLANK_HOLD_MS)
                return
            }
            if (!shuttingDown) {
                // The panel is ready again: next frame, honouring the floor.
                val floor = if (minIntervalMs >= 0) minIntervalMs else frameIntervalMs
                val elapsed = t - frameStartedAt
                scheduleTick((floor - elapsed).coerceAtLeast(0L))
            }
        }
        dispatchNext(g)
        if (!cmd.isFrame && queue.isEmpty() && current == null && !shuttingDown) {
            scheduleTick(50L) // setup drained: start the frames
        }
    }

    // ------------------------------------------------------------ the frames

    private fun tick(g: Int) {
        if (g != gen || !connected || width == 0) return
        if (shuttingDown) return
        if (now() < holdUntil) {
            scheduleTick(200L)
            return
        }
        if (current != null || awaitingAck || queue.isNotEmpty()) {
            if (now() - lastWriteAt > ACK_TIMEOUT_MS + 1000L) {
                Log.w(TAG, "pipeline stalled on ${current?.label}, resetting")
                current = null
                awaitingAck = false
                queue.clear()
            } else {
                scheduleTick(50L)
                return
            }
        }
        val bmp = try {
            renderFrame?.invoke()
        } catch (_: Exception) {
            null
        }
        if (bmp == null) {
            scheduleTick(frameIntervalMs)
            return
        }
        frameStartedAt = now()
        val cmd = frameCmd(bmp, "frame")
        if (skipUnchanged && cmd.crc == lastFrameCrc) {
            scheduleTick(frameIntervalMs)
            return
        }
        lastFrameCrc = cmd.crc
        enqueue(g, cmd)
    }

    /** Builds the frame command for the selected encoding; recycles [bmp]. */
    private fun frameCmd(bmp: Bitmap, label: String, thenDisable: Boolean = false): Cmd {
        val cmd = when (frameMode) {
            FRAME_CAMERA -> {
                // go-ipxl Payload(TYPE_CAMERA): 9-byte header, no CRC, no slot.
                val rgb = rgb888(bmp)
                val fl = if (cameraFrameLen > 0) cameraFrameLen else rgb.size
                val total = 9 + rgb.size
                val head = byteArrayOf(
                    (total and 0xff).toByte(), ((total shr 8) and 0xff).toByte(),
                    0x00, 0x00, 0x00, *le32(fl)
                )
                Cmd(head + rgb, label, expectsAck = cameraAcks, isFrame = true,
                    thenDisable = thenDisable, crc = crc32(rgb))
            }
            FRAME_RAW -> {
                val rgb = rgb888(bmp)
                Cmd(imageHeader(rgb) + rgb, label, expectsAck = true, isFrame = true,
                    thenDisable = thenDisable, crc = crc32(rgb))
            }
            else -> {
                val pngStream = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 100, pngStream)
                val png = pngStream.toByteArray()
                Cmd(imageHeader(png) + png, label, expectsAck = true, isFrame = true,
                    thenDisable = thenDisable, crc = crc32(png))
            }
        }
        bmp.recycle()
        return cmd
    }

    /** Opcode 0x0002 header: size, CRC32 and slot 0 (show now, don't store). */
    private fun imageHeader(payload: ByteArray): ByteArray {
        val total = 15 + payload.size
        return byteArrayOf(
            (total and 0xff).toByte(), ((total shr 8) and 0xff).toByte(),
            0x02, 0x00, 0x00,
            *le32(payload.size), *le32(crc32(payload).toInt()),
            // Slot 0 = show immediately WITHOUT storing: frames written to a
            // stored slot make the panel replay its slot list in between.
            0x00, 0x00
        )
    }

    /** Row-major RGB888, top-left origin — the panel's native frame layout. */
    private fun rgb888(bmp: Bitmap): ByteArray {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val out = ByteArray(w * h * 3)
        var o = 0
        for (p in px) {
            out[o++] = (p shr 16).toByte()
            out[o++] = (p shr 8).toByte()
            out[o++] = p.toByte()
        }
        return out
    }

    private fun crc32(b: ByteArray): Long = CRC32().apply { update(b) }.value

    private fun le32(v: Int): ByteArray = byteArrayOf(
        (v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(),
        ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte()
    )

    private fun hex(b: ByteArray): String = b.joinToString(" ") { "%02x".format(it) }
}
