package com.abdllh.aura.ui

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import com.abdllh.aura.util.dp

/** A small floating menu in Aura's style (long-press on apps). */
object Menu {
    class Item(val icon: Int, val text: String, val action: () -> Unit)

    fun show(anchor: View, items: List<Item>) {
        if (items.isEmpty()) return
        val ctx: Context = anchor.context
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8.dp, 8.dp, 8.dp, 8.dp)
            background = Shapes.rect(Palette.card, 20f, if (Palette.dark) Palette.stroke else 0)
            elevate(14f)
        }
        val popup = PopupWindow(box, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        for (it in items) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumWidth = 250.dp
                setPaddingRelative(16.dp, 0, 22.dp, 0)
                background = Shapes.ghost(14f)
                isClickable = true
                setOnClickListener { _ -> popup.dismiss(); it.action() }
            }
            row.addView(ctx.iconView(it.icon, 24, Palette.text), lp(24.dp, 24.dp))
            row.addView(ctx.label(17f, Palette.text, Fonts.MEDIUM).apply { text = it.text }, lp(WRAP, WRAP).apply { marginStart = 14.dp })
            box.addView(row, lp(MATCH, 58.dp))
        }
        popup.setBackgroundDrawable(ColorDrawable(0))
        popup.isOutsideTouchable = true
        popup.elevation = 14f.dp
        box.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        // above the anchor when there is room (the dock is at the bottom), centred on it
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val x = loc[0] + anchor.width / 2 - box.measuredWidth / 2
        val above = loc[1] - box.measuredHeight - 8.dp
        val y = if (above > 8.dp) above else loc[1] + anchor.height + 8.dp
        val maxX = (anchor.rootView.width - box.measuredWidth - 8.dp).coerceAtLeast(8.dp)
        popup.showAtLocation(anchor, Gravity.TOP or Gravity.LEFT, x.coerceIn(8.dp, maxX), y) // window coordinates: LEFT
    }
}
