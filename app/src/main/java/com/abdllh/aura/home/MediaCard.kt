package com.abdllh.aura.home

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
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
import com.abdllh.aura.ui.flp
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lp
import com.abdllh.aura.ui.pressScale
import com.abdllh.aura.ui.roundedClip
import com.abdllh.aura.util.dp

/** "Now playing" card with transport controls. Tapping it opens the playing app (or the stock Music app). */
class MediaCard(ctx: Context, private val host: HomeHost) : FrameLayout(ctx) {
    private val art = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val artFallback = ImageView(ctx).apply { setImageResource(R.drawable.ic_music); setColorFilter(Palette.text3); scaleType = ImageView.ScaleType.CENTER_INSIDE }
    private val title: AText = ctx.label(19f, Palette.text, Fonts.MEDIUM)
    private val artist: AText = ctx.label(14f, Palette.text2, Fonts.REGULAR)
    private val source: AText = ctx.label(11f, Palette.text3, Fonts.MEDIUM)
    private val playBtn = FrameLayout(ctx)
    private val playIcon = ImageView(ctx)
    private var np: NowPlaying? = null

    private val listener: (NowPlaying?) -> Unit = { bind(it) }

    init {
        background = Shapes.clickableCard(26f)
        roundedClip(26f)
        isClickable = true
        pressScale(0.985f)
        setOnClickListener { openSource() }

        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(18.dp, 16.dp, 18.dp, 14.dp) }

        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val artBox = FrameLayout(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF232A35.toInt(), 0xFF171C24.toInt())).apply { cornerRadius = 16f.dp }
            roundedClip(16f)
        }
        artBox.addView(artFallback, flp(MATCH, MATCH).also { it.setMargins(22.dp, 22.dp, 22.dp, 22.dp) })
        artBox.addView(art, flp(MATCH, MATCH))
        top.addView(artBox, lp(84.dp, 84.dp))
        val txt = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        txt.addView(source)
        txt.addView(title, lp(MATCH, WRAP).apply { topMargin = 4.dp })
        txt.addView(artist, lp(MATCH, WRAP).apply { topMargin = 3.dp })
        top.addView(txt, lp(0, WRAP, 1f).apply { marginStart = 14.dp })
        col.addView(top, lp(MATCH, WRAP))

        col.addView(View(ctx), lp(MATCH, 0, 1f))

        // transport controls keep their left-to-right order in every language (like a physical player)
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; layoutDirection = LAYOUT_DIRECTION_LTR }
        row.addView(transport(ctx, R.drawable.ic_skip_back) { MediaMonitor.send(context, MediaMonitor.Key.PREV) }, lp(56.dp, 56.dp))
        playBtn.apply {
            background = Shapes.pressable(Shapes.oval(Palette.text), Shapes.oval(Palette.text2))
            isClickable = true
            pressScale(0.92f)
            setOnClickListener { togglePlay() }
            addView(playIcon.apply { setColorFilter(Palette.bg); scaleType = ImageView.ScaleType.FIT_CENTER }, flp(26.dp, 26.dp, Gravity.CENTER))
        }
        row.addView(playBtn, lp(60.dp, 60.dp).apply { marginStart = 22.dp; marginEnd = 22.dp })
        row.addView(transport(ctx, R.drawable.ic_skip_fwd) { MediaMonitor.send(context, MediaMonitor.Key.NEXT) }, lp(56.dp, 56.dp))
        col.addView(row, lp(MATCH, WRAP))
        addView(col, flp(MATCH, MATCH))
        bind(null)
    }

    private fun transport(ctx: Context, res: Int, onClick: () -> Unit): FrameLayout = FrameLayout(ctx).apply {
        isClickable = true
        pressScale(0.9f)
        setOnClickListener { onClick() }
        addView(ImageView(ctx).apply { setImageResource(res); setColorFilter(Palette.text); scaleType = ImageView.ScaleType.FIT_CENTER }, flp(26.dp, 26.dp, Gravity.CENTER))
    }

    private fun togglePlay() {
        if (np == null) { openSource(); return }
        MediaMonitor.send(context, MediaMonitor.Key.PLAY_PAUSE)
    }

    private fun openSource() {
        val pkg = np?.sourcePkg?.takeIf { AppRepo.isInstalled(context, it) } ?: Known.MUSIC
        if (!AppRepo.launch(context, pkg)) host.toast(context.getString(R.string.err_app_missing))
    }

    private fun bind(n: NowPlaying?) {
        np = n
        if (n == null) {
            title.setText(R.string.media_nothing)
            artist.setText(R.string.media_tap_music)
            source.text = ""
            art.setImageDrawable(null)
            artFallback.visibility = VISIBLE
            playIcon.setImageResource(R.drawable.ic_play)
        } else {
            title.text = n.title
            artist.text = n.artist
            source.text = sourceLabel(n.sourcePkg)
            if (n.art != null) { art.setImageBitmap(n.art); artFallback.visibility = GONE } else { art.setImageDrawable(null); artFallback.visibility = VISIBLE }
            playIcon.setImageResource(if (n.playing) R.drawable.ic_pause else R.drawable.ic_play)
        }
    }

    private fun sourceLabel(pkg: String?): String = when (pkg) {
        null -> ""
        Known.MUSIC -> context.getString(R.string.dock_music)
        Known.BT_MUSIC -> context.getString(R.string.src_bt)
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
