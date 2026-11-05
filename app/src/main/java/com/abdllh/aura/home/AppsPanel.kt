package com.abdllh.aura.home

import android.os.Bundle
import android.widget.FrameLayout
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.look

/**
 * A stand-in, so that the dock's cell and Home's tile have their panel and the row's plate lands on it: an empty room with the old drawer over it, until
 * section 6.6's grid replaces this file. Going, it takes [AppDrawer] and [DockPins] with it (ShellRoot.sheets, built in HomePanel.buildUi).
 */
class AppsPanel(shell: HomeActivity) : Panel(shell, Screens.APPS) {
    override val root = FrameLayout(this).look { setBackgroundColor(Palette.bg) }
    override val opaque: Boolean get() = true
    override val keep: Boolean get() = true
    override val recolours: Boolean get() = true

    override fun onShow(args: Bundle?) {
        val sheets = shell.root.sheets
        for (i in 0 until sheets.childCount) (sheets.getChildAt(i) as? AppDrawer)?.let { d ->
            // (closed, by Back or a tap outside: back where he was, there is nothing here)
            d.onClosed = { if (isTop) finish() }
            d.open()
        }
    }
}
