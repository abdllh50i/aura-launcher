package com.abdllh.aura.home

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.abdllh.aura.R
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.MapBackdropView
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.flp
import com.abdllh.aura.ui.iconView
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lp
import com.abdllh.aura.ui.pressScale
import com.abdllh.aura.ui.roundedClip
import com.abdllh.aura.util.Prefs
import com.abdllh.aura.util.dp

/** Navigation card: map backdrop, "Where to?" and Home/Work shortcuts. Opens the chosen navigation app. */
class NavCard(ctx: Context, private val host: HomeHost) : FrameLayout(ctx) {
    private val map = MapBackdropView(ctx)
    private val homeChip: LinearLayout
    private val workChip: LinearLayout
    private val homeSub: AText
    private val workSub: AText

    init {
        background = Shapes.card(26f)
        roundedClip(26f)
        isClickable = true
        addView(map, flp(MATCH, MATCH))

        // legibility gradient on the start side
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val fade = View(ctx).apply {
            background = GradientDrawable(
                if (rtl) GradientDrawable.Orientation.RIGHT_LEFT else GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xF2101419.toInt(), 0xB3101419.toInt(), 0x00101419)
            )
        }
        addView(fade, flp(MATCH, MATCH))

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22.dp, 20.dp, 22.dp, 20.dp)
        }

        // search pill
        val pill = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18.dp, 0, 22.dp, 0)
            background = Shapes.pressable(
                Shapes.rect(0xE61A2029.toInt(), 28f, Palette.stroke),
                Shapes.rect(0xFF222A35.toInt(), 28f, Palette.stroke)
            )
            isClickable = true
            pressScale(0.98f)
            setOnClickListener { openNav() }
        }
        pill.addView(ctx.iconView(R.drawable.ic_search, 22, Palette.text2))
        pill.addView(ctx.label(18f, Palette.text2, Fonts.REGULAR).apply { setText(R.string.home_where_to) }, lp(WRAP, WRAP).apply { marginStart = 12.dp })
        col.addView(pill, lp(300.dp, 54.dp))

        col.addView(View(ctx), lp(MATCH, 0, 1f))

        // home / work chips
        val chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        homeSub = ctx.label(12f, Palette.text3, Fonts.REGULAR)
        workSub = ctx.label(12f, Palette.text3, Fonts.REGULAR)
        homeChip = chip(ctx, R.drawable.ic_home, R.string.home_home, homeSub) { go(Prefs.homeAddress, R.string.home_home) }
        workChip = chip(ctx, R.drawable.ic_briefcase, R.string.home_work, workSub) { go(Prefs.workAddress, R.string.home_work) }
        chips.addView(homeChip, lp(WRAP, 56.dp))
        chips.addView(workChip, lp(WRAP, 56.dp).apply { marginStart = 12.dp })
        col.addView(chips, lp(MATCH, WRAP))
        addView(col, flp(MATCH, MATCH))

        // open-map button (end/bottom)
        val open = FrameLayout(ctx).apply {
            background = Shapes.pressable(Shapes.oval(Palette.accent), Shapes.oval(Palette.mix(Palette.accent, 0xFFFFFFFF.toInt(), 0.2f)))
            isClickable = true
            pressScale(0.9f)
            setOnClickListener { openNav() }
        }
        open.addView(ctx.iconView(R.drawable.ic_nav, 26, Palette.onColor(Palette.accent)).apply { layoutParams = flp(26.dp, 26.dp, Gravity.CENTER) })
        addView(open, flp(60.dp, 60.dp, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, 22.dp, 20.dp); marginEnd = 22.dp })

        setOnClickListener { openNav() }
        refresh()
    }

    private fun chip(ctx: Context, icon: Int, title: Int, sub: AText, onClick: () -> Unit): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16.dp, 0, 20.dp, 0)
            background = Shapes.pressable(
                Shapes.rect(0xE61A2029.toInt(), 18f, Palette.stroke),
                Shapes.rect(0xFF222A35.toInt(), 18f, Palette.stroke)
            )
            isClickable = true
            pressScale(0.97f)
            setOnClickListener { onClick() }
            addView(ctx.iconView(icon, 24, Palette.accent))
            val t = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            t.addView(ctx.label(16f, Palette.text, Fonts.MEDIUM).apply { setText(title) })
            t.addView(sub)
            addView(t, lp(WRAP, WRAP).apply { marginStart = 12.dp })
        }

    fun refresh() {
        homeSub.text = Prefs.homeAddress.ifBlank { context.getString(R.string.home_tap_to_set) }
        workSub.text = Prefs.workAddress.ifBlank { context.getString(R.string.home_tap_to_set) }
        homeSub.maxWidth = 150.dp
        workSub.maxWidth = 150.dp
    }

    private fun go(address: String, which: Int) {
        if (address.isBlank()) {
            host.toast(context.getString(R.string.home_set_address_hint, context.getString(which)))
            host.openSettings(com.abdllh.aura.settings.SettingsActivity.PAGE_NAVIGATION)
            return
        }
        val pkg = AppRepo.navPackage(context)
        if (pkg == null) { host.toast(context.getString(R.string.err_no_nav)); return }
        val uri = if (pkg == "com.waze") "waze://?q=${Uri.encode(address)}&navigate=yes" else "google.navigation:q=${Uri.encode(address)}"
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context.startActivity(i) } catch (_: Throwable) { openNav() }
    }

    private fun openNav() {
        val pkg = AppRepo.navPackage(context)
        if (pkg == null || !AppRepo.launch(context, pkg)) host.toast(context.getString(R.string.err_no_nav))
    }
}
