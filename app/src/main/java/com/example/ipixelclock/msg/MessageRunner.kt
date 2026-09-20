package com.example.ipixelclock.msg

import com.example.ipixelclock.font.PixelFont
import com.example.ipixelclock.font.PixelFonts
import com.example.ipixelclock.render.PixelCanvas
import com.example.ipixelclock.settings.Settings
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The custom message: a line of text that takes the panel over for a few
 * seconds, on demand or on a schedule, and then hands it straight back.
 *
 * ### Threads
 *
 * Everything here runs on the render thread except [showNow], which is called
 * from a web connection or the app's UI thread and does nothing but set a flag.
 * That is the whole of the cross-thread surface: no message state is read or
 * written off the render thread, so there is no lock to take on the path that
 * has to keep the panel fed.
 *
 * ### Timing
 *
 * A run is `repeat` passes over the pages, each page held for the effect's own
 * step, all of it measured from one start timestamp. Nothing counts frames — see
 * [MessageEffect]. A run that overruns its welcome because the panel stalled
 * simply ends on time with the last frames unseen, rather than finishing late.
 *
 * ### The schedule
 *
 * Firing is anchored to the wall clock rather than to a timer started at boot:
 * "every 15 minutes" means :00, :15, :30, :45, the same slots after a restart as
 * before it. The slot in force when scheduling is first seen is recorded and not
 * fired, so enabling the toggle does not immediately throw a message on screen —
 * the first showing is at the next boundary. SHOW NOW is there for right away.
 */
class MessageRunner {

    private val triggered = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    /**
     * Requests a showing at the next frame. Callable from any thread.
     *
     * Deliberately works whether or not the feature's own switch is on: it is
     * the button you press to see what the text will look like, and one that did
     * nothing until a separate toggle was found would be a poor way to say so.
     */
    fun showNow() {
        cancelled.set(false)
        triggered.set(true)
    }

    /**
     * Ends the showing in progress. Callable from any thread.
     *
     * A flag rather than clearing the run directly: the run is render-thread
     * state, and a web connection reaching in to null it while the render thread
     * is midway through reading it is exactly the kind of tear this project
     * takes a copy under a lock to avoid elsewhere.
     */
    fun cancel() {
        triggered.set(false)
        cancelled.set(true)
    }

    // ------------------------------------------------- render-thread state

    private class Run(
        val startMs: Long,
        val effect: MessageEffect,
        val pages: List<MessageArt>,
        val stepMs: Long,
        val steps: Int,
        val canvasW: Int,
        val canvasH: Int
    ) {
        val totalMs: Long = stepMs * steps
    }

    private var run: Run? = null

    /** The schedule slot last fired. [UNSET] until scheduling has been seen. */
    private var firedSlot = UNSET

    /** True while a message is on the panel, so the loop keeps its frame rate up. */
    val active: Boolean get() = run != null

    /**
     * Draws the message if one is due, and reports whether it took the panel.
     *
     * False means the caller should draw the clock as usual.
     */
    fun frame(settings: Settings, canvas: PixelCanvas, nowMs: Long): Boolean {
        if (cancelled.getAndSet(false)) run = null

        val wanted = triggered.getAndSet(false) || scheduleDue(settings, nowMs)
        if (wanted) start(settings, canvas, nowMs)

        val r = run ?: return false

        // A run is built around one geometry. If the panel is re-mounted or a
        // different one connects mid-message the pages no longer fit, so the run
        // is dropped rather than drawn wrong; the next showing is built for the
        // new size.
        if (r.canvasW != canvas.width || r.canvasH != canvas.height) {
            run = null
            return false
        }

        val elapsed = nowMs - r.startMs
        if (elapsed < 0L || elapsed >= r.totalMs) {
            run = null
            return false
        }

        val step = (elapsed / r.stepMs).toInt().coerceIn(0, r.steps - 1)
        val page = step % r.pages.size
        val inStep = elapsed - step * r.stepMs

        // The message's own backdrop, when it has been given one. Painted over
        // whatever the background engine just drew rather than instead of it:
        // the point of the switch is a notice that stays legible over matrix
        // rain, and that means covering the rain for as long as it is up.
        if (settings.messageCustomBackground) canvas.clear(settings.messageBackground)

        r.effect.draw(
            MessageContext(
                canvas = canvas,
                art = r.pages[page],
                // Off, the message wears the clock's colour, so an interruption
                // still looks like the same device.
                color = if (settings.messageCustomColor) settings.messageColor
                else settings.colorPrimary,
                progress = inStep.toFloat() / r.stepMs,
                elapsedMs = inStep,
                stepMs = r.stepMs,
                page = page,
                pageCount = r.pages.size,
                pass = step / r.pages.size,
                seed = (r.startMs.toInt() * 31) + step
            )
        )
        return true
    }

    // ------------------------------------------------------------ the start

    private fun start(settings: Settings, canvas: PixelCanvas, nowMs: Long) {
        val font = messageFont(canvas.height)
        val effect = MessageEffects.of(settings.messageEffect)
        val texts = pageTexts(settings, font, canvas.width, effect)
        if (texts.isEmpty()) {
            run = null
            return
        }
        run = Run(
            startMs = nowMs,
            effect = effect,
            pages = texts.map { MessageArt(it, font) },
            stepMs = stepFor(settings, effect, texts, font, canvas.width, canvas.height),
            steps = texts.size * settings.messageRepeat.coerceIn(1, 20),
            canvasW = canvas.width,
            canvasH = canvas.height
        )
    }

    /** The message split the way the chosen effect needs it. */
    private fun pageTexts(
        settings: Settings,
        font: PixelFont,
        canvasW: Int,
        effect: MessageEffect
    ): List<String> {
        val text = settings.messageText.trim()
        if (text.isEmpty()) return emptyList()
        return if (effect.paged) MessagePager.pages(text, font, canvasW) else listOf(text)
    }

    /**
     * One step length for every page, taken from the widest.
     *
     * Pages of different lengths each running at their own natural speed reads
     * as the panel hesitating rather than as a rhythm.
     */
    private fun stepFor(
        settings: Settings,
        effect: MessageEffect,
        texts: List<String>,
        font: PixelFont,
        canvasW: Int,
        canvasH: Int
    ): Long {
        var widest = 1
        for (t in texts) {
            val w = font.measure(t)
            if (w > widest) widest = w
        }
        return effect.stepMs(
            settings.messageSpeed,
            MessageMetrics(widest, font.height, canvasW, canvasH)
        ).coerceAtLeast(200L)
    }

    /** What a showing would consist of, for the UI to report back. */
    class Plan(val pages: Int, val stepMs: Long, val totalMs: Long)

    /**
     * Works out a showing without starting one.
     *
     * Pure — it reads no run state and allocates no pixel masks, only measuring
     * the text — so the web threads may call it. That matters: `state()` is
     * pushed on every settings change, and rasterising a 512-character message
     * each time a slider moved would be real work on the wrong thread.
     */
    fun plan(settings: Settings, canvasW: Int, canvasH: Int): Plan? {
        if (canvasW <= 0 || canvasH <= 0) return null
        val font = messageFont(canvasH)
        val effect = MessageEffects.of(settings.messageEffect)
        val texts = pageTexts(settings, font, canvasW, effect)
        if (texts.isEmpty()) return null
        val step = stepFor(settings, effect, texts, font, canvasW, canvasH)
        val steps = texts.size * settings.messageRepeat.coerceIn(1, 20)
        return Plan(texts.size, step, step * steps)
    }

    /**
     * The face the message is drawn in.
     *
     * SYSTEM at the largest whole multiple of its 7 rows that fits, which on a
     * 16-row panel is 14 — the "regular 16 pixel height font". Whole multiples
     * only: a fractionally scaled 7-row font has broken stems. A font picker of
     * its own is a later job; when it lands this is the one call to change.
     */
    private fun messageFont(height: Int): PixelFont {
        val base = PixelFonts.SYSTEM.shortest
        val factor = (height / base.height).coerceAtLeast(1)
        return if (factor >= 2) base.scaled(factor) else base
    }

    // --------------------------------------------------------- the schedule

    private fun scheduleDue(settings: Settings, nowMs: Long): Boolean {
        if (!settings.messageEnabled || !settings.messageSchedule) {
            // Forget where we were, so switching the schedule back on waits for
            // the next boundary instead of firing on the spot.
            firedSlot = UNSET
            return false
        }
        val every = settings.messageEveryMinutes.coerceIn(1, 1440)
        val slot = slotOf(nowMs, every)
        if (firedSlot == UNSET) {
            firedSlot = slot
            return false
        }
        if (slot == firedSlot) return false
        firedSlot = slot
        return true
    }

    /**
     * Which repeat of the interval the local clock is in.
     *
     * Local time, from the offset rather than a Calendar: this is asked on every
     * frame and there is no reason to allocate for it. No `java.time` — the app
     * runs on API 21.
     */
    private fun slotOf(nowMs: Long, everyMinutes: Int): Int {
        val local = nowMs + TimeZone.getDefault().getOffset(nowMs)
        val minuteOfDay = ((local / 60000L) % 1440L).toInt()
        return minuteOfDay / everyMinutes
    }

    private companion object {
        const val UNSET = -1
    }
}
