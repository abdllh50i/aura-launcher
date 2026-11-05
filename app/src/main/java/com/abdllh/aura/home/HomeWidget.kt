package com.abdllh.aura.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.TransitionDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.abdllh.aura.R
import com.abdllh.aura.chrome.Tweens
import com.abdllh.aura.media.MediaMonitor
import com.abdllh.aura.media.NowPlaying
import com.abdllh.aura.music.Backdrop
import com.abdllh.aura.music.BtMusic
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.IconButton
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.PlayPauseView
import com.abdllh.aura.ui.Plates
import com.abdllh.aura.ui.PressGesture
import com.abdllh.aura.ui.Surface
import com.abdllh.aura.ui.SurfaceCard
import com.abdllh.aura.ui.ThinProgress
import com.abdllh.aura.ui.Tint
import com.abdllh.aura.ui.Tokens2
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.at
import com.abdllh.aura.ui.box
import com.abdllh.aura.ui.enter
import com.abdllh.aura.ui.ink
import com.abdllh.aura.ui.iconView
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.look
import com.abdllh.aura.ui.plateView

/**
 * Home's music widget, 364 x 182 at (12, 324) (SPEC.md sections 5 and 12): the song's wash, cover, names, a thin progress line and prev, pause, next, always left to right; while the car moves, the
 * mini's rects, so pause and next stand where they stand on every driving screen; with no song, the card and the three sources. The three faces are all built and laid out, and cross-fade in alpha.
 * The logic is `MediaCard`'s: what plays comes from [MediaMonitor], Bluetooth sends a song in parts, so the old cover waits dimmed for the new one, and a new song's names come in from the side of the
 * button pressed. Coordinates are the window's: the numbers of the layout tables.
 */
internal class HomeWidget(ctx: Context, private val host: HomeHost) : FrameLayout(ctx) {
    private val ar = Fonts.arabicUi

    /** The empty card, and what stands in until a song's wash is made. */
    private val card = SurfaceCard(ctx, Surface.CARD)
    private val washOld = washView(ctx)
    private val washNew = washView(ctx)
    private val washFade = Tweens()
    private val washPart = washFade.part(0f) { v -> washNew.alpha = v }

    private val parked = group(ctx)
    private val moving = group(ctx)
    private val empty = group(ctx)
    private val faces = HomeFaces(listOf(parked, moving, empty), empty)

    private val cover84 = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_XY }
    private val cover72 = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_XY }
    private val title84: AText = ctx.label(Type.widgetTitle, { Palette.ink })
    private val title72: AText = ctx.label(Type.widgetTitle, { Palette.ink }, moving = true)
    private val sub84: AText = ctx.label(Type.widgetSub, { Palette.ink2 }).apply { textDirection = TEXT_DIRECTION_LOCALE }
    private val sub72: AText = ctx.label(Type.miniSub, { Palette.ink2Move }, moving = true).apply { textDirection = TEXT_DIRECTION_LOCALE }
    private val source: AText = ctx.label(Type.miniSub, { Palette.ink2Move }, moving = true).apply {
        gravity = Gravity.RIGHT or Gravity.TOP
        textAlignment = TEXT_ALIGNMENT_GRAVITY
        textDirection = TEXT_DIRECTION_LOCALE
    }
    private val progress84 = ThinProgress(ctx)
    private val progress72 = ThinProgress(ctx)
    private val play84 = PlayHit(ctx, 40, -2, false)
    private val play72 = PlayHit(ctx, 36, 0, true)
    private val prev = transport(ctx, R.drawable.ic_prev, 32, 76, 64, R.string.media_prev, -2) { skip(-1, MediaMonitor.Key.PREV) }
    private val next = transport(ctx, R.drawable.ic_next, 32, 76, 64, R.string.media_next, -2) { skip(+1, MediaMonitor.Key.NEXT) }
    private val next72 = IconButton(
        ctx, R.drawable.ic_next, 36, 68, 80, IconButton.Kind.TONAL, PressGesture.ICON, plateToken = { Palette.fill }, token = { Palette.ink }
    ).apply {
        contentDescription = ctx.getString(R.string.media_next)
        setOnClickListener { skip(+1, MediaMonitor.Key.NEXT) }
    }

    private var np: NowPlaying? = null
    private var bound = false
    private var shownSong: String? = null
    private var shownArt: Bitmap? = null
    private var holdingArt = false
    private var lastDir = 0
    private var lastDirAt = 0L
    private var movingNow = false
    private var listening = false
    private var fraction = -1f
    private var washKey: Any? = NONE
    private var washDark = Palette.dark
    private var prepared: Backdrop.Wash? = null
    private var preparedFor: Bitmap? = null
    private val sources = HashMap<String, String>()
    private val listener: (NowPlaying?) -> Unit = { n -> if (!debugHeld) bind(n) }

    private val tick = object : Runnable {
        override fun run() {
            if (debugHeld) return
            val p = MediaMonitor.progress()
            setFraction(if (p != null && p.second > 0) (p.first.toFloat() / p.second).coerceIn(0f, 1f) else -1f)
            if (np?.playing == true && listening) postDelayed(this, 1000L)
        }
    }
    private val dropArt = Runnable { holdingArt = false; showArt(null) }

    init {
        addView(card, Surface.CARD.at(12, 324, 364, 182))
        addView(washOld, at(12 - Backdrop.CARD_MARGIN, 324 - Backdrop.CARD_MARGIN, WASH_W, WASH_H))
        addView(washNew, at(12 - Backdrop.CARD_MARGIN, 324 - Backdrop.CARD_MARGIN, WASH_W, WASH_H))
        washOld.visibility = INVISIBLE
        washNew.visibility = INVISIBLE
        // a tap on the card: the player. The press shows as a plate at once, so it reads in sun, and nothing here scales
        addView(View(ctx).apply {
            val t = Tint(ctx, R.drawable.plate_26) { Palette.fillPress }
            background = PressGesture.plate(t.drawable)
            look { t.recolour() }
            isClickable = true
            setOnClickListener { openSource() }
        }, at(12, 324, 364, 182))
        addView(parked, MATCH_WINDOW())
        addView(moving, MATCH_WINDOW())
        addView(empty, MATCH_WINDOW())

        // parked: the cover at the reading edge, the transport always left to right
        val textX = if (ar) 26 else 122
        parked.addView(cover84, at(if (ar) 278 else 26, 338, 84, 84))
        parked.addView(title84, Type.widgetTitle.box(textX, 342, 240))
        parked.addView(sub84, Type.widgetSub.box(textX, 378, 240))
        parked.addView(progress84, at(30, 434, 328, 4))
        parked.addView(prev, at(52, 442, 76, 64))
        parked.addView(play84, at(156, 442, 76, 64))
        parked.addView(next, at(260, 442, 76, 64))

        // moving: pause and next at the mini's own rects, in both languages
        val movingX = if (ar) 26 else 110
        moving.addView(cover72, at(if (ar) 290 else 26, 336, 72, 72))
        moving.addView(title72, Type.widgetTitle.box(movingX, 336, 252))
        moving.addView(sub72, Type.miniSub.box(movingX, 370, 252))
        moving.addView(progress72, at(30, 416, 328, 4))
        moving.addView(play72, at(12, 426, 64, 80))
        moving.addView(next72, at(76, 426, 68, 80))
        moving.addView(source, Type.miniSub.box(150, 451, 212))

        // nothing playing: the plate, two lines, three sources that start when tapped (section 12)
        val plateX = if (ar) 304 else 28
        empty.addView(ctx.plateView(Tokens2.R_inner) { Palette.fill }, at(plateX, 338, 56, 56))
        empty.addView(ctx.iconView(R.drawable.ic_music, 30) { Palette.ink2 }, at(plateX + 13, 351, 30, 30))
        val lineX = if (ar) 28 else 96
        empty.addView(ctx.label(Type.widgetTitle, { Palette.ink }).apply { setText(R.string.media_nothing) }, Type.widgetTitle.box(lineX, 335, 264))
        empty.addView(ctx.label(Type.widgetSub, { Palette.ink2 }).apply { setText(R.string.home_choose_source) }, Type.widgetSub.box(lineX, 369, 264))
        val w = 110
        val xs = if (ar) intArrayOf(256, 139, 22) else intArrayOf(22, 139, 256)
        empty.addView(sourceButton(ctx, R.drawable.ic_iphone, R.string.home_src_naghm_short) { startSource(true) }, at(xs[0], 432, w, 64))
        empty.addView(sourceButton(ctx, R.drawable.ic_bluetooth, R.string.src_bt) { startSource(true) }, at(xs[1], 432, w, 64))
        empty.addView(sourceButton(ctx, R.drawable.ic_usb, R.string.home_src_usb) { startSource(false) }, at(xs[2], 432, w, 64))

        progress84.color = { Palette.ink }
        progress72.color = { Palette.ink }
        play84.onTap = { togglePlay() }
        play72.onTap = { togglePlay() }
        look {
            if (washDark != Palette.dark) {
                washDark = Palette.dark
                val w = prepared
                if (w != null && w.night == Palette.dark && preparedFor === np?.art && w.widget != null) {
                    washOld.setImageBitmap(w.widget)
                    washNew.setImageBitmap(null)
                    washNew.visibility = INVISIBLE
                    washKey = np?.art
                } else {
                    washKey = NONE
                    if (np != null) wantWash(np?.art, false)
                }
                prepared = null
                preparedFor = null
                if (shownArt == null) setCovers(null, cut = true) // (a song with no cover: its plate is the theme's)
            }
        }
        bind(null)
    }

    override fun hasOverlappingRendering() = false

    private fun group(c: Context) = object : FrameLayout(c) {
        override fun hasOverlappingRendering() = false
    }

    private fun washView(c: Context) = object : ImageView(c) {
        override fun hasOverlappingRendering() = false
    }.apply { scaleType = ImageView.ScaleType.FIT_XY }

    private fun transport(c: Context, res: Int, px: Int, w: Int, h: Int, desc: Int, dy: Int, onClick: () -> Unit) =
        IconButton(c, res, px, w, h, centerDy = dy, token = { Palette.ink }).apply {
            contentDescription = c.getString(desc)
            setOnClickListener { onClick() }
        }

    private fun sourceButton(c: Context, icon: Int, name: Int, onClick: () -> Unit): View = FrameLayout(c).apply {
        val t = Tint(c, Plates.res(Tokens2.R_inner)) { Palette.fill }
        background = t.drawable
        look { t.recolour() }
        val g = PressGesture(this, PressGesture.CONTROL)
        setOnTouchListener { _, e -> g.onTouch(e); false }
        isClickable = true
        setOnClickListener { onClick() }
        val row = android.widget.LinearLayout(c).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        row.addView(c.iconView(icon, 24) { Palette.ink }, android.widget.LinearLayout.LayoutParams(24, 24))
        val l = c.label(Type.homeChip, { Palette.ink }).apply { setText(name); gravity = Gravity.CENTER; includeFontPadding = false }
        row.addView(l, android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 32).apply { marginStart = 6 })
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    // ------------------------------------------------------------------------------------------ the panel's
    /** Home is on top: what plays, and one update of the line a second. Off when it is not: a hidden view asks for no frame. */
    fun listen(on: Boolean) {
        if (on == listening) return
        listening = on
        if (on) {
            MediaMonitor.observe(listener)
        } else {
            MediaMonitor.unobserve(listener)
            removeCallbacks(tick)
        }
    }

    fun setMoving(on: Boolean, animate: Boolean) {
        if (on == movingNow) return
        movingNow = on
        syncFace(animate)
    }

    private fun syncFace(animate: Boolean) {
        faces.show(if (np == null) empty else if (movingNow) moving else parked, animate)
    }

    /** Off the main thread, before a theme change: the song's wash for that theme. */
    fun prepareTheme(night: Boolean) {
        val art = np?.art
        if (np == null) return
        prepared = try { Backdrop.wash(context.applicationContext, art, night) } catch (_: Throwable) { null }
        preparedFor = art
    }

    // ------------------------------------------------------------------------------------------ what plays
    private fun skip(dir: Int, key: MediaMonitor.Key) {
        lastDir = dir
        lastDirAt = SystemClock.uptimeMillis()
        MediaMonitor.send(context, key)
    }

    private fun togglePlay() {
        if (np == null) { openSource(); return }
        MediaMonitor.send(context, MediaMonitor.Key.PLAY_PAUSE)
    }

    /** The player of its own for its own playback, Bluetooth music and when nothing plays; otherwise the app that plays. */
    private fun openSource() {
        val pkg = np?.sourcePkg
        if (pkg == null || pkg == MediaMonitor.SOURCE_BT || pkg == context.packageName || !AppRepo.isInstalled(context, pkg)) {
            Screens.music(context)
            return
        }
        if (!AppRepo.launch(context, pkg)) Screens.music(context)
    }

    private fun startSource(bt: Boolean) {
        Screens.musicOn(context, bt)
    }

    private fun bind(n: NowPlaying?) {
        if (bound && n == np) return // (the same song, state and cover published again)
        bound = true
        val wasNull = np == null
        np = n
        val song = n?.let { "${it.sourcePkg}|${it.title}" }
        val newSong = song != shownSong
        val animate = newSong && shownSong != null && isShown && !movingNow
        shownSong = song
        if (n == null) {
            setArt(null, false)
            washOld.visibility = INVISIBLE
            washNew.visibility = INVISIBLE
            washKey = NONE
            card.visibility = VISIBLE
            play84.setPlaying(false)
            play72.setPlaying(false)
        } else {
            setTexts(n.title, n.artist, sourceLabel(n.sourcePkg))
            setArt(n.art, newSong)
            wantWash(n.art, isShown)
            play84.setPlaying(n.playing)
            play72.setPlaying(n.playing)
        }
        if (wasNull != (n == null)) syncFace(isShown)
        if (animate) {
            val dir = if (SystemClock.uptimeMillis() - lastDirAt < SKIP_MS) lastDir else 0
            lastDirAt = 0L
            // (left to right in every language, like the transport: "next" comes in from the right)
            if (dir != 0) { title84.enter(dx = 24f * dir); sub84.enter(dx = 24f * dir, delay = 40L) }
            else { title84.enter(dy = 8f); sub84.enter(dy = 8f, delay = 50L) }
        }
        removeCallbacks(tick)
        tick.run()
    }

    private fun setTexts(title: String, artist: String, src: String) {
        title84.text = title
        title72.text = title
        val bidi = android.text.BidiFormatter.getInstance()
        // the UI language orders "artist · source": an English artist must not turn an Arabic line around
        sub84.text = when {
            artist.isNotBlank() && src.isNotBlank() -> "${bidi.unicodeWrap(artist)} · ${bidi.unicodeWrap(src)}"
            artist.isNotBlank() -> artist
            else -> src
        }
        sub72.text = artist
        source.text = src
    }

    private fun setFraction(f: Float) {
        if (f == fraction) return
        fraction = f
        progress84.progress = f
        progress72.progress = f
    }

    /** "من نغم" for the owner's phone player, "بلوتوث", an app's name; nothing for the player of its own: the card itself is that. */
    private fun sourceLabel(pkg: String?): String = when (pkg) {
        null, context.packageName -> ""
        Known.MUSIC -> context.getString(R.string.dock_music)
        MediaMonitor.SOURCE_BT, Known.BT_MUSIC -> context.getString(if (BtMusic.viaNaghm) R.string.home_src_naghm else R.string.src_bt)
        Known.RADIO -> context.getString(R.string.dock_radio)
        else -> sources.getOrPut(pkg) {
            try {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Throwable) { "" }
        }
    }

    /** [newSong] without a cover yet: the old one stays, dimmed, for a moment (its cover usually comes right after). */
    private fun setArt(b: Bitmap?, newSong: Boolean) {
        if (b == null && (holdingArt || (newSong && shownArt != null && isShown))) {
            if (!holdingArt) {
                holdingArt = true
                cover84.alpha = HOLD_ALPHA
                cover72.alpha = HOLD_ALPHA
                postDelayed(dropArt, ART_WAIT_MS)
            }
            return
        }
        if (holdingArt) { holdingArt = false; removeCallbacks(dropArt) }
        cover84.alpha = 1f
        cover72.alpha = 1f
        showArt(b)
    }

    private fun showArt(b: Bitmap?) {
        if (b === shownArt && cover84.drawable != null) return
        shownArt = b
        setCovers(b, cut = !isShown || movingNow)
    }

    private fun setCovers(b: Bitmap?, cut: Boolean) {
        if (b == null) {
            flying = null
            cover84.setImageDrawable(Backdrop.noArt(context, 84, Tokens2.R_coverWidget))
            cover72.setImageDrawable(Backdrop.noArt(context, 72, COVER_72_R))
            return
        }
        Backdrop.roundAsync(context, b, 84, Tokens2.R_coverWidget) { r -> if (shownArt === b) place(cover84, r, cut) }
        Backdrop.roundAsync(context, b, 72, COVER_72_R) { r -> if (shownArt === b) place(cover72, r, cut) }
        Backdrop.roundAsync(context, b, com.abdllh.aura.chrome.Flyer.SIZE, Tokens2.R_coverBig) { r -> if (shownArt === b) flying = r }
    }

    /** The cover's flight: a square copy at the big cover's size, and where it flies from. Parked, with a cover. */
    private var flying: Bitmap? = null
    val flightBitmap: Bitmap? get() = if (shownArt != null && !movingNow) flying else null
    val flightView: View? get() = if (shownArt != null && !movingNow && np != null) cover84 else null

    /** Crossfade 240, from 98 %, parked. A cut while moving, under the fade. */
    private fun place(v: ImageView, r: Bitmap, cut: Boolean) {
        val next: Drawable = BitmapDrawable(resources, r)
        val old = v.drawable.let { (it as? TransitionDrawable)?.getDrawable(1) ?: it }
        if (cut || old == null) {
            v.setImageDrawable(next)
            return
        }
        val fade = TransitionDrawable(arrayOf(old, next)).apply { isCrossFadeEnabled = true; startTransition(COVER_MS) }
        v.setImageDrawable(fade)
        v.scaleX = .98f
        v.scaleY = .98f
        v.animate().scaleX(1f).scaleY(1f).setDuration(COVER_MS.toLong()).setInterpolator(Motion.MOVE).setStartDelay(0L).start()
        v.postDelayed({ if (v.drawable === fade) v.setImageDrawable(next) }, COVER_MS + 40L)
    }

    /** The wash of this song, made once on [Backdrop]'s worker: 400 ms over the old one, parked; a cut when moving. */
    private fun wantWash(art: Bitmap?, animate: Boolean) {
        val key: Any? = art
        if (key === washKey) return
        washKey = key
        Backdrop.washAsync(context, art, Palette.dark) { w ->
            if (washKey === key && w.widget != null && w.night == Palette.dark && np != null) showWash(w.widget, animate && !movingNow && isShown)
        }
    }

    private fun showWash(b: Bitmap, animate: Boolean) {
        card.visibility = INVISIBLE
        if (!animate || washOld.visibility != VISIBLE) {
            washFade.stop()
            washOld.setImageBitmap(b)
            washOld.visibility = VISIBLE
            washNew.setImageBitmap(null)
            washNew.visibility = INVISIBLE
            return
        }
        washNew.setImageBitmap(b)
        washNew.visibility = VISIBLE
        washPart.snap(0f)
        washPart.to(1f, 0L, WASH_MS, Motion.MOVE)
        postDelayed({
            if (washPart.value >= 1f) {
                washOld.setImageBitmap(b)
                washNew.setImageBitmap(null)
                washNew.visibility = INVISIBLE
            }
        }, WASH_MS + 40L)
    }

    /** Debug builds: the mockups' song, whatever plays. */
    private var debugHeld = false

    internal fun debugSong(n: NowPlaying?, src: String?, f: Float) {
        debugHeld = true
        bound = true
        val wasNull = np == null
        np = n
        shownSong = n?.title
        if (n == null) {
            setArt(null, false)
            washOld.visibility = INVISIBLE
            washNew.visibility = INVISIBLE
            washKey = NONE
            card.visibility = VISIBLE
        } else {
            setTexts(n.title, n.artist, src ?: sourceLabel(n.sourcePkg))
            shownArt = null
            cover84.setImageDrawable(null)
            cover72.setImageDrawable(null)
            showArt(n.art)
            if (n.art == null) setCovers(null, true)
            wantWash(n.art, false)
            play84.setPlaying(n.playing, false)
            play72.setPlaying(n.playing, false)
        }
        if (wasNull != (n == null)) syncFace(false)
        setFraction(f)
    }

    override fun onDetachedFromWindow() {
        if (holdingArt) { removeCallbacks(dropArt); dropArt.run() }
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    /** Pause and play as one morphing glyph on a plate or none: [hit] the whole view, the glyph and plate in its middle; no text, so its scale costs no layer. */
    private class PlayHit(ctx: Context, glyph: Int, dy: Int, plate: Boolean) : FrameLayout(ctx) {
        private val glyphView = PlayPauseView(ctx).apply {
            token = { Palette.ink }
            glyphPx = glyph
            centerDy = dy
        }
        private val gesture = PressGesture(this, PressGesture.ICON)
        var onTap: () -> Unit = {}

        init {
            isClickable = true
            contentDescription = ctx.getString(R.string.media_play)
            if (plate) addView(ctx.plateView(Tokens2.R_inner) { Palette.fill }, LayoutParams(56, 56, Gravity.CENTER))
            addView(glyphView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            setOnClickListener { onTap() }
        }

        fun setPlaying(p: Boolean, animate: Boolean = true) = glyphView.setPlaying(p, animate)

        override fun hasOverlappingRendering() = false

        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            gesture.onTouch(e)
            return super.dispatchTouchEvent(e)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean = gesture.tolerant(e) { super.onTouchEvent(it) }
    }

    private companion object {
        val NONE = Any()
        const val WASH_W = 424
        const val WASH_H = 242
        const val WASH_MS = 400L
        const val COVER_MS = 240
        const val COVER_72_R = 14
        const val HOLD_ALPHA = .45f
        const val SKIP_MS = 3000L
        const val ART_WAIT_MS = 1500L
    }
}

/** A group laid out as the window, so a child's rect is the layout table's. */
@Suppress("FunctionName")
internal fun MATCH_WINDOW() = at(0, 0, 1024, 600)

/**
 * Faces of a part of Home, all built, laid out and kept, of which one shows: a change is a crossfade in alpha, on one frame callback, that turns back from where it is; 180 `enter` in, 120 `exit` out. What
 * has gone is not drawn and takes no touch.
 */
internal class HomeFaces(views: List<View>, first: View?) {
    private var shown = first
    private val tweens = Tweens(onRest = { settle() })
    private val parts = views.associateWith { v ->
        val start = if (v === first) 1f else 0f
        v.visibility = if (v === first) View.VISIBLE else View.INVISIBLE
        v.alpha = start
        tweens.part(start) { a -> v.alpha = a }
    }

    fun show(v: View?, animate: Boolean) {
        if (v === shown) return
        shown = v
        if (!animate) {
            tweens.stop()
            for ((k, p) in parts) p.snap(if (k === v) 1f else 0f)
            settle()
            return
        }
        for ((k, p) in parts) {
            if (k === v) {
                k.visibility = View.VISIBLE
                p.to(1f, 0L, IN_MS, Motion.ENTER)
            } else if (p.value > 0f) p.to(0f, 0L, OUT_MS, Motion.EXIT)
        }
    }

    val current: View? get() = shown

    private fun settle() {
        for ((k, _) in parts) k.visibility = if (k === shown) View.VISIBLE else View.INVISIBLE
    }

    private companion object {
        const val IN_MS = 180L
        const val OUT_MS = 120L
    }
}
