package com.abdllh.aura.settings

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.R
import com.abdllh.aura.home.AppRepo
import com.abdllh.aura.home.Known
import com.abdllh.aura.nav.MapExtras
import com.abdllh.aura.system.Controls
import com.abdllh.aura.system.CrashGuard
import com.abdllh.aura.system.Device
import com.abdllh.aura.ui.ABtn
import com.abdllh.aura.ui.AuraSlider
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Segmented
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.Snack
import com.abdllh.aura.ui.Space
import com.abdllh.aura.ui.Theme
import com.abdllh.aura.ui.Touch
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.iconView
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lp
import com.abdllh.aura.ui.pressScale
import com.abdllh.aura.util.Prefs
import com.abdllh.aura.util.dp

/**
 * A choice of the theme, the accent or the language: the control shows the choice landing first (the indicator
 * slides, the tick comes in), then the screen takes it ([SettingsActivity.restyle]). A newer choice made before then
 * replaces the one waiting. The choice itself is kept at the tap (the theme in Prefs, the accent and the language in
 * [SettingsActivity.pendingAccent] / [SettingsActivity.pendingLang]), so leaving the screen meanwhile does not lose it.
 */
private class Later(private val v: View) {
    private var r: Runnable? = null
    fun then(what: () -> Unit) {
        r?.let { v.removeCallbacks(it) }
        r = Runnable { r = null; what() }.also { v.postDelayed(it, Motion.MEDIUM) }
    }
}

/** A segmented choice for a settings card: equal widths, on the tonal track, a car-sized target. */
private fun Context.choices(labels: List<String>, selected: Int, onSelect: (Int) -> Unit): Segmented =
    Segmented(this, labels, equal = true, textSp = Type.BUTTON.sp, onSelect = onSelect).apply {
        trackColor = Palette.card2
        minimumHeight = Touch.MIN.dp
        select(selected, animate = false)
    }

// ---------------------------------------------------------------------------------------------- General
class GeneralPage(private val act: SettingsActivity) : Page(R.string.set_general, R.drawable.ic_sliders) {
    override fun build(ctx: Context): View {
        val col = ctx.pageColumn()
        col.addSection(ctx.getString(R.string.set_appearance))

        val modes = listOf(Theme.AUTO, Theme.LIGHT, Theme.DARK)
        val names = listOf(ctx.getString(R.string.theme_auto), ctx.getString(R.string.theme_light), ctx.getString(R.string.theme_dark))
        var themeLater: Later? = null
        val theme = ctx.choices(names, modes.indexOf(Prefs.theme).coerceAtLeast(0)) { i ->
            // (kept at once; the screen changes only when the choice changes what shows now: "auto" by day on the
            // light theme changes nothing)
            Prefs.theme = modes[i]
            themeLater?.then { act.restyle() }
        }
        themeLater = Later(theme)
        var accentLater: Later? = null
        val swatches = Swatches(ctx, Palette.accents, Prefs.accent) { c ->
            act.pendingAccent = c
            accentLater?.then { act.restyle() }
        }
        accentLater = Later(swatches)
        col.addRow(ctx.group(
            ctx.block(R.drawable.ic_contrast, ctx.getString(R.string.set_theme), ctx.getString(R.string.set_theme_sub), theme),
            ctx.block(R.drawable.ic_droplet, ctx.getString(R.string.set_accent), null, swatches)
        ))
        col.addRow(ctx.group(
            ctx.switchRow(R.drawable.ic_clock, ctx.getString(R.string.set_clock24), null, Prefs.clock24) { Prefs.clock24 = it },
            ctx.switchRow(R.drawable.ic_list, ctx.getString(R.string.set_dock_labels), null, Prefs.dockLabels) { Prefs.dockLabels = it }
        ))

        col.addSection(ctx.getString(R.string.set_language))
        val codes = listOf("system", "ar", "en")
        var langLater: Later? = null
        val lang = ctx.choices(listOf(ctx.getString(R.string.lang_system), "العربية", "English"), codes.indexOf(Prefs.lang).coerceAtLeast(0)) { i ->
            act.pendingLang = codes[i]
            langLater?.then { act.restyle() }
        }
        langLater = Later(lang)
        // (the Arabic name in the Arabic typeface, also in the English UI; its letters are drawn smaller than Latin
        // ones at one size, so it is a little larger to look the same size as "English" beside it)
        lang.item(1).apply { Fonts.arabic()?.let { typeface = it }; textSize = Type.BUTTON.sp + 2f }
        col.addRow(ctx.group(ctx.block(null, null, null, lang)))
        return col
    }
}

// ---------------------------------------------------------------------------------------------- Display & sound
class DisplaySoundPage(private val act: SettingsActivity) : Page(R.string.set_display_sound, R.drawable.ic_sun) {
    /** The page's sliders and their values, set again at each visit ([onShow]). */
    private var levels: (() -> Unit)? = null

    // The page is kept between visits, and the volume changes meanwhile (the wheel buttons, the volume display over
    // this screen), the brightness too (the home screen's controls). A drag moves from the value shown, so a stale
    // one would set the volume from it: 5 playing, 15 shown, the first move a sudden loud jump.
    override fun onShow() { levels?.invoke() }

    override fun build(ctx: Context): View {
        val col = ctx.pageColumn()

        val bv = ctx.valueText("")
        val bs = AuraSlider(ctx).apply {
            max = 100
            setIcon(R.drawable.ic_sun)
            onChange = { v, user ->
                bv.text = "$v%"
                if (user && !Controls.setBrightnessPercent(ctx, v)) {
                    Snack.show(ctx, R.string.err_write_settings, outlives = true)
                    Controls.requestWriteSettings(ctx)
                }
            }
        }
        // the volume as the car's own step (the number its buttons and the dock show), not a percentage
        val vv = ctx.valueText("")
        val vs = AuraSlider(ctx).apply {
            max = Controls.volumeMax(ctx)
            setIcon(R.drawable.ic_volume)
            onChange = { v, user ->
                if (user) Controls.setVolume(ctx, v)
                vv.text = "$v"
            }
        }
        // (not through onChange: nothing is sent to the car, only what it has is shown)
        levels = {
            bs.setProgress(Controls.brightnessPercent(ctx), false)
            bv.text = "${bs.progress}%"
            vs.setProgress(Controls.volume(ctx), false)
            vv.text = "${vs.progress}"
        }
        col.addRow(ctx.group(
            ctx.sliderRow(ctx.getString(R.string.ctl_brightness), bs, bv),
            ctx.sliderRow(ctx.getString(R.string.ctl_volume), vs, vv)
        ))
        col.addRow(ctx.group(
            ctx.switchRow(R.drawable.ic_steering, ctx.getString(R.string.set_volume_hud), ctx.getString(R.string.set_volume_hud_sub),
                com.abdllh.aura.system.VolumeHud.enabled) { on ->
                com.abdllh.aura.system.VolumeHud.enabled = on
                com.abdllh.aura.system.VolumeHud.apply(ctx) { ok ->
                    if (!ok) act.runOnUiThread { Snack.show(ctx, R.string.set_failed, long = true) }
                }
            },
            ctx.switchRow(R.drawable.ic_eye_off, ctx.getString(R.string.set_hide_status), ctx.getString(R.string.set_hide_status_sub), Prefs.hideStatusBar) {
                Prefs.hideStatusBar = it
                if (!com.abdllh.aura.system.SystemBars.apply(ctx)) Snack.show(ctx, R.string.set_failed)
            },
            ctx.switchRow(R.drawable.ic_layers, ctx.getString(R.string.set_screen_anim), ctx.getString(R.string.set_screen_anim_sub), Prefs.screenAnimations) {
                Prefs.screenAnimations = it
                if (!com.abdllh.aura.system.Animations.apply(ctx)) Snack.show(ctx, R.string.set_failed)
            },
            if (!com.abdllh.aura.system.AssistiveBall.available) null
            else ctx.switchRow(R.drawable.ic_layers, ctx.getString(R.string.set_ball), ctx.getString(R.string.set_ball_sub),
                com.abdllh.aura.system.AssistiveBall.shown(ctx)) { on -> com.abdllh.aura.system.AssistiveBall.set(ctx, on) }
        ))

        col.addSection(ctx.getString(R.string.set_music))
        col.addRow(ctx.group(
            ctx.switchRow(R.drawable.ic_steering, ctx.getString(R.string.set_music_keys), ctx.getString(R.string.set_music_keys_sub),
                com.abdllh.aura.system.MusicKeys.enabled) { on ->
                com.abdllh.aura.system.MusicKeys.enabled = on
                com.abdllh.aura.system.MusicKeys.applyRules() // (the firmware's key rules with or without the music keys)
            },
            ctx.switchRow(R.drawable.ic_disc, ctx.getString(R.string.set_bt_cover), ctx.getString(R.string.set_bt_cover_sub),
                com.abdllh.aura.music.CoverSearch.enabled) { on -> com.abdllh.aura.music.CoverSearch.enabled = on },
            if (!com.abdllh.aura.system.StockMusic.available(ctx)) null
            else ctx.switchRow(R.drawable.ic_music, ctx.getString(R.string.set_music_replace), ctx.getString(R.string.set_music_replace_sub),
                com.abdllh.aura.system.StockMusic.enabled) { on ->
                com.abdllh.aura.system.StockMusic.enabled = on
                com.abdllh.aura.system.StockMusic.apply(ctx) { ok ->
                    if (!ok) act.runOnUiThread { Snack.show(ctx, R.string.set_failed, long = true) }
                }
            }
        ))
        LyricsCard.addTo(act, ctx, col) // the owner's lyrics server and the timing of lyrics by listening (1.13)

        if (com.abdllh.aura.phone.Calls.available) {
            col.addSection(ctx.getString(R.string.set_calls))
            col.addRow(ctx.group(
                ctx.switchRow(R.drawable.ic_phone, ctx.getString(R.string.set_call_card), ctx.getString(R.string.set_call_card_sub),
                    com.abdllh.aura.phone.Calls.enabled) { on ->
                    com.abdllh.aura.phone.Calls.enabled = on
                    com.abdllh.aura.phone.Calls.apply(ctx) { ok ->
                        if (!ok) act.runOnUiThread { Snack.show(ctx, R.string.set_failed, long = true) }
                    }
                },
                ctx.switchRow(R.drawable.ic_volume, ctx.getString(R.string.set_call_ring), ctx.getString(R.string.set_call_ring_sub),
                    com.abdllh.aura.phone.Ringtone.enabled) { on ->
                    com.abdllh.aura.phone.Ringtone.enabled = on
                    com.abdllh.aura.phone.Ringtone.apply(ctx) { ok ->
                        if (!ok) act.runOnUiThread { Snack.show(ctx, R.string.set_failed, long = true) }
                    }
                },
                ctx.settingRow(R.drawable.ic_play, ctx.getString(R.string.set_call_ring_play), null, ctx.chevron()) {
                    com.abdllh.aura.phone.Ringtone.play(ctx)
                }
            ))
        }

        col.addSection(ctx.getString(R.string.set_shortcuts))
        val stock = AppRepo.isInstalled(ctx, Known.STOCK_LAUNCHER)
        col.addRow(ctx.group(
            if (!AppRepo.isInstalled(ctx, "com.nwd.audioset")) null
            else ctx.settingRow(R.drawable.ic_sliders, ctx.getString(R.string.set_equalizer), ctx.getString(R.string.set_equalizer_sub), ctx.chevron()) {
                if (!AppRepo.launch(ctx, "com.nwd.audioset")) Snack.show(ctx, R.string.err_app_missing)
            },
            if (!stock) null
            else ctx.settingRow(R.drawable.ic_power, ctx.getString(R.string.ctl_screen_off), null, ctx.chevron()) {
                com.abdllh.aura.home.Actions.screenOff(ctx)
            },
            if (!stock) null
            else ctx.settingRow(R.drawable.ic_monitor, ctx.getString(R.string.set_screensaver), null, ctx.chevron()) {
                AppRepo.launchComponent(ctx, Known.STOCK_LAUNCHER, Known.ACT_LOCK)
            },
            ctx.settingRow(R.drawable.ic_monitor, ctx.getString(R.string.set_android_display), null, ctx.chevron()) {
                try { ctx.startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) { }
            }
        ))
        return col
    }
}

// ---------------------------------------------------------------------------------------------- Navigation
class NavigationPage(private val act: SettingsActivity) : Page(R.string.set_navigation, R.drawable.ic_nav) {
    private var offline: OfflineCard? = null
    private var extras: ExtrasCard? = null
    /** The count of the cameras marked from the car, kept current while the page shows (they change on the map). */
    private var myCameras: (() -> Unit)? = null

    override fun onShow() {
        offline?.attach(); extras?.attach()
        myCameras?.let { MapExtras.removeListener(it); MapExtras.addListener(it); it() }
    }

    override fun onHide() {
        offline?.detach(); extras?.detach()
        myCameras?.let { MapExtras.removeListener(it) }
    }

    override fun build(ctx: Context): View {
        val col = ctx.pageColumn()
        col.addSection(ctx.getString(R.string.set_nav_app))

        // Aura Maps first, then the installed navigation apps; the chosen one has a tick (a tap on it changes nothing)
        val builtIn = Prefs.builtInMaps
        val rows = ArrayList<View>()
        rows.add(navRow(ctx, R.drawable.ic_compass, ctx.getString(R.string.set_maps_builtin), ctx.getString(R.string.set_maps_builtin_sub), builtIn) {
            Prefs.builtInMaps = true
        })
        val choices = ArrayList<Pair<String, String>>() // pkg ("" = automatic) -> label
        choices.add("" to ctx.getString(R.string.set_nav_auto))
        for (a in AppRepo.installedNavApps(ctx)) choices.add(a.pkg to a.label)
        val selected = Prefs.navPackage
        for ((pkg, name) in choices) {
            val on = !builtIn && (pkg == selected || (pkg.isEmpty() && selected.isEmpty()))
            // (the firmware's navigation-app property, written again by a tap on the chosen app: something may have
            // reset it since)
            val push: () -> Unit = { if (pkg.isNotEmpty()) com.abdllh.aura.system.SystemProps.set("persist.sys.navi_set_by_app", pkg) }
            rows.add(navRow(ctx, R.drawable.ic_nav, name, if (pkg.isEmpty()) ctx.getString(R.string.set_nav_auto_sub) else null, on, again = push) {
                Prefs.builtInMaps = false
                Prefs.navPackage = pkg
                push()
            })
        }
        col.addRow(ctx.group(*rows.toTypedArray()))
        // Google's own map, roads and shops, without internet: only its app can keep them (how, in one place)
        if (AppRepo.isInstalled(ctx, Known.MAPS)) col.addHint(ctx.getString(R.string.set_nav_google_hint))

        col.addSection(ctx.getString(R.string.set_places))
        col.addRow(ctx.group(placeRow(ctx, R.drawable.ic_home, R.string.home_home, "home"), placeRow(ctx, R.drawable.ic_briefcase, R.string.home_work, "work")))
        col.addHint(ctx.getString(R.string.set_places_hint))

        col.addSection(ctx.getString(R.string.off_section))
        val card = OfflineCard(act, ctx).also { offline = it }
        col.addRow(ctx.group(card.view))
        col.addHint(ctx.getString(R.string.off_hint))
        card.attach()

        // what the owner added to the map: cameras, bumps, signals, streets' limits
        val more = ExtrasCard(ctx).also { extras = it }
        col.addRow(ctx.group(more.view))
        col.addHint(ctx.getString(R.string.extras_hint))
        more.attach()

        // "a camera here": the button on the maps, and the cameras marked with it
        val count = ctx.valueText("")
        val refresh = {
            val n = MapExtras.mine.size
            count.text = if (n == 0) ctx.getString(R.string.set_my_cameras_none) else ctx.getString(R.string.set_my_cameras, "$n")
        }
        refresh()
        myCameras = refresh
        col.addRow(ctx.group(
            ctx.switchRow(R.drawable.ic_speed_camera, ctx.getString(R.string.set_mark_camera), ctx.getString(R.string.set_mark_camera_sub),
                com.abdllh.aura.nav.CameraMark.enabled) { on -> com.abdllh.aura.nav.CameraMark.enabled = on },
            ctx.settingRow(R.drawable.ic_trash, ctx.getString(R.string.set_my_cameras_clear), null, count) {
                if (MapExtras.mine.isEmpty()) return@settingRow
                com.abdllh.aura.ui.Confirm.show(ctx, ctx.getString(R.string.set_my_cameras_clear_title), ctx.getString(R.string.set_my_cameras_clear_text),
                    ctx.getString(R.string.set_my_cameras_clear)) {
                    MapExtras.clearMine()
                    refresh()
                }
            }
        ))
        return col
    }

    /**
     * A navigation app to choose; the chosen one is ticked, in the accent. A tap on the ticked one changes nothing on
     * the page; it only runs [again] (the firmware's own setting written once more, as every tap did until 1.12).
     */
    private fun navRow(ctx: Context, icon: Int, name: String, sub: String?, on: Boolean, again: (() -> Unit)? = null, choose: () -> Unit): View =
        ctx.settingRow(icon, name, sub, if (on) ctx.iconView(R.drawable.ic_check_circle, 26, Palette.accentFg) else null,
            if (on) Palette.accentFg else Palette.text2) {
            if (on) { again?.invoke(); return@settingRow }
            choose()
            act.rebuild()
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

    // (the gear's source and what the CAN box sent change while the car drives)
    override val keep: Boolean get() = false
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
                col.addView(row, lp(MATCH, WRAP).apply { if (i > 0) topMargin = Space.M.dp })
            }
            row!!.addView(tile(ctx, t), lp(0, 120.dp, 1f).apply { if (i % 3 != 0) marginStart = Space.M.dp })
        }
        // pad the last row so tiles keep their width
        val rem = tiles.size % 3
        if (rem != 0) for (k in 0 until 3 - rem) row!!.addView(View(ctx), lp(0, 120.dp, 1f).apply { marginStart = Space.M.dp })
        col.addHint(ctx.getString(R.string.v_hint))

        col.addSection(ctx.getString(R.string.set_connections))
        internet = InternetCard(ctx)
        col.addRow(ctx.group(
            ctx.switchRow(R.drawable.ic_wifi, ctx.getString(R.string.set_wifi_keeper), ctx.getString(R.string.set_wifi_keeper_sub),
                com.abdllh.aura.system.WifiKeeper.enabled) { on ->
                com.abdllh.aura.system.WifiKeeper.enabled = on
                com.abdllh.aura.system.WifiKeeper.apply(ctx)
            },
            ctx.switchRow(R.drawable.ic_wifi, ctx.getString(R.string.set_wifi_on_start), ctx.getString(R.string.set_wifi_on_start_sub),
                com.abdllh.aura.system.WifiOnStart.enabled) { on ->
                com.abdllh.aura.system.WifiOnStart.enabled = on
                if (on) com.abdllh.aura.system.WifiOnStart.ensure("switch")
            },
            if (!com.abdllh.aura.system.ZLinkGuard.available(ctx)) null
            else ctx.switchRow(R.drawable.ic_smartphone, ctx.getString(R.string.set_zlink_off), ctx.getString(R.string.set_zlink_off_sub),
                com.abdllh.aura.system.ZLinkGuard.enabled) { on ->
                com.abdllh.aura.system.ZLinkGuard.enabled = on
                com.abdllh.aura.system.ZLinkGuard.apply(ctx)
            }
        ))
        col.addRow(ctx.group(internet!!.view))
        col.addHint(ctx.getString(R.string.net_hint))

        if (com.abdllh.aura.system.Power.available || BuildConfig.DEBUG) {
            col.addSection(ctx.getString(R.string.set_power))
            col.addRow(ctx.group(
                ctx.switchRow(R.drawable.ic_power, ctx.getString(R.string.set_full_off), ctx.getString(R.string.set_full_off_sub),
                    com.abdllh.aura.system.Power.fullOffOnAccOff) { on ->
                    com.abdllh.aura.system.Power.fullOffOnAccOff = on
                    com.abdllh.aura.system.Power.apply(ctx)
                },
                ctx.settingRow(R.drawable.ic_power, ctx.getString(R.string.set_off_now), ctx.getString(R.string.set_off_now_sub),
                    ctx.chevron(), Palette.danger) {
                    com.abdllh.aura.ui.Confirm.show(act, ctx.getString(R.string.off_now_title), ctx.getString(R.string.off_now_text),
                        ctx.getString(R.string.off_now_action)) {
                        com.abdllh.aura.system.Power.powerOffNow(ctx) {
                            Snack.show(ctx, R.string.off_now_not_taken, long = true)
                        }
                    }
                }
            ))
            col.addHint(ctx.getString(R.string.set_power_hint))
        }

        col.addSection(ctx.getString(R.string.set_gear))
        // which CAN box the CAN app is set up for, and whether a gear came from it (P and N need one)
        val box = com.abdllh.aura.system.CanGear.boxInfo(ctx)?.let { box ->
            val sent = when {
                com.abdllh.aura.system.CanGear.raw >= 0 -> ctx.getString(R.string.set_gear_box_yes, com.abdllh.aura.system.CanGear.raw)
                com.abdllh.aura.system.CanGear.messages > 0 -> ctx.getString(R.string.set_gear_box_no)
                else -> ctx.getString(R.string.set_gear_box_silent)
            }
            ctx.settingRow(R.drawable.ic_info, ctx.getString(R.string.set_gear_box), ctx.getString(R.string.set_gear_box_sub, box, sent))
        }
        col.addRow(ctx.group(
            ctx.settingRow(R.drawable.ic_car, ctx.getString(R.string.set_gear_row), ctx.getString(when {
                com.abdllh.aura.system.Gear.canReports -> R.string.set_gear_can
                com.abdllh.aura.system.CanGear.supported(ctx) -> R.string.set_gear_can_waiting
                else -> R.string.set_gear_basic
            })),
            box
        ))
        return col
    }

    private fun tile(ctx: Context, t: Tile): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = Shapes.clickableCard()
        isClickable = true
        pressScale(Motion.PRESS_CONTROL)
        setOnClickListener {
            try { t.run(ctx) } catch (_: Throwable) { Snack.show(ctx, R.string.err_app_missing) }
        }
        addView(ctx.iconView(t.icon, 34, Palette.text), lp(34.dp, 34.dp))
        addView(ctx.label(Type.BUTTON, Palette.text2, gravity = Gravity.CENTER).apply { setText(t.label) }, lp(WRAP, WRAP).apply { topMargin = Space.M.dp })
    }
}

// ---------------------------------------------------------------------------------------------- About
class AboutPage(private val act: SettingsActivity) : Page(R.string.set_about, R.drawable.ic_info) {
    // (the notes from the unit and the last crash change while the screen is up)
    override val keep: Boolean get() = false

    override fun build(ctx: Context): View {
        val col = ctx.pageColumn()
        val rows = ArrayList<View>()
        // A short value at the row's end; a long one (the notes from the unit) under the title, where it has the
        // row's width and three lines, instead of squeezing the title to "…" and being cut itself.
        fun info(icon: Int, title: Int, value: String) {
            rows.add(if (value.length > 28) ctx.settingRow(icon, ctx.getString(title), value)
                else ctx.settingRow(icon, ctx.getString(title), null, ctx.valueText(value)))
        }

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
            setPadding(Space.XL.dp, Space.XXL.dp, Space.XL.dp, Space.XL.dp)
            background = Shapes.card()
        }
        brand.addView(android.widget.ImageView(ctx).apply {
            setImageResource(logo)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }, lp(if (arabic) 230.dp else 300.dp, WRAP))
        brand.addView(ctx.label(Type.FOOTNOTE, Palette.text3, gravity = Gravity.CENTER).apply {
            text = "Automotive Modern Responsive Interface"
            letterSpacing = 0.04f
        }, lp(MATCH, WRAP).apply { topMargin = Space.L.dp })
        col.addRow(brand)

        info(R.drawable.ic_car, R.string.about_device, Device.model)
        info(R.drawable.ic_cpu, R.string.about_android, "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        info(R.drawable.ic_layers, R.string.about_build, Device.buildId)
        info(R.drawable.ic_star, R.string.about_aura, "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        // how the 3D car is drawn, and how fast it turned last time (for reports from the unit)
        val fps = com.abdllh.aura.ui.CarStage.lastFps
        info(R.drawable.ic_car, R.string.about_car_view, ctx.getString(
            if (com.abdllh.aura.ui.CarStage.gpuDrawing) R.string.about_car_gpu else R.string.about_car_cpu) +
            if (fps > 0f) " · ${Math.round(fps)} fps" else "")
        info(R.drawable.ic_shield, R.string.about_role, ctx.getString(
            when {
                Device.isSystemApp(ctx) && Device.isPrivileged(ctx) -> R.string.role_system
                Device.isSystemApp(ctx) -> R.string.role_system_basic
                else -> R.string.role_user
            }))
        // the last stock screen the home screen took the place of as the unit came on (for reports from the unit)
        com.abdllh.aura.system.HomeGuard.note.takeIf { it.isNotEmpty() }?.let { info(R.drawable.ic_home, R.string.about_covered, it) }
        // how the music button last reached AMRI: its own key (no stock screen), or a stock screen that had to be covered
        com.abdllh.aura.system.MusicKeys.note.takeIf { it.isNotEmpty() }?.let { info(R.drawable.ic_steering, R.string.about_music_key, it) }
        // the last Bluetooth song's lookup: where its cover and length came from, or why nothing came
        com.abdllh.aura.music.CoverSearch.note.takeIf { it.isNotEmpty() }?.let { info(R.drawable.ic_disc, R.string.about_cover, it) }
        // how the last song handed to YouTube's app went (it could not be tried before the unit)
        com.abdllh.aura.music.YtMusic.note.takeIf { it.isNotEmpty() }?.let { info(R.drawable.ic_music, R.string.about_yt, it) }
        col.addRow(ctx.group(*rows.toTypedArray()))

        // Home-app control (car unit only)
        if (Device.isNwd) {
            col.addSection(ctx.getString(R.string.about_home_app))
            val cur = Device.homePackage()
            val isAura = cur == ctx.packageName
            val btn = ABtn(ctx).apply {
                kind = if (isAura) ABtn.Kind.TONAL else ABtn.Kind.PRIMARY
                setText(if (isAura) R.string.about_use_stock else R.string.about_use_aura)
                setOnClickListener {
                    // also moves the boot-time kill switch, otherwise the next boot would undo the choice; the message
                    // outlives this screen (the home screen changes under it)
                    val ok = CrashGuard.setAuraEnabled(ctx, !isAura, "user")
                    Snack.show(ctx, if (ok) R.string.about_home_changed else R.string.about_home_failed, long = true, outlives = true)
                    act.rebuild()
                }
            }
            col.addRow(ctx.group(ctx.settingRow(R.drawable.ic_home, ctx.getString(R.string.about_home_now), if (isAura) ctx.getString(R.string.app_name) else cur.ifEmpty { "-" }, btn)))
        }

        CrashGuard.lastCrash(ctx)?.let {
            col.addSection(ctx.getString(R.string.about_diag))
            col.addRow(ctx.group(ctx.settingRow(R.drawable.ic_alert, ctx.getString(R.string.about_last_crash), it.lineSequence().take(3).joinToString("\n"), null, Palette.warn)))
        }

        col.addView(ctx.hint(ctx.getString(R.string.about_credit)), lp(MATCH, WRAP).apply { topMargin = Space.XXL.dp })
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

