package com.abdllh.aura.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.TransitionDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.abdllh.aura.R
import com.abdllh.aura.media.MediaMonitor
import com.abdllh.aura.media.NowPlaying
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Elev
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.PlayPauseView
import com.abdllh.aura.ui.Radius
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.Space
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.enter
import com.abdllh.aura.ui.fadeTo
import com.abdllh.aura.ui.flp
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lift
import com.abdllh.aura.ui.lp
import com.abdllh.aura.ui.pressScale
import com.abdllh.aura.ui.roundedClip
import com.abdllh.aura.util.dp

/**
 * Floating "now playing" bar along the bottom of the map. Tapping it opens the playing app (or the stock Music app).
 * A thin line along its bottom shows how far the song is, when its source tells.
 * A new song's names come in with a little movement: from the side of the button pressed (next, previous), else
 * rising. Bluetooth sends a song in parts (the title, then the artist, then the cover), so the movement follows the
 * title only, and the old cover waits dimmed for the new one instead of dropping to the note in between.
 */
class MediaCard(ctx: Context, private val host: HomeHost) : LinearLayout(ctx) {
    private val art = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val artFallback = ImageView(ctx).apply { setImageResource(R.drawable.ic_music); setColorFilter(Palette.text3); scaleType = ImageView.ScaleType.FIT_CENTER }
    private val title: AText = ctx.label(Type.LABEL)
    // the UI language orders "artist · source" (an English artist must not turn an Arabic line around)
    private val artist: AText = ctx.label(Type.FOOTNOTE, Palette.text2).apply { textDirection = TEXT_DIRECTION_LOCALE }
    private val playIcon = PlayPauseView(ctx).apply { color = Palette.onColor(Palette.accent) }
    private val prev: View
    private val next: View
    private var np: NowPlaying? = null
    private var bound = false
    private var shownSong: String? = null
    private var shownArt: Bitmap? = null
    private var holdingArt = false // a new song's cover has not come yet: the old one waits, dimmed
    private var lastDir = 0          // +1 next, -1 previous: the button last pressed
    private var lastDirAt = 0L
    private val sources = HashMap<String, String>() // app names (each one a package manager call)
    private var fraction = -1f // how far the song is (-1: unknown, no line)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lineRect = RectF()

    private val listener: (NowPlaying?) -> Unit = { bind(it) }
    private val tick = object : Runnable {
        override fun run() {
            val p = MediaMonitor.progress()
            val f = if (p != null && p.second > 0) (p.first.toFloat() / p.second).coerceIn(0f, 1f) else -1f
            if (f != fraction) { fraction = f; invalidate() }
            if (np?.playing == true) postDelayed(this, 1000L)
        }
    }
    private val dropArt = Runnable { holdingArt = false; showArt(null) }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setWillNotDraw(false)
        setPaddingRelative(PAD.dp, PAD.dp, Space.S.dp, PAD.dp)
        background = Shapes.glassPressable(Radius.XL)
        // the darker glass and its edge set it off the dark map; a shadow only on the light theme
        lift(Elev.FLOAT)
        isClickable = true
        pressScale(Motion.PRESS_SURFACE)
        setOnClickListener { openSource() }

        val artBox = FrameLayout(ctx).apply {
            background = Shapes.rect(Palette.card2, Radius.XL - PAD)
            roundedClip(Radius.XL - PAD)
        }
        artBox.addView(artFallback, flp(30.dp, 30.dp, Gravity.CENTER))
        artBox.addView(art, flp(MATCH, MATCH))
        addView(artBox, lp(68.dp, 68.dp))

        val txt = LinearLayout(ctx).apply { orientation = VERTICAL }
        txt.addView(title, lp(MATCH, WRAP))
        txt.addView(artist, lp(MATCH, WRAP).apply { topMargin = Space.XXS.dp })
        addView(txt, lp(0, WRAP, 1f).apply { marginStart = Space.M.dp; marginEnd = Space.S.dp })

        // transport controls keep their left-to-right order in every language (like a physical player)
        val row = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = LAYOUT_DIRECTION_LTR }
        prev = transport(ctx, R.drawable.ic_skip_back, R.string.media_prev) { skip(-1, MediaMonitor.Key.PREV) }
        row.addView(prev, lp(54.dp, 54.dp))
        // the accent, not white: a white disc was the brightest thing on the night screen
        val play = FrameLayout(ctx).apply {
            background = Shapes.pressable(Shapes.oval(Palette.accent), Shapes.oval(Palette.accentPressed()))
            isClickable = true
            contentDescription = ctx.getString(R.string.media_play)
            pressScale(Motion.PRESS_ICON)
            setOnClickListener { togglePlay() }
            addView(playIcon, flp(28.dp, 28.dp, Gravity.CENTER))
        }
        row.addView(play, lp(60.dp, 60.dp).apply { marginStart = Space.XS.dp; marginEnd = Space.XS.dp })
        next = transport(ctx, R.drawable.ic_skip_fwd, R.string.media_next) { skip(+1, MediaMonitor.Key.NEXT) }
        row.addView(next, lp(54.dp, 54.dp))
        addView(row, lp(WRAP, WRAP))
        bind(null)
    }

    private fun transport(ctx: Context, res: Int, desc: Int, onClick: () -> Unit): FrameLayout = FrameLayout(ctx).apply {
        background = Shapes.ghostOval()
        isClickable = true
        contentDescription = ctx.getString(desc)
        pressScale(Motion.PRESS_ICON)
        setOnClickListener { onClick() }
        addView(ImageView(ctx).apply { setImageResource(res); setColorFilter(Palette.text); scaleType = ImageView.ScaleType.FIT_CENTER }, flp(27.dp, 27.dp, Gravity.CENTER))
    }

    private fun skip(dir: Int, key: MediaMonitor.Key) {
        lastDir = dir
        lastDirAt = SystemClock.uptimeMillis()
        MediaMonitor.send(context, key)
    }

    private fun togglePlay() {
        if (np == null) { openSource(); return }
        MediaMonitor.send(context, MediaMonitor.Key.PLAY_PAUSE)
    }

    /** Aura Music for its own playback, Bluetooth music and when nothing plays; otherwise the app that plays. */
    private fun openSource() {
        val pkg = np?.sourcePkg
        if (pkg == null || pkg == MediaMonitor.SOURCE_BT || pkg == context.packageName || !AppRepo.isInstalled(context, pkg)) {
            com.abdllh.aura.music.MusicActivity.open(context)
            return
        }
        if (!AppRepo.launch(context, pkg)) com.abdllh.aura.music.MusicActivity.open(context)
    }

    private fun bind(n: NowPlaying?) {
        if (bound && n == np) return // (the same song, state and cover published again)
        val first = !bound
        bound = true
        np = n
        val song = n?.let { "${it.sourcePkg}|${it.title}" }
        val newSong = song != shownSong
        val animate = newSong && shownSong != null && isShown
        shownSong = song
        if (n == null) {
            title.setText(R.string.media_nothing)
            artist.setText(R.string.media_tap_music)
            setArt(null, false)
            playIcon.setPlaying(false)
        } else {
            title.text = n.title
            val src = sourceLabel(n.sourcePkg)
            val bidi = android.text.BidiFormatter.getInstance()
            artist.text = when {
                n.artist.isNotBlank() && src.isNotBlank() -> "${bidi.unicodeWrap(n.artist)}  ·  ${bidi.unicodeWrap(src)}"
                n.artist.isNotBlank() -> n.artist
                else -> src
            }
            setArt(n.art, newSong)
            playIcon.setPlaying(n.playing)
        }
        if (animate) {
            val dir = if (SystemClock.uptimeMillis() - lastDirAt < SKIP_MS) lastDir else 0
            lastDirAt = 0L
            // (left to right in every language, like the transport buttons: "next" comes in from the right)
            if (dir != 0) { title.enter(dx = 24f * dir); artist.enter(dx = 24f * dir, delay = 40L) }
            else { title.enter(dy = 8f); artist.enter(dy = 8f, delay = 50L) }
        }
        // nothing to skip while nothing plays: prev and next say so
        val idle = n == null
        if (prev.isEnabled == idle || first) {
            prev.isEnabled = !idle
            next.isEnabled = !idle
            val a = if (idle) 0.35f else 1f
            if (first || !isShown) { prev.alpha = a; next.alpha = a } else { prev.fadeTo(a); next.fadeTo(a) }
        }
        removeCallbacks(tick)
        tick.run()
    }

    /** [newSong] without a cover yet: the old one stays, dimmed, for a moment (its cover usually comes right after). */
    private fun setArt(b: Bitmap?, newSong: Boolean) {
        if (b == null && (holdingArt || (newSong && shownArt != null && isShown))) {
            if (!holdingArt) {
                holdingArt = true
                art.fadeTo(0.35f)
                postDelayed(dropArt, ART_WAIT_MS)
            }
            return
        }
        if (holdingArt) { holdingArt = false; removeCallbacks(dropArt) }
        showArt(b)
    }

    /** The new cover fades in over the old one (or over the note, when there was none); none: the note shows. */
    private fun showArt(b: Bitmap?) {
        if (b === shownArt) {
            if (art.alpha < 1f) art.fadeTo(1f, Motion.MEDIUM) // (the same cover after a wait)
            return
        }
        shownArt = b
        val shown = art.drawable
        val old = (shown as? TransitionDrawable)?.getDrawable(1) ?: shown
        val next = b?.let { BitmapDrawable(resources, it) }
        when {
            next == null && old != null && isShown -> {
                // the note comes back under the cover going out
                artFallback.visibility = VISIBLE
                art.fadeTo(0f) { if (shownArt == null) { art.setImageDrawable(null); art.alpha = 1f } }
                return
            }
            old != null && next != null && isShown -> {
                val fade = TransitionDrawable(arrayOf(old, next)).apply { isCrossFadeEnabled = true; startTransition(Motion.MEDIUM.toInt()) }
                art.setImageDrawable(fade)
                // then the cover alone (the fade's layers share one size: a cover that is not square would be stretched)
                art.postDelayed({ if (art.drawable === fade) art.setImageDrawable(next) }, Motion.MEDIUM + 40)
                if (art.alpha < 1f) art.fadeTo(1f, Motion.MEDIUM)
            }
            next != null && isShown -> {
                art.setImageDrawable(next)
                art.alpha = 0f
                art.fadeTo(1f, Motion.MEDIUM)
            }
            else -> {
                art.animate().cancel()
                art.setImageDrawable(next)
                art.alpha = 1f
            }
        }
        artFallback.visibility = if (b == null) VISIBLE else GONE
    }

    /** The song's progress: a thin line inside the card's bottom edge. */
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (fraction < 0f) return
        val h = 3f.dp
        val inset = 22f.dp
        val y = height - 4f.dp
        val w = width - 2 * inset
        lineRect.set(inset, y - h, inset + w, y)
        linePaint.color = Palette.track
        c.drawRoundRect(lineRect, h / 2, h / 2, linePaint)
        if (fraction > 0f) {
            // left to right in every language, like the music screen's bar and the times under it
            lineRect.set(inset, y - h, inset + (w * fraction).coerceAtLeast(h), y)
            linePaint.color = Palette.accent
            c.drawRoundRect(lineRect, h / 2, h / 2, linePaint)
        }
    }

    private fun sourceLabel(pkg: String?): String = when (pkg) {
        null, context.packageName -> "" // Aura Music: the card itself is the music player
        Known.MUSIC -> context.getString(R.string.dock_music)
        MediaMonitor.SOURCE_BT, Known.BT_MUSIC -> context.getString(R.string.src_bt)
        Known.RADIO -> context.getString(R.string.dock_radio)
        else -> sources.getOrPut(pkg) {
            try {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Throwable) { "" }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        MediaMonitor.observe(listener)
    }

    override fun onDetachedFromWindow() {
        MediaMonitor.unobserve(listener)
        removeCallbacks(tick)
        if (holdingArt) { removeCallbacks(dropArt); dropArt.run() }
        super.onDetachedFromWindow()
    }

    private companion object {
        const val PAD = 10           // dp, the card's padding (the cover's corner is the card's less this)
        const val SKIP_MS = 3000L    // a new song this soon after next / previous came from that button
        const val ART_WAIT_MS = 1500L
    }
}
