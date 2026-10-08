package com.abdllh.aura.settings

import android.content.Context
import android.view.View

/** One category of the settings screen. */
abstract class Page(val titleRes: Int, val iconRes: Int) {
    abstract fun build(ctx: Context): View
    open fun onShow() {}
    open fun onHide() {}
}
