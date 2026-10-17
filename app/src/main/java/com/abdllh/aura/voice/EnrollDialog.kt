package com.abdllh.aura.voice

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.abdllh.aura.R
import com.abdllh.aura.ui.ABtn
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Modal
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.Space
import com.abdllh.aura.ui.Spring
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.anim
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lp
import com.abdllh.aura.util.dp

/**
 * Teaches the assistant the owner's "عمري" (or, for use without the internet, one of the requests): said a few times,
 * each on its own (a pause before and after), with a level meter that shows the microphone hears. A recording far
 * from the others (a cough, a word cut short, a bump) is asked for again. The background listener stays off the
 * microphone meanwhile.
 */
internal object EnrollDialog {
    /** What is taught: [prompt] (n, of) says what to say; the recordings go to [file]. */
    class Spec(
        val title: String,
        val prompt: (Int, Int) -> String,
        val samples: Int,
        val maxMs: Int,
        val endHops: Int,
        val frames: IntRange,
        val file: (Activity) -> java.io.File,
        val doneText: Int = R.string.amri_enroll_done
    )

    /** The wake word: six times (more of the owner's ways of saying it: a truer "own variation" and fewer misses). */
    fun show(act: Activity, done: (Boolean) -> Unit) = show(act, Spec(
        act.getString(R.string.amri_enroll_title), { n, of -> act.getString(R.string.amri_enroll_say, n, of) },
        samples = 6, maxMs = 1600, endHops = 20, frames = 25..150, file = { WakeModel.file(it) }), done)

    /** A request for use without the internet ([label]: what it does): three times, up to 2.6 s, pauses inside it. */
    fun teach(act: Activity, label: String, file: java.io.File, done: (Boolean) -> Unit) = show(act, Spec(
        act.getString(R.string.amri_teach_title, label), { n, of -> act.getString(R.string.amri_teach_say, label, n, of) },
        samples = 3, maxMs = 2600, endHops = 40, frames = 25..260, file = { file }, doneText = R.string.amri_teach_done), done)

    fun show(act: Activity, spec: Spec, done: (Boolean) -> Unit) {
        val samples = spec.samples
        val main = Handler(Looper.getMainLooper())
        // a Modal: it comes and goes like the app's other cards (until 1.12 a bare dialog that cut in and out)
        val card = Modal.card(act)
        val m = Modal(act, card)
        val d = m.dialog
        card.addView(act.label(Type.TITLE, Palette.text, lines = 2).apply { text = spec.title }, lp(MATCH, WRAP))
        val prompt = act.label(Type.BODY, Palette.text2, lines = 3)
        card.addView(prompt, lp(MATCH, WRAP).apply { topMargin = Space.S.dp })
        val dots = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val dotViews = (0 until samples).map { View(act).also { v -> dots.addView(v, lp(18.dp, 18.dp).apply { marginEnd = Space.M.dp }) } }
        card.addView(dots, lp(MATCH, WRAP).apply { topMargin = Space.L.dp })
        val meter = Meter(act)
        card.addView(meter, lp(MATCH, 14.dp).apply { topMargin = Space.L.dp })
        val status = act.label(Type.FOOTNOTE, Palette.text3, lines = 2)
        card.addView(status, lp(MATCH, WRAP).apply { topMargin = Space.S.dp })
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        row.addView(ABtn(act).apply {
            kind = ABtn.Kind.TONAL
            setText(R.string.btn_cancel)
            setOnClickListener { m.close() }
        }, lp(WRAP, WRAP))
        card.addView(row, lp(MATCH, WRAP).apply { topMargin = Space.XL.dp })

        val words = ArrayList<Spectrum>()
        var saved = false
        var ended = false
        val filled = BooleanArray(samples)
        fun paint() {
            for ((i, v) in dotViews.withIndex()) {
                val on = i < words.size
                v.background = Shapes.oval(if (on) Palette.accent else Palette.track)
                // (a recording taken: its dot fills with a small pop, so the take is seen without reading the line)
                if (on && !filled[i]) { v.scaleX = 0.6f; v.scaleY = 0.6f; v.anim(Motion.MEDIUM, Motion.EMPHASIZED).scaleX(1f).scaleY(1f).start() }
                filled[i] = on
            }
            prompt.text = spec.prompt(words.size + 1, samples)
        }
        paint()

        Amri.enrolling = true
        AmriService.pauseMic()
        val seg = Segmenter(maxMs = spec.maxMs, onLevel = { l -> main.post { meter.level = l } }, endHops = spec.endHops) { pcm, noise ->
            val f = Features.spectrum(pcm, noise)
            val voice = VoiceCheck.isVoice(pcm, noise)
            main.post {
                if (saved || ended) return@post
                // 0.25 s to 1.5 s: a single word (a word cut short or a whole sentence is not it), and a voice (not
                // a bump, a door, a cough)
                if (f.frames !in spec.frames || !voice) { status.setText(R.string.amri_enroll_again); return@post }
                if (words.size >= 2) {
                    // far from all the others: probably not the same word (a cough, a different word)
                    val m = WakeModel(words)
                    if (m.score(f) > 2.2f) { status.setText(R.string.amri_enroll_different); return@post }
                }
                words.add(f)
                status.setText(R.string.amri_enroll_good)
                if (words.size == samples) {
                    // one of the first two may be the odd one out (they were not checked against others)
                    val odd = WakeModel(words).outlier()
                    if (odd >= 0) {
                        words.removeAt(odd)
                        status.setText(R.string.amri_enroll_redo)
                        paint()
                        return@post
                    }
                    if (!WakeModel.save(spec.file(act), words)) {
                        words.clear()
                        status.setText(R.string.amri_enroll_save_failed)
                        paint()
                        return@post
                    }
                    saved = true
                    paint()
                    prompt.setText(spec.doneText)
                    main.postDelayed({ if (!act.isFinishing && !act.isDestroyed && d.isShowing) m.close() }, 1200)
                    return@post
                }
                paint()
            }
        }
        val loop = MicLoop(AmriService.device(act, Amri.micId), seg) { err -> main.post { if (!ended) status.text = err } }
        loop.start()

        // One way out, however it goes: dismissed, or Settings left (HOME) with the dialog up. A dialog whose activity
        // is destroyed loses its window without telling its dismiss listener: the microphone would stay open, the
        // wake word listener off, and the next sounds in the car would replace the owner's recordings.
        lateinit var watch: Application.ActivityLifecycleCallbacks
        fun end() {
            if (ended) return
            ended = true
            act.application.unregisterActivityLifecycleCallbacks(watch)
            loop.quit()
            // the wake word listener back on the microphone once this recording has let go of it
            Thread {
                try { loop.join(1500) } catch (_: InterruptedException) { }
                main.post { Amri.enrolling = false; AmriService.resumeMic() }
            }.start()
            if (!act.isDestroyed) done(saved)
        }
        watch = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStopped(a: Activity) {
                if (a !== act) return
                try { if (d.isShowing) d.dismiss() } catch (_: Throwable) { }
                end()
            }
            override fun onActivityDestroyed(a: Activity) { if (a === act) end() }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        }
        act.application.registerActivityLifecycleCallbacks(watch)
        d.setOnDismissListener { end() }
        m.show()
    }

    /**
     * The microphone's level over the background, filled from the start side (the right in Arabic, as every bar of
     * the app). The level comes with each piece of sound; a spring carries the bar between them instead of a step.
     */
    private class Meter(c: Context) : View(c) {
        private val glide = Spring(0f, 400f, 0.9f, 0.002f) { invalidate() }
        var level: Float
            get() = glide.target
            set(v) { glide.animateTo(v.coerceIn(0f, 1f)) }
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.track }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.accent }
        private val r = RectF()

        override fun onDetachedFromWindow() {
            glide.cancel()
            super.onDetachedFromWindow()
        }

        override fun onDraw(c: Canvas) {
            val h = height.toFloat()
            val w = width.toFloat()
            r.set(0f, 0f, w, h)
            c.drawRoundRect(r, h / 2, h / 2, track)
            val len = (w * glide.value.coerceIn(0f, 1f)).coerceAtLeast(h)
            if (layoutDirection == LAYOUT_DIRECTION_RTL) r.set(w - len, 0f, w, h) else r.set(0f, 0f, len, h)
            c.drawRoundRect(r, h / 2, h / 2, fill)
        }
    }
}
