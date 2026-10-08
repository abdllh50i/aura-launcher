package com.abdllh.aura.home

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.abdllh.aura.R
import com.abdllh.aura.settings.SettingsActivity
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
import com.abdllh.aura.update.UpdateManager
import com.abdllh.aura.util.Prefs
import com.abdllh.aura.util.dp

/** Bottom bar: volume stepper on the start side, app shortcuts in the middle, settings on the end side. */
class Dock(ctx: Context, private val host: HomeHost) : FrameLayout(ctx) {

    private class Item(val icon: Int, val label: Int, val pkg: String?, val action: (() -> Unit)? = null)

    private val center = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
    private val volText: AText = ctx.label(15f, Palette.text2, Fonts.MEDIUM, gravity = Gravity.CENTER)
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var badge: View? = null

    init {
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00000000, 0xCC07090C.toInt()))
        setPadding(16.dp, 0, 16.dp, 0)

        // start: volume stepper
        val vol = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = LAYOUT_DIRECTION_LTR }
        vol.addView(stepper(ctx, "–") { step(-1) }, lp(48.dp, 48.dp))
        vol.addView(volText, lp(54.dp, WRAP))
        vol.addView(stepper(ctx, "+") { step(+1) }, lp(48.dp, 48.dp))
        addView(vol, flp(WRAP, MATCH, Gravity.START or Gravity.CENTER_VERTICAL))

        addView(center, flp(WRAP, MATCH, Gravity.CENTER))

        // end: settings with update badge
        val settings = tile(ctx, Item(R.drawable.ic_settings, R.string.dock_settings, null) { host.openSettings(0) })
        val wrap = FrameLayout(ctx)
        wrap.addView(settings, flp(WRAP, WRAP, Gravity.CENTER))
        badge = View(ctx).apply { background = Shapes.oval(Palette.accent, Palette.bg, 2) }
        wrap.addView(badge, flp(12.dp, 12.dp, Gravity.TOP or Gravity.END).apply { setMargins(0, 12.dp, 22.dp, 0); marginEnd = 22.dp })
        addView(wrap, flp(WRAP, MATCH, Gravity.END or Gravity.CENTER_VERTICAL))
        rebuild()
    }

    private fun stepper(ctx: Context, glyph: String, onClick: () -> Unit): FrameLayout = FrameLayout(ctx).apply {
        background = Shapes.pressable(Shapes.oval(0x00000000), Shapes.oval(0x22FFFFFF))
        isClickable = true
        pressScale(0.88f)
        setOnClickListener { onClick() }
        addView(ctx.label(26f, Palette.text, Fonts.LIGHT, gravity = Gravity.CENTER).apply { text = glyph }, flp(MATCH, MATCH))
    }

    private fun step(dir: Int) {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (dir > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
        syncVolume()
    }

    fun syncVolume() {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        volText.text = "${(cur * 100f / max).toInt()}"
    }

    private fun items(): List<Item> {
        val ctx = context
        val list = ArrayList<Item>()
        list.add(Item(R.drawable.ic_phone, R.string.dock_phone, Known.PHONE))
        list.add(Item(R.drawable.ic_nav, R.string.dock_nav, null) {
            val p = AppRepo.navPackage(ctx)
            if (p == null || !AppRepo.launch(ctx, p)) host.toast(ctx.getString(R.string.err_no_nav))
        })
        list.add(Item(R.drawable.ic_music, R.string.dock_music, Known.MUSIC))
        list.add(Item(R.drawable.ic_radio, R.string.dock_radio, Known.RADIO))
        if (AppRepo.isInstalled(ctx, Known.CAM360)) list.add(Item(R.drawable.ic_camera, R.string.dock_camera, Known.CAM360))
        if (AppRepo.isInstalled(ctx, Known.ZLINK)) list.add(Item(R.drawable.ic_smartphone, R.string.dock_link, Known.ZLINK))
        if (AppRepo.isInstalled(ctx, Known.VIDEO)) list.add(Item(R.drawable.ic_video, R.string.dock_video, Known.VIDEO))
        list.add(Item(R.drawable.ic_apps, R.string.dock_apps, null) { host.openDrawer() })
        return list
    }

    fun rebuild() {
        center.removeAllViews()
        for ((i, it) in items().withIndex()) {
            center.addView(tile(context, it), lp(WRAP, MATCH).apply { if (i > 0) marginStart = 6.dp })
        }
        syncVolume()
        refreshBadge()
    }

    fun refreshBadge() {
        badge?.visibility = if (UpdateManager.hasUpdateBadge()) VISIBLE else GONE
    }

    private fun tile(ctx: Context, item: Item): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        minimumWidth = 84.dp
        setPadding(8.dp, 6.dp, 8.dp, 6.dp)
        background = Shapes.pressable(Shapes.rect(0x00000000, 18f), Shapes.rect(0x1CFFFFFF, 18f))
        isClickable = true
        pressScale(0.9f)
        setOnClickListener {
            val a = item.action
            if (a != null) a() else if (item.pkg != null && !AppRepo.launch(ctx, item.pkg)) host.toast(ctx.getString(R.string.err_app_missing))
        }
        addView(ImageView(ctx).apply { setImageResource(item.icon); setColorFilter(Palette.text); scaleType = ImageView.ScaleType.FIT_CENTER }, lp(30.dp, 30.dp))
        if (Prefs.dockLabels) {
            addView(ctx.label(11.5f, Palette.text2, Fonts.REGULAR, gravity = Gravity.CENTER).apply { setText(item.label) }, lp(WRAP, WRAP).apply { topMargin = 5.dp })
        }
    }
}
