package com.abdllh.aura.util

import android.content.Context

/** Density helpers. The car unit runs at 160dpi (1dp == 1px) but we stay density-independent. */
object Dp {
    @JvmField
    var density = 1f

    fun init(ctx: Context) {
        density = ctx.resources.displayMetrics.density
    }
}

val Int.dp: Int get() = (this * Dp.density + 0.5f).toInt()
val Float.dp: Float get() = this * Dp.density
val Int.dpf: Float get() = this * Dp.density
