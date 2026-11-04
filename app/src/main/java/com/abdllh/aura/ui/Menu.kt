package com.abdllh.aura.ui

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import com.abdllh.aura.R

/**
 * A small floating menu (a long press on an app). At least 260 wide, rows 60 in `navItem`, on the `overlay` 9-patch (SPEC.md
 * section 5). It grows out of the item that was pressed (its pivot on that side) and, after a choice, shrinks away quickly; the
 * system's popup animations are off, since this firmware ships them at zero and they cut. The window is the card with its outset,
 * where a tap closes the menu like a tap anywhere else outside it.
 */
object Menu {
    class Item(val icon: Int, val text: String, val action: () -> Unit)

    private const val MIN_W = 260
    private const val ROW_H = 60
    private const val PAD = 8

    fun show(anchor: View, items: List<Item>) {
        if (items.isEmpty()) return
        val ctx: Context = anchor.context
        val o = Surface.OVERLAY.outset
        val box = SurfaceColumn(ctx, Surface.OVERLAY).apply { setPadding(o + PAD, o + PAD, o + PAD, o + PAD) }
        val frame = FrameLayout(ctx).apply { addView(box, flp(WRAP, WRAP)) }
        val popup = PopupWindow(frame, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        frame.setOnClickListener { popup.dismiss() }
        var chosen = false
        for (it in items) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumWidth = MIN_W - 2 * PAD
                setPaddingRelative(14, 0, 22, 0)
                val press = Tint(ctx, R.drawable.plate_18) { Palette.fillPress }
                background = PressGesture.plate(press.drawable)
                look { press.recolour() }
                isClickable = true
                setOnClickListener { _ ->
                    if (chosen) return@setOnClickListener
                    chosen = true
                    it.action()
                    box.exit(scaleTo = 0.96f, dur = Motion.XSHORT) { popup.dismiss() }
                }
            }
            if (it.icon != 0) row.addView(ImageView(ctx).apply { setImageResource(it.icon) }.glyph { Palette.icon }, lp(26, 26).apply { marginEnd = 14 })
            row.addView(ctx.label(Type.navItem, { Palette.ink }).apply { text = it.text }, lp(WRAP, Type.navItem.viewH))
            box.addView(row, lp(MATCH, ROW_H))
        }
        popup.setBackgroundDrawable(ColorDrawable(0))
        popup.isOutsideTouchable = true
        popup.animationStyle = 0
        popup.isClippingEnabled = false // (the shadow's margin may reach past the screen's edge; the box itself never does)
        frame.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val w = box.measuredWidth - 2 * o
        val h = box.measuredHeight - 2 * o
        // above the anchor when there is room (the dock is at the bottom), centred on it
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val centre = loc[0] + anchor.width / 2
        val aboveY = loc[1] - h - PAD
        val above = aboveY > Tokens2.CONTENT_TOP
        val y = if (above) aboveY else loc[1] + anchor.height + PAD
        val maxX = (anchor.rootView.width - w - PAD).coerceAtLeast(PAD)
        val x = (centre - w / 2).coerceIn(PAD, maxX)
        // it grows from the side of the anchor, towards where it opens
        box.pivotX = o + (centre - x).toFloat().coerceIn(0f, w.toFloat())
        box.pivotY = o + if (above) h.toFloat() else 0f
        popup.showAtLocation(anchor, Gravity.TOP or Gravity.LEFT, x - o, y - o) // window coordinates: LEFT
        Look.opened(frame)
        popup.setOnDismissListener { Look.closed(frame) }
        com.abdllh.aura.home.Panel.own(ctx, { popup.isShowing }, { popup.dismiss() }) // (it goes when the panel under it does)
        box.enter(scaleFrom = 0.94f)
    }
}
