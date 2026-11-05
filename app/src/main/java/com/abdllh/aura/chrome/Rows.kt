package com.abdllh.aura.chrome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
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
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.PlayPauseView
import com.abdllh.aura.ui.PressGesture
import com.abdllh.aura.ui.Recolours
import com.abdllh.aura.ui.Surface
import com.abdllh.aura.ui.SurfaceCard
import com.abdllh.aura.ui.Surfaces
import com.abdllh.aura.ui.Tint
import com.abdllh.aura.ui.Tokens2
import com.abdllh.aura.ui.Tones
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.at
import com.abdllh.aura.ui.box
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.look
import com.abdllh.aura.ui.surfaceImage

/**
 * The one bottom row, y 492..600 in its parent, the same pixels on every screen: two capsules and six colour tiles, nearest the driver first ([Asked.ORDER_NEAREST]), their cells filling the capsule
 * ([ChromeRules.tileX]). Bitmaps only, which [Look][com.abdllh.aura.ui.Look] swaps for the other theme's. Nothing here moves when the screen changes: the tile of the screen he is on gets a short bar ([mark]).
 * The volume and the mic lie over the left capsule ([VolumeCluster], [VoiceButton]). Its own group has no layer, and a touch on its blank parts under y 516 stops here: it is not meant for the map under it.
 */
internal class Row(ctx: Context, open: (String) -> Unit) : FrameLayout(ctx) {
    private val tiles = Asked.ORDER_NEAREST.map { panel -> Tile(ctx, panel).apply { setOnClickListener { open(panel) } } }

    init {
        addView(ctx.surfaceImage("cap-left"), at(-12, 0, 320, 128))
        addView(ctx.surfaceImage("cap-dock"), at(264, 0, 772, 128))
        for ((i, t) in tiles.withIndex()) addView(t, at(ChromeRules.tileX(i) - Tile.W / 2, SOLID, Tile.W, 84))
    }

    /** The screen he is on: its tile is marked, and a press on it does nothing. A panel with no tile: none. */
    fun mark(panel: String) {
        for (t in tiles) t.current = t.panel == panel
    }

    override fun hasOverlappingRendering() = false

    override fun onTouchEvent(e: MotionEvent): Boolean = e.y >= SOLID || e.actionMasked != MotionEvent.ACTION_DOWN

    companion object {
        const val TOP = 492
        const val SOLID = 516 - TOP
    }
}

/**
 * A tile of the row: 60 x 60, its bitmap and white glyph, in a hit as wide as its cell with the row's centre at its own centre line. One view with no children, drawn in one go, so its alpha needs no layer.
 * [current]: the short bar in `ink` under it, in the 6 px between the tile and the capsule's edge.
 */
internal class Tile(ctx: Context, val panel: String) : View(ctx), Recolours {
    private val art: Bitmap? = Surfaces.res(ctx, when (panel) {
        Screens.HOME -> R.drawable.tile_home
        Screens.MAPS -> R.drawable.tile_map
        Screens.MUSIC -> R.drawable.tile_music
        Screens.PHONE -> R.drawable.tile_phone
        Screens.APPS -> R.drawable.tile_apps
        else -> R.drawable.tile_settings
    })
    private val glyph: Drawable? = when (panel) {
        Screens.HOME -> glyphOf(ctx, R.drawable.ic_home, 36)
        Screens.MUSIC -> glyphOf(ctx, R.drawable.ic_music, 36)
        Screens.PHONE -> glyphOf(ctx, R.drawable.ic_phone, 34)
        Screens.APPS -> glyphOf(ctx, R.drawable.ic_apps, 31)
        Screens.SETTINGS -> glyphOf(ctx, R.drawable.ic_gear, 37)
        else -> null // (the map has its art)
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bar = RectF(W / 2f - MARK_W / 2f, MARK_TOP, W / 2f + MARK_W / 2f, MARK_TOP + MARK_H)
    private val gesture = PressGesture(this, PressGesture.ICON)

    var current = false
        set(v) {
            if (v == field) return
            field = v
            if (v) {
                gesture.reset() // (the press that brought him here, if the finger is still on it)
                isPressed = false
            }
            invalidate()
        }

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

    override fun onLook() = invalidate()

    override fun hasOverlappingRendering() = false

    override fun onDraw(c: Canvas) {
        art?.let { c.drawBitmap(it, (W - ART) / 2f, 10f, paint) }
        glyph?.draw(c)
        if (current) {
            paint.color = Palette.ink
            c.drawRoundRect(bar, MARK_H / 2f, MARK_H / 2f, paint)
        }
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (current) return true
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
        /** The hit: its cell, with what rounding leaves over. */
        const val W = 120
        private const val ART = 60
        /** The row's centre line, y 556, in a view from y 516. */
        private const val CY = 40f
        private const val MARK_W = 20f
        private const val MARK_H = 3f
        /** y 587: 1 px under the tile, 2 above the capsule's edge. */
        private const val MARK_TOP = 71f
    }
}

/**
 * The song, on Maps only: a small floating card at the top right, under the time, x 772..1008, y 48..108. The cover at the far end, the title, and pause at the driver's side of it; anywhere else on the card opens
 * Music. Everywhere else he has the Music tile or Home's widget, so nothing there. In the same pixels in both languages, only the words change.
 */
internal class SongCard(ctx: Context) : SurfaceCard(ctx, Surface.PILL) {
    private val cover = ImageView(ctx)
    private val title: AText = ctx.label(Type.miniSub, { Palette.ink }, moving = true).apply {
        gravity = (if (Fonts.arabicUi) Gravity.RIGHT else Gravity.LEFT) or Gravity.TOP
        textAlignment = TEXT_ALIGNMENT_GRAVITY
    }
    private val play = PlayPauseView(ctx).apply {
        glyphPx = 34
        token = { Palette.ink }
    }
    private var coverOf: Bitmap? = null
    private var hasSong = false

    /** A tap on the card. */
    var onOpen: () -> Unit = {}

    init {
        alpha = 0f
        visibility = INVISIBLE
        val press = Tint(ctx, R.drawable.plate_18) { Palette.fillPress }
        addView(View(ctx).apply {
            background = PressGesture.plate(press.drawable)
            isClickable = true
            setOnClickListener { onOpen() }
        }, at(0, 0, W, H))
        addView(cover, at(W - 6 - COVER, 6, COVER, COVER))
        addView(title, Type.miniSub.box(if (Fonts.arabicUi) 60 else 68, 15, TITLE_W))
        play.isClickable = true
        play.contentDescription = ctx.getString(R.string.media_play)
        play.setOnClickListener { MediaMonitor.send(context, MediaMonitor.Key.PLAY_PAUSE) }
        val g = PressGesture(play, PressGesture.ICON)
        play.setOnTouchListener { _, e -> g.onTouch(e); false }
        addView(play, at(0, 0, 56, H))
        look { press.recolour() }
        dimCover()
    }

    // (the plate with no cover is of one theme, and a cover is dimmed at night)
    override fun onLook() {
        super.onLook()
        dimCover()
        if (hasSong && coverOf == null) cover.setImageDrawable(Backdrop.noArt(context, COVER, Tokens2.R_coverMini))
    }

    private fun dimCover() {
        cover.alpha = if (Palette.dark) NIGHT_COVER else 1f
    }

    /** The song, whether it shows or not. The cover is rounded once per song on [Backdrop]'s worker. */
    fun setSong(title: CharSequence, cover: Bitmap?, playing: Boolean, animate: Boolean) {
        if (title != this.title.text) this.title.text = title
        play.setPlaying(playing, animate && visibility == VISIBLE)
        if (cover !== coverOf || !hasSong) {
            hasSong = true
            coverOf = cover
            if (cover != null) Backdrop.roundAsync(context, cover, COVER, Tokens2.R_coverMini) { if (coverOf === cover) this.cover.setImageBitmap(it) }
            else this.cover.setImageDrawable(Backdrop.noArt(context, COVER, Tokens2.R_coverMini))
        }
    }

    companion object {
        const val X = 772
        const val Y = 48
        const val W = 236
        const val H = 60
        private const val COVER = 48
        private const val TITLE_W = 110
        private const val NIGHT_COVER = .8f
    }
}
