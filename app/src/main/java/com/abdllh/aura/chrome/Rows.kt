package com.abdllh.aura.chrome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.abdllh.aura.R
import com.abdllh.aura.home.Screens
import com.abdllh.aura.media.MediaMonitor
import com.abdllh.aura.music.Backdrop
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Asked
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.IconButton
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.PlayPauseView
import com.abdllh.aura.ui.PressGesture
import com.abdllh.aura.ui.Recolours
import com.abdllh.aura.ui.Spring
import com.abdllh.aura.ui.Surfaces
import com.abdllh.aura.ui.Tint
import com.abdllh.aura.ui.Tokens2
import com.abdllh.aura.ui.Tones
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.at
import com.abdllh.aura.ui.box
import com.abdllh.aura.ui.fadeTo
import com.abdllh.aura.ui.ink
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.look
import com.abdllh.aura.ui.plateView
import com.abdllh.aura.ui.surfaceImage

/**
 * What the two bottom rows have in common. One group, with no layer of its own: its alpha goes into each thing it draws
 * (hasOverlappingRendering false), because a layer here would be one more off-screen buffer in the frame that must answer, and what is in a row
 * hardly overlaps. A row that is going takes no new touch from the moment it starts to go, and a touch that began on it hears it end. Under
 * [solidTop] a touch on the row's blank parts stops here: it is not meant for the map or the panel under it.
 */
internal open class Row(ctx: Context, private val solidTop: Int) : FrameLayout(ctx) {
    private var touching = false

    var live = true
        set(v) {
            if (field && !v && touching) {
                touching = false
                val now = SystemClock.uptimeMillis()
                val c = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
                super.dispatchTouchEvent(c)
                c.recycle()
            }
            field = v
        }

    override fun hasOverlappingRendering() = false

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { if (!live) return false; touching = true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touching = false
        }
        return live && super.dispatchTouchEvent(e)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean = e.y >= solidTop || e.actionMasked != MotionEvent.ACTION_DOWN
}

/**
 * Home's row, y 492..600 in its parent (CHANGES.md section 2): two glass capsules and five colour tiles at their own centres, in the dock's order. Bitmaps only, which [Look][com.abdllh.aura.ui.Look] swaps
 * for the other theme's. Nothing here moves with the row's change: it crossfades in place.
 */
internal class RowHome(ctx: Context, open: (String) -> Unit) : Row(ctx, SOLID) {
    init {
        addView(ctx.surfaceImage("cap-left"), at(-12, 0, 320, 128))
        addView(ctx.surfaceImage("cap-dock"), at(264, 0, 772, 128))
        val tiles = Asked.ORDER_NEAREST.filter { it.panel != Screens.HOME }
        for ((i, c) in tiles.withIndex()) {
            val t = Tile(ctx, c.panel).apply { setOnClickListener { open(c.panel) } }
            addView(t, at(Asked.tileX(i) - Tile.W / 2, SOLID, Tile.W, 84))
        }
    }

    companion object {
        const val TOP = 492
        const val SOLID = 516 - TOP
    }
}

/**
 * A tile of Home's row: 60 x 60, its bitmap and white glyph (section 5), in a hit of 96 x 84 with the row's centre at its own centre line. One view with no children, drawn in one go, so its
 * alpha needs no layer.
 */
internal class Tile(ctx: Context, panel: String) : View(ctx) {
    private val art: Bitmap? = Surfaces.res(ctx, when (panel) {
        Screens.MAPS -> R.drawable.tile_map
        Screens.MUSIC -> R.drawable.tile_music
        Screens.PHONE -> R.drawable.tile_phone
        Screens.APPS -> R.drawable.tile_apps
        else -> R.drawable.tile_settings
    })
    private val glyph: Drawable? = when (panel) {
        Screens.MUSIC -> glyphOf(ctx, R.drawable.ic_music, 36)
        Screens.PHONE -> glyphOf(ctx, R.drawable.ic_phone, 34)
        Screens.APPS -> glyphOf(ctx, R.drawable.ic_apps, 31)
        Screens.SETTINGS -> glyphOf(ctx, R.drawable.ic_gear, 37)
        else -> null // (the map has its art)
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val gesture = PressGesture(this, PressGesture.ICON)

    init {
        isClickable = true
        contentDescription = ctx.getString(ChromeWords.nameOf(panel))
        pivotX = W / 2f
        pivotY = CY
    }

    private fun glyphOf(ctx: Context, res: Int, px: Int): Drawable = ctx.getDrawable(res)!!.mutate().also {
        it.setTint(Palette.onAccent)
        // (an odd size stands half a pixel off: it goes to the right and down, as the mockups' does)
        val x = W / 2 - px / 2
        val y = CY.toInt() - px / 2
        it.setBounds(x, y, x + px, y + px)
    }

    override fun hasOverlappingRendering() = false

    override fun onDraw(c: Canvas) {
        art?.let { c.drawBitmap(it, (W - ART) / 2f, 10f, paint) }
        glyph?.draw(c)
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        gesture.onTouch(e)
        if (e.actionMasked == MotionEvent.ACTION_DOWN) Tones.tick(context)
        return super.dispatchTouchEvent(e)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean = gesture.tolerant(e) { super.onTouchEvent(it) }

    override fun onDetachedFromWindow() {
        gesture.reset()
        super.onDetachedFromWindow()
    }

    companion object {
        /** The hit: its centre plus or minus 48. */
        const val W = 96
        private const val ART = 60
        /** The row's centre line, y 556, in a view from y 516. */
        private const val CY = 40f
    }
}

/**
 * The dock, y 500..600 in its parent (SPEC.md sections 1, 4 and 5, CHANGES.md section 2): the bar's bitmap, the volume's pill, the selected plate on its spring, six cells, the mini player. The plate is under the
 * glyphs and they retint by how much of it covers them, by the frame while it moves, so a glyph is white only where the plate is under it.
 */
internal class RowDock(ctx: Context, open: (String) -> Unit) : Row(ctx, SOLID), Recolours {
    private val order = Asked.ORDER_NEAREST
    private val plate = SelectedPlate(ctx)
    private val cells = ArrayList<IconButton>()
    val glance = Glance(ctx)

    /** Where the plate's centre is, in the screen's x. */
    private val slide = Spring(0f, Tokens2.SPRING_dockPlate[0], Tokens2.SPRING_dockPlate[1], restDelta = 0.25f) { x ->
        plate.translationX = x - ChromeRules.PLATE_W / 2f
        retint()
    }
    private var plateShown = 0f

    init {
        addView(ctx.surfaceImage("dock"), at(0, 0, 1024, 100))
        addView(ctx.plateView(Tokens2.R_inner) { Palette.fill }, at(16, 526 - TOP, 184, 60))
        addView(plate, at(0, 526 - TOP, ChromeRules.PLATE_W, 60))
        plate.alpha = 0f
        for ((i, c) in order.withIndex()) {
            val b = IconButton(ctx, c.icon, ChromeRules.GLYPH, ChromeRules.CELL_W, 84, centerDy = -2, pressPlate = true).apply {
                contentDescription = ctx.getString(ChromeWords.nameOf(c.panel))
                pivotY = 40f
                setOnClickListener { open(c.panel) }
                setOnTouchListener { _, e ->
                    if (e.actionMasked == MotionEvent.ACTION_DOWN) Tones.tick(context)
                    false
                }
            }
            cells.add(b)
            addView(b, at(Asked.centreX(i) - ChromeRules.CELL_W / 2, 516 - TOP, ChromeRules.CELL_W, 84))
        }
        addView(glance, at(Glance.X, 516 - TOP, Glance.W, 84))
    }

    /** The selected cell's panel: the plate there at once, under what is fading in (from Home), or nothing. */
    fun placeOn(panel: String) {
        val i = order.indexOfFirst { it.panel == panel }
        selectedFade.cancel()
        if (i >= 0) {
            slide.snapTo(Asked.centreX(i).toFloat())
            plateShown = 1f
        } else plateShown = 0f
        plate.alpha = plateShown
        retint()
    }

    /** Between panels of the dock: the plate slides on its spring. To or from a panel with no cell it fades in place. Turned back from where it is. */
    fun slideTo(panel: String) {
        val i = order.indexOfFirst { it.panel == panel }
        if (i >= 0) {
            if (plateShown <= 0f) {
                slide.snapTo(Asked.centreX(i).toFloat())
                selectedFade.to(1f)
            } else {
                selectedFade.to(1f)
                slide.animateTo(Asked.centreX(i).toFloat())
            }
        } else selectedFade.to(0f)
    }

    private val selectedFade = object {
        private val tween = Tweens(onFrame = { retint() })
        private val part = tween.part(0f) { v -> plateShown = v; plate.alpha = v }

        fun to(target: Float) {
            part.snap(plateShown)
            if (plateShown != target) part.to(target, 0L, Tokens2.MS_swap, Motion.MOVE)
        }

        fun cancel() = tween.stop()
    }

    private fun retint() {
        val at = plate.translationX + ChromeRules.PLATE_W / 2f
        for (i in cells.indices) cells[i].coverage = ChromeRules.coverage(Asked.centreX(i).toFloat(), at) * plateShown
    }

    override fun onLook() = retint()

    companion object {
        const val TOP = 500
        const val SOLID = 516 - TOP
    }
}

/** The selected plate: `plate-18` in `selected` and `ring-18` in `selectedEdge`, 64 x 60. */
internal class SelectedPlate(ctx: Context) : View(ctx), Recolours {
    private val plate = Tint(ctx, R.drawable.plate_18) { Palette.selected }
    private val ring = Tint(ctx, R.drawable.ring_18) { Palette.selectedEdge }

    override fun onLook() {
        plate.recolour()
        ring.recolour()
        invalidate()
    }

    override fun hasOverlappingRendering() = false

    override fun onDraw(c: Canvas) {
        plate.drawable.setBounds(0, 0, width, height)
        ring.drawable.setBounds(0, 0, width, height)
        plate.drawable.draw(c)
        ring.drawable.draw(c)
    }
}

/**
 * The mini player at the right end of the dock, x 772..1008 (CHANGES.md section 2): the plate, the cover at the far end, the title, and pause at the driver's side of it. Anywhere else on the plate opens
 * Music. With no song, or on the parked Music panel, nothing: the cells do not move. Which it is comes from [ChromeRules.mini], a crossfade of 140 `move` inside a dock that is showing; in the same pixels
 * in both languages, only the words change.
 */
internal class Glance(ctx: Context) : FrameLayout(ctx), Recolours {
    private val cover = ImageView(ctx)
    private val title: AText = ctx.label(Type.miniSub, { Palette.ink }, moving = true).apply {
        gravity = (if (Fonts.arabicUi) Gravity.RIGHT else Gravity.LEFT) or Gravity.TOP
        textAlignment = TEXT_ALIGNMENT_GRAVITY
    }
    private val play = PlayPauseView(ctx).apply {
        glyphPx = 34
        centerDy = -2
        token = { Palette.ink }
    }
    private var coverOf: Bitmap? = null
    private var hasSong = false
    private var showing = false

    /** A tap on the plate. */
    var onOpen: () -> Unit = {}

    init {
        alpha = 0f
        visibility = INVISIBLE
        val plate = Tint(ctx, R.drawable.plate_18) { Palette.fill }
        val press = Tint(ctx, R.drawable.plate_18) { Palette.fillPress }
        addView(View(ctx).apply { background = plate.drawable }, at(0, 10, W, 60))
        addView(View(ctx).apply {
            background = PressGesture.plate(press.drawable)
            isClickable = true
            setOnClickListener { onOpen() }
        }, at(0, 10, W, 60))
        addView(cover, at(182, 16, COVER, COVER))
        addView(title, Type.miniSub.box(if (Fonts.arabicUi) 60 else 68, 25, TITLE_W))
        play.isClickable = true
        play.contentDescription = ctx.getString(R.string.media_play)
        play.setOnClickListener { MediaMonitor.send(context, MediaMonitor.Key.PLAY_PAUSE) }
        val g = PressGesture(play, PressGesture.ICON)
        play.setOnTouchListener { _, e -> g.onTouch(e); false }
        addView(play, at(0, 0, 56, 84))
        look {
            plate.recolour()
            press.recolour()
        }
        onLook()
    }

    override fun hasOverlappingRendering() = false

    // (the plate with no cover is of one theme, and a cover is dimmed at night)
    override fun onLook() {
        cover.alpha = if (Palette.dark) NIGHT_COVER else 1f
        if (hasSong && coverOf == null) cover.setImageDrawable(Backdrop.noArt(context, COVER, Tokens2.R_coverMini))
    }

    /** The song, whether it shows or not. The cover is rounded once per song on [Backdrop]'s worker. */
    fun setSong(title: CharSequence, cover: Bitmap?, playing: Boolean, animate: Boolean) {
        if (title != this.title.text) this.title.text = title
        play.setPlaying(playing, animate && showing)
        if (cover !== coverOf || !hasSong) {
            hasSong = true
            coverOf = cover
            if (cover != null) Backdrop.roundAsync(context, cover, COVER, Tokens2.R_coverMini) { if (coverOf === cover) this.cover.setImageBitmap(it) }
            else this.cover.setImageDrawable(Backdrop.noArt(context, COVER, Tokens2.R_coverMini))
        }
    }

    fun show(on: Boolean, animate: Boolean) {
        if (on == showing) return
        showing = on
        animate().cancel()
        if (!animate) {
            alpha = if (on) 1f else 0f
            visibility = if (on) VISIBLE else INVISIBLE
            return
        }
        visibility = VISIBLE
        fadeTo(if (on) 1f else 0f, Tokens2.MS_swap, Motion.MOVE) { if (!showing) visibility = INVISIBLE }
    }

    companion object {
        const val X = 772
        const val W = 236
        private const val COVER = 48
        private const val TITLE_W = 110
        private const val NIGHT_COVER = .8f
    }
}
