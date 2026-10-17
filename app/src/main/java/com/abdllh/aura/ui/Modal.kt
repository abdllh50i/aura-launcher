package com.abdllh.aura.ui

import android.app.Dialog
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.abdllh.aura.util.dp

/**
 * A centred card over a dimmed screen (Confirm, the input and enrolment dialogs). The dialog's window does no motion
 * of its own (the system's dialog animation cuts on this firmware, whose animation scales ship at zero, and its dim
 * was not the sheets' scrim): the window covers the screen, holds its own [Palette.scrim], and the card arrives like
 * everything else that arrives ([enter], [Motion.EMPHASIZED]) and goes quicker than it came ([exit]). Back and a tap
 * on the scrim close it through the same animation (while [cancelable]); the dialog's dismiss listener still runs,
 * at the end of it.
 * Build the card with [card], fill it, then `Modal(ctx, card).show()`.
 */
class Modal(ctx: Context, private val card: View, widthDp: Int = 560) {
    val dialog = Dialog(ctx)
    private val scrim = object : View(ctx) { override fun hasOverlappingRendering() = false }
        .apply { setBackgroundColor(Palette.scrim) }
    private var closing = false
    var cancelable = true

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        // (once closing, touches are spent here: a second tap on the action while the card fades must not act again)
        val root = object : FrameLayout(ctx) {
            override fun dispatchTouchEvent(e: MotionEvent): Boolean = closing || super.dispatchTouchEvent(e)
        }
        root.addView(scrim, flp(MATCH, MATCH))
        root.addView(card, flp(widthDp.dp, WRAP, Gravity.CENTER))
        card.isClickable = true // (a tap on the card is not a tap on the scrim)
        scrim.setOnClickListener { if (cancelable) close() }
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(0))
            setLayout(MATCH, MATCH)
            setWindowAnimations(0)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        dialog.setOnKeyListener { _, code, e ->
            if (code != KeyEvent.KEYCODE_BACK) false
            else { if (e.action == KeyEvent.ACTION_UP && cancelable) close(); true }
        }
    }

    fun show(): Modal {
        dialog.show()
        scrim.alpha = 0f
        scrim.fadeTo(1f)
        card.enter(scaleFrom = 0.96f)
        return this
    }

    /** Closes with the exit motion, then dismisses the dialog and runs [then]. A second call while closing does nothing. */
    fun close(then: (() -> Unit)? = null) {
        if (closing) return
        closing = true
        card.exit(scaleTo = 0.98f, dur = Motion.XSHORT)
        scrim.fadeTo(0f, Motion.SHORT, Motion.EXIT) {
            try { dialog.dismiss() } catch (_: Throwable) { } // (its activity may be gone meanwhile)
            then?.invoke()
        }
    }

    companion object {
        /** The modal card: the sheet's surface, [Radius.XL], the dialogs' padding; children go in one column. */
        fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28.dp, Space.XL.dp, 28.dp, 22.dp)
            background = Shapes.rect(Palette.sheet, Radius.XL, if (Palette.dark) Palette.stroke else 0)
            lift(Elev.OVERLAY)
        }
    }
}
