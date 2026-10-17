package com.abdllh.aura.settings

import android.content.Context
import android.view.View

/** One category of the settings screen. */
abstract class Page(val titleRes: Int, val iconRes: Int) {
    abstract fun build(ctx: Context): View
    open fun onShow() {}
    open fun onHide() {}

    /**
     * Whether the screen keeps the page once built. False for a page of lines read when it is built that change
     * meanwhile (the gear's source, the notes from the unit): it is built again at every visit, as every page was
     * until 1.11.
     */
    open val keep: Boolean get() = true
}
