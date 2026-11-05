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
 * Home's row, y 490..600 in its parent (SPEC.md sections 1 and 6.1): three glass capsules, the separator, five colour tiles at the row's first five centres in the dock's
 * own order, the prayer capsule's icon, prayer and time left. Bitmaps only, which [Look][com.abdllh.aura.ui.Look] swaps for the other theme's.
 */
internal class RowHome(ctx: Context, order: List<Asked.Cell>, private val open: (String) -> Unit) : Row(ctx, SOLID) {
    private val prayerIcon = ImageView(ctx)
    private val prayer: AText = ctx.label(Type.chipPrayer, { Palette.ink }).apply {
        gravity = Gravity.RIGHT or Gravity.TOP
        textAlignment = TEXT_ALIGNMENT_GRAVITY
    }
    private val left: AText = ctx.label(Type.chipPrayerSub, { Palette.ink2 }).apply {
        gravity = Gravity.LEFT or Gravity.TOP
        textAlignment = TEXT_ALIGNMENT_GRAVITY
    }

    /** The tiles, and the pixels each stands from its own dock cell. */
    private val tiles = ArrayList<Tile>()
    private val shifts: IntArray

    init {
        addView(ctx.surfaceImage("cap-left"), at(-16, 0, 324, 132))
        addView(ctx.surfaceImage("cap-dock"), at(264, 0, 428, 132))
        addView(ctx.surfaceImage("cap-chip"), at(648, 0, 392, 132))
        addView(View(ctx).look { setBackgroundColor(Palette.line) }, at(203, 536 - TOP, 1, 40))
        addView(ctx.plateView(Tokens2.R_inner) { Palette.fill }, at(952, 530 - TOP, 52, 52))
        addView(prayerIcon, at(963, 541 - TOP, 30, 30))
        look { prayerIcon.setColorFilter(Palette.ink) }
        addView(prayer, Type.chipPrayer.box(940 - 240, 540 - TOP, 240))
        addView(left, Type.chipPrayerSub.box(692, 540 - TOP, 112))
        val all = ChromeRules.shifts(order.map { it.panel })
        var at = 0
        val own = ArrayList<Int>()
        for ((i, c) in order.withIndex()) {
            if (c.panel == Screens.HOME) continue
            val t = Tile(ctx, c.panel).apply { setOnClickListener { open(c.panel) } }
            tiles.add(t)
            own.add(all[i])
            addView(t, at(Asked.centreX(at) - 36, 26, 72, 84))
            at++
        }
        shifts = own.toIntArray()
    }

    /** [k] 0: Home, 1: the dock. A tile stands at its own centre on Home; it arrives at its dock cell's. */
    fun shift(k: Float) {
        for (i in tiles.indices) tiles[i].translationX = shifts[i] * k
    }

    fun setPrayer(word: CharSequence, leftWord: CharSequence, icon: Int) {
        if (word != prayer.text) prayer.text = word
        if (leftWord != left.text) left.text = leftWord
        if (icon != prayerRes) {
            prayerRes = icon
            prayerIcon.setImageResource(icon)
        }
    }

    private var prayerRes = 0

    companion object {
        const val TOP = 490
        const val SOLID = 516 - TOP
    }
}

/**
 * A tile of Home's row: 60 x 60, its bitmap and white glyph (section 5), in a hit of 72 x 84 with the row's centre at its own centre line. One view with no children, drawn in one go, so its
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
        pivotX = 36f
        pivotY = CY
    }

    private fun glyphOf(ctx: Context, res: Int, px: Int): Drawable = ctx.getDrawable(res)!!.mutate().also {
        it.setTint(Palette.onAccent)
        // (an odd size stands half a pixel off: it goes to the right and down, as the mockups' does)
        val x = 36 - px / 2
        val y = CY.toInt() - px / 2
        it.setBounds(x, y, x + px, y + px)
    }

    override fun hasOverlappingRendering() = false

    override fun onDraw(c: Canvas) {
        art?.let { c.drawBitmap(it, 6f, 10f, paint) }
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

    private companion object {
        /** The row's centre line, y 556, in a view from y 516. */
        const val CY = 40f
    }
}

/**
 * The dock, y 500..600 in its parent (SPEC.md sections 1, 4 and 5): the bar's bitmap, the volume's pill, the selected plate on its spring, six cells, the glance. The plate is under the
 * glyphs and they retint by how much of it covers them, by the frame while it moves, so a glyph is white only where the plate is under it.
 */
internal class RowDock(ctx: Context, private val order: List<Asked.Cell>, open: (String) -> Unit) : Row(ctx, SOLID), Recolours {
    private val plate = SelectedPlate(ctx)
    private val cells = ArrayList<IconButton>()
    private val shifts = ChromeRules.shifts(order.map { it.panel })
    val glance = Glance(ctx)

    /** Where the plate's centre is, in the screen's x. */
    private val slide = Spring(0f, Tokens2.SPRING_dockPlate[0], Tokens2.SPRING_dockPlate[1], restDelta = 0.25f) { x ->
        plate.translationX = x - ChromeRules.PLATE_W / 2f
        retint()
    }
    private var plateShown = 0f
    private var shift = 1f

    init {
        addView(ctx.surfaceImage("dock"), at(0, 0, 1024, 100))
        addView(ctx.plateView(Tokens2.R_inner) { Palette.fill }, at(12, 526 - TOP, 188, 60))
        addView(plate, at(0, 526 - TOP, ChromeRules.PLATE_W, 60))
        plate.alpha = 0f
        for ((i, c) in order.withIndex()) {
            val b = IconButton(ctx, c.icon, ChromeRules.GLYPH, 72, 84, centerDy = -2, pressPlate = true).apply {
                contentDescription = ctx.getString(ChromeWords.nameOf(c.panel))
                pivotY = 40f
                setOnClickListener { open(c.panel) }
                setOnTouchListener { _, e ->
                    if (e.actionMasked == MotionEvent.ACTION_DOWN) Tones.tick(context)
                    false
                }
            }
            cells.add(b)
            addView(b, at(Asked.centreX(i) - 36, 516 - TOP, 72, 84))
        }
        addView(glance, at(730, 516 - TOP, 282, 84))
    }

    /** [k] as [RowHome.shift]: each cell that has a tile comes from where its tile stands on Home; the home cell has none and stays. */
    fun shift(k: Float) {
        shift = k
        for (i in cells.indices) cells[i].translationX = -shifts[i] * (1f - k)
        retint()
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
        for (i in cells.indices) {
            val x = Asked.centreX(i) + cells[i].translationX
            cells[i].coverage = ChromeRules.coverage(x, at) * plateShown
        }
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
 * The right end of the dock, x 730..1012 (SPEC.md section 5): the next prayer's icon and time, or the mini-player. Two faces, the same view at every panel, crossfading in 140 `move`
 * when the face changes; which it is comes from [ChromeRules.glance]. In the same pixels in both languages, only the words change.
 */
internal class Glance(ctx: Context) : FrameLayout(ctx), Recolours {
    private val prayerFace = Face(ctx)
    private val prayerIcon = ImageView(ctx)
    private var quiet = false
    private val prayer: AText = ctx.label(Type.chipPrayer, { if (quiet) Palette.ink2 else Palette.ink }).apply {
        gravity = Gravity.RIGHT or Gravity.TOP
        textAlignment = TEXT_ALIGNMENT_GRAVITY
    }
    private val miniFace = Face(ctx)
    private val cover = ImageView(ctx)
    private val title: AText = ctx.label(Type.miniSub, { Palette.ink }, moving = true)
    private val play = PlayPauseView(ctx).apply {
        glyphPx = 32
        centerDy = -2
        token = { Palette.ink }
    }
    private val next = IconButton(ctx, R.drawable.ic_next, 34, 48, 84, centerDy = -2, token = { Palette.ink })
    private var prayerRes = 0
    private var coverOf: Bitmap? = null
    private var hasSong = false

    /** The title's tap: the player. */
    var onTitle: () -> Unit = {}

    init {
        prayerFace.addView(ctx.plateView(Tokens2.R_inner) { Palette.fill }, at(222, 14, 52, 52))
        prayerFace.addView(prayerIcon, at(233, 25, 30, 30))
        look { prayerIcon.setColorFilter(Palette.ink) }
        prayerFace.addView(prayer, Type.chipPrayer.box(210 - 240, 24, 240))
        addView(prayerFace, at(0, 0, 282, 84))

        if (Fonts.arabicUi) title.apply {
            gravity = Gravity.RIGHT or Gravity.TOP
            textAlignment = TEXT_ALIGNMENT_GRAVITY
        } else title.apply {
            gravity = Gravity.LEFT or Gravity.TOP
            textAlignment = TEXT_ALIGNMENT_GRAVITY
        }
        val tap = View(ctx).apply {
            isClickable = true
            setOnClickListener { onTitle() }
        }
        miniFace.addView(tap, at(96, 0, 186, 84))
        miniFace.addView(cover, at(230, 18, 44, 44))
        miniFace.addView(title, Type.miniSub.box(104, 25, 118))
        play.isClickable = true
        play.setOnClickListener { MediaMonitor.send(context, MediaMonitor.Key.PLAY_PAUSE) }
        val g = PressGesture(play, PressGesture.ICON)
        play.setOnTouchListener { _, e -> g.onTouch(e); false }
        miniFace.addView(play, at(0, 0, 48, 84))
        next.setOnClickListener { MediaMonitor.send(context, MediaMonitor.Key.NEXT) }
        miniFace.addView(next, at(48, 0, 48, 84))
        miniFace.visibility = INVISIBLE
        miniFace.alpha = 0f
        addView(miniFace, at(0, 0, 282, 84))
    }

    private var mini = false

    override fun hasOverlappingRendering() = false

    // (the plate with no cover is of one theme)
    override fun onLook() {
        if (hasSong && coverOf == null) cover.setImageDrawable(Backdrop.noArt(context, COVER, Tokens2.R_coverMini))
    }

    fun setPrayer(word: CharSequence, icon: Int, quiet: Boolean) {
        if (word != prayer.text) prayer.text = word
        if (icon != prayerRes) {
            prayerRes = icon
            prayerIcon.setImageResource(icon)
        }
        if (quiet != this.quiet) {
            this.quiet = quiet
            prayer.setTextColor(if (quiet) Palette.ink2 else Palette.ink)
            // (a weight down: this one label is laid out again, when the car starts moving or the prayer comes near)
            prayer.typeface = Fonts.get(if (quiet) 400 else Type.chipPrayer.weight)
        }
    }

    /** The song in the mini's place, whichever face shows. The cover is rounded once per song on [Backdrop]'s worker. */
    fun setSong(title: CharSequence, cover: Bitmap?, playing: Boolean, animate: Boolean) {
        if (title != this.title.text) this.title.text = title
        play.setPlaying(playing, animate && mini)
        if (cover !== coverOf || !hasSong) {
            hasSong = true
            coverOf = cover
            if (cover != null) Backdrop.roundAsync(context, cover, COVER, Tokens2.R_coverMini) { if (coverOf === cover) this.cover.setImageBitmap(it) }
            else this.cover.setImageDrawable(Backdrop.noArt(context, COVER, Tokens2.R_coverMini))
        }
    }

    fun face(mini: Boolean, animate: Boolean) {
        if (mini == this.mini) return
        this.mini = mini
        val on = if (mini) miniFace else prayerFace
        val off = if (mini) prayerFace else miniFace
        if (!animate) {
            on.animate().cancel()
            off.animate().cancel()
            on.alpha = 1f
            on.visibility = VISIBLE
            off.alpha = 0f
            off.visibility = INVISIBLE
            return
        }
        on.visibility = VISIBLE
        on.fadeTo(1f, Tokens2.MS_swap, Motion.MOVE)
        off.fadeTo(0f, Tokens2.MS_swap, Motion.MOVE) { if (off.alpha == 0f) off.visibility = INVISIBLE }
    }

    /** Takes no touch where it shows nothing. */
    private class Face(ctx: Context) : FrameLayout(ctx) {
        override fun hasOverlappingRendering() = false
    }

    private companion object {
        const val COVER = 44
    }
}
