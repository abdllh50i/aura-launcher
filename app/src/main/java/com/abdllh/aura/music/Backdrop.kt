package com.abdllh.aura.music

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.abdllh.aura.ui.Palette
import java.util.concurrent.Executors

/**
 * What a cover gives the music screen: its colour as a fill and as text, and the whole backdrop behind the screen as
 * one small picture.
 *
 * Until 1.12 the backdrop was four full-screen layers (the window's colour, a gradient, the cover scaled up from 18 px
 * and a translucent veil), filled on every frame the screen drew: the lyrics' glide, the playing row's bars, the
 * progress. On this GPU that was several milliseconds a frame before any content, and 18 texels stretched to 600 px
 * with bilinear filtering showed creases and blocks, not a blur. Now the same layers are worked out once per cover
 * into a picture of [W] texels across, blurred for real, and the window draws that one picture as its background:
 * one opaque fill a frame, two while a new cover crossfades in.
 */
internal object Backdrop {
    /** Texels across; the height follows the screen's shape. Upscaled with filtering, a blurred picture stays smooth. */
    private const val W = 48

    /** The theme as it was when the look was asked for (read on the main thread; the work runs on another). */
    class Theme(val dark: Boolean, val bg: Int, val card: Int, val accent: Int, val accentFg: Int, val aspect: Float)

    /** [fill]: progress, play button, indicators, the bars. [text]: the cover's colour as a label or icon. */
    class Look(val fill: Int, val text: Int, val back: Bitmap)

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** [look] off the main thread, then [done] on it. */
    fun lookAsync(cover: Bitmap?, t: Theme, done: (Look) -> Unit) {
        worker.execute {
            val l = look(cover, t)
            main.post { done(l) }
        }
    }

    fun look(cover: Bitmap?, t: Theme): Look {
        // (a cover that cannot be read here, a hardware one for instance, is a look without it rather than a crash)
        val readable = try {
            cover?.let { if (Build.VERSION.SDK_INT >= 26 && it.config == Bitmap.Config.HARDWARE) it.copy(Bitmap.Config.ARGB_8888, false) else it }
        } catch (_: Throwable) { null }
        val px = try { readable?.let { small(it, t.aspect) } } catch (_: Throwable) { null }
        val (fill, text) = try { readable?.let { colours(swatch(it), t) } } catch (_: Throwable) { null } ?: (t.accent to t.accentFg)
        return Look(fill, text, compose(px, fill, t))
    }

    /** The whole cover at 12 × 12, unblurred, for its colour (the backdrop shows only its middle band). */
    private fun swatch(cover: Bitmap): IntArray {
        val s = Bitmap.createScaledBitmap(cover, 12, 12, true)
        val p = IntArray(144)
        s.getPixels(p, 0, 12, 0, 0, 12, 12)
        if (s !== cover) s.recycle()
        return p
    }

    private fun height(aspect: Float) = (W / aspect).toInt().coerceIn(8, W)

    /** The cover cut to the screen's shape (its middle band, as a centre crop shows it), [W] across, blurred. */
    private fun small(cover: Bitmap, aspect: Float): IntArray {
        var b = cover
        val h = height(aspect)
        // the band: as wide as the cover, as tall as the screen's shape allows (or the other way for a wide cover)
        val bw: Int; val bh: Int
        if (b.width / b.height.toFloat() < aspect) { bw = b.width; bh = (b.width / aspect).toInt().coerceAtLeast(1) }
        else { bh = b.height; bw = (b.height * aspect).toInt().coerceAtLeast(1) }
        val src = Rect((b.width - bw) / 2, (b.height - bh) / 2, (b.width + bw) / 2, (b.height + bh) / 2)
        // halved step by step: one bilinear step from 600 px to 48 samples a few scattered pixels and drops the rest
        var w = bw
        while (w / 2 >= W * 2) {
            val next = Bitmap.createBitmap(w / 2, ((w / 2) / aspect).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            Canvas(next).drawBitmap(b, src, Rect(0, 0, next.width, next.height), FILTER)
            if (b !== cover) b.recycle()
            b = next
            src.set(0, 0, next.width, next.height)
            w = next.width
        }
        val out = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(b, src, Rect(0, 0, W, h), FILTER)
        if (b !== cover) b.recycle()
        val p = IntArray(W * h)
        out.getPixels(p, 0, W, 0, 0, W, h)
        out.recycle()
        // three box passes each way come close to a gaussian (about 2.5 texels: some 50 px on the screen)
        repeat(3) { boxBlur(p, W, h, 2) }
        return p
    }

    private val FILTER = Paint(Paint.FILTER_BITMAP_FLAG)

    /** One horizontal and one vertical box pass of [r] texels, edges clamped. */
    private fun boxBlur(p: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(p.size)
        val n = 2 * r + 1
        for (y in 0 until h) for (x in 0 until w) {
            var a = 0; var rr = 0; var g = 0; var bb = 0
            for (k in -r..r) {
                val c = p[y * w + (x + k).coerceIn(0, w - 1)]
                a += c ushr 24; rr += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; bb += c and 0xFF
            }
            tmp[y * w + x] = ((a / n) shl 24) or ((rr / n) shl 16) or ((g / n) shl 8) or (bb / n)
        }
        for (y in 0 until h) for (x in 0 until w) {
            var a = 0; var rr = 0; var g = 0; var bb = 0
            for (k in -r..r) {
                val c = tmp[(y + k).coerceIn(0, h - 1) * w + x]
                a += c ushr 24; rr += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; bb += c and 0xFF
            }
            p[y * w + x] = ((a / n) shl 24) or ((rr / n) shl 16) or ((g / n) shl 8) or (bb / n)
        }
    }

    /**
     * The layers of before, worked out per texel in the order they were drawn: the theme's background; a wash of the
     * cover's colour from the top start corner, gone by the middle of the diagonal; the cover at 0.9 (dark) or 0.55
     * (light); a veil of the background, thin at the top and nearly whole at the bottom, so text reads on any cover.
     */
    private fun compose(cover: IntArray?, fill: Int, t: Theme): Bitmap {
        val h = height(t.aspect)
        val wash = Palette.mix(t.bg, fill, if (t.dark) 0.30f else 0.22f)
        val art = if (t.dark) 0.9f else 0.55f
        val v0 = if (t.dark) 0.35f else 0.45f
        val v1 = if (t.dark) 0.62f else 0.70f
        val v2 = if (t.dark) 0.88f else 0.90f
        val a2 = t.aspect * t.aspect
        val out = IntArray(W * h)
        for (y in 0 until h) {
            val v = (y + 0.5f) / h
            val veil = if (v < 0.5f) v0 + (v1 - v0) * v / 0.5f else v1 + (v2 - v1) * (v - 0.5f) / 0.5f
            for (x in 0 until W) {
                val u = (x + 0.5f) / W
                // where this texel falls along the top-left to bottom-right diagonal of the screen (0..1)
                val d = (u * a2 + v) / (a2 + 1f)
                var c = if (d < 0.5f) Palette.mix(wash, t.bg, d / 0.5f) else t.bg
                if (cover != null) c = Palette.mix(c, cover[y * W + x] or 0xFF000000.toInt(), art)
                out[y * W + x] = Palette.mix(c, t.bg, veil) or 0xFF000000.toInt()
            }
        }
        return Bitmap.createBitmap(out, W, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * The cover's most striking colour (pixels grouped by hue, each weighted by how saturated and bright it is; the
     * strongest group wins: an average of a blue-and-orange cover is a muddy grey-pink), made lively, then brought
     * just far enough from the background to be seen: as a fill 3:1 on the screen's background (a dark blue was 1.8:1
     * on the dark theme, a yellow 1.3:1 on the light one), as text 4.5:1 on the playing row's tint. Grey covers keep
     * the owner's accent.
     */
    private fun colours(p: IntArray, t: Theme): Pair<Int, Int> {
        val weight = FloatArray(12)
        val sumR = FloatArray(12); val sumG = FloatArray(12); val sumB = FloatArray(12)
        val hsv = FloatArray(3)
        for (c in p) {
            Color.colorToHSV(c, hsv)
            val w = hsv[1] * hsv[2] * hsv[2]
            if (w < 0.02f) continue
            val bin = (hsv[0] / 30f).toInt().coerceIn(0, 11)
            weight[bin] += w
            sumR[bin] += ((c shr 16) and 0xFF) * w; sumG[bin] += ((c shr 8) and 0xFF) * w; sumB[bin] += (c and 0xFF) * w
        }
        val best = weight.indices.maxByOrNull { weight[it] } ?: return t.accent to t.accentFg
        val w = weight[best]
        if (w <= 0f) return t.accent to t.accentFg
        val dominant = Color.rgb((sumR[best] / w).toInt(), (sumG[best] / w).toInt(), (sumB[best] / w).toInt())
        Color.colorToHSV(dominant, hsv)
        if (hsv[1] < 0.12f) return t.accent to t.accentFg
        hsv[1] = hsv[1].coerceAtLeast(0.55f)
        hsv[2] = if (t.dark) hsv[2].coerceIn(0.75f, 0.95f) else hsv[2].coerceIn(0.55f, 0.85f)
        val fill = reach(Color.HSVToColor(hsv), t.bg, 3.0, t.dark)
        val text = reach(fill, Palette.mix(t.card, fill, 0.16f), 4.5, t.dark)
        return fill to text
    }

    /** [c] mixed toward white (dark theme) or black (light) only as far as it takes to reach [ratio] on [on]. */
    private fun reach(c: Int, on: Int, ratio: Double, dark: Boolean): Int {
        val toward = if (dark) Color.WHITE else Color.BLACK
        var k = 0f
        var out = c
        while (k < 0.8f && Palette.contrast(out, on) < ratio) {
            k += 0.04f
            out = Palette.mix(c, toward, k)
        }
        return out
    }
}
