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

    /**
     * The panel's physical buffer — the same frame after the orientation
     * transform, which is what the driver is handed. Kept separately because on
     * a vertical mount it is not the same picture as [frameSnapshot].
     */
    private var panelSnapshot: PixelCanvas? = null

    private val frameLock = Any()

    /** The render thread. Composites frames; touches neither Bluetooth nor sockets. */
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    /** Preview fan-out runs here, never on the render thread. See [publishPreview]. */
    private var previewThread: HandlerThread? = null
    private var previewHandler: Handler? = null
    private val previewPending = java.util.concurrent.atomic.AtomicBoolean(false)

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

        val t = HandlerThread("ipixel-render").also { it.start() }
        thread = t
        handler = Handler(t.looper)

        val p = HandlerThread("ipixel-preview", android.os.Process.THREAD_PRIORITY_BACKGROUND)
            .also { it.start() }
        previewThread = p
        previewHandler = Handler(p.looper)

        startForegroundNotice()
        server.start()

        if (s.startOnBoot || s.displayOn) {
            // Rendering always runs; whether it reaches a panel is a separate
            // question answered by panelRequested.
            scheduleRender(0L)
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
        scheduleRender(0L)
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
        previewHandler?.removeCallbacksAndMessages(null)
        previewThread?.quitSafely()
        previewThread = null
        previewHandler = null
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
        hub.renderFrame = { latestPanelBitmap() }

        // A clock is mostly still. The driver ships with dedupe off because a
        // telemetry page changes every frame, but here the face is identical
        // between colon blinks, and the panel needs 100-250 ms to swallow a
        // frame it did not need. Dropping the repeats hands that time back to
        // the frames that do change, so a transition gets the full rate.
        // Overridable by the "dedupe" key in ipixel_lab.xml, below.
        hub.skipUnchanged = true

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
    //
    // Four threads, and the split is the point:
    //
    //   render   composites frames and nothing else
    //   ipixel   the driver's own thread: chunking, GATT writes, acks
    //   preview  WebSocket fan-out and the in-app preview
    //   web      one per HTTP connection
    //
    // What makes this worth doing is that IPixelHub pulls frames by calling
    // renderFrame() *on its own thread*, and it is a strict one-frame-at-a-time
    // pipeline: every millisecond spent inside that call is a millisecond the
    // panel is not being written to. Compositing a face with a transition
    // running, on a 2013 tablet, is not free. So the render loop runs
    // independently and keeps a finished frame ready; renderFrame() just takes
    // a copy of it and returns. The driver never waits for the renderer, the
    // renderer never waits for Bluetooth, and neither waits for a browser.

    private val renderTick = Runnable { renderLoop() }

    private fun scheduleRender(delayMs: Long) {
        handler?.removeCallbacks(renderTick)
        handler?.postDelayed(renderTick, delayMs)
    }

    /**
     * The render loop. Runs on its own thread, at its own cadence, whether or
     * not a panel is attached — the previews are fed the same frames either way.
     */
    private fun renderLoop() {
        val s = store.current
        val live = panelRequested && livePanel.isLive
        val w = if (live) hub.width else simulated.width
        val h = if (live) hub.height else simulated.height
        renderOnce(w, h)

        // A still clock does not need twelve frames a second. The driver's own
        // dedupe drops the repeats anyway, but not rendering them at all saves
        // the CPU as well as the radio.
        val interval = if (isAnimated(s)) s.frameIntervalMs.toLong() else 500L
        scheduleRender(interval)
    }

    private fun isAnimated(s: Settings): Boolean =
        s.background != "off" ||
            s.colorMode == "rainbow" || s.colorMode == "gradient-animated" ||
            s.visibility == "duty" || s.showSeconds || s.blinkColon

    /**
     * Composites one frame and stores it. Render thread only.
     *
     * Two snapshots come out of it, because they are not the same picture on a
     * vertical mount: [panelSnapshot] is the panel's physical buffer, after the
     * orientation transform, and [frameSnapshot] is the scene as it will look on
     * the wall, which is what a preview should show.
     */
    private fun renderOnce(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val s = store.current
        val canvas = renderer.render(s, w, h, System.currentTimeMillis()) ?: return
        countFrame()
        val shown = renderer.scene ?: canvas

        synchronized(frameLock) {
            panelSnapshot = reuse(panelSnapshot, canvas).also { it.copyFrom(canvas) }
            frameSnapshot = reuse(frameSnapshot, shown).also { it.copyFrom(shown) }
        }
        publishPreview()
    }

    private fun reuse(existing: PixelCanvas?, like: PixelCanvas): PixelCanvas =
        if (existing != null && existing.width == like.width && existing.height == like.height) {
            existing
        } else {
            PixelCanvas(like.width, like.height)
        }

    /**
     * What the driver gets when it asks for a frame: a copy of whatever the
     * render loop last finished, and no compositing on its thread.
     *
     * Null until the first frame exists, which the driver handles by waiting and
     * asking again.
     */
    private fun latestPanelBitmap(): Bitmap? {
        var changed = true
        val bmp = synchronized(frameLock) {
            val snap = panelSnapshot ?: return@synchronized null
            // Mirror the driver's own dedupe so the reported rate is frames the
            // panel actually receives. Counting every pull would overstate it
            // badly on a still clock: the driver asks a dozen times a second
            // and transmits twice.
            val hash = java.util.Arrays.hashCode(snap.pixels)
            changed = !hub.skipUnchanged || hash != lastPulledHash
            lastPulledHash = hash
            snap.toBitmap()
        }
        // This call is the panel's own pull — the driver asks exactly once per
        // frame it is about to consider sending. Counting here, rather than in
        // the render loop, is the difference between measuring the panel and
        // measuring ourselves, and the tuner has to measure the panel.
        if (bmp != null && changed) countPanelFrame()
        return bmp
    }

    /** Last frame handed to the driver, to tell a repeat from a new one. */
    private var lastPulledHash = 0

    /**
     * Hands the latest frame to the previews, off the render thread.
     *
     * This used to broadcast inline, and it cost about four fifths of the panel's
     * throughput. `renderFrame` is called on the driver's own thread, and the
     * driver is a strict one-frame-at-a-time pipeline: whatever that call does,
     * the panel waits for. Writing 7 kB to each WebSocket viewer — a blocking
     * socket write, subject to the viewer's own backpressure — put a browser on
     * the critical path of an LED panel. Measured on the 144x16: 14 fps with
     * nobody watching, 3.5 fps with one browser open.
     *
     * So the frame is copied under [frameLock] (cheap, one arraycopy) and the
     * fan-out runs on its own thread. Requests coalesce — if the previous
     * publish has not finished, this frame is simply the one that gets skipped,
     * because a preview that misses a frame is a preview, while a panel that
     * misses a frame is a stutter.
     */
    private fun publishPreview() {
        if (!previewPending.compareAndSet(false, true)) return
        val h = previewHandler ?: run { previewPending.set(false); return }
        h.post {
            previewPending.set(false)
            val frame = synchronized(frameLock) {
                val src = frameSnapshot ?: return@synchronized null
                PixelCanvas(src.width, src.height).also { it.copyFrom(src) }
            } ?: return@post
            try {
                server.broadcastFrame(frame)
            } catch (e: Exception) {
                Log.d(TAG, "preview broadcast failed", e)
            }
            previewListener?.let { l ->
                try {
                    l(frame)
                } catch (_: Exception) {
                }
            }
        }
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
    /** Frames the panel accepted per second. The number that matters. */
    @Volatile
    private var measuredFps = 0.0
    private var frameTally = 0
    private var tallyStartMs = 0L

    /** Frames composited per second. Informational — it should exceed the panel. */
    @Volatile
    private var renderFps = 0.0
    private var renderTally = 0
    private var renderStartMs = 0L

    /** Called once per frame the driver pulls, i.e. per frame the panel takes. */
    private fun countPanelFrame() {
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

    /** Called once per composited frame, whether or not anything consumes it. */
    private fun countFrame() {
        renderTally++
        val now = android.os.SystemClock.elapsedRealtime()
        if (renderStartMs == 0L) {
            renderStartMs = now
            return
        }
        val elapsed = now - renderStartMs
        if (elapsed >= 3000L) {
            renderFps = renderTally * 1000.0 / elapsed
            renderTally = 0
            renderStartMs = now
        }
        // With no panel attached there is nothing pulling frames, so the render
        // loop stands in for the panel's clock — otherwise the simulated
        // preview would report zero.
        if (!(panelRequested && livePanel.isLive)) {
            measuredFps = renderFps
        }
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
        // onResolution can fire more than once for one connection — the name
        // gives a provisional size and the set-time reply confirms it. Without
        // this the second call restarts a probe that is already half done.
        if (tuningIndex >= 0 && w == tuningW && h == tuningH) return
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
        // Dedupe would make the probe measure how still the clock is rather
        // than how fast the panel is; a static face would score every candidate
        // identically at the blink rate. Restored in finishTuning.
        hub.skipUnchanged = false
        applyConfig(tuning.candidates[0])
        tuningNote = "measuring — ${tuning.candidates[0].label}"
        Log.i(TAG, "tuning ${w}x$h: trying ${tuning.candidates[0].label}")
        pushState()
    }

    /** Called per frame while a probe is running. */
    private fun stepTuning(now: Long) {
        if (tuningFrames == 0) {
            // Start the clock on the candidate's FIRST ACTUAL FRAME, not when
            // it was selected.
            //
            // The driver holds all traffic for three seconds after the ROM
            // erase (WIPE_HOLD_MS), and the probe begins the moment the panel
            // reports its size — which is inside that hold. Timing from
            // selection charged the dead time to whichever candidate happened
            // to run first, and that is always the driver's own tuned default.
            // It scored 3.6 fps on a path measured at 10.3 immediately
            // afterwards, and lost to a genuinely slower one.
            tuningStartMs = now
            tuningFrames = 1
            return
        }
        tuningFrames++
        val elapsed = now - tuningStartMs
        if (elapsed < PanelTuning.PROBE_MS && tuningFrames < PanelTuning.PROBE_FRAMES) return

        // tuningFrames counts the first frame too, and elapsed spans from it,
        // so the number of intervals is one fewer than the number of frames.
        val fps = (tuningFrames - 1) * 1000.0 / elapsed.coerceAtLeast(1L)
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
        // 0 means "not started": the next candidate's own first frame starts
        // its clock, so a mode switch costs it nothing.
        tuningFrames = 0
        applyConfig(tuning.candidates[tuningIndex])
        tuningNote = "measuring — ${tuning.candidates[tuningIndex].label}"
        pushState()
    }

    private fun finishTuning() {
        tuningIndex = -1
        hub.skipUnchanged = true
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
        scheduleRender(0L)
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
            put("renderFps", Math.round(renderFps * 10.0) / 10.0)
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
