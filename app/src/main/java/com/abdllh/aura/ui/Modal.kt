package com.abdllh.aura.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * A card over a dimmed screen (Confirm, the input and enrolment dialogs; SPEC.md section 5). The dialog's window does no motion of
 * its own (the system's dialog animation cuts on this firmware, whose animation scales ship at zero, and its dim was not ours): the
 * window covers the screen, holds its own flat scrim, and the card comes and goes by the laws of section 9: alpha, scale .98 to 1,
 * 200 `enter`; out alpha, 140 `exit`. Back and a tap on the scrim close it through the same animation (while [cancelable]); the
 * dialog's dismiss listener still runs, at the end of it.
 * 560 wide, height to fit and at most 400, the `overlay` 9-patch centred in y 44..506; with a field ([followKeyboard]) its bottom
 * sits above the keyboard. Never while the car moves: [dismissAll] is what the car's starting to move calls, as Cancel.
 * Build the card with [card], fill it, then `Modal(ctx, card).show()`.
 */
class Modal(private val ctx: Context, private val card: View, widthDp: Int = WIDTH) {
    val dialog = Dialog(ctx)
    private val scrim = object : View(ctx) { override fun hasOverlappingRendering() = false }
        .apply { setBackgroundColor(Palette.scrim) }
    private var closing = false
    var cancelable = true
    private val outset = Surface.OVERLAY.outset
    private val root: FrameLayout

    /** Its colours at a theme change: the flat scrim is here, the card takes care of itself. */
    private inner class Root : FrameLayout(ctx), Recolours {
        // (once closing, touches are spent here: a second tap on the action while the card fades must not act again)
        override fun dispatchTouchEvent(e: MotionEvent): Boolean = closing || super.dispatchTouchEvent(e)

        override fun onLook() = scrim.setBackgroundColor(Palette.scrim)

        // (centred in the content's y 44..506, whatever the card's bounds with their outset: it may be taller than that)
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            super.onLayout(changed, l, t, r, b)
            val h = b - t
            val centre = (Tokens2.CONTENT_TOP + (h - (600 - Tokens2.CONTENT_BOTTOM))) / 2
            val top = centre - card.measuredHeight / 2
            card.layout(card.left, top, card.right, top + card.measuredHeight)
        }
    }

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        root = Root()
        root.addView(scrim, flp(MATCH, MATCH))
        root.addView(card, flp(widthDp + 2 * outset, WRAP, Gravity.CENTER_HORIZONTAL))
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
        dialog.setOnDismissListener { Look.closed(root); open.remove(this) }
    }

    fun show(): Modal {
        dialog.show()
        Look.opened(root)
        open.add(this)
        // (its window is on top of every panel: it goes when the panel under it does)
        com.abdllh.aura.home.Panel.own(ctx, { dialog.isShowing }, { dialog.dismiss() })
        scrim.alpha = 0f
        scrim.fadeTo(1f, IN_MS, Motion.ENTER)
        card.enter(scaleFrom = 0.98f, dur = IN_MS)
        return this
    }

    /** Closes with the exit motion, then dismisses the dialog and runs [then]. A second call while closing does nothing. */
    fun close(then: (() -> Unit)? = null) {
        if (closing) return
        closing = true
        card.exit(dur = OUT_MS)
        scrim.fadeTo(0f, OUT_MS, Motion.EXIT) {
            try { dialog.dismiss() } catch (_: Throwable) { } // (its activity may be gone meanwhile)
            then?.invoke()
        }
    }

    /**
     * It has a field: the keyboard comes, and the card's bottom is kept above it. The system only pans a window as far as the
     * focused field needs, which could leave the card's buttons under the keyboard; the card moves up by itself instead.
     */
    fun followKeyboard(): Modal {
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        val seen = Rect()
        root.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (!root.isAttachedToWindow) return
                root.getWindowVisibleDisplayFrame(seen)
                val at = IntArray(2)
                root.getLocationInWindow(at)
                val visibleBottom = seen.bottom - seen.top - at[1] + seen.top
                val keyboardUp = visibleBottom < root.height - KEYBOARD_MIN
                val cardBottom = card.bottom - outset
                val want = if (keyboardUp) minOf(0, visibleBottom - KEYBOARD_GAP - cardBottom) else 0
                if (card.translationY != want.toFloat()) card.animate().translationY(want.toFloat()).setDuration(IN_MS).setInterpolator(Motion.ENTER).start()
            }
        })
        return this
    }

    companion object {
        private const val WIDTH = 560
        private const val MAX_H = 400
        private const val IN_MS = Tokens2.MS_banner
        private const val OUT_MS = Tokens2.MS_bannerOut
        private const val KEYBOARD_MIN = 100
        private const val KEYBOARD_GAP = 8
        private val open = ArrayList<Modal>()

        /** The car started moving: every dialog goes as Cancel (section 6.0). */
        fun dismissAll() {
            for (m in open.toList()) m.close()
        }

        /** The modal card: the `overlay` surface, the dialogs' padding; children go in one column. Give it a title and a body with [title], [body]. */
        fun card(ctx: Context): LinearLayout = SurfaceColumn(ctx, Surface.OVERLAY, MAX_H).apply {
            val o = Surface.OVERLAY.outset
            setPadding(o + 28, o + 22, o + 28, o + 20)
        }

        /** The title in `stateTitle`. */
        fun title(ctx: Context, s: CharSequence?): AText = ctx.label(Type.stateTitle, { Palette.ink }, lines = 2).apply { text = s }

        /** The body in `stateBody`, as many lines as it wants. */
        fun body(ctx: Context, s: CharSequence?): AText = ctx.label(Type.stateBody, { Palette.ink2 }, lines = 0).apply { text = s }

        /**
         * The buttons in a row of equal widths, gap 8, with [action] nearest the driver: on the left in both languages. [others]
         * lie to its right.
         */
        fun buttons(ctx: Context, action: View, vararg others: View): LinearLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            addView(action, lp(0, WRAP, 1f))
            for (b in others) addView(b, lp(0, WRAP, 1f).apply { leftMargin = 8 })
        }
    }
}
