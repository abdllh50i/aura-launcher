package com.abdllh.aura.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.View
import com.abdllh.aura.nav.MapStyle
import com.abdllh.aura.util.dp
import java.io.File
import java.util.Locale
import java.util.Random
import java.util.concurrent.Executors

/**
 * What the home screen's map panel shows until the live map has drawn: the picture of the live map kept the last time
 * it came to rest ([picture], see [com.abdllh.aura.nav.AuraMap.pictureFile]) — the car is usually still where it was
 * parked, so the screen opens on the real streets and the live map takes over almost unseen. Before there is such a
 * picture it is the map's plain land colour: invented streets cross-fading over the real ones read as a ghost map.
 * In place of the live map when it cannot run ([com.abdllh.aura.nav.MapGuard], [grid]) it is a quiet street grid
 * with a park and a waterfront in the map's own colours. Made once per size into a bitmap on a background thread and
 * faded in; static afterwards.
 */
class MapBackdropView(context: Context, private val picture: File?, private val grid: Boolean) : View(context) {
    private var bmp: Bitmap? = null
    private var reveal = 0f
    private var generation = 0
    private val p = Paint(Paint.FILTER_BITMAP_FLAG)
    private val land = MapStyle.tones().land

    // (one flat picture: faded as it is, without an offscreen copy)
    override fun hasOverlappingRendering() = false

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        if (w <= 0 || h <= 0) return
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val gen = ++generation
        val tones = MapStyle.tones()
        val dark = Palette.dark
        val density = 1f.dp
        val pic = picture
        val streets = grid
        RENDER.execute {
            val b = try { pic?.let { load(it, w, h) } ?: if (streets) render(w, h, tones, dark, rtl, density) else null } catch (_: Throwable) { null }
            MAIN.post {
                if (gen != generation || b == null) return@post
                bmp = b
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = Motion.MEDIUM
                    interpolator = Motion.STANDARD
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
        val b = bmp
        // (the bitmap covers the whole view once it is in: no fill under it)
        if (b == null || reveal < 1f) c.drawColor(land)
        if (b != null) {
            p.alpha = (255 * reveal).toInt()
            c.drawBitmap(b, 0f, 0f, p)
            p.alpha = 255
        }
    }

    companion object {
        /** Where the car is on the map, as a fraction of its size (x is mirrored in right-to-left layouts). */
        const val POS_X = 0.64f
        const val POS_Y = 0.50f
        private val RENDER = Executors.newSingleThreadExecutor()
        private val MAIN = Handler(Looper.getMainLooper())

        /** The home map's kept picture for the current theme and language (each draws the map differently). */
        fun pictureFile(ctx: Context): File =
            File(ctx.cacheDir, "home-map-${if (Palette.dark) "dark" else "light"}-${Locale.getDefault().language}.webp")

        /** The kept picture, scaled to cover the view (centred), or null when there is none yet. */
        private fun load(f: File, w: Int, h: Int): Bitmap? {
            if (!f.isFile) return null
            val src = BitmapFactory.decodeFile(f.path) ?: return null
            val scale = maxOf(w / src.width.toFloat(), h / src.height.toFloat())
            val sw = (w / scale).toInt().coerceAtMost(src.width)
            val sh = (h / scale).toInt().coerceAtMost(src.height)
            val from = Rect((src.width - sw) / 2, (src.height - sh) / 2, (src.width + sw) / 2, (src.height + sh) / 2)
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(src, from, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
            src.recycle()
            return out
        }

        /** Software rendering of the street grid (runs on the render thread; touches no view state). */
        private fun render(w: Int, h: Int, k: MapStyle.Tones, dark: Boolean, rtl: Boolean, d: Float): Bitmap {
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
            if (!dark) {
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
