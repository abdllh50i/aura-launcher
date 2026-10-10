package com.abdllh.aura.voice

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import com.abdllh.aura.R
import com.abdllh.aura.ui.ABtn
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lp
import com.abdllh.aura.util.dp

/**
 * Teaches the assistant the owner's "عمري": said [SAMPLES] times, each a word on its own (a pause before and after),
 * with a level meter that shows the microphone hears. A recording far from the others (a cough, a word cut short)
 * is asked for again. The background listener stays off the microphone meanwhile.
 */
internal object EnrollDialog {
    private const val SAMPLES = 4

    fun show(act: Activity, done: (Boolean) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        val d = Dialog(act)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28.dp, 24.dp, 28.dp, 22.dp)
            background = Shapes.rect(Palette.sheet, 26f, if (Palette.dark) Palette.stroke else 0)
        }
        card.addView(act.label(22f, Palette.text, Fonts.MEDIUM, lines = 2).apply { setText(R.string.amri_enroll_title) }, lp(MATCH, WRAP))
        val prompt = act.label(18f, Palette.text2, Fonts.REGULAR, lines = 3).apply { setLineSpacing(0f, 1.15f) }
        card.addView(prompt, lp(MATCH, WRAP).apply { topMargin = 10.dp })
        val dots = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val dotViews = (0 until SAMPLES).map { View(act).also { v -> dots.addView(v, lp(18.dp, 18.dp).apply { marginEnd = 10.dp }) } }
        card.addView(dots, lp(MATCH, WRAP).apply { topMargin = 18.dp })
        val meter = Meter(act)
        card.addView(meter, lp(MATCH, 14.dp).apply { topMargin = 18.dp })
        val status = act.label(15f, Palette.text3, Fonts.REGULAR, lines = 2)
        card.addView(status, lp(MATCH, WRAP).apply { topMargin = 10.dp })
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        row.addView(ABtn(act).apply {
            kind = ABtn.Kind.TONAL
            setText(R.string.btn_cancel)
            setPadding(26.dp, 0, 26.dp, 0)
            setOnClickListener { d.dismiss() }
        }, lp(WRAP, 56.dp))
        card.addView(row, lp(MATCH, WRAP).apply { topMargin = 20.dp })

        val words = ArrayList<Spectrum>()
        var saved = false
        var ended = false
        fun paint() {
            for ((i, v) in dotViews.withIndex()) v.background = Shapes.oval(if (i < words.size) Palette.accent else Palette.withAlpha(Palette.text3, 0.35f))
            prompt.text = act.getString(R.string.amri_enroll_say, words.size + 1, SAMPLES)
        }
        paint()

        Amri.enrolling = true
        AmriService.pauseMic()
        val seg = Segmenter(maxMs = 1600, onLevel = { l -> main.post { meter.level = l } }) { pcm, noise ->
            val f = Features.spectrum(pcm, noise)
            main.post {
                if (saved || ended) return@post
                // 0.25 s to 1.5 s: a single word (a word cut short or a whole sentence is not it)
                if (f.frames !in 25..150) { status.setText(R.string.amri_enroll_again); return@post }
                if (words.size >= 2) {
                    // far from all the others: probably not the same word (a cough, a different word)
                    val m = WakeModel(words)
                    if (m.score(f) > 2.2f) { status.setText(R.string.amri_enroll_different); return@post }
                }
                words.add(f)
                status.setText(R.string.amri_enroll_good)
                if (words.size == SAMPLES) {
                    // one of the first two may be the odd one out (they were not checked against others)
                    val odd = WakeModel(words).outlier()
                    if (odd >= 0) {
                        words.removeAt(odd)
                        status.setText(R.string.amri_enroll_redo)
                        paint()
                        return@post
                    }
                    if (!WakeModel.save(act, words)) {
                        words.clear()
                        status.setText(R.string.amri_enroll_save_failed)
                        paint()
                        return@post
                    }
                    saved = true
                    paint()
                    prompt.setText(R.string.amri_enroll_done)
                    main.postDelayed({ if (!act.isFinishing && !act.isDestroyed && d.isShowing) try { d.dismiss() } catch (_: Throwable) { } }, 1200)
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
        d.setContentView(card)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(0))
            setLayout(560.dp, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        d.show()
    }

    /** The microphone's level over the background. */
    private class Meter(c: Context) : View(c) {
        var level = 0f
            set(v) { field = field + (v - field) * 0.5f; invalidate() }
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.withAlpha(Palette.text3, 0.25f) }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.accent }
        private val r = RectF()

        override fun onDraw(c: Canvas) {
            val h = height.toFloat()
            r.set(0f, 0f, width.toFloat(), h)
            c.drawRoundRect(r, h / 2, h / 2, track)
            r.right = (width * level).coerceAtLeast(h)
            c.drawRoundRect(r, h / 2, h / 2, fill)
        }
    }
}
