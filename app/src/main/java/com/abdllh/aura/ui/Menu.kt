package com.abdllh.aura.ui

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import com.abdllh.aura.util.dp

/**
 * A small floating menu in Aura's style (long-press on apps). It grows out of the item that was pressed (its pivot
 * on that side) and, after a choice, shrinks away quickly; the system's popup animations are off, since this
 * firmware ships them at zero and they cut.
 */
object Menu {
    class Item(val icon: Int, val text: String, val action: () -> Unit)

    fun show(anchor: View, items: List<Item>) {
        if (items.isEmpty()) return
        val ctx: Context = anchor.context
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Space.S.dp, Space.S.dp, Space.S.dp, Space.S.dp)
            background = Shapes.rect(Palette.card, Radius.L, if (Palette.dark) Palette.stroke else 0)
            lift(Elev.OVERLAY)
        }
        // the shadow needs room inside the popup's window (it is cut at the window's edge): a margin around the box,
        // where a tap closes the menu like a tap anywhere else outside it
        val room = Space.L.dp
        val frame = FrameLayout(ctx).apply {
            setPadding(room, room, room, room)
            clipToPadding = false
            addView(box, flp(WRAP, WRAP))
        }
        val popup = PopupWindow(frame, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        frame.setOnClickListener { popup.dismiss() }
        var chosen = false
        for (it in items) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumWidth = 250.dp
                setPaddingRelative(Space.L.dp, 0, Space.XL.dp, 0)
                background = Shapes.ghost(Radius.M)
                isClickable = true
                pressScale(Motion.PRESS_SURFACE)
                setOnClickListener { _ ->
                    if (chosen) return@setOnClickListener
                    chosen = true
                    it.action()
                    box.exit(scaleTo = 0.96f, dur = Motion.XSHORT) { popup.dismiss() }
                }
            }
            row.addView(ctx.iconView(it.icon, 24, Palette.text), lp(24.dp, 24.dp))
            row.addView(ctx.label(Type.BUTTON).apply { text = it.text }, lp(WRAP, WRAP).apply { marginStart = 14.dp })
            box.addView(row, lp(MATCH, 58.dp))
        }
        popup.setBackgroundDrawable(ColorDrawable(0))
        popup.isOutsideTouchable = true
        popup.animationStyle = 0
        popup.isClippingEnabled = false // (the shadow's margin may reach past the screen's edge; the box itself never does)
        frame.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val w = box.measuredWidth
        val h = box.measuredHeight
        // above the anchor when there is room (the dock is at the bottom), centred on it
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val centre = loc[0] + anchor.width / 2
        val aboveY = loc[1] - h - 8.dp
        val above = aboveY > 8.dp
        val y = if (above) aboveY else loc[1] + anchor.height + 8.dp
        val maxX = (anchor.rootView.width - w - 8.dp).coerceAtLeast(8.dp)
        val x = (centre - w / 2).coerceIn(8.dp, maxX)
        // it grows from the side of the anchor, towards where it opens
        box.pivotX = (centre - x).toFloat().coerceIn(0f, w.toFloat())
        box.pivotY = if (above) h.toFloat() else 0f
        popup.showAtLocation(anchor, Gravity.TOP or Gravity.LEFT, x - room, y - room) // window coordinates: LEFT
        box.enter(scaleFrom = 0.94f)
    }
}
