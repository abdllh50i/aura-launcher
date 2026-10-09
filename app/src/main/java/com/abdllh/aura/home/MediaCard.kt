package com.abdllh.aura.home

import android.content.Context
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
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.elevate
import com.abdllh.aura.ui.flp
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lp
import com.abdllh.aura.ui.pressScale
import com.abdllh.aura.ui.roundedClip
import com.abdllh.aura.util.dp

/** Floating "now playing" bar on the map. Tapping it opens the playing app (or the stock Music app). */
class MediaCard(ctx: Context, private val host: HomeHost) : LinearLayout(ctx) {
    private val art = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val artFallback = ImageView(ctx).apply { setImageResource(R.drawable.ic_music); setColorFilter(Palette.text3); scaleType = ImageView.ScaleType.FIT_CENTER }
    private val title: AText = ctx.label(18f, Palette.text, Fonts.MEDIUM)
    // the UI language orders "artist · source" (an English artist must not turn an Arabic line around)
    private val artist: AText = ctx.label(14.5f, Palette.text2, Fonts.REGULAR).apply { textDirection = TEXT_DIRECTION_LOCALE }
    private val playIcon = ImageView(ctx)
    private var np: NowPlaying? = null

    private val listener: (NowPlaying?) -> Unit = { bind(it) }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPaddingRelative(12.dp, 12.dp, 12.dp, 12.dp)
        background = Shapes.glassPressable(20f)
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
            pressScale(0.9f)
            setOnClickListener { togglePlay() }
            addView(playIcon.apply { setColorFilter(Palette.card); scaleType = ImageView.ScaleType.FIT_CENTER }, flp(27.dp, 27.dp, Gravity.CENTER))
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
        pressScale(0.88f)
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
        if (n == null) {
            title.setText(R.string.media_nothing)
            artist.setText(R.string.media_tap_music)
            art.setImageDrawable(null)
            artFallback.visibility = VISIBLE
            playIcon.setImageResource(R.drawable.ic_play)
        } else {
            title.text = n.title
            val src = sourceLabel(n.sourcePkg)
            val bidi = android.text.BidiFormatter.getInstance()
            artist.text = when {
                n.artist.isNotBlank() && src.isNotBlank() -> "${bidi.unicodeWrap(n.artist)}  ·  ${bidi.unicodeWrap(src)}"
                n.artist.isNotBlank() -> n.artist
                else -> src
            }
            if (n.art != null) { art.setImageBitmap(n.art); artFallback.visibility = GONE } else { art.setImageDrawable(null); artFallback.visibility = VISIBLE }
            playIcon.setImageResource(if (n.playing) R.drawable.ic_pause else R.drawable.ic_play)
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
        super.onDetachedFromWindow()
    }
}
