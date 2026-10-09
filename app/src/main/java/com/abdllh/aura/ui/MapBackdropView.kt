package com.abdllh.aura.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.View
import com.abdllh.aura.util.dp
import java.util.Random
import java.util.concurrent.Executors

/**
 * Decorative map behind the navigation panel: a quiet street grid, a park and a waterfront. It is drawn once per
 * size/theme into a bitmap on a background thread and faded in, and is static afterwards (the car's position is a
 * separate small [MapPuck], so its pulse does not redraw the whole map). A stand-in until a real map can be
 * embedded: tapping the panel opens the navigation app.
 */
class MapBackdropView(context: Context) : View(context) {
    private var bmp: Bitmap? = null
    private var reveal = 0f
    private var generation = 0
    private val p = Paint(Paint.FILTER_BITMAP_FLAG)

    private class Colors(
        val land: Int, val block: Int, val park: Int, val water: Int,
        val road: Int, val major: Int, val casing: Int, val dark: Boolean
    )

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        if (w <= 0 || h <= 0) return
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val gen = ++generation
        val colors = Colors(Palette.mapLand, Palette.mapBlock, Palette.mapPark, Palette.mapWater,
            Palette.mapRoad, Palette.mapRoadMajor, Palette.mapCasing, Palette.dark)
        val density = 1f.dp
        RENDER.execute {
            val b = try { render(w, h, colors, rtl, density) } catch (_: Throwable) { null }
            MAIN.post {
                if (gen != generation || b == null) return@post
                bmp = b
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 360
                    addUpdateListener { reveal = it.animatedValue as Float; invalidate() }
                    start()
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        generation++
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Palette.mapLand)
        bmp?.let {
            p.alpha = (255 * reveal).toInt()
            c.drawBitmap(it, 0f, 0f, p)
            p.alpha = 255
        }
    }

    companion object {
        /** Where the car is on the map, as a fraction of its size (x is mirrored in right-to-left layouts). */
        const val POS_X = 0.64f
        const val POS_Y = 0.50f
        private val RENDER = Executors.newSingleThreadExecutor()
        private val MAIN = Handler(Looper.getMainLooper())

        /** Software rendering of the static map (runs on the render thread; touches no view state). */
        private fun render(w: Int, h: Int, k: Colors, rtl: Boolean, d: Float): Bitmap {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            if (rtl) { c.translate(w.toFloat(), 0f); c.scale(-1f, 1f) }
            val wf = w.toFloat()
            val hf = h.toFloat()
            val px = wf * POS_X
            val py = hf * POS_Y
            val rnd = Random(7)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            c.drawColor(k.land)

            // the street grid is turned a little so the map does not look like graph paper
            c.save()
            c.rotate(-14f, px, py)
            val ext = maxOf(wf, hf) * 0.75f
            val x0 = px - ext; val x1 = px + ext
            val y0 = py - ext; val y1 = py + ext
            val cols = ArrayList<Float>()
            val rows = ArrayList<Float>()
            var x = x0
            while (x < x1) { cols.add(x); x += (46f + rnd.nextFloat() * 26f) * d }
            var y = y0
            while (y < y1) { rows.add(y); y += (38f + rnd.nextFloat() * 20f) * d }

            // building footprints inside the blocks
            p.style = Paint.Style.FILL
            p.color = k.block
            val r = RectF()
            for (i in 0 until cols.size - 1) for (j in 0 until rows.size - 1) {
                if (rnd.nextFloat() < 0.35f) continue
                val bw = cols[i + 1] - cols[i]
                val bh = rows[j + 1] - rows[j]
                val inset = 7f * d
                r.set(cols[i] + inset, rows[j] + inset, cols[i] + inset + (bw - 2 * inset) * (0.45f + rnd.nextFloat() * 0.55f),
                    rows[j] + inset + (bh - 2 * inset) * (0.5f + rnd.nextFloat() * 0.5f))
                c.drawRoundRect(r, 3f * d, 3f * d, p)
            }

            // park (a few blocks merged)
            p.color = k.park
            val pi = cols.indexOfFirst { it > px - 230f * d }.coerceAtLeast(0)
            val pj = rows.indexOfFirst { it > py + 60f * d }.coerceAtLeast(0)
            if (pi + 3 < cols.size && pj + 2 < rows.size) {
                r.set(cols[pi] + 4f * d, rows[pj] + 4f * d, cols[pi + 3] - 4f * d, rows[pj + 2] - 4f * d)
                c.drawRoundRect(r, 14f * d, 14f * d, p)
            }

            // minor streets
            p.style = Paint.Style.STROKE
            p.strokeCap = Paint.Cap.ROUND
            if (!k.dark) {
                p.color = k.casing
                p.strokeWidth = 5.5f * d
                for (cx in cols) c.drawLine(cx, y0, cx, y1, p)
                for (ry in rows) c.drawLine(x0, ry, x1, ry, p)
            }
            p.color = k.road
            p.strokeWidth = 3.6f * d
            for (cx in cols) c.drawLine(cx, y0, cx, y1, p)
            for (ry in rows) c.drawLine(x0, ry, x1, ry, p)

            // arterial roads along the grid, one passing the car
            fun major(path: Path, width: Float) {
                p.color = k.casing; p.strokeWidth = (width + 3f) * d
                c.drawPath(path, p)
                p.color = k.major; p.strokeWidth = width * d
                c.drawPath(path, p)
            }
            val ci = cols.indexOfFirst { it > px }.coerceAtLeast(1)
            val rj = rows.indexOfFirst { it > py }.coerceAtLeast(1)
            major(Path().apply { moveTo(x0, py); lineTo(x1, py) }, 8f)
            major(Path().apply { moveTo(cols[ci - 1] + 0f, y0); lineTo(cols[ci - 1], y1) }, 7f)
            if (rj + 2 < rows.size) major(Path().apply { moveTo(x0, rows[rj + 2]); lineTo(x1, rows[rj + 2]) }, 6f)
            c.restore()

            // waterfront in the far corner, with a highway curving along it
            p.style = Paint.Style.FILL
            p.color = k.water
            val water = Path().apply {
                moveTo(wf * 0.70f, -10f)
                cubicTo(wf * 0.80f, hf * 0.10f, wf * 0.84f, hf * 0.26f, wf * 0.98f, hf * 0.30f)
                cubicTo(wf * 1.04f, hf * 0.32f, wf * 1.06f, hf * 0.33f, wf + 10f, hf * 0.34f)
                lineTo(wf + 10f, -10f)
                close()
            }
            c.drawPath(water, p)
            p.style = Paint.Style.STROKE
            val hwy = Path().apply {
                moveTo(wf * 0.60f, -10f)
                cubicTo(wf * 0.72f, hf * 0.16f, wf * 0.78f, hf * 0.34f, wf + 10f, hf * 0.40f)
            }
            p.color = k.casing; p.strokeWidth = 12f * d
            c.drawPath(hwy, p)
            p.color = k.major; p.strokeWidth = 9f * d
            c.drawPath(hwy, p)
            return bmp
        }
    }
}
