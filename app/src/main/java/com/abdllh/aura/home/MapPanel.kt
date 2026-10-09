package com.abdllh.aura.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.abdllh.aura.R
import com.abdllh.aura.settings.SettingsActivity
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.MapBackdropView
import com.abdllh.aura.ui.MapPuck
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.elevate
import com.abdllh.aura.ui.flp
import com.abdllh.aura.ui.iconView
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lp
import com.abdllh.aura.ui.pressScale
import com.abdllh.aura.ui.roundedClip
import com.abdllh.aura.util.Prefs
import com.abdllh.aura.util.dp

/** End side of the home screen: the map with a "Where to?" pill, Home/Work shortcuts and the floating player. */
class MapPanel(ctx: Context, private val host: HomeHost) : FrameLayout(ctx) {
    private val map = MapBackdropView(ctx)
    private val puck = MapPuck(ctx)
    val media = MediaCard(ctx, host)
    val search: LinearLayout
    val chips: LinearLayout
    private val homeTitle: AText
    private val workTitle: AText
    private val homeIcon: ImageView
    private val workIcon: ImageView

    init {
        background = Shapes.rect(Palette.mapLand, 24f)
        roundedClip(24f)
        if (!Palette.dark) elevate(5f) // on the light theme the map is close to the background colour
        isClickable = true
        setOnClickListener { openNav() }
        addView(map, flp(MATCH, MATCH))
        addView(puck, flp(MapPuck.SIZE_DP.dp, MapPuck.SIZE_DP.dp, Gravity.TOP or Gravity.LEFT))

        val top = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        search = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(18.dp, 0, 26.dp, 0)
            background = Shapes.glassPressable(26f)
            elevate(6f)
            isClickable = true
            pressScale(0.98f)
            setOnClickListener { openNav() }
            addView(ctx.iconView(R.drawable.ic_search, 22, Palette.text2), lp(22.dp, 22.dp))
            addView(ctx.label(17f, Palette.text2, Fonts.REGULAR).apply { setText(R.string.home_where_to) }, lp(WRAP, WRAP).apply { marginStart = 12.dp })
        }
        search.minimumWidth = 270.dp
        top.addView(search, lp(WRAP, 52.dp))

        chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        homeTitle = ctx.label(14.5f, Palette.text, Fonts.MEDIUM)
        workTitle = ctx.label(14.5f, Palette.text, Fonts.MEDIUM)
        homeIcon = ctx.iconView(R.drawable.ic_home, 19, Palette.accent)
        workIcon = ctx.iconView(R.drawable.ic_briefcase, 19, Palette.accent)
        chips.addView(chip(ctx, homeIcon, homeTitle, R.string.home_home) { go(Prefs.homeAddress, R.string.home_home) }, lp(WRAP, 42.dp))
        chips.addView(chip(ctx, workIcon, workTitle, R.string.home_work) { go(Prefs.workAddress, R.string.home_work) }, lp(WRAP, 42.dp).apply { marginStart = 10.dp })
        top.addView(chips, lp(WRAP, WRAP).apply { topMargin = 10.dp })
        addView(top, flp(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { setMargins(14.dp, 14.dp, 14.dp, 0) })

        addView(media, flp(MATCH, WRAP, Gravity.BOTTOM or Gravity.START).apply { setMargins(14.dp, 0, 14.dp, 14.dp) })
        refresh()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val half = MapPuck.SIZE_DP.dp / 2f
        val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) w * (1f - MapBackdropView.POS_X) else w * MapBackdropView.POS_X
        puck.translationX = x - half
        puck.translationY = h * MapBackdropView.POS_Y - half
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // the player takes the map's width up to a comfortable maximum
        (media.layoutParams as LayoutParams).width = minOf(440.dp, MeasureSpec.getSize(widthMeasureSpec) - 28.dp)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun chip(ctx: Context, icon: ImageView, title: AText, text: Int, onClick: () -> Unit): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(14.dp, 0, 18.dp, 0)
            background = Shapes.glassPressable(21f)
            elevate(4f)
            isClickable = true
            pressScale(0.96f)
            setOnClickListener { onClick() }
            addView(icon, lp(19.dp, 19.dp))
            title.setText(text)
            addView(title, lp(WRAP, WRAP).apply { marginStart = 9.dp })
        }

    fun refresh() {
        val homeSet = Prefs.homeAddress.isNotBlank()
        val workSet = Prefs.workAddress.isNotBlank()
        homeTitle.setTextColor(if (homeSet) Palette.text else Palette.text2)
        workTitle.setTextColor(if (workSet) Palette.text else Palette.text2)
        homeIcon.setColorFilter(if (homeSet) Palette.accent else Palette.text3)
        workIcon.setColorFilter(if (workSet) Palette.accent else Palette.text3)
    }

    private fun go(address: String, which: Int) {
        if (address.isBlank()) {
            host.toast(context.getString(R.string.home_set_address_hint, context.getString(which)))
            host.openSettings(SettingsActivity.PAGE_NAVIGATION)
            return
        }
        val pkg = AppRepo.navPackage(context)
        if (pkg == null) { host.toast(context.getString(R.string.err_no_nav)); return }
        val uri = if (pkg == "com.waze") "waze://?q=${Uri.encode(address)}&navigate=yes" else "google.navigation:q=${Uri.encode(address)}"
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context.startActivity(i) } catch (_: Throwable) { openNav() }
    }

    private fun openNav() {
        if (!Actions.nav(context)) host.toast(context.getString(R.string.err_no_nav))
    }
}
