package com.abdllh.aura.home

import android.os.Bundle
import android.widget.FrameLayout
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Snack
import com.abdllh.aura.ui.look

/**
 * A stand-in, so that the dock's cell and Home's tile have their panel and the row's plate lands on it: an empty room with the old drawer over it, until
 * section 6.6's grid replaces this file. Going, it takes [AppDrawer] and [DockPins] with it. The drawer is its own now and is made at the first open: Home knows nothing of it.
 */
class AppsPanel(shell: HomeActivity) : Panel(shell, Screens.APPS) {
    override val root = FrameLayout(this).look { setBackgroundColor(Palette.bg) }
    override val opaque: Boolean get() = true
    override val keep: Boolean get() = true

    private var drawer: AppDrawer? = null

    override fun onShow(args: Bundle?) {
        val d = drawer ?: AppDrawer(shell, object : HomeHost {
            override fun openControls() {}
            override fun openSettings(page: Int) = Screens.settings(this@AppsPanel, page).let { }
            override fun toast(msg: String, outlives: Boolean) = Snack.show(this@AppsPanel, msg, outlives = outlives)
            override fun restyle() {}
        }).also {
            drawer = it
            shell.root.sheets.addView(it, MATCH, MATCH)
        }
        // (closed, by Back or a tap outside: back where he was, there is nothing here)
        d.onClosed = { if (isTop) finish() }
        d.open()
    }
}
