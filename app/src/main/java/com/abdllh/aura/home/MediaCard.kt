package com.abdllh.aura.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.TransitionDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.abdllh.aura.R
import com.abdllh.aura.media.MediaMonitor
import com.abdllh.aura.media.NowPlaying
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.PlayPauseView
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.elevate
import com.abdllh.aura.ui.flp
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lp
import com.abdllh.aura.ui.pressScale
import com.abdllh.aura.ui.riseIn
import com.abdllh.aura.ui.roundedClip
import com.abdllh.aura.util.dp

/**
 * Floating "now playing" bar on the map. Tapping it opens the playing app (or the stock Music app). A thin line along
 * its bottom shows how far the song is, when its source tells; a new song's names come in with a little movement.
 */
class MediaCard(ctx: Context, private val host: HomeHost) : LinearLayout(ctx) {
    private val art = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val artFallback = ImageView(ctx).apply { setImageResource(R.drawable.ic_music); setColorFilter(Palette.text3); scaleType = ImageView.ScaleType.FIT_CENTER }
    private val title: AText = ctx.label(18f, Palette.text, Fonts.MEDIUM)
    // the UI language orders "artist · source" (an English artist must not turn an Arabic line around)
    private val artist: AText = ctx.label(14.5f, Palette.text2, Fonts.REGULAR).apply { textDirection = TEXT_DIRECTION_LOCALE }
    private val playIcon = PlayPauseView(ctx).apply { color = Palette.card }
    private var np: NowPlaying? = null
    private var shownSong: String? = null
    private var shownArt: android.graphics.Bitmap? = null
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

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setWillNotDraw(false)
        setPaddingRelative(12.dp, 12.dp, 12.dp, 12.dp)
        background = Shapes.glassPressable(22f)
        elevate(10f)
        isClickable = true
        pressScale(0.985f)
        setOnClickListener { openSource() }

        val artBox = FrameLayout(ctx).apply {
            background = Shapes.rect(Palette.card2, 14f)
            roundedClip(14f)
        }
        artBox.addView(artFallback, flp(30.dp, 30.dp, Gravity.CENTER))
        artBox.addView(art, flp(MATCH, MATCH))
        addView(artBox, lp(72.dp, 72.dp))

        val txt = LinearLayout(ctx).apply { orientation = VERTICAL }
        txt.addView(title, lp(MATCH, WRAP))
        txt.addView(artist, lp(MATCH, WRAP).apply { topMargin = 3.dp })
        addView(txt, lp(0, WRAP, 1f).apply { marginStart = 14.dp; marginEnd = 8.dp })

        // transport controls keep their left-to-right order in every language (like a physical player)
        val row = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = LAYOUT_DIRECTION_LTR }
        row.addView(transport(ctx, R.drawable.ic_skip_back, R.string.media_prev) { MediaMonitor.send(context, MediaMonitor.Key.PREV) }, lp(58.dp, 58.dp))
        val play = FrameLayout(ctx).apply {
            background = Shapes.pressable(Shapes.oval(Palette.text), Shapes.oval(Palette.text2))
            isClickable = true
            contentDescription = ctx.getString(R.string.media_play)
            pressScale(0.88f)
            setOnClickListener { togglePlay() }
            addView(playIcon, flp(30.dp, 30.dp, Gravity.CENTER))
        }
        row.addView(play, lp(64.dp, 64.dp).apply { marginStart = 6.dp; marginEnd = 6.dp })
        row.addView(transport(ctx, R.drawable.ic_skip_fwd, R.string.media_next) { MediaMonitor.send(context, MediaMonitor.Key.NEXT) }, lp(58.dp, 58.dp))
        addView(row, lp(WRAP, WRAP))
        bind(null)
    }

    private fun transport(ctx: Context, res: Int, desc: Int, onClick: () -> Unit): FrameLayout = FrameLayout(ctx).apply {
        background = Shapes.ghostOval()
        isClickable = true
        contentDescription = ctx.getString(desc)
        pressScale(0.86f)
        setOnClickListener { onClick() }
        addView(ImageView(ctx).apply { setImageResource(res); setColorFilter(Palette.text); scaleType = ImageView.ScaleType.FIT_CENTER }, flp(29.dp, 29.dp, Gravity.CENTER))
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
        np = n
        val song = n?.let { "${it.sourcePkg}|${it.title}|${it.artist}" }
        val changed = song != shownSong && shownSong != null && isShown
        shownSong = song
        if (n == null) {
            title.setText(R.string.media_nothing)
            artist.setText(R.string.media_tap_music)
            setArt(null)
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
            setArt(n.art)
            playIcon.setPlaying(n.playing)
        }
        if (changed) { title.riseIn(8f, 0L); artist.riseIn(8f, 50L) }
        removeCallbacks(tick)
        tick.run()
    }

    /** The new cover fades in over the old one. */
    private fun setArt(b: android.graphics.Bitmap?) {
        if (b === shownArt) return
        shownArt = b
        val shown = art.drawable
        val old = (shown as? TransitionDrawable)?.getDrawable(1) ?: shown
        val next = b?.let { BitmapDrawable(resources, it) }
        if (old != null && next != null && isShown) {
            val fade = TransitionDrawable(arrayOf(old, next)).apply { isCrossFadeEnabled = true; startTransition(Motion.MEDIUM.toInt()) }
            art.setImageDrawable(fade)
            // then the cover alone (the fade's layers share one size: a cover that is not square would be stretched)
            art.postDelayed({ if (art.drawable === fade) art.setImageDrawable(next) }, Motion.MEDIUM + 40)
        } else art.setImageDrawable(next)
        artFallback.visibility = if (b == null) VISIBLE else GONE
    }

    /** The song's progress: a thin line inside the card's bottom edge. */
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (fraction < 0f) return
        val h = 3f.dp
        val inset = 22f.dp
        val y = height - 6f.dp
        val w = width - 2 * inset
        lineRect.set(inset, y - h, inset + w, y)
        linePaint.color = if (Palette.dark) 0x26FFFFFF else 0x1A000000
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
        else -> try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Throwable) { "" }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        MediaMonitor.observe(listener)
    }

    override fun onDetachedFromWindow() {
        MediaMonitor.unobserve(listener)
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }
}
