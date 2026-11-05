package com.abdllh.aura.nav.drive

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.style.ReplacementSpan
import android.view.View
import com.abdllh.aura.R
import com.abdllh.aura.system.DriveState
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.enter
import com.abdllh.aura.ui.exit
import com.abdllh.aura.ui.ink

/** Cards and banners run at 0.7 of their time while the car moves (SPEC.md section 9). */
internal fun moveMs(ms: Long): Long = if (DriveState.moving) ms * 7 / 10 else ms

/** Visible and not on its way out. */
internal val View.up: Boolean get() = visibility == View.VISIBLE && getTag(R.id.aura_exiting) != true

/**
 * A card comes or goes by section 9's table: in, alpha and y +8 to 0 on `enter`; out, alpha on `exit`. Only when that changes, since the column is read on every fix. A card on a layer for
 * the run ([com.abdllh.aura.ui.anim]): a big surface fades as one. INVISIBLE, not GONE: every card is laid out once and keeps its place, so nothing is laid out for a card that comes.
 */
internal fun View.showCard(show: Boolean, inMs: Long = 180L, outMs: Long = 140L, dy: Float = 8f, delay: Long = 0L) {
    if (show == up) return
    if (!isAttachedToWindow || (parent as? View)?.isShown != true) {
        animate().cancel()
        setTag(R.id.aura_exiting, null)
        alpha = 1f
        translationY = 0f
        visibility = if (show) View.VISIBLE else View.INVISIBLE
        return
    }
    if (show) enter(dy = dy, delay = delay, dur = moveMs(inMs))
    else exit(dur = moveMs(outMs)) { visibility = View.INVISIBLE }
}

/** A line of a moving screen: one role, its weight by the theme, and a fade of it takes no off-screen buffer (a label with spans would ask for one). */
internal class Line(ctx: Context) : AText(ctx) {
    override fun hasOverlappingRendering() = false
}

internal fun Context.line(role: Type.Role, token: () -> Int, moving: Boolean = true): AText =
    Line(this).apply {
        oneLine(role, moving)
        alignStart = true
        ink(token)
    }

/** A space of exact width in a line of spans: fonts disagree about a space. */
internal class GapSpan(private val px: Int) : ReplacementSpan() {
    override fun getSize(p: Paint, t: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int = px

    override fun draw(c: Canvas, t: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, p: Paint) {}
}
