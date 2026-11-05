package com.abdllh.aura.settings

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.R
import com.abdllh.aura.home.AppRepo
import com.abdllh.aura.home.Known
import com.abdllh.aura.nav.MapExtras
import com.abdllh.aura.phone.MessageBanner
import com.abdllh.aura.system.CrashGuard
import com.abdllh.aura.system.Device
import com.abdllh.aura.system.Notices
import com.abdllh.aura.ui.ABtn
import com.abdllh.aura.ui.AuraSlider
import com.abdllh.aura.ui.Confirm
import com.abdllh.aura.ui.Menu
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Segmented
import com.abdllh.aura.ui.Snack
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.iconView
import com.abdllh.aura.ui.lp
import com.abdllh.aura.voice.Amri
import com.abdllh.aura.voice.OfflineCommands
import com.abdllh.aura.voice.VoiceSetup

/**
 * الورشة, a plain row of Car and system (SPEC.md section 6.6, 5.D): what the seven pages no longer show, with its pref and what it does untouched: the way back to stock first, because it must stay
 * reachable; then every switch of 1.x that the design keeps out of the owner's way; then what is only read, the gear's source, the notes from the unit, the last crash. Reports from the unit are the
 * only test there is, so all of this stays. Nothing about spoken navigation.
 */
class WorkshopPage(private val act: SettingsPanel) : Page(R.string.st_workshop) {
    override val keep: Boolean get() = false // (the notes, the gear's source: read when it is built)
    private var internet: InternetCard? = null
    private var extras: ExtrasCard? = null
    private var myCameras: (() -> Unit)? = null
    private var showDelay: (() -> Unit)? = null

    override fun onShow() {
        internet?.attach()
        extras?.attach()
        myCameras?.let { MapExtras.removeListener(it); MapExtras.addListener(it); it() }
        showDelay?.invoke()
    }

    override fun onHide() {
        internet?.detach()
        extras?.detach()
        myCameras?.let { MapExtras.removeListener(it) }
    }

    override fun build(ctx: Context): View {
        val col = ctx.pageColumn()
        // ---- the way back
        if (Device.isNwd) {
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
            col.addPart(ctx.getString(R.string.about_home_app))
            col.addView(ctx.rows(ctx.settingRow(null, ctx.getString(R.string.about_home_now), if (isAura) ctx.getString(R.string.app_name) else cur.ifEmpty { "-" }, btn)))
        }
        WaybackCard.addTo(col, ctx, act) // the way back to the previous version, and what the guard has seen

        amri(col, ctx)
        music(col, ctx)
        calls(col, ctx)
        screen(col, ctx)
        maps(col, ctx)
        net(col, ctx)
        power(col, ctx)
        gear(col, ctx)
        notes(col, ctx)
        return col
    }

    private fun amri(col: LinearLayout, ctx: Context) {
        col.addPart(ctx.getString(R.string.amri_name))
        val enrolled = VoiceSetup.enrolled(ctx)
        val sens = Segmented(ctx, listOf(ctx.getString(R.string.voice_sens_strict), ctx.getString(R.string.voice_sens_normal),
            ctx.getString(R.string.voice_sens_relaxed)), equal = true, maxW = act.controlW) { i -> Amri.sensitivity = i }.apply { select(Amri.sensitivity, animate = false) }
        // the sounds it learned not to take for its name (a wake not meant: nothing asked, tapped away), forgettable
        val learned = Amri.learnedSounds(ctx)
        val learnedRow = ctx.settingRow(null, ctx.getString(R.string.voice_learned),
            if (learned == 0) ctx.getString(R.string.voice_learned_none)
            else ctx.resources.getQuantityString(R.plurals.voice_learned_some, learned, learned),
            if (learned == 0) null else ctx.chevron(), onClick = if (learned == 0) null else { {
                ctx.parked {
                    Confirm.show(ctx, ctx.getString(R.string.voice_learned_forget_title), ctx.getString(R.string.voice_learned_forget_text),
                        ctx.getString(R.string.voice_learned_forget)) { Amri.forgetLearnedSounds(ctx); act.rebuild() }
                }
            } })
        // the microphone: the system's choice or one of the inputs, picked from a menu (the one in use ticked)
        val inputs = try {
            (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getDevices(AudioManager.GET_DEVICES_INPUTS)
                .filter { it.type != AudioDeviceInfo.TYPE_TELEPHONY && it.type != AudioDeviceInfo.TYPE_REMOTE_SUBMIX }
        } catch (_: Throwable) { emptyList() }
        val choices = listOf(0 to ctx.getString(R.string.voice_mic_auto)) + inputs.map { it.id to VoiceSetup.name(ctx, it) }
        val now = choices.indexOfFirst { it.first == Amri.micId }.coerceAtLeast(0)
        val mic = ctx.valueChevron(choices[now].second)
        col.addView(ctx.rows(
            ctx.controlRow(ctx.getString(R.string.voice_sens), sens, act.controlW - NARROWER),
            if (enrolled) learnedRow else null,
            ctx.settingRow(null, ctx.getString(R.string.voice_mic), null, mic) {
                Menu.show(mic, choices.map { (id, name) ->
                    Menu.Item(if (id == Amri.micId) R.drawable.ic_check else R.drawable.ic_mic, name) {
                        if (id != Amri.micId) { Amri.micId = id; act.rebuild() }
                    }
                })
            },
            ctx.settingRow(null, ctx.getString(R.string.voice_try), ctx.getString(R.string.voice_try_sub), ctx.chevron()) { act.withMic { Amri.wake(ctx) } },
            ctx.settingRow(null, ctx.getString(R.string.voice_offline_title), null, ctx.chevron()) { act.open(OfflineRequestsPage(act)) }
        ))
    }

    private fun music(col: LinearLayout, ctx: Context) {
        col.addPart(ctx.getString(R.string.set_music))
        // (1.18) how late the phone's sound comes out of the speakers: the lyrics of Bluetooth songs follow what is heard
        val bt = com.abdllh.aura.music.BtMusic
        val step = bt.DELAY_STEP_MS
        val dv = ctx.valueText("").apply { gravity = Gravity.CENTER; layoutParams = lp(DELAY_W, Type.rowValue.viewH) }
        val ds = AuraSlider(ctx).apply {
            max = (bt.DELAY_MAX_MS / step).toInt()
            onChange = { v, user ->
                dv.text = ctx.getString(R.string.set_bt_delay_value, com.abdllh.aura.music.MusicActivity.delaySeconds(v * step))
                if (user) bt.delayMs = v * step
            }
        }
        // (shown anew at each visit: a line held on the music screen measures it meanwhile)
        showDelay = {
            ds.setProgress(((bt.delayMs + step / 2) / step).toInt(), false)
            dv.text = ctx.getString(R.string.set_bt_delay_value, com.abdllh.aura.music.MusicActivity.delaySeconds(bt.delayMs))
        }
        val lyrics = LyricsCard(act, ctx)
        col.addView(ctx.rows(
            ctx.switchRow(null, ctx.getString(R.string.set_bt_cover), ctx.getString(R.string.set_bt_cover_sub),
                com.abdllh.aura.music.CoverSearch.enabled) { on -> com.abdllh.aura.music.CoverSearch.enabled = on },
            ctx.controlRow(ctx.getString(R.string.set_bt_delay), ctx.sliderControl(ds, start = dv), act.controlW - NARROWER),
            ctx.switchRow(null, ctx.getString(R.string.set_music_keys), ctx.getString(R.string.set_music_keys_sub),
                com.abdllh.aura.system.MusicKeys.enabled) { on ->
                com.abdllh.aura.system.MusicKeys.enabled = on
                com.abdllh.aura.system.MusicKeys.applyRules() // (the firmware's key rules with or without the music keys)
            },
            if (!com.abdllh.aura.system.StockMusic.available(ctx)) null
            else ctx.switchRow(null, ctx.getString(R.string.set_music_replace), ctx.getString(R.string.set_music_replace_sub),
                com.abdllh.aura.system.StockMusic.enabled) { on ->
                com.abdllh.aura.system.StockMusic.enabled = on
                com.abdllh.aura.system.StockMusic.apply(ctx) { ok ->
                    if (!ok) act.runOnUiThread { Snack.show(ctx, R.string.set_failed, long = true) }
                }
            },
            lyrics.addressRow,
            lyrics.testRow
        ))
        col.addNote(ctx.getString(R.string.set_bt_delay_hint))
    }

    private fun calls(col: LinearLayout, ctx: Context) {
        col.addPart(ctx.getString(R.string.set_notices))
        col.addView(ctx.rows(
            if (!com.abdllh.aura.phone.Calls.available) null
            else ctx.switchRow(null, ctx.getString(R.string.set_call_card), ctx.getString(R.string.set_call_card_sub),
                com.abdllh.aura.phone.Calls.enabled) { on ->
                com.abdllh.aura.phone.Calls.enabled = on
                com.abdllh.aura.phone.Calls.apply(ctx) { ok ->
                    if (!ok) act.runOnUiThread { Snack.show(ctx, R.string.set_failed, long = true) }
                }
            },
            if (!com.abdllh.aura.phone.Calls.available) null
            else ctx.settingRow(null, ctx.getString(R.string.set_call_ring_play), null, ctx.chevron()) { com.abdllh.aura.phone.Ringtone.play(ctx) },
            ctx.switchRow(null, ctx.getString(R.string.set_notices_hide), ctx.getString(R.string.set_notices_hide_sub),
                Notices.hideAndroid) { on -> Notices.hideAndroid = on; Notices.apply(ctx) },
            ctx.settingRow(null, ctx.getString(R.string.set_notices_try), null, ctx.chevron()) { MessageBanner.preview(ctx) }
        ))
        col.addNote(ctx.getString(R.string.set_notices_whatsapp))
    }

    private fun screen(col: LinearLayout, ctx: Context) {
        col.addPart(ctx.getString(R.string.st_display))
        val stock = AppRepo.isInstalled(ctx, Known.STOCK_LAUNCHER)
        col.addView(ctx.rows(
            ctx.switchRow(null, ctx.getString(R.string.st_tick), ctx.getString(R.string.st_tick_sub), com.abdllh.aura.util.Prefs.pressTick) { com.abdllh.aura.util.Prefs.pressTick = it },
            ctx.switchRow(null, ctx.getString(R.string.set_volume_hud), ctx.getString(R.string.set_volume_hud_sub),
                com.abdllh.aura.system.VolumeHud.enabled) { on ->
                com.abdllh.aura.system.VolumeHud.enabled = on
                com.abdllh.aura.system.VolumeHud.apply(ctx) { ok ->
                    if (!ok) act.runOnUiThread { Snack.show(ctx, R.string.set_failed, long = true) }
                }
            },
            ctx.switchRow(null, ctx.getString(R.string.set_hide_status), ctx.getString(R.string.set_hide_status_sub), com.abdllh.aura.util.Prefs.hideStatusBar) {
                com.abdllh.aura.util.Prefs.hideStatusBar = it
                if (!com.abdllh.aura.system.SystemBars.apply(ctx)) Snack.show(ctx, R.string.set_failed)
            },
            ctx.switchRow(null, ctx.getString(R.string.set_screen_anim), ctx.getString(R.string.set_screen_anim_sub), com.abdllh.aura.util.Prefs.screenAnimations) {
                com.abdllh.aura.util.Prefs.screenAnimations = it
                if (!com.abdllh.aura.system.Animations.apply(ctx)) Snack.show(ctx, R.string.set_failed)
            },
            if (!com.abdllh.aura.system.AssistiveBall.available) null
            else ctx.switchRow(null, ctx.getString(R.string.set_ball), ctx.getString(R.string.set_ball_sub),
                com.abdllh.aura.system.AssistiveBall.shown(ctx)) { on -> com.abdllh.aura.system.AssistiveBall.set(ctx, on) },
            if (!AppRepo.isInstalled(ctx, "com.nwd.audioset")) null
            else ctx.settingRow(null, ctx.getString(R.string.set_equalizer), ctx.getString(R.string.set_equalizer_sub), ctx.chevron()) {
                if (!AppRepo.launch(ctx, "com.nwd.audioset")) Snack.show(ctx, R.string.err_app_missing)
            },
            if (!stock) null
            else ctx.settingRow(null, ctx.getString(R.string.set_screensaver), null, ctx.chevron()) { AppRepo.launchComponent(ctx, Known.STOCK_LAUNCHER, Known.ACT_LOCK) },
            ctx.settingRow(null, ctx.getString(R.string.set_android_display), null, ctx.chevron()) {
                try { ctx.startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) { }
            }
        ))
    }

    private fun maps(col: LinearLayout, ctx: Context) {
        col.addPart(ctx.getString(R.string.st_maps))
        // what the owner added to the map: cameras, bumps, signals, streets' limits
        val more = ExtrasCard(ctx).also { extras = it }
        // "a camera here": the button on the maps, and the cameras marked with it
        val count = ctx.valueText("")
        val refresh = {
            val n = MapExtras.mine.size
            count.text = if (n == 0) ctx.getString(R.string.set_my_cameras_none) else ctx.getString(R.string.set_my_cameras, "$n")
        }
        refresh()
        myCameras = refresh
        col.addView(ctx.rows(
            more.view,
            ctx.switchRow(null, ctx.getString(R.string.set_mark_camera), ctx.getString(R.string.set_mark_camera_sub),
                com.abdllh.aura.nav.CameraMark.enabled) { on -> com.abdllh.aura.nav.CameraMark.enabled = on },
            ctx.settingRow(null, ctx.getString(R.string.set_my_cameras_clear), null, count) {
                if (MapExtras.mine.isEmpty()) return@settingRow
                ctx.parked {
                    Confirm.show(ctx, ctx.getString(R.string.set_my_cameras_clear_title), ctx.getString(R.string.set_my_cameras_clear_text),
                        ctx.getString(R.string.set_my_cameras_clear)) {
                        MapExtras.clearMine()
                        refresh()
                    }
                }
            }
        ))
        col.addNote(ctx.getString(R.string.extras_hint))
    }

    private fun net(col: LinearLayout, ctx: Context) {
        col.addPart(ctx.getString(R.string.set_connections))
        internet = InternetCard(ctx)
        col.addView(ctx.rows(
            ctx.switchRow(null, ctx.getString(R.string.set_wifi_keeper), ctx.getString(R.string.set_wifi_keeper_sub),
                com.abdllh.aura.system.WifiKeeper.enabled) { on ->
                com.abdllh.aura.system.WifiKeeper.enabled = on
                com.abdllh.aura.system.WifiKeeper.apply(ctx)
            },
            ctx.switchRow(null, ctx.getString(R.string.set_wifi_on_start), ctx.getString(R.string.set_wifi_on_start_sub),
                com.abdllh.aura.system.WifiOnStart.enabled) { on ->
                com.abdllh.aura.system.WifiOnStart.enabled = on
                if (on) com.abdllh.aura.system.WifiOnStart.ensure("switch")
            },
            if (!com.abdllh.aura.system.ZLinkGuard.available(ctx)) null
            else ctx.switchRow(null, ctx.getString(R.string.set_zlink_off), ctx.getString(R.string.set_zlink_off_sub),
                com.abdllh.aura.system.ZLinkGuard.enabled) { on ->
                com.abdllh.aura.system.ZLinkGuard.enabled = on
                com.abdllh.aura.system.ZLinkGuard.apply(ctx)
            },
            ctx.settingRow(null, ctx.getString(R.string.v_wifi), null, ctx.chevron()) {
                try { ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) { }
            },
            internet!!.view
        ))
        col.addNote(ctx.getString(R.string.net_hint))
    }

    private fun power(col: LinearLayout, ctx: Context) {
        val stock = AppRepo.isInstalled(ctx, Known.STOCK_LAUNCHER)
        val can = com.abdllh.aura.system.Power.available || BuildConfig.DEBUG
        if (!can && !stock) return
        col.addPart(ctx.getString(R.string.set_power))
        col.addView(ctx.rows(
            if (!can) null
            else ctx.settingRow(null, ctx.getString(R.string.set_off_now), ctx.getString(R.string.set_off_now_sub), ctx.chevron()) {
                ctx.parked {
                    Confirm.show(act, ctx.getString(R.string.off_now_title), ctx.getString(R.string.off_now_text), ctx.getString(R.string.off_now_action)) {
                        com.abdllh.aura.system.Power.powerOffNow(ctx) { Snack.show(ctx, R.string.off_now_not_taken, long = true) }
                    }
                }
            },
            if (!stock) null
            else ctx.settingRow(null, ctx.getString(R.string.v_restart), null, ctx.chevron()) { AppRepo.launchComponent(ctx, Known.STOCK_LAUNCHER, Known.ACT_REBOOT) }
        ))
    }

    private fun gear(col: LinearLayout, ctx: Context) {
        col.addPart(ctx.getString(R.string.set_gear))
        // which CAN box the CAN app is set up for, and whether a gear came from it (P and N need one)
        val box = com.abdllh.aura.system.CanGear.boxInfo(ctx)?.let { box ->
            val sent = when {
                com.abdllh.aura.system.CanGear.raw >= 0 -> ctx.getString(R.string.set_gear_box_yes, com.abdllh.aura.system.CanGear.raw)
                com.abdllh.aura.system.CanGear.messages > 0 -> ctx.getString(R.string.set_gear_box_no)
                else -> ctx.getString(R.string.set_gear_box_silent)
            }
            ctx.settingRow(null, ctx.getString(R.string.set_gear_box), ctx.getString(R.string.set_gear_box_sub, box, sent))
        }
        // the box's parser and version: one photo of this page tells whether a gear can ever come from the CAN box
        val manager = com.abdllh.aura.system.CanGear.manager(ctx)
        val parser = manager?.let { m ->
            val can = when (com.abdllh.aura.system.CanGear.gearPossible(m)) {
                true -> ctx.getString(R.string.set_gear_parser_maybe)
                false -> ctx.getString(R.string.set_gear_parser_never)
                null -> null
            }
            val version = com.abdllh.aura.system.CanGear.boxVersion(ctx)
            ctx.settingRow(null, ctx.getString(R.string.set_gear_parser),
                listOfNotNull("⁨$m⁩", version?.let { "⁨$it⁩" }, can).joinToString("  ·  "))
        }
        // the handbrake wire, as the MCU reports it, and whether it is believed for P
        val hb = com.abdllh.aura.system.Gear.handbrakeState
        val hbState = ctx.getString(when (hb) { null -> R.string.set_gear_hb_never; true -> R.string.set_gear_hb_pulled; false -> R.string.set_gear_hb_released })
        val hbUse = when {
            hb == null -> null
            com.abdllh.aura.system.Gear.handbrakeTrusted -> ctx.getString(R.string.set_gear_hb_used)
            com.abdllh.aura.system.Gear.handbrakeFake -> ctx.getString(R.string.set_gear_hb_fake)
            else -> ctx.getString(R.string.set_gear_hb_one_way)
        }
        col.addView(ctx.rows(
            ctx.settingRow(null, ctx.getString(R.string.set_gear_row), ctx.getString(when {
                com.abdllh.aura.system.Gear.canReports -> R.string.set_gear_can
                com.abdllh.aura.system.CanGear.supported(ctx) -> R.string.set_gear_can_waiting
                else -> R.string.set_gear_basic
            })),
            box,
            parser,
            ctx.settingRow(null, ctx.getString(R.string.set_gear_hb), listOfNotNull(hbState, hbUse).joinToString("  ·  "))
        ))
    }

    private fun notes(col: LinearLayout, ctx: Context) {
        col.addPart(ctx.getString(R.string.about_diag))
        val rows = ArrayList<View?>()
        // A short value at the row's end; a long one (the notes from the unit) under the title, where it has the
        // row's width and three lines, instead of squeezing the title to "…" and being cut itself.
        fun info(title: Int, value: String) {
            rows.add(if (value.length > 28) ctx.settingRow(null, ctx.getString(title), value)
                else ctx.settingRow(null, ctx.getString(title), null, ctx.valueText(value)))
        }
        info(R.string.about_device, Device.model)
        info(R.string.about_android, "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        info(R.string.about_build, Device.buildId)
        info(R.string.about_aura, "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        // how the 3D car is drawn, and how fast it turned last time (for reports from the unit)
        val fps = com.abdllh.aura.ui.CarStage.lastFps
        info(R.string.about_car_view, ctx.getString(
            if (com.abdllh.aura.ui.CarStage.gpuDrawing) R.string.about_car_gpu else R.string.about_car_cpu) +
            if (fps > 0f) " · ${Math.round(fps)} fps" else "")
        // how the car came on screen at this start (the unit is the first place the real timings can be seen)
        com.abdllh.aura.ui.CarStage.startNote(ctx).takeIf { it.isNotEmpty() }?.let { info(R.string.about_car_start, it) }
        info(R.string.about_role, ctx.getString(
            when {
                Device.isSystemApp(ctx) && Device.isPrivileged(ctx) -> R.string.role_system
                Device.isSystemApp(ctx) -> R.string.role_system_basic
                else -> R.string.role_user
            }))
        // the last stock screen the home screen took the place of as the unit came on (for reports from the unit)
        com.abdllh.aura.system.HomeGuard.note.takeIf { it.isNotEmpty() }?.let { info(R.string.about_covered, it) }
        // how the music button last reached AMRI: its own key (no stock screen), or a stock screen that had to be covered
        com.abdllh.aura.system.MusicKeys.note.takeIf { it.isNotEmpty() }?.let { info(R.string.about_music_key, it) }
        // the last time the home screen started in a way it should not have to: a healthy unit has no such line
        com.abdllh.aura.home.Screens.note.takeIf { it.isNotEmpty() }?.let { info(R.string.about_home_start, it) }
        // the screens that are panels of the one window: how long the last one took, and the last that failed to open
        com.abdllh.aura.home.PanelStack.lastOpen.takeIf { it.isNotEmpty() }?.let { info(R.string.about_panel_open, it) }
        com.abdllh.aura.home.PanelStack.failedNote(ctx).takeIf { it.isNotEmpty() }?.let { info(R.string.about_panel_failed, it) }
        com.abdllh.aura.home.DrawWatch.note(ctx).takeIf { it.isNotEmpty() }?.let { info(R.string.about_window_lost, it) }
        // the last Bluetooth song's lookup: where its cover and length came from, or why nothing came
        com.abdllh.aura.music.CoverSearch.note.takeIf { it.isNotEmpty() }?.let { info(R.string.about_cover, it) }
        // how the last song handed to YouTube's app went (it could not be tried before the unit)
        com.abdllh.aura.music.YtMusic.note.takeIf { it.isNotEmpty() }?.let { info(R.string.about_yt, it) }
        // Naghm on the owner's iPhone: when it last connected over the hotspot, and from where
        com.abdllh.aura.music.PhoneLink.note.takeIf { it.isNotEmpty() }?.let { info(R.string.about_naghm, it) }
        // a place set on the phone: the last one and how it went; and whether the server runs and who last asked it
        com.abdllh.aura.link.PlaceInbox.lastLine(ctx).takeIf { it.isNotEmpty() }?.let { info(R.string.link_about_place, it) }
        com.abdllh.aura.link.PhoneLinkCard.aboutLine(ctx).takeIf { it.isNotEmpty() }?.let { info(R.string.link_title, it) }
        // what navigation last did on its own: a late route taken, the route left, a trip resumed or not (and why)
        com.abdllh.aura.nav.NavNote.text.takeIf { it.isNotEmpty() }?.let { info(R.string.about_nav, it) }
        // the start-up cuts of 2.0's foundations, none of which could be tried before the unit
        com.abdllh.aura.nav.MapLib.note.takeIf { it.isNotEmpty() }?.let { info(R.string.about_map_lib, it) }
        // the one map under the window: when it drew at this start, or why the home screen has none
        com.abdllh.aura.nav.MapController.note.takeIf { it.isNotEmpty() }?.let { info(R.string.about_map_state, it) }
        com.abdllh.aura.system.IdleGc.note.takeIf { it.isNotEmpty() }?.let { info(R.string.about_idle_gc, it) }
        com.abdllh.aura.system.DexCompile.note.takeIf { it.isNotEmpty() }?.let { info(R.string.about_compile, it) }
        com.abdllh.aura.nav.pack.PackManager.lastInstall()?.takeIf { it.isNotEmpty() }?.let { info(R.string.st_pack_last, it) }
        CrashGuard.lastCrash(ctx)?.let {
            rows.add(ctx.settingRow(null, ctx.getString(R.string.about_last_crash), it.lineSequence().take(3).joinToString("\n")))
        }
        col.addView(ctx.rows(*rows.toTypedArray()))
        col.addNote(ctx.getString(R.string.about_credit))
    }

    private companion object {
        const val DELAY_W = 74
        /** Here the words before a control are longer than the seven pages'. */
        const val NARROWER = 100
    }
}

/** The fourteen requests taught in the owner's voice, for when there is no internet (a tick once one is). */
class OfflineRequestsPage(private val act: SettingsPanel) : Page(R.string.voice_offline_title) {
    override val keep: Boolean get() = false

    override fun build(ctx: Context): View {
        val col = ctx.pageColumn()
        col.addNote(ctx.getString(R.string.voice_offline_sub))
        val rows = OfflineCommands.items.map { item ->
            val taught = OfflineCommands.taught(ctx, item.id)
            val tick = if (taught) ctx.iconView(R.drawable.ic_check, 24) { Palette.accentInk } else ctx.chevron()
            ctx.settingRow(null, ctx.getString(R.string.voice_cmd_quoted, ctx.getString(item.label)),
                ctx.getString(if (taught) R.string.voice_cmd_taught else R.string.voice_cmd_untaught), tick) {
                ctx.parked { act.withMic { VoiceSetup.teach(act.shell, item) { act.rebuild() } } }
            }
        }
        col.addView(ctx.rows(*rows.toTypedArray()))
        return col
    }
}
