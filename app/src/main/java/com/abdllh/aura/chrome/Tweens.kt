package com.abdllh.aura.chrome

import android.animation.TimeInterpolator
import android.view.Choreographer

/**
 * Timed values that run on one frame callback and turn back from where they are: a new [Part.to] takes over a part at its present value, and nothing
 * is queued (SPEC.md section 9). Time here is its own: the first frame already counts one frame, so the first frame answers, and a frame that took
 * long (a panel's first draw) moves it on by two frames at most, so a slow frame costs a little time and never a jump. Not an animator, so the firmware's
 * animator scale does not touch it; whether things move at all is the caller's (the owner's switch).
 */
internal class Tweens(private val onFrame: () -> Unit = {}, private val onRest: () -> Unit = {}) : Choreographer.FrameCallback {
    private val parts = ArrayList<Part>()
    private var running = false
    private var last = 0L

    inner class Part(initial: Float, private val set: (Float) -> Unit) {
        var value = initial
            private set
        private var from = initial
        private var to = initial
        private var delay = 0f
        private var dur = 0f
        private var curve: TimeInterpolator = TimeInterpolator { it }
        private var t = 0f
        internal var live = false

        fun to(target: Float, delayMs: Long, durMs: Long, interp: TimeInterpolator) {
            from = value
            to = target
            delay = delayMs.toFloat()
            dur = durMs.toFloat()
            curve = interp
            t = 0f
            live = true
            start()
        }

        fun snap(v: Float) {
            live = false
            value = v
            from = v
            to = v
            set(v)
        }

        internal fun step(dt: Float) {
            t += dt
            at(t)
        }

        /** Where it is [ms] after its [to] was called. */
        internal fun at(ms: Float) {
            val f = ((ms - delay) / dur).coerceIn(0f, 1f)
            value = if (f >= 1f) to else from + (to - from) * curve.getInterpolation(f)
            set(value)
            if (f >= 1f) live = false
        }
    }

    fun part(initial: Float, set: (Float) -> Unit): Part = Part(initial, set).also { parts.add(it); set(initial) }

    val isRunning: Boolean get() = running

    private fun start() {
        if (running) return
        running = true
        last = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    /** Debug builds' still pictures of a move: every part where it is [ms] in, and nothing runs. */
    fun holdAt(ms: Float) {
        stop()
        for (p in parts) if (p.live) { p.at(ms); p.live = true }
        onFrame()
    }

    override fun doFrame(ns: Long) {
        if (!running) return
        val dt = if (last == 0L) FRAME_MS else ((ns - last) / 1e6f).coerceIn(0f, MOST_MS)
        last = ns
        var more = false
        for (p in parts) if (p.live) {
            p.step(dt)
            if (p.live) more = true
        }
        onFrame()
        if (more) Choreographer.getInstance().postFrameCallback(this)
        else {
            running = false
            onRest()
        }
    }

    private companion object {
        const val FRAME_MS = 16.67f
        const val MOST_MS = 34f
    }
}
