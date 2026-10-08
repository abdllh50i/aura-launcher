package com.abdllh.aura.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import com.abdllh.aura.util.dp

/** Full-screen dark backdrop with a soft accent glow in the top corner. Static: drawn once per size/accent change. */
class BackdropView(context: Context) : View(context) {
    private val base = Paint()
    private val glow = Paint()
    private var glowAccent = 0

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        base.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF0D1015.toInt(), 0xFF07090C.toInt(), Shader.TileMode.CLAMP)
        rebuildGlow()
    }

    private fun rebuildGlow() {
        glowAccent = Palette.accent
        val r = 560.dp.toFloat()
        val cx = if (layoutDirection == LAYOUT_DIRECTION_RTL) 40.dp.toFloat() else width - 40.dp.toFloat()
        glow.shader = RadialGradient(cx, -60.dp.toFloat(), r, intArrayOf(Palette.withAlpha(Palette.accent, 0.20f), 0x00000000), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        if (glowAccent != Palette.accent) rebuildGlow()
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), base)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glow)
    }
}
