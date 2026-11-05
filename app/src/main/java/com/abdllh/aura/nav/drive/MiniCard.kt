package com.abdllh.aura.nav.drive

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.TextPaint
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import com.abdllh.aura.R
import com.abdllh.aura.home.Known
import com.abdllh.aura.media.MediaMonitor
import com.abdllh.aura.media.NowPlaying
import com.abdllh.aura.music.Backdrop
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.IconButton
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.PressGesture
import com.abdllh.aura.ui.Recolours
import com.abdllh.aura.ui.ThemeSwap
import com.abdllh.aura.ui.Tokens2
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.anim
import com.abdllh.aura.ui.at
import com.abdllh.aura.ui.fadeTo
import com.abdllh.aura.ui.pressScale

/**
 * The mini card (SPEC.md section 5), 364 x 80 at (12, 426), which never moves and is absent with no song. The song's own wash, with its card, edge and shadow in one bitmap ([Backdrop], once per song on its
 * worker); the cover at the right, the words between it and the transport, pause and next at the left in both languages, always in that order. The title steps down to `miniSub`'s size before its ellipsis.
 * A tap on the words: Music. A tap on the cover: [onCover]. The theme's wash is ready before the theme flips ([ThemeSwap.readySteps]).
 */
internal class MiniCard(ctx: Context, private val onOpen: () -> Unit, private val onCover: () -> Unit) : Hit(ctx, Backdrop.CARD_MARGIN), Recolours {
    private val wash = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_XY }
    private val coverA = cover(ctx)
    private val coverB = cover(ctx)
    private var coverFront = coverA
    private val title: AText = ctx.line(Type.miniTitle, { Palette.ink }).apply { gravity = Gravity.CENTER_VERTICAL }
    private val sub: AText = ctx.line(Type.miniSub, { Palette.ink2Move }).apply { gravity = Gravity.CENTER_VERTICAL }
    private val pause = button(ctx, R.drawable.ic_pause, 64, R.string.media_play) { MediaMonitor.send(context, MediaMonitor.Key.PLAY_PAUSE) }
    private val next = button(ctx, R.drawable.ic_next, 68, R.string.media_next) { MediaMonitor.send(context, MediaMonitor.Key.NEXT) }
    private val fit = TextPaint(title.paint)
    private val main = Handler(Looper.getMainLooper())

    private var songKey: String? = null
    private var art: Bitmap? = null
    private var artDone = false
    private var titleSmall = false
    private var playing = true
    private val washes = HashMap<Boolean, Backdrop.Wash>()
    private var washAsked: Boolean? = null
    /** A new song's cover usually comes a moment after its title (Bluetooth sends it in parts): the old one waits, dimmed. */
    private val dropCover = Runnable { takeArt(null) }

    private val ready: (Boolean, () -> Unit) -> Unit = { night, done ->
        val a = art
        // (on the theme's worker: it waits for this, as section 8 says)
        if (washes[night] == null) {
            val w = Backdrop.wash(context.applicationContext, a, night)
            main.post { if (a === art) washes[night] = w }
        }
        done()
    }

    init {
        val m = Backdrop.CARD_MARGIN
        addView(wash, at(0, 0, 424, 140))
        val texts = FrameLayout(ctx).apply {
            isClickable = true
            pressScale(Motion.PRESS_SURFACE)
            setOnClickListener { onOpen() }
        }
        texts.addView(title, at(0, 5, 158, Type.miniTitle.viewH))
        texts.addView(sub, at(0, 39, 158, Type.miniSub.viewH))
        addView(texts, at(m + 136, m, 158, 80))
        for (c in arrayOf(coverA, coverB)) {
            addView(c, at(m + 304, m + 14, 52, 52))
            c.isClickable = true
            c.setOnClickListener { onCover() }
        }
        coverB.alpha = 0f
        addView(pause, at(m, m, 64, 80))
        addView(next, at(m + 64, m, 68, 80))
        setCover(null)
        askWash()
        ThemeSwap.readySteps.add(ready)
    }

    /** It lets go of what the theme's path holds of it. */
    fun destroy() {
        ThemeSwap.readySteps.remove(ready)
        main.removeCallbacks(dropCover)
    }

    override fun onLook() {
        askWash()
        if (art == null) setNoArt(coverFront)
        coverAlpha()
    }

    private fun coverAlpha() {
        // (the night's cover steps back: section 0's table. Both views, the one in front at rest)
        coverFront.alpha = if (Palette.dark) NIGHT_COVER else 1f
    }

    fun bind(n: NowPlaying) {
        val key = "${n.sourcePkg}|${n.title}"
        val newSong = key != songKey
        songKey = key
        setTitle(n.title)
        val s = n.artist.ifBlank { sourceWord(n.sourcePkg) }
        if (sub.text.toString() != s) sub.text = s
        if (n.playing != playing) {
            playing = n.playing
            pause.setIcon(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        }
        if (n.art !== art || !artDone) {
            if (n.art == null && newSong && art != null && isShown) {
                coverFront.fadeTo(0.45f)
                main.removeCallbacks(dropCover)
                main.postDelayed(dropCover, COVER_WAIT_MS)
            } else takeArt(n.art)
        }
    }

    private fun takeArt(a: Bitmap?) {
        main.removeCallbacks(dropCover)
        art = a
        artDone = true
        washes.clear()
        washAsked = null
        askWash()
        if (a == null) { setCover(null); return }
        Backdrop.roundAsync(context, a, COVER_PX, Tokens2.R_coverMini) { b -> if (art === a) setCover(b) }
    }

    private fun askWash() {
        val night = Palette.dark
        val have = washes[night]
        if (have != null) {
            wash.setImageBitmap(have.mini)
            return
        }
        if (washAsked == night) return
        washAsked = night
        val a = art
        Backdrop.washAsync(context, a, night) { w ->
            if (a === art) {
                washes[night] = w
                if (washAsked == night) washAsked = null
                if (night == Palette.dark) wash.setImageBitmap(w.mini)
            }
        }
    }

    /** The new cover crossfades over the old: 240, scale 0.98 to 1. */
    private fun setCover(b: Bitmap?) {
        val to = if (coverFront === coverA) coverB else coverA
        val from = coverFront
        if (b != null) to.setImageBitmap(b) else setNoArt(to)
        coverFront = to
        val rest = if (Palette.dark) NIGHT_COVER else 1f
        to.animate().cancel()
        from.animate().cancel()
        if (isShown) {
            to.alpha = 0f
            to.scaleX = 0.98f
            to.scaleY = 0.98f
            to.anim(Tokens2.MS_cover, Motion.MOVE).alpha(rest).scaleX(1f).scaleY(1f).start()
            from.fadeTo(0f, Tokens2.MS_cover)
        } else {
            to.alpha = rest
            from.alpha = 0f
        }
    }

    private fun setNoArt(v: ImageView) = v.setImageDrawable(Backdrop.noArt(context, COVER_PX, Tokens2.R_coverMini))

    private fun setTitle(s: String) {
        if (title.text.toString() == s) return
        fit.textSize = Type.miniTitle.sp
        fit.typeface = Fonts.of(Type.miniTitle, true)
        val small = Layout.getDesiredWidth(s, fit) > TEXT_W
        if (small != titleSmall) {
            titleSmall = small
            title.oneLine(if (small) Type.miniSub else Type.miniTitle, true)
        }
        title.text = s
    }

    private fun sourceWord(pkg: String?): String = when (pkg) {
        null, context.packageName -> ""
        MediaMonitor.SOURCE_BT, Known.BT_MUSIC -> context.getString(R.string.src_bt)
        Known.MUSIC -> context.getString(R.string.dock_music)
        Known.RADIO -> context.getString(R.string.dock_radio)
        else -> try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Throwable) { "" }
    }

    private companion object {
        const val COVER_PX = 52
        const val TEXT_W = 158f
        const val NIGHT_COVER = 0.8f
        const val COVER_WAIT_MS = 1500L

        fun cover(ctx: Context) = object : ImageView(ctx) { override fun hasOverlappingRendering() = false }.apply { scaleType = ImageView.ScaleType.FIT_XY }

        /** A transport key of the left: glyph 36 on a quiet plate 56, on a hit as high as the card. */
        fun button(ctx: Context, icon: Int, hitW: Int, desc: Int, onClick: () -> Unit) =
            IconButton(ctx, icon, 36, hitW, 80, IconButton.Kind.TONAL, PressGesture.ICON, plateRadius = Tokens2.R_mid, plateToken = { Palette.fill }, token = { Palette.ink }).apply {
                contentDescription = ctx.getString(desc)
                setOnClickListener { onClick() }
            }
    }
}
