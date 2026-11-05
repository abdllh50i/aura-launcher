package com.abdllh.aura.music

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.abdllh.aura.R
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Plates
import com.abdllh.aura.ui.Surfaces
import com.abdllh.aura.ui.Tint
import com.abdllh.aura.ui.Tokens2
import java.util.concurrent.Executors

/**
 * What a cover gives the music screen: its colour as a fill and as text, and the whole backdrop behind the screen as
 * one small picture.
 *
 * Until 1.12 the backdrop was four full-screen layers (the window's colour, a gradient, the cover scaled up from 18 px
 * and a translucent veil), filled on every frame the screen drew: the lyrics' glide, the playing row's bars, the
 * progress. On this GPU that was several milliseconds a frame before any content, and 18 texels stretched to 600 px
 * with bilinear filtering showed creases and blocks, not a blur. Now the same layers are worked out once per cover
 * into a picture of [W] texels across, blurred for real, and the screen's root draws that one picture as its background
 * (the window has none, in the one window of 2.0): one opaque fill a frame, two while a new cover crossfades in.
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
    private fun reach(c: Int, on: Int, ratio: Double, dark: Boolean): Int = Palette.reach(c, on, ratio, dark)

    // ------------------------------------------------------------------------------------------ the song's colour in 2.0
    /**
     * What a song gives the design (SPEC.md section 4, "Song colour, on the unit"), once per song on the single worker, nothing per
     * frame. [panel]: the Music panel's background, one 48 x 28 opaque bitmap to stretch with FILTER on to 1024 x 600. [widget] and
     * [mini]: Home's widget and the mini card, card, edge and shadow in one bitmap, to show in a plain ImageView at the card's rect grown
     * by [CARD_MARGIN]; no clip. [fill]: the song's colour as a fill. [wordPill] and [wordPillInk]: the current word's pill.
     */
    class Wash(val night: Boolean, val fill: Int, val wordPill: Int, val wordPillInk: Int, val panel: Bitmap, val widget: Bitmap?, val mini: Bitmap?)

    /** The margin of [Wash.widget] and [Wash.mini] round the card: `wash-*` of the design. */
    const val CARD_MARGIN = 30
    private const val WASH_W = 364
    private const val WIDGET_H = 182
    private const val MINI_H = 80
    private const val ASPECT = 1024f / 600f

    /** Stops of the sea cover, each keeping its saturation and value and taking its hue from the song's fill. */
    private val NIGHT_STOPS = floatArrayOf(0f, .45f, .8f, 1f)
    private val NIGHT_RGB = intArrayOf(Color.rgb(5, 13, 24), Color.rgb(7, 28, 44), Color.rgb(9, 50, 72), Color.rgb(12, 64, 88))
    private val DAY_STOPS = floatArrayOf(0f, .6f, 1f)
    private val DAY_RGB = intArrayOf(Color.WHITE, Color.rgb(240, 248, 252), Color.rgb(219, 238, 248))
    /** A song with no cover: its luminance is a constant, so only the vignette gives it shape. */
    private const val NO_COVER_W = .52f

    /** [wash] off the main thread, then [done] on it. */
    fun washAsync(ctx: Context, cover: Bitmap?, night: Boolean, done: (Wash) -> Unit) {
        val app = ctx.applicationContext
        worker.execute {
            val w = wash(app, cover, night)
            main.post { done(w) }
        }
    }

    /** On the worker. Never throws for a bad cover: a song with no readable cover has the no-cover wash. */
    fun wash(ctx: Context, cover: Bitmap?, night: Boolean): Wash {
        val c = if (night) Tokens2.NIGHT else Tokens2.DAY
        val t = Theme(night, c.bg, c.cardFlat, c.accent, c.accentInk, ASPECT)
        val readable = try {
            cover?.let { if (Build.VERSION.SDK_INT >= 26 && it.config == Bitmap.Config.HARDWARE) it.copy(Bitmap.Config.ARGB_8888, false) else it }
        } catch (_: Throwable) { null }
        val px = try { readable?.let { small(it, ASPECT) } } catch (_: Throwable) { null }
        val fill = try { readable?.let { colours(swatch(it), t).first } } catch (_: Throwable) { null } ?: c.accent
        val h = height(ASPECT)
        // the luminance, normalised to 0..1
        val lum = FloatArray(W * h)
        if (px == null) lum.fill(NO_COVER_W)
        else {
            var lo = 1f
            var hi = 0f
            for (i in lum.indices) {
                val p = px[i]
                val l = (0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)) / 255f
                lum[i] = l
                if (l < lo) lo = l
                if (l > hi) hi = l
            }
            val span = (hi - lo).coerceAtLeast(0.001f)
            for (i in lum.indices) lum[i] = (lum[i] - lo) / span
        }
        val hsv = FloatArray(3)
        Color.colorToHSV(fill, hsv)
        val hue = hsv[0]
        val stops = if (night) NIGHT_STOPS else DAY_STOPS
        val rgb = (if (night) NIGHT_RGB else DAY_RGB).map { rehue(it, hue) }.toIntArray()
        val out = IntArray(W * h)
        for (y in 0 until h) {
            val yy = y / h.toFloat()
            for (x in 0 until W) {
                val xx = x / W.toFloat()
                var o = ramp(stops, rgb, (lum[y * W + x] * .9f).coerceIn(0f, 1f))
                if (night) {
                    val v = 1f - .38f * ((xx - .55f) * (xx - .55f) * 2.2f + (yy - .42f) * (yy - .42f) * 2.6f)
                    o = Color.rgb((Color.red(o) * v).toInt(), (Color.green(o) * v).toInt(), (Color.blue(o) * v).toInt())
                }
                out[y * W + x] = o
            }
        }
        val panel = Bitmap.createBitmap(out, W, h, Bitmap.Config.ARGB_8888)
        val coverWash = if (px != null) Bitmap.createBitmap(px, W, h, Bitmap.Config.ARGB_8888) else panel
        // (the fill's hue at full strength: the design's veil is the sea cover's (5,30,46), which is that mixed 82 % to black; from the fill itself,
        // whose saturation and value are held down so that it works as a fill, the card came out grey)
        val black = Palette.mix(Color.HSVToColor(floatArrayOf(hue, .89f, 1f)), Color.BLACK, .82f)
        val widget = try {
            if (night) card(ctx, "widget", WIDGET_H, coverWash, black, .68f, .82f, true) else card(ctx, "widget", WIDGET_H, coverWash, Color.WHITE, .80f, .86f, false)
        } catch (_: Throwable) { null }
        val mini = try {
            if (night) card(ctx, "mini", MINI_H, panel, Color.rgb(0x15, 0x18, 0x1F), .42f, .42f, true) else card(ctx, "mini", MINI_H, panel, Color.WHITE, .10f, .10f, false)
        } catch (_: Throwable) { null }
        // (the song's hue at the saturation and value of what the sea cover gives, wordPill: from the fill itself the pill was duller than
        // the design's, because the fill's value is held down so that it can be a fill. Then as far as it takes to read.)
        hsv[0] = hue
        hsv[1] = if (night) .55f else .89f
        hsv[2] = if (night) .96f else .525f
        val pill = Palette.reach(Color.HSVToColor(hsv), c.wordPillInk, 4.5, night)
        return Wash(night, fill, if (px == null) c.wordPill else pill, c.wordPillInk, panel, widget, mini)
    }

    private fun rehue(c: Int, hue: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        hsv[0] = hue
        return Color.HSVToColor(hsv)
    }

    private fun ramp(stops: FloatArray, rgb: IntArray, t: Float): Int {
        var i = 1
        while (i < stops.lastIndex && t > stops[i]) i++
        val k = ((t - stops[i - 1]) / (stops[i] - stops[i - 1])).coerceIn(0f, 1f)
        return Palette.mix(rgb[i - 1], rgb[i], k)
    }

    /**
     * One song's card, in a software canvas: [base] scaled to cover the card at [CARD_MARGIN], the veil over it, `DST_IN` with
     * `wash-mask-<size>`, then `wash-frame-<size>-<theme>` for edge and shadow. A little noise, as a stretched wash would band.
     */
    private fun card(ctx: Context, size: String, h: Int, base: Bitmap, veil: Int, top: Float, bottom: Float, night: Boolean): Bitmap? {
        val mask = Surfaces.software(ctx, "wash-mask-$size") ?: return null
        val frame = Surfaces.software(ctx, "wash-frame-$size-${if (night) "night" else "day"}") ?: return null
        val out = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val m = CARD_MARGIN
        // to cover: the middle of the bitmap, in the card's shape
        val k = maxOf(WASH_W / base.width.toFloat(), h / base.height.toFloat())
        val sw = (WASH_W / k).toInt()
        val sh = (h / k).toInt()
        val sx = (base.width - sw) / 2
        val sy = (base.height - sh) / 2
        val dst = Rect(m, m, m + WASH_W, m + h)
        c.drawBitmap(base, Rect(sx, sy, sx + sw, sy + sh), dst, FILTER)
        val px = IntArray(WASH_W * h)
        out.getPixels(px, 0, WASH_W, m, m, WASH_W, h)
        dither(px)
        out.setPixels(px, 0, WASH_W, m, m, WASH_W, h)
        val v = Paint().apply {
            shader = LinearGradient(0f, m.toFloat(), 0f, (m + h).toFloat(), Palette.withAlpha(veil, top), Palette.withAlpha(veil, bottom), Shader.TileMode.CLAMP)
        }
        c.drawRect(dst, v)
        c.drawBitmap(mask, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        c.drawBitmap(frame, 0f, 0f, null)
        mask.recycle()
        frame.recycle()
        return out
    }

    /** Triangular noise of about one level, from a small generator: a stretched wash bands on an 8 bit panel without it. */
    private fun dither(p: IntArray) {
        var s = 0x2545F491
        for (i in p.indices) {
            s = s xor (s shl 13); s = s xor (s ushr 17); s = s xor (s shl 5)
            val n = ((s and 3) + ((s ushr 2) and 3) - 3) / 2
            val c = p[i]
            val r = (((c shr 16) and 0xFF) + n).coerceIn(0, 255)
            val g = (((c shr 8) and 0xFF) + n).coerceIn(0, 255)
            val b = ((c and 0xFF) + n).coerceIn(0, 255)
            p[i] = (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }
    }

    /** [round] off the main thread, then [done] on it. */
    fun roundAsync(ctx: Context, cover: Bitmap, size: Int, radius: Int, done: (Bitmap) -> Unit) {
        val app = ctx.applicationContext
        worker.execute {
            val b = round(app, cover, size, radius)
            main.post { done(b) }
        }
    }

    /**
     * A cover rounded once, at its displayed size, so no cover uses clipToOutline: `DST_IN` with the plate drawn at that size. Big 204 r
     * 20, widget 84 r 16, moving widget 72 r 14, mini 52 r 12, glance 44 r 12, row 56 r 12, album 120 r 16. Only on a worker.
     */
    fun round(ctx: Context, cover: Bitmap, size: Int, radius: Int): Bitmap {
        var b = if (Build.VERSION.SDK_INT >= 26 && cover.config == Bitmap.Config.HARDWARE) cover.copy(Bitmap.Config.ARGB_8888, false) else cover
        // the middle square, halved step by step: one bilinear step from 640 to 52 samples a few pixels and drops the rest
        val side = minOf(b.width, b.height)
        val src = Rect((b.width - side) / 2, (b.height - side) / 2, (b.width + side) / 2, (b.height + side) / 2)
        var w = side
        while (w / 2 >= size) {
            val next = Bitmap.createBitmap(w / 2, w / 2, Bitmap.Config.ARGB_8888)
            Canvas(next).drawBitmap(b, src, Rect(0, 0, next.width, next.height), FILTER)
            if (b !== cover) b.recycle()
            b = next
            src.set(0, 0, next.width, next.height)
            w = next.width
        }
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(b, src, Rect(0, 0, size, size), FILTER)
        if (b !== cover) b.recycle()
        val mask = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val mc = Canvas(mask)
        // (the nearest baked plate at or over the radius, scaled to it: the same curve)
        val baked = when { radius <= 12 -> 12; radius <= 16 -> 16; radius <= 18 -> 18; radius <= 20 -> 20; radius <= 23 -> 23; else -> 26 }
        val k = radius / baked.toFloat()
        mc.scale(k, k)
        val plate = ctx.getDrawable(Plates.res(baked))!!.mutate()
        plate.setBounds(0, 0, (size / k + .5f).toInt(), (size / k + .5f).toInt())
        plate.draw(mc)
        c.drawBitmap(mask, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        mask.recycle()
        return out
    }

    /** A song with no cover, at any size: a `fill2` plate at the cover's rect with the `music` glyph in `ink2` at 45 % of its size. */
    fun noArt(ctx: Context, size: Int, radius: Int): Drawable {
        val plate = Tint(ctx, Plates.res(radius)) { Palette.fill2 }
        val glyph = Tint(ctx, R.drawable.ic_music) { Palette.ink2 }
        val inset = (size - size * 45 / 100) / 2
        return LayerDrawable(arrayOf(plate.drawable, InsetDrawable(glyph.drawable, inset)))
    }
}
