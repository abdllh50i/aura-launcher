package com.abdllh.aura.home

import android.content.Context
import android.graphics.Bitmap
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
import com.abdllh.aura.ui.Tint
import com.abdllh.aura.ui.Tokens2
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.at
import com.abdllh.aura.ui.box
import com.abdllh.aura.ui.enter
import com.abdllh.aura.ui.iconView
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.look
import com.abdllh.aura.ui.plateView

/**
 * Home's music widget, 364 x 184 at (16, 324) (CHANGES.md section 3): the song's wash, a large cover at the far end, the names over pause and next, and nothing else: one layout, parked and moving, so pause and
 * next never move under the finger. With no song, the card and the three sources. The two faces are both built and laid out, and cross-fade in alpha. The logic is `MediaCard`'s: what plays comes from
 * [MediaMonitor], Bluetooth sends a song in parts, so the old cover waits dimmed for the new one, and a new song's names come in from the side of the button pressed. Coordinates are the window's: the numbers of the
 * layout tables.
 */
internal class HomeWidget(ctx: Context, private val host: HomeHost) : FrameLayout(ctx) {
    private val ar = Fonts.arabicUi

    /** The empty card, and what stands in until a song's wash is made. */
    private val card = SurfaceCard(ctx, Surface.CARD)
    private val washOld = washView(ctx)
    private val washNew = washView(ctx)
    private val washFade = Tweens()
    private val washPart = washFade.part(0f) { v -> washNew.alpha = v }

    private val playing = group(ctx)
    private val empty = group(ctx)
    private val faces = HomeFaces(listOf(playing, empty), empty)

    private val cover = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_XY }
    private val title: AText = ctx.label(Type.widgetTitle, { Palette.ink })
    private val sub: AText = ctx.label(Type.miniSub, { Palette.ink2 }, moving = true)
    private val play = PlayHit(ctx)
    private val next = IconButton(
        ctx, R.drawable.ic_next, 36, 68, 80, IconButton.Kind.TONAL, PressGesture.ICON, plateToken = { Palette.fill }, token = { Palette.ink }
    ).apply {
        contentDescription = ctx.getString(R.string.media_next)
        setOnClickListener { skip() }
    }

    private var np: NowPlaying? = null
    private var bound = false
    private var shownSong: String? = null
    private var shownArt: Bitmap? = null
    private var holdingArt = false
    private var nextAt = 0L
    private var listening = false
    private var washKey: Any? = NONE
    private var washDark = Palette.dark
    private var prepared: Backdrop.Wash? = null
    private var preparedFor: Bitmap? = null
    private val listener: (NowPlaying?) -> Unit = { n -> if (!debugHeld) take(n) }
    /** Bluetooth went in the middle of a song: it stays, paused, and says so (section 12). */
    private var btLost = false

    /** The moving look: a new song and its cover come as a cut, and the cover does not fly. Nothing moves. */
    var movingNow = false

    private val dropArt = Runnable { holdingArt = false; showArt(null) }

    init {
        addView(card, Surface.CARD.at(X, Y, W, H))
        addView(washOld, at(X - Backdrop.CARD_MARGIN, Y - Backdrop.CARD_MARGIN, WASH_W, WASH_H))
        addView(washNew, at(X - Backdrop.CARD_MARGIN, Y - Backdrop.CARD_MARGIN, WASH_W, WASH_H))
        washOld.visibility = INVISIBLE
        washNew.visibility = INVISIBLE
        // a tap on the card: the player. The press shows as a plate at once, so it reads in sun, and nothing here scales
        addView(View(ctx).apply {
            val t = Tint(ctx, R.drawable.plate_26) { Palette.fillPress }
            background = PressGesture.plate(t.drawable)
            look { t.recolour() }
            isClickable = true
            setOnClickListener { openSource() }
        }, at(X, Y, W, H))
        addView(playing, MATCH_WINDOW())
        addView(empty, MATCH_WINDOW())

        // the cover at the far end, the words and under them pause and next, on the driver's side in both languages
        playing.addView(cover, at(212, 340, COVER, COVER))
        playing.addView(title, Type.widgetTitle.box(32, 347, 164))
        playing.addView(sub, Type.miniSub.box(32, 384, 164))
        playing.addView(play, at(if (ar) 68 else 28, 424, 64, 80))
        playing.addView(next, at(if (ar) 132 else 92, 424, 68, 80))

        // nothing playing: the plate, two lines, three sources that start when tapped (section 12)
        val plateX = if (ar) 308 else 32
        empty.addView(ctx.plateView(Tokens2.R_inner) { Palette.fill }, at(plateX, 341, 56, 56))
        empty.addView(ctx.iconView(R.drawable.ic_music, 30) { Palette.ink2 }, at(plateX + 13, 354, 30, 30))
        val lineX = if (ar) 32 else 100
        empty.addView(ctx.label(Type.widgetTitle, { Palette.ink }).apply { setText(R.string.media_nothing) }, Type.widgetTitle.box(lineX, 338, 264))
        empty.addView(ctx.label(Type.widgetSub, { Palette.ink2 }).apply { setText(R.string.home_choose_source) }, Type.widgetSub.box(lineX, 372, 264))
        val w = 110
        val xs = if (ar) intArrayOf(260, 143, 26) else intArrayOf(26, 143, 260)
        empty.addView(sourceButton(ctx, R.drawable.ic_iphone, R.string.home_src_naghm_short) { startSource(true) }, at(xs[0], 434, w, 64))
        empty.addView(sourceButton(ctx, R.drawable.ic_bluetooth, R.string.home_src_bt_short) { startSource(true) }, at(xs[1], 434, w, 64))
        empty.addView(sourceButton(ctx, R.drawable.ic_usb, R.string.home_src_usb) { startSource(false) }, at(xs[2], 434, w, 64))

        play.onTap = { togglePlay() }
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
                if (shownArt == null) setCover(null, cut = true) // (a song with no cover: its plate is the theme's)
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
    /** Home is on top: what plays. Off when it is not: a hidden view asks for no frame. */
    fun listen(on: Boolean) {
        if (on == listening) return
        listening = on
        if (on) MediaMonitor.observe(listener) else MediaMonitor.unobserve(listener)
    }

    /** Off the main thread, before a theme change: the song's wash for that theme. */
    fun prepareTheme(night: Boolean) {
        val art = np?.art
        if (np == null) return
        prepared = try { Backdrop.wash(context.applicationContext, art, night) } catch (_: Throwable) { null }
        preparedFor = art
    }

    // ------------------------------------------------------------------------------------------ what plays
    /** What [MediaMonitor] says, but a Bluetooth song whose link has gone does not leave the card: it clears itself when the link is back or something else plays, not for a paused song of another source. */
    private fun take(n: NowPlaying?) {
        val was = np
        if (n?.playing != true && was != null && was.sourcePkg == MediaMonitor.SOURCE_BT && !BtMusic.connected) {
            if (!btLost) {
                btLost = true
                bind(was.copy(playing = false))
                sub.text = context.getString(R.string.home_bt_lost)
            }
            return
        }
        if (btLost) {
            btLost = false
            bound = false // (the same song again: its own words)
        }
        bind(n)
    }

    private fun skip() {
        if (btLost) { openSource(); return }
        nextAt = SystemClock.uptimeMillis()
        MediaMonitor.send(context, MediaMonitor.Key.NEXT)
    }

    private fun togglePlay() {
        if (np == null || btLost) { openSource(); return }
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
            play.setPlaying(false)
        } else {
            title.text = n.title
            sub.text = n.artist
            setArt(n.art, newSong)
            wantWash(n.art, isShown)
            play.setPlaying(n.playing)
        }
        if (wasNull != (n == null)) syncFace(isShown)
        if (animate) {
            val fromNext = SystemClock.uptimeMillis() - nextAt < SKIP_MS
            nextAt = 0L
            // (left to right in every language, like the transport: "next" comes in from the right)
            if (fromNext) { title.enter(dx = 24f); sub.enter(dx = 24f, delay = 40L) }
            else { title.enter(dy = 8f); sub.enter(dy = 8f, delay = 50L) }
        }
    }

    private fun syncFace(animate: Boolean) {
        faces.show(if (np == null) empty else playing, animate)
    }

    /** [newSong] without a cover yet: the old one stays, dimmed, for a moment (its cover usually comes right after). */
    private fun setArt(b: Bitmap?, newSong: Boolean) {
        if (b == null && (holdingArt || (newSong && shownArt != null && isShown))) {
            if (!holdingArt) {
                holdingArt = true
                cover.alpha = HOLD_ALPHA
                postDelayed(dropArt, ART_WAIT_MS)
            }
            return
        }
        if (holdingArt) { holdingArt = false; removeCallbacks(dropArt) }
        cover.alpha = 1f
        showArt(b)
    }

    private fun showArt(b: Bitmap?) {
        if (b === shownArt && cover.drawable != null) return
        shownArt = b
        setCover(b, cut = !isShown || movingNow)
    }

    private fun setCover(b: Bitmap?, cut: Boolean) {
        if (b == null) {
            flying = null
            cover.setImageDrawable(Backdrop.noArt(context, COVER, COVER_R))
            return
        }
        Backdrop.roundAsync(context, b, COVER, COVER_R) { r -> if (shownArt === b) place(r, cut) }
        Backdrop.roundAsync(context, b, com.abdllh.aura.chrome.Flyer.SIZE, Tokens2.R_coverBig) { r -> if (shownArt === b) flying = r }
    }

    /** The cover's flight: a square copy at the big cover's size, and where it flies from. Parked, with a cover. */
    private var flying: Bitmap? = null
    val flightBitmap: Bitmap? get() = if (shownArt != null && !movingNow) flying else null
    val flightView: View? get() = if (shownArt != null && !movingNow && np != null) cover else null

    /** Crossfade 240, from 98 %, parked. A cut while moving, under the fade. */
    private fun place(r: Bitmap, cut: Boolean) {
        val v = cover
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

    internal fun debugSong(n: NowPlaying?) {
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
            title.text = n.title
            sub.text = n.artist
            shownArt = null
            cover.setImageDrawable(null)
            showArt(n.art)
            if (n.art == null) setCover(null, true)
            wantWash(n.art, false)
            play.setPlaying(n.playing, false)
        }
        if (wasNull != (n == null)) syncFace(false)
    }

    override fun onDetachedFromWindow() {
        if (holdingArt) { removeCallbacks(dropArt); dropArt.run() }
        super.onDetachedFromWindow()
    }

    /** Pause and play as one morphing glyph on its plate: the hit is the whole view, the glyph and plate in its middle; no text, so its scale costs no layer. */
    private class PlayHit(ctx: Context) : FrameLayout(ctx) {
        private val glyphView = PlayPauseView(ctx).apply {
            token = { Palette.ink }
            glyphPx = 36
        }
        private val gesture = PressGesture(this, PressGesture.ICON)
        var onTap: () -> Unit = {}

        init {
            isClickable = true
            contentDescription = ctx.getString(R.string.media_play)
            addView(ctx.plateView(Tokens2.R_inner) { Palette.fill }, LayoutParams(56, 56, Gravity.CENTER))
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

    companion object {
        /** The cover's radius, which its flight starts from. */
        const val COVER_R = Tokens2.R_coverBig
        private val NONE = Any()
        private const val X = 16
        private const val Y = 324
        private const val W = 364
        private const val H = 184
        private const val COVER = 152
        private const val WASH_W = 424
        private const val WASH_H = 244
        private const val WASH_MS = 400L
        private const val COVER_MS = 240
        private const val HOLD_ALPHA = .45f
        private const val SKIP_MS = 3000L
        private const val ART_WAIT_MS = 1500L
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
