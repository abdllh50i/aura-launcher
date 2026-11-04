package com.abdllh.aura.home

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.Drawable

/**
 * The home screen's room: one flat colour over the whole of it, as a panel's room is, until the map under the window has drawn and
 * [hole] opens where its card is. Four rectangles and four fake corners (the map's surface cannot be rounded: its card's corners are
 * the wall's), no path and no layer: this is drawn again in every frame of anything else on the home screen. The design's wall-hole
 * bitmap does the same with one picture.
 */
class Wall(color: Int, private val radius: Int) : Drawable() {
    private val p = Paint().apply { this.color = color }
    /** A corner of the wall: its colour outside a quarter circle, the top left one; the other three are it turned over. */
    private val corner: Bitmap = Bitmap.createBitmap(radius, radius, Bitmap.Config.ARGB_8888).also { b ->
        val c = Canvas(b)
        c.drawColor(color)
        val r = radius.toFloat()
        c.drawCircle(r, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) })
    }

    var hole: Rect? = null
        set(v) {
            field = v
            invalidateSelf()
        }

    override fun draw(c: Canvas) {
        val b = bounds
        val h = hole
        if (h == null) {
            c.drawRect(b, p)
            return
        }
        val l = h.left.toFloat()
        val t = h.top.toFloat()
        val r = h.right.toFloat()
        val d = h.bottom.toFloat()
        c.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), t, p)
        c.drawRect(b.left.toFloat(), d, b.right.toFloat(), b.bottom.toFloat(), p)
        c.drawRect(b.left.toFloat(), t, l, d, p)
        c.drawRect(r, t, b.right.toFloat(), d, p)
        cornerAt(c, l, t, 1f, 1f)
        cornerAt(c, r, t, -1f, 1f)
        cornerAt(c, l, d, 1f, -1f)
        cornerAt(c, r, d, -1f, -1f)
    }

    private fun cornerAt(c: Canvas, x: Float, y: Float, sx: Float, sy: Float) {
        val s = c.save()
        c.translate(x, y)
        c.scale(sx, sy)
        c.drawBitmap(corner, 0f, 0f, null)
        c.restoreToCount(s)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = if (hole == null) PixelFormat.OPAQUE else PixelFormat.TRANSLUCENT
}
