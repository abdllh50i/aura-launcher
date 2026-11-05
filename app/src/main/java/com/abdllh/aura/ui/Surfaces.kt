package com.abdllh.aura.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.NinePatchDrawable
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.abdllh.aura.R

/**
 * The baked surfaces of SPEC.md section 4: every shadow, edge and rounded corner on screen is a bitmap, and no code reads a
 * shadow or a stroke. The same outsets in both themes, so a theme change swaps bitmaps and nothing is laid out.
 */
enum class Surface(private val nightRes: Int, private val dayRes: Int, @JvmField val outset: Int) {
    /** Fill 96 %: the map is faintly alive under it. */
    CARD(R.drawable.card_night, R.drawable.card_day, 36),
    /** Search, chips, map buttons. */
    PILL(R.drawable.pill_night, R.drawable.pill_day, 16),
    /** Fully opaque: banners, sheets, dialogs, menus. */
    OVERLAY(R.drawable.overlay_night, R.drawable.overlay_day, 44);

    val res: Int get() = if (Palette.dark) nightRes else dayRes

    fun drawable(ctx: Context): Drawable = ctx.getDrawable(res)!!

    /** Both themes' bitmaps in memory, for good ("kept always: chrome, 9-patches"), so a swap decodes nothing on the main thread. */
    internal fun warm(ctx: Context, keep: MutableList<Drawable>) {
        keep.add(ctx.getDrawable(nightRes)!!)
        keep.add(ctx.getDrawable(dayRes)!!)
    }
}

/**
 * A view's background on a surface, whose 9-patch changes with the theme in place ([recolour]). `view.background = ` at a theme change would be
 * wrong: `View.setBackgroundDrawable` takes the padding of a 9-patch's guides over the view's own (a Modal's card, a Snack, a Menu lost theirs), and
 * asks for a layout.
 */
class SurfaceBackground(private val ctx: Context, private val surface: Surface) : DrawableWrapper(surface.drawable(ctx)) {
    private var res = surface.res

    fun recolour() {
        if (surface.res == res) return
        res = surface.res
        drawable = surface.drawable(ctx)
    }
}

object Plates {
    /** The white plate of a visual radius: tint it with a [Tint]. Radii 12, 16, 18, 20, 23, 26 are baked. */
    fun res(radius: Int): Int = when {
        radius <= 12 -> R.drawable.plate_12
        radius <= 16 -> R.drawable.plate_16
        radius <= 18 -> R.drawable.plate_18
        radius <= 20 -> R.drawable.plate_20
        radius <= 23 -> R.drawable.plate_23
        else -> R.drawable.plate_26
    }
}

/**
 * Bitmaps that are not 9-patches. The big ones of assets/surfaces (the room, the dock, the capsules; kept out of aapt2's recompression) are
 * decoded [Bitmap.Config.HARDWARE], so they are not held twice, kept here while a screen wants them, at most one theme's at rest. The small
 * ones of res ([res]) are ordinary bitmaps: a hardware bitmap costs a buffer of its own and a trip through a GL context of the uploader's, on the
 * calling thread, to save 10 KB (one second each on the emulator, with several running; a page of 16 held its first frame for 17 s).
 */
object Surfaces {
    private val kept = HashMap<String, Bitmap>()
    private val warm = ArrayList<Drawable>()

    /** A file in assets/surfaces, by its generated name: `room-night`, `dock-day`, `cap-left-night`. */
    @Synchronized
    fun asset(ctx: Context, name: String): Bitmap? = kept[name] ?: decodeAsset(ctx, name, hardware = true)?.also { kept[name] = it }

    /** `room` for this theme: `room-night` or `room-day`. */
    fun themed(ctx: Context, stem: String, night: Boolean = Palette.dark): Bitmap? = asset(ctx, stem + if (night) "-night" else "-day")

    /** What a software Canvas must read ([com.abdllh.aura.music.Backdrop]'s masks and frames). Not kept. */
    fun software(ctx: Context, name: String): Bitmap? = decodeAsset(ctx, name, hardware = false)

    /** A plain bitmap of res/drawable-nodpi (tile_music, scrim_top_night...), one per file, kept. */
    @Synchronized
    fun res(ctx: Context, id: Int): Bitmap? {
        val key = "res:$id"
        kept[key]?.let { return it }
        val b = try { BitmapFactory.decodeResource(ctx.resources, id, BitmapFactory.Options().apply { inScaled = false }) } catch (_: Throwable) { null }
        if (b != null) kept[key] = b
        return b
    }

    /** Lets go of one theme's, when it has gone from the screen (section 8, step 5). */
    @Synchronized
    fun free(night: Boolean) {
        val tail = if (night) "-night" else "-day"
        kept.keys.filter { it.endsWith(tail) }.forEach { kept.remove(it) }
    }

    /** Off the main thread, before a theme change: what the other theme needs, so nothing decodes in the frame of the swap. */
    fun prepare(ctx: Context, night: Boolean) {
        synchronized(warm) {
            if (warm.isEmpty()) for (s in Surface.values()) s.warm(ctx, warm)
        }
        for (stem in arrayOf("room", "dock", "cap-left", "cap-dock", "cap-chip", "cover-shadow")) themed(ctx, stem, night)
    }

    private fun decodeAsset(ctx: Context, name: String, hardware: Boolean): Bitmap? = try {
        ctx.assets.open("surfaces/$name.png").use { input ->
            val o = BitmapFactory.Options().apply {
                inScaled = false
                inPreferredConfig = if (hardware) Bitmap.Config.HARDWARE else Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeStream(input, null, o)
        }
    } catch (t: Throwable) {
        Log.w("AuraSurfaces", "$name: $t")
        null
    }
}

/**
 * The base of cards, pills and overlays. A surface's bounds are the visible rect plus the outset, and its padding (from the
 * 9-patch's guides) is the visible rect, so bounds overlap: outside the padding rect a touch is rejected and falls through to
 * what is under; inside it the card consumes it, so a tap on a card's blank area never pans the map. Children lay out in the
 * visible rect. Always give it an exact size: a 9-patch's minimum is its whole bitmap, so WRAP_CONTENT would give 156 px.
 */
open class SurfaceCard(ctx: Context, @JvmField val surface: Surface = Surface.CARD) : FrameLayout(ctx), Recolours {
    private val plate = SurfaceBackground(ctx, surface)
    private var kept = false

    init {
        background = plate
    }

    /** Takes the new theme's bitmap: same outset, same size, so nothing is laid out again. */
    override fun onLook() = plate.recolour()

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            val o = surface.outset
            kept = e.x >= o && e.y >= o && e.x < width - o && e.y < height - o
        }
        if (!kept) return false
        return super.dispatchTouchEvent(e) || e.actionMasked == MotionEvent.ACTION_DOWN
    }

    override fun onTouchEvent(e: MotionEvent): Boolean = super.onTouchEvent(e) || kept
}

/**
 * [SurfaceCard] as a column whose height comes from what is in it, and at most [maxH] high where a sheet or a dialog says so: the
 * height to fit of SPEC.md section 5. Touches in the outset are not its own, so they reach what is under: a scrim that closes, the
 * card above.
 */
open class SurfaceColumn(ctx: Context, @JvmField val surface: Surface = Surface.CARD, private val maxH: Int = 0) : LinearLayout(ctx), Recolours {
    private val plate = SurfaceBackground(ctx, surface)
    private var kept = false

    init {
        orientation = VERTICAL
        background = plate
    }

    override fun onLook() = plate.recolour()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var h = heightMeasureSpec
        if (maxH > 0) {
            val most = maxH + 2 * surface.outset
            if (MeasureSpec.getMode(h) == MeasureSpec.UNSPECIFIED || MeasureSpec.getSize(h) > most) h = MeasureSpec.makeMeasureSpec(most, MeasureSpec.AT_MOST)
        }
        super.onMeasure(widthMeasureSpec, h)
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            val o = surface.outset
            kept = e.x >= o && e.y >= o && e.x < width - o && e.y < height - o
        }
        if (!kept) return false
        return super.dispatchTouchEvent(e) || e.actionMasked == MotionEvent.ACTION_DOWN
    }

    override fun onTouchEvent(e: MotionEvent): Boolean = super.onTouchEvent(e) || kept
}

/**
 * Where a surface goes in a FrameLayout, from its visible rect in the parent's coordinates: from the left in every language,
 * because cards do not swap sides with it (SPEC.md section 1).
 */
fun Surface.at(x: Int, y: Int, w: Int, h: Int): FrameLayout.LayoutParams =
    FrameLayout.LayoutParams(w + 2 * outset, h + 2 * outset, Gravity.LEFT or Gravity.TOP).apply {
        leftMargin = x - outset
        topMargin = y - outset
    }

/** A plain view's rect in a FrameLayout, from the left in every language. */
fun at(x: Int, y: Int, w: Int, h: Int): FrameLayout.LayoutParams =
    FrameLayout.LayoutParams(w, h, Gravity.LEFT or Gravity.TOP).apply {
        leftMargin = x
        topMargin = y
    }

/**
 * A one-line label's rect from its line box, as the layout tables give it: the view is 1.5 em tall and centred on the box, so
 * it may overlap its neighbour, which is harmless because a label draws nothing outside its glyphs.
 */
fun Type.Role.box(x: Int, lineTop: Int, w: Int): FrameLayout.LayoutParams = at(x, lineTop + viewTop, w, viewH)

/** A plate of a visual radius in one colour token as a plain view, recoloured in place. */
fun Context.plateView(radius: Int, token: () -> Int): View = object : View(this) {
    override fun hasOverlappingRendering() = false
}.apply {
    val t = Tint(context, Plates.res(radius), token)
    background = t.drawable
    look { t.recolour() }
}

/** A bitmap of assets/surfaces in a plain view, in this theme, and in the next one when it changes. */
fun Context.surfaceImage(stem: String): ImageView = ImageView(this).apply {
    scaleType = ImageView.ScaleType.FIT_XY
    look { setImageBitmap(Surfaces.themed(context, stem)) }
}

/** A plain bitmap of res/drawable-nodpi that has a night and a day file. */
fun Context.surfaceImage(night: Int, day: Int): ImageView = ImageView(this).apply {
    scaleType = ImageView.ScaleType.FIT_XY
    look { setImageBitmap(Surfaces.res(context, if (Palette.dark) night else day)) }
}

/**
 * A scrolling list's edge inside a card or an overlay (SPEC.md section 4): the baked fade stretched sideways, in the surface's own
 * colour, so no saveLayer. Lay it over the list's first or last 28 px, with the list inset 8 from the surface's edge: a rect so inset is
 * inside the corner, and the fade cannot poke out of it (the mockups' fade has rounded corners of its own, which a bitmap cannot have).
 */
fun Context.scrollFade(surface: Surface, top: Boolean): ImageView {
    val overlay = surface == Surface.OVERLAY
    return surfaceImage(
        when {
            overlay && top -> R.drawable.fade_overlay_top_night
            overlay -> R.drawable.fade_overlay_bot_night
            top -> R.drawable.fade_card_top_night
            else -> R.drawable.fade_card_bot_night
        },
        when {
            overlay && top -> R.drawable.fade_overlay_top_day
            overlay -> R.drawable.fade_overlay_bot_day
            top -> R.drawable.fade_card_top_day
            else -> R.drawable.fade_card_bot_day
        }
    )
}

/** Whether a drawable is a real 9-patch once it is out of aapt2: its guides were kept (the gallery checks). */
fun Drawable.isNinePatch(): Boolean = this is NinePatchDrawable
