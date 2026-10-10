package com.abdllh.aura.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.R
import com.abdllh.aura.home.AppRepo
import com.abdllh.aura.home.Known
import com.abdllh.aura.system.Controls
import com.abdllh.aura.system.CrashGuard
import com.abdllh.aura.system.Device
import com.abdllh.aura.ui.ABtn
import com.abdllh.aura.ui.AuraSlider
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.Theme
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.iconView
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lp
import com.abdllh.aura.ui.pressScale
import com.abdllh.aura.util.Prefs
import com.abdllh.aura.util.dp

// ---------------------------------------------------------------------------------------------- General
class GeneralPage(private val act: SettingsActivity) : Page(R.string.set_general, R.drawable.ic_sliders) {
    override fun build(ctx: Context): View {
        val col = ctx.pageColumn()
        col.addView(ctx.sectionTitle(ctx.getString(R.string.set_appearance)))

        val theme = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp, 16.dp, 20.dp, 18.dp)
            background = Shapes.card(18f)
        }
        val themeHead = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        themeHead.addView(ctx.iconView(R.drawable.ic_contrast, 24, Palette.text2), lp(24.dp, 24.dp).apply { marginEnd = 16.dp })
        val themeTitles = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        themeTitles.addView(ctx.label(17f, Palette.text, Fonts.MEDIUM).apply { setText(R.string.set_theme) })
        themeTitles.addView(ctx.label(13.5f, Palette.text2, Fonts.REGULAR, lines = 2).apply { setText(R.string.set_theme_sub) }, lp(MATCH, WRAP).apply { topMargin = 3.dp })
        themeHead.addView(themeTitles, lp(0, WRAP, 1f))
        theme.addView(themeHead)
        val modes = listOf(Theme.AUTO, Theme.LIGHT, Theme.DARK)
        val names = listOf(ctx.getString(R.string.theme_auto), ctx.getString(R.string.theme_light), ctx.getString(R.string.theme_dark))
        theme.addView(Segmented(ctx, names, modes.indexOf(Prefs.theme).coerceAtLeast(0)) { i ->
            Prefs.theme = modes[i]
            if (Theme.refresh()) act.rebuild()
        }, lp(MATCH, WRAP).apply { topMargin = 14.dp })
        col.addRow(theme)

        val accent = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp, 16.dp, 20.dp, 18.dp)
            background = Shapes.card(18f)
        }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(ctx.iconView(R.drawable.ic_droplet, 24, Palette.text2), lp(24.dp, 24.dp).apply { marginEnd = 16.dp })
        head.addView(ctx.label(17f, Palette.text, Fonts.MEDIUM).apply { setText(R.string.set_accent) })
        accent.addView(head)
        accent.addView(Swatches(ctx, Palette.accents, Prefs.accent) { Prefs.accent = it; act.rebuild() }, lp(WRAP, WRAP).apply { topMargin = 14.dp })
        col.addRow(accent)

        col.addRow(ctx.switchRow(R.drawable.ic_clock, ctx.getString(R.string.set_clock24), null, Prefs.clock24) { Prefs.clock24 = it })
        col.addRow(ctx.switchRow(R.drawable.ic_list, ctx.getString(R.string.set_dock_labels), null, Prefs.dockLabels) { Prefs.dockLabels = it })

        col.addView(ctx.sectionTitle(ctx.getString(R.string.set_language)), lp(MATCH, WRAP).apply { topMargin = 22.dp })
        val langCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp, 16.dp, 20.dp, 18.dp)
            background = Shapes.card(18f)
        }
        val codes = listOf("system", "ar", "en")
        val seg = Segmented(ctx, listOf(ctx.getString(R.string.lang_system), "العربية", "English"), codes.indexOf(Prefs.lang).coerceAtLeast(0), arabicIndex = 1) { i ->
            Prefs.lang = codes[i]
            act.recreate()
        }
        langCard.addView(seg, lp(MATCH, WRAP))
        col.addRow(langCard)
        return col
    }
}

// ---------------------------------------------------------------------------------------------- Display & sound
class DisplaySoundPage(private val act: SettingsActivity) : Page(R.string.set_display_sound, R.drawable.ic_sun) {
    override fun build(ctx: Context): View {
        val col = ctx.pageColumn()

        val bv = ctx.valueText("")
        val bs = AuraSlider(ctx).apply {
            max = 100
            setIcon(R.drawable.ic_sun)
            setProgress(Controls.brightnessPercent(ctx), false)
            bv.text = "${progress}%"
            onChange = { v, user ->
                bv.text = "$v%"
                if (user && !Controls.setBrightnessPercent(ctx, v)) {
                    Toast.makeText(ctx, R.string.err_write_settings, Toast.LENGTH_SHORT).show()
                    Controls.requestWriteSettings(ctx)
                }
            }
        }
        col.addRow(ctx.sliderRow(R.drawable.ic_sun, ctx.getString(R.string.ctl_brightness), bs, bv))

        val vv = ctx.valueText("")
        val vs = AuraSlider(ctx).apply {
            max = Controls.volumeMax(ctx)
            setIcon(R.drawable.ic_volume)
            setProgress(Controls.volume(ctx), false)
            vv.text = Controls.percentText(ctx, progress)
            onChange = { v, user ->
                if (user) Controls.setVolume(ctx, v)
                vv.text = Controls.percentText(ctx, v)
            }
        }
        col.addRow(ctx.sliderRow(R.drawable.ic_volume, ctx.getString(R.string.ctl_volume), vs, vv))
        col.addRow(ctx.switchRow(R.drawable.ic_steering, ctx.getString(R.string.set_volume_hud), ctx.getString(R.string.set_volume_hud_sub),
            com.abdllh.aura.system.VolumeHud.enabled) { on ->
            com.abdllh.aura.system.VolumeHud.enabled = on
            com.abdllh.aura.system.VolumeHud.apply(ctx) { ok ->
                if (!ok) act.runOnUiThread { Toast.makeText(ctx, R.string.set_failed, Toast.LENGTH_LONG).show() }
            }
        }, 8)

        col.addRow(ctx.switchRow(R.drawable.ic_eye_off, ctx.getString(R.string.set_hide_status), ctx.getString(R.string.set_hide_status_sub), Prefs.hideStatusBar) {
            Prefs.hideStatusBar = it
            if (!com.abdllh.aura.system.SystemBars.apply(ctx)) Toast.makeText(ctx, R.string.set_failed, Toast.LENGTH_SHORT).show()
        }, 16)

        col.addView(ctx.sectionTitle(ctx.getString(R.string.set_music)), lp(MATCH, WRAP).apply { topMargin = 22.dp })
        col.addRow(ctx.switchRow(R.drawable.ic_steering, ctx.getString(R.string.set_music_keys), ctx.getString(R.string.set_music_keys_sub),
            com.abdllh.aura.system.MusicKeys.enabled) { on -> com.abdllh.aura.system.MusicKeys.enabled = on }, 8)
        col.addRow(ctx.switchRow(R.drawable.ic_disc, ctx.getString(R.string.set_bt_cover), ctx.getString(R.string.set_bt_cover_sub),
            com.abdllh.aura.music.CoverSearch.enabled) { on -> com.abdllh.aura.music.CoverSearch.enabled = on }, 8)
        if (com.abdllh.aura.system.StockMusic.available(ctx)) {
            col.addRow(ctx.switchRow(R.drawable.ic_music, ctx.getString(R.string.set_music_replace), ctx.getString(R.string.set_music_replace_sub),
                com.abdllh.aura.system.StockMusic.enabled) { on ->
                com.abdllh.aura.system.StockMusic.enabled = on
                com.abdllh.aura.system.StockMusic.apply(ctx) { ok ->
                    if (!ok) act.runOnUiThread { Toast.makeText(ctx, R.string.set_failed, Toast.LENGTH_LONG).show() }
                }
            }, 8)
        }

        col.addView(ctx.sectionTitle(ctx.getString(R.string.set_shortcuts)), lp(MATCH, WRAP).apply { topMargin = 22.dp })
        if (AppRepo.isInstalled(ctx, "com.nwd.audioset")) {
            col.addRow(ctx.settingRow(R.drawable.ic_sliders, ctx.getString(R.string.set_equalizer), ctx.getString(R.string.set_equalizer_sub), ctx.chevron()) {
                if (!AppRepo.launch(ctx, "com.nwd.audioset")) Toast.makeText(ctx, R.string.err_app_missing, Toast.LENGTH_SHORT).show()
            })
        }
        if (AppRepo.isInstalled(ctx, Known.STOCK_LAUNCHER)) {
            col.addRow(ctx.settingRow(R.drawable.ic_power, ctx.getString(R.string.ctl_screen_off), null, ctx.chevron()) {
                AppRepo.launchComponent(ctx, Known.STOCK_LAUNCHER, Known.ACT_SCREEN_OFF)
            })
            col.addRow(ctx.settingRow(R.drawable.ic_monitor, ctx.getString(R.string.set_screensaver), null, ctx.chevron()) {
                AppRepo.launchComponent(ctx, Known.STOCK_LAUNCHER, Known.ACT_LOCK)
            })
        }
        col.addRow(ctx.settingRow(R.drawable.ic_monitor, ctx.getString(R.string.set_android_display), null, ctx.chevron()) {
            try { ctx.startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) { }
        })
        return col
    }
}

// ---------------------------------------------------------------------------------------------- Navigation
class NavigationPage(private val act: SettingsActivity) : Page(R.string.set_navigation, R.drawable.ic_nav) {
    override fun build(ctx: Context): View {
        val col = ctx.pageColumn()
        col.addView(ctx.sectionTitle(ctx.getString(R.string.set_nav_app)))

        // Aura Maps first, then the installed navigation apps
        val builtIn = Prefs.builtInMaps
        col.addRow(ctx.settingRow(R.drawable.ic_compass, ctx.getString(R.string.set_maps_builtin), ctx.getString(R.string.set_maps_builtin_sub),
            if (builtIn) ctx.iconView(R.drawable.ic_check_circle, 26, Palette.accent) else null,
            if (builtIn) Palette.accent else Palette.text2) {
            Prefs.builtInMaps = true
            act.rebuild()
        }, 8)
        val choices = ArrayList<Pair<String, String>>() // pkg ("" = automatic) -> label
        choices.add("" to ctx.getString(R.string.set_nav_auto))
        for (a in AppRepo.installedNavApps(ctx)) choices.add(a.pkg to a.label)
        val selected = Prefs.navPackage
        for ((pkg, name) in choices) {
            val on = !builtIn && (pkg == selected || (pkg.isEmpty() && selected.isEmpty()))
            col.addRow(ctx.settingRow(R.drawable.ic_nav, name, if (pkg.isEmpty()) ctx.getString(R.string.set_nav_auto_sub) else null,
                if (on) ctx.iconView(R.drawable.ic_check_circle, 26, Palette.accent) else null,
                if (on) Palette.accent else Palette.text2) {
                Prefs.builtInMaps = false
                Prefs.navPackage = pkg
                if (pkg.isNotEmpty()) com.abdllh.aura.system.SystemProps.set("persist.sys.navi_set_by_app", pkg)
                act.rebuild()
            }, 8)
        }

        col.addView(ctx.sectionTitle(ctx.getString(R.string.set_places)), lp(MATCH, WRAP).apply { topMargin = 22.dp })
        col.addRow(placeRow(ctx, R.drawable.ic_home, R.string.home_home, "home"))
        col.addRow(placeRow(ctx, R.drawable.ic_briefcase, R.string.home_work, "work"))
        col.addRow(ctx.hint(ctx.getString(R.string.set_places_hint)), 12)
        return col
    }

    /** Home / Work: chosen on the map (a long press drops the pin). */
    private fun placeRow(ctx: Context, icon: Int, title: Int, which: String): View {
        val saved = if (which == "home") com.abdllh.aura.nav.Places.home else com.abdllh.aura.nav.Places.work
        val legacy = if (which == "home") Prefs.homeAddress else Prefs.workAddress
        val value = saved?.name ?: legacy
        return ctx.settingRow(icon, ctx.getString(title), value.ifBlank { ctx.getString(R.string.home_tap_to_set) }, ctx.iconView(R.drawable.ic_pin, 22, Palette.text3)) {
            com.abdllh.aura.nav.MapsActivity.open(ctx) { putExtra("pick", which) }
        }
    }
}

// ---------------------------------------------------------------------------------------------- Vehicle & system
class VehiclePage(private val act: SettingsActivity) : Page(R.string.set_vehicle, R.drawable.ic_car) {
    private class Tile(val icon: Int, val label: Int, val available: (Context) -> Boolean, val run: (Context) -> Unit)
    private var internet: InternetCard? = null

    override fun onShow() { internet?.attach() }
    override fun onHide() { internet?.detach() }

    override fun build(ctx: Context): View {
        val col = ctx.pageColumn()
        val stock = Known.STOCK_LAUNCHER
        val tiles = listOf(
            Tile(R.drawable.ic_settings, R.string.v_car_settings, { AppRepo.isInstalled(it, Known.CAR_SETTING) }, { AppRepo.launch(it, Known.CAR_SETTING) }),
            Tile(R.drawable.ic_car, R.string.v_mycar, { AppRepo.isInstalled(it, Known.MYCAR) }, { AppRepo.launch(it, Known.MYCAR) }),
            Tile(R.drawable.ic_steering, R.string.v_wheel, { AppRepo.isInstalled(it, stock) }, { AppRepo.launchComponent(it, stock, Known.ACT_WHEEL) }),
            Tile(R.drawable.ic_tool, R.string.v_toolbox, { AppRepo.isInstalled(it, stock) }, { AppRepo.launchComponent(it, stock, Known.ACT_TOOLBOX) }),
            Tile(R.drawable.ic_sliders, R.string.v_android_settings, { true }, { c -> try { c.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) { } }),
            Tile(R.drawable.ic_wifi, R.string.v_wifi, { true }, { c -> try { c.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) { } }),
            Tile(R.drawable.ic_folder, R.string.v_files, { AppRepo.isInstalled(it, Known.FILES) }, { AppRepo.launch(it, Known.FILES) }),
            Tile(R.drawable.ic_smartphone, R.string.v_phone_link, { AppRepo.isInstalled(it, Known.ZLINK) }, { AppRepo.launch(it, Known.ZLINK) }),
            Tile(R.drawable.ic_refresh, R.string.v_restart, { AppRepo.isInstalled(it, stock) }, { AppRepo.launchComponent(it, stock, Known.ACT_REBOOT) })
        ).filter { it.available(ctx) }

        var row: LinearLayout? = null
        for ((i, t) in tiles.withIndex()) {
            if (i % 3 == 0) {
                row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                col.addView(row, lp(MATCH, WRAP).apply { topMargin = 12.dp })
            }
            row!!.addView(tile(ctx, t), lp(0, 124.dp, 1f).apply { if (i % 3 != 0) marginStart = 12.dp })
        }
        // pad the last row so tiles keep their width
        val rem = tiles.size % 3
        if (rem != 0) for (k in 0 until 3 - rem) row!!.addView(View(ctx), lp(0, 124.dp, 1f).apply { marginStart = 12.dp })
        col.addRow(ctx.hint(ctx.getString(R.string.v_hint)), 14)

        col.addView(ctx.sectionTitle(ctx.getString(R.string.set_connections)), lp(MATCH, WRAP).apply { topMargin = 22.dp })
        col.addRow(ctx.switchRow(R.drawable.ic_wifi, ctx.getString(R.string.set_wifi_keeper), ctx.getString(R.string.set_wifi_keeper_sub),
            com.abdllh.aura.system.WifiKeeper.enabled) { on ->
            com.abdllh.aura.system.WifiKeeper.enabled = on
            com.abdllh.aura.system.WifiKeeper.apply(ctx)
        }, 8)
        if (com.abdllh.aura.system.ZLinkGuard.available(ctx)) {
            col.addRow(ctx.switchRow(R.drawable.ic_smartphone, ctx.getString(R.string.set_zlink_off), ctx.getString(R.string.set_zlink_off_sub),
                com.abdllh.aura.system.ZLinkGuard.enabled) { on ->
                com.abdllh.aura.system.ZLinkGuard.enabled = on
                com.abdllh.aura.system.ZLinkGuard.apply(ctx)
            }, 8)
        }
        internet = InternetCard(ctx).also { col.addRow(it.view, 8) }
        col.addRow(ctx.hint(ctx.getString(R.string.net_hint)), 10)

        if (com.abdllh.aura.system.Power.available || com.abdllh.aura.BuildConfig.DEBUG) {
            col.addView(ctx.sectionTitle(ctx.getString(R.string.set_power)), lp(MATCH, WRAP).apply { topMargin = 22.dp })
            col.addRow(ctx.switchRow(R.drawable.ic_power, ctx.getString(R.string.set_full_off), ctx.getString(R.string.set_full_off_sub),
                com.abdllh.aura.system.Power.fullOffOnAccOff) { on ->
                com.abdllh.aura.system.Power.fullOffOnAccOff = on
                com.abdllh.aura.system.Power.apply(ctx)
            }, 8)
            col.addRow(ctx.settingRow(R.drawable.ic_power, ctx.getString(R.string.set_off_now), ctx.getString(R.string.set_off_now_sub),
                ctx.chevron(), Palette.danger) {
                com.abdllh.aura.ui.Confirm.show(act, ctx.getString(R.string.off_now_title), ctx.getString(R.string.off_now_text),
                    ctx.getString(R.string.off_now_action)) {
                    com.abdllh.aura.system.Power.powerOffNow(ctx) {
                        Toast.makeText(ctx, R.string.off_now_not_taken, Toast.LENGTH_LONG).show()
                    }
                }
            }, 8)
            col.addRow(ctx.hint(ctx.getString(R.string.set_power_hint)), 10)
        }

        col.addView(ctx.sectionTitle(ctx.getString(R.string.set_gear)), lp(MATCH, WRAP).apply { topMargin = 22.dp })
        col.addRow(ctx.settingRow(R.drawable.ic_car, ctx.getString(R.string.set_gear_row), ctx.getString(when {
            com.abdllh.aura.system.Gear.canReports -> R.string.set_gear_can
            com.abdllh.aura.system.CanGear.supported(ctx) -> R.string.set_gear_can_waiting
            else -> R.string.set_gear_basic
        })), 8)
        return col
    }

    private fun tile(ctx: Context, t: Tile): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = Shapes.clickableCard(20f)
        isClickable = true
        pressScale(0.96f)
        setOnClickListener {
            try { t.run(ctx) } catch (_: Throwable) { Toast.makeText(ctx, R.string.err_app_missing, Toast.LENGTH_SHORT).show() }
        }
        addView(ctx.iconView(t.icon, 36, Palette.text), lp(36.dp, 36.dp))
        addView(ctx.label(16f, Palette.text2, Fonts.MEDIUM, gravity = Gravity.CENTER).apply { setText(t.label) }, lp(WRAP, WRAP).apply { topMargin = 10.dp })
    }
}

// ---------------------------------------------------------------------------------------------- About
class AboutPage(private val act: SettingsActivity) : Page(R.string.set_about, R.drawable.ic_info) {
    override fun build(ctx: Context): View {
        val col = ctx.pageColumn()
        fun info(icon: Int, title: Int, value: String) = col.addRow(ctx.settingRow(icon, ctx.getString(title), null, ctx.valueText(value)), 8)

        // the logo: Arabic or English with the interface, white letters on dark, ink letters on light
        val arabic = ctx.resources.configuration.locales.get(0).language == "ar"
        val logo = when {
            arabic && Palette.dark -> R.drawable.logo_amri_ar
            arabic -> R.drawable.logo_amri_ar_ink
            Palette.dark -> R.drawable.logo_amri_en
            else -> R.drawable.logo_amri_en_ink
        }
        val brand = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24.dp, 30.dp, 24.dp, 24.dp)
            background = Shapes.card(22f)
        }
        brand.addView(android.widget.ImageView(ctx).apply {
            setImageResource(logo)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }, lp(if (arabic) 230.dp else 300.dp, WRAP))
        brand.addView(ctx.label(14.5f, Palette.text3, Fonts.REGULAR, gravity = Gravity.CENTER).apply {
            text = "Automotive Modern Responsive Interface"
            letterSpacing = 0.04f
        }, lp(MATCH, WRAP).apply { topMargin = 16.dp })
        col.addView(brand, lp(MATCH, WRAP))

        info(R.drawable.ic_car, R.string.about_device, Device.model)
        info(R.drawable.ic_cpu, R.string.about_android, "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        info(R.drawable.ic_layers, R.string.about_build, Device.buildId)
        info(R.drawable.ic_star, R.string.about_aura, "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        info(R.drawable.ic_shield, R.string.about_role, ctx.getString(
            when {
                Device.isSystemApp(ctx) && Device.isPrivileged(ctx) -> R.string.role_system
                Device.isSystemApp(ctx) -> R.string.role_system_basic
                else -> R.string.role_user
            }))

        // Home-app control (car unit only)
        if (Device.isNwd) {
            col.addView(ctx.sectionTitle(ctx.getString(R.string.about_home_app)), lp(MATCH, WRAP).apply { topMargin = 22.dp })
            val cur = Device.homePackage()
            val isAura = cur == ctx.packageName
            val btn = ABtn(ctx).apply {
                kind = if (isAura) ABtn.Kind.TONAL else ABtn.Kind.PRIMARY
                setText(if (isAura) R.string.about_use_stock else R.string.about_use_aura)
                setOnClickListener {
                    // also moves the boot-time kill switch, otherwise the next boot would undo the choice
                    val ok = CrashGuard.setAuraEnabled(ctx, !isAura, "user")
                    Toast.makeText(ctx, if (ok) R.string.about_home_changed else R.string.about_home_failed, Toast.LENGTH_LONG).show()
                    act.rebuild()
                }
            }
            col.addRow(ctx.settingRow(R.drawable.ic_home, ctx.getString(R.string.about_home_now), if (isAura) ctx.getString(R.string.app_name) else cur.ifEmpty { "-" }, btn), 8)
        }

        CrashGuard.lastCrash(ctx)?.let {
            col.addView(ctx.sectionTitle(ctx.getString(R.string.about_diag)), lp(MATCH, WRAP).apply { topMargin = 22.dp })
            col.addRow(ctx.settingRow(R.drawable.ic_alert, ctx.getString(R.string.about_last_crash), it.lineSequence().take(3).joinToString("\n"), null, Palette.warn), 8)
        }

        col.addRow(ctx.hint(ctx.getString(R.string.about_credit)), 22)
        return col
    }
}

/** Wraps a page view in a vertical scroller. */
fun Context.scrolled(content: View): ScrollView = ScrollView(this).apply {
    isVerticalScrollBarEnabled = false
    overScrollMode = View.OVER_SCROLL_NEVER
    isFillViewport = false
    addView(content, MATCH, WRAP)
}
