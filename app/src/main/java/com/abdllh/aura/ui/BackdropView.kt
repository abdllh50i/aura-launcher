package com.abdllh.aura.ui

import android.content.Context
import android.graphics.Canvas
import android.view.View

/**
 * Full-screen background in the theme's background colour. Deliberately flat: the car stage fades its edges into
 * exactly this colour.
 */
class BackdropView(context: Context) : View(context) {
    override fun onDraw(c: Canvas) {
        c.drawColor(Palette.bg)
    }
}
