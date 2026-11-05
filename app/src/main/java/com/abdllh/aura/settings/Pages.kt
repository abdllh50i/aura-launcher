package com.abdllh.aura.settings

import android.content.Context
import android.content.Intent
import android.view.View
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.R
import com.abdllh.aura.home.Screens
import com.abdllh.aura.link.Pairing
import com.abdllh.aura.link.PhoneServer
import com.abdllh.aura.music.LyricsServer
import com.abdllh.aura.music.PhoneLink
import com.abdllh.aura.nav.CameraMark
import com.abdllh.aura.nav.MapExtras
import com.abdllh.aura.nav.Places
import com.abdllh.aura.nav.pack.PackManager
import com.abdllh.aura.phone.Calls
import com.abdllh.aura.phone.Ringtone
import com.abdllh.aura.system.Controls
import com.abdllh.aura.system.Device
import com.abdllh.aura.system.Notices
import com.abdllh.aura.system.Power
import com.abdllh.aura.system.WifiOnStart
import com.abdllh.aura.ui.ABtn
import com.abdllh.aura.ui.AuraSlider
import com.abdllh.aura.ui.Confirm
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Segmented
import com.abdllh.aura.ui.Snack
import com.abdllh.aura.ui.Theme
import com.abdllh.aura.ui.iconView
import com.abdllh.aura.update.UpdateManager
import com.abdllh.aura.util.Fmt
import com.abdllh.aura.util.Prefs
import com.abdllh.aura.voice.Amri
import com.abdllh.aura.voice.VoiceSetup

/*
 * The six pages, each its few rows in the panel's one card, and the sub-pages they open. A switch that left holds the safe value in the code of what it switched, and what the old workshop only showed
 * is in the Screen check's report (docs/DEV.md, "Far fewer settings").
 */

// ---------------------------------------------------------------------------------------------- الشاشة
class DisplayPage(private val act: SettingsPanel) : Page(R.string.st_display, R.drawable.ic_screen) {
    override val scrolls: Boolean get() = false
    private var shownAgain: (() -> Unit)? = null

    // (the brightness and the theme change meanwhile, in Controls: a drag moves from the value shown)
    override fun onShow() { shownAgain?.invoke() }

    override fun build(ctx: Context): View {
        val bright = AuraSlider(ctx).apply {
            max = 100
            onChange = { v, user ->
                if (user && !Controls.setBrightnessPercent(ctx, v)) {
                    Snack.show(ctx, R.string.err_write_settings, outlives = true)
                    Controls.requestWriteSettings(ctx)
                }
            }
        }
        val dim = ctx.iconView(R.drawable.ic_sun_dim, 24) { Palette.ink }
        val sun = ctx.iconView(R.drawable.ic_sun, 28) { Palette.ink }
        val modes = listOf(Theme.AUTO, Theme.LIGHT, Theme.DARK)
        val theme = Segmented(ctx, listOf(ctx.getString(R.string.st_theme_auto), ctx.getString(R.string.st_theme_day), ctx.getString(R.string.st_theme_night)),
            equal = true, maxW = act.controlW) { i ->
            Prefs.theme = modes[i]
            act.takeTheme()
        }
        val language = Segmented(ctx, listOf("العربية", "English"), equal = true, maxW = act.controlW) { i ->
            Prefs.lang = if (i == 0) "ar" else "en"
            act.takeLanguage()
        }
        shownAgain = {
            bright.setProgress(Controls.brightnessPercent(ctx), false)
            theme.select(modes.indexOf(Prefs.theme).coerceAtLeast(0), false)
            // (the window's direction is the language it speaks, also for a stored "system")
            language.select(if (act.rtl) 0 else 1, false)
        }
        shownAgain?.invoke()
        return ctx.rows(
            ctx.controlRow(ctx.getString(R.string.ctl_brightness), ctx.sliderControl(bright, dim, sun), act.controlW),
            ctx.controlRow(ctx.getString(R.string.set_theme), theme, act.controlW),
            ctx.controlRow(ctx.getString(R.string.st_lang), language, act.controlW),
            ctx.switchRow(null, ctx.getString(R.string.set_clock24), null, Prefs.clock24) {
                Prefs.clock24 = it
                com.abdllh.aura.chrome.Chrome.of(act.shell)?.clockChanged()
            },
            fill = act.fillH
        )
    }
}

// ---------------------------------------------------------------------------------------------- الخرائط
class MapsPage(private val act: SettingsPanel) : Page(R.string.st_maps, R.drawable.ic_map) {
    override val scrolls: Boolean get() = false
    override val keep: Boolean get() = false // (the places and the marked cameras change on the map)
    private var offline: ValueChevron? = null
    private val packHeard: () -> Unit = { offline?.value = packWord(act) }

    override fun onShow() {
        PackManager.removeListener(packHeard)
        PackManager.addListener(packHeard)
        packHeard()
    }

    override fun onHide() { PackManager.removeListener(packHeard) }

    override fun build(ctx: Context): View {
        offline = ctx.valueChevron(packWord(ctx))
        val marked = MapExtras.mine.size
        return ctx.rows(
            ctx.settingRow(null, ctx.getString(R.string.off_section), null, offline) { act.open(PackPage(act)) },
            // (chosen on the map: the row only opens it, and comes back here)
            ctx.settingRow(null, ctx.getString(R.string.st_place_home), null, ctx.valueChevron(placeWord(ctx, "home"))) { Screens.mapsPick(ctx, "home") },
            ctx.settingRow(null, ctx.getString(R.string.st_place_work), null, ctx.valueChevron(placeWord(ctx, "work"))) { Screens.mapsPick(ctx, "work") },
            ctx.switchRow(null, ctx.getString(R.string.set_mark_camera), null, CameraMark.enabled) { CameraMark.enabled = it },
            // (only with something marked: a camera that went from the road would beep there for ever, once the button's own undo has gone)
            if (marked == 0) null else ctx.settingRow(null, ctx.getString(R.string.set_my_cameras, "$marked"), null, ABtn(ctx).apply {
                kind = ABtn.Kind.TONAL
                setText(R.string.set_my_cameras_clear)
                setOnClickListener {
                    Confirm.show(ctx, ctx.getString(R.string.set_my_cameras_clear_title), ctx.getString(R.string.set_my_cameras_clear_text), ctx.getString(R.string.set_my_cameras_clear)) {
                        MapExtras.clearMine()
                        act.rebuild()
                    }
                }
            }),
            fill = act.fillH
        )
    }
}

/** The pack's state at the far end of its row: real, with its size. */
internal fun packWord(c: Context, s: PackManager.Status = PackManager.status): String = when (s.state) {
    PackManager.State.NONE -> c.getString(R.string.st_pack_none)
    PackManager.State.CHECKING -> c.getString(R.string.pack_checking)
    PackManager.State.OFFER -> c.getString(if ((s.offer ?: s.usbOffer)?.isUpdate == true) R.string.st_pack_newer else R.string.st_pack_offer)
    PackManager.State.NO_SPACE -> c.getString(R.string.st_pack_nospace)
    PackManager.State.FETCHING, PackManager.State.VERIFYING -> c.getString(R.string.st_pack_going, "${s.percent}")
    PackManager.State.WAITING, PackManager.State.TRIP_HOLD, PackManager.State.PAUSED -> c.getString(R.string.st_pack_paused, "${s.percent}")
    PackManager.State.INSTALLED -> c.getString(R.string.st_pack_ready, Fmt.bytes(s.currentBytes))
    PackManager.State.FAILED -> c.getString(R.string.st_pack_stopped)
}

private fun placeWord(c: Context, which: String): String {
    val saved = if (which == "home") Places.home else Places.work
    val legacy = if (which == "home") Prefs.homeAddress else Prefs.workAddress
    return (saved?.name ?: legacy).ifBlank { c.getString(R.string.home_tap_to_set) }
}

// ---------------------------------------------------------------------------------------------- الموسيقى
class MusicPage(private val act: SettingsPanel) : Page(R.string.st_music, R.drawable.ic_music) {
    override val scrolls: Boolean get() = false
    override val keep: Boolean get() = false // (whether the switch can be on follows the microphone's permission, and the server's row what was typed on its page)

    override fun build(ctx: Context): View = ctx.rows(
        // (shown on only where it can work: without the microphone's permission it hears nothing)
        ctx.switchRow(null, ctx.getString(R.string.lyr_srv_listen), ctx.getString(R.string.st_lyrics_listen_sub), LyricsServer.listen && Amri.hasMicPermission(ctx)) { on ->
            when {
                !on -> LyricsServer.listen = false
                !LyricsServer.configured -> { Snack.show(ctx, R.string.lyr_srv_listen_needs); act.rebuild() }
                else -> act.withMic { LyricsServer.listen = true }
            }
        },
        // (the word being sung lights on the lyrics' lit line; off: whole lines)
        ctx.switchRow(null, ctx.getString(R.string.kar_words), null, com.abdllh.aura.music.Words.on) { on -> com.abdllh.aura.music.Words.on = on },
        ctx.settingRow(null, ctx.getString(R.string.lyr_srv_title), null, ctx.valueChevron(ctx.getString(if (LyricsServer.configured) R.string.st_saved else R.string.st_not_set))) {
            act.open(LyricsServerPage())
        },
        fill = act.fillH
    )
}

// ---------------------------------------------------------------------------------------------- عمري
class AmriPage(private val act: SettingsPanel) : Page(R.string.amri_name, R.drawable.ic_mic) {
    override val scrolls: Boolean get() = false
    override val keep: Boolean get() = false

    override fun build(ctx: Context): View {
        val enrolled = VoiceSetup.enrolled(ctx)
        val sens = Segmented(ctx, listOf(ctx.getString(R.string.voice_sens_strict), ctx.getString(R.string.voice_sens_normal), ctx.getString(R.string.voice_sens_relaxed)),
            equal = true, maxW = act.controlW) { i -> Amri.sensitivity = i }.apply { select(Amri.sensitivity, animate = false) }
        return ctx.rows(
            ctx.switchRow(null, ctx.getString(R.string.st_wake), null, Amri.enabled && enrolled) { on ->
                if (!on) { Amri.enabled = false; return@switchRow }
                // on only with the microphone and the owner's voice
                act.withMic {
                    if (VoiceSetup.enrolled(ctx)) { Amri.enabled = true; act.rebuild() }
                    else VoiceSetup.enroll(act.shell) { ok -> if (ok) Amri.enabled = true; act.rebuild() }
                }
            },
            ctx.settingRow(null, ctx.getString(R.string.voice_enroll), null, ctx.valueChevron(ctx.getString(if (enrolled) R.string.st_enrolled else R.string.st_six_times))) {
                // (set even when it already was: the setter starts the listener with the new recordings)
                act.withMic { VoiceSetup.enroll(act.shell) { ok -> if (ok) Amri.enabled = true; act.rebuild() } }
            },
            ctx.settingRow(null, ctx.getString(R.string.voice_try), null, ctx.chevron()) { act.withMic { Amri.wake(ctx) } },
            ctx.controlRow(ctx.getString(R.string.voice_sens), sens, act.controlW),
            fill = act.fillH
        )
    }
}

// ---------------------------------------------------------------------------------------------- الجوال
class PhonePage(private val act: SettingsPanel) : Page(R.string.st_phone, R.drawable.ic_iphone) {
    override val scrolls: Boolean get() = false
    private var link: StateDot? = null
    private var naghm: StateDot? = null
    private val heard: () -> Unit = { states() }

    override fun onShow() {
        PhoneServer.removeListener(heard)
        PhoneServer.addListener(heard)
        states()
    }

    override fun onHide() { PhoneServer.removeListener(heard) }

    private fun states() {
        val paired = PhoneServer.enabled && Pairing.devices().isNotEmpty()
        link?.set(act.getString(if (paired) R.string.st_linked else R.string.st_unlinked), paired)
        val n = PhoneLink.active
        naghm?.set(act.getString(if (n) R.string.st_naghm_on else R.string.st_naghm_off), n)
    }

    override fun build(ctx: Context): View {
        val l = ctx.stateDot(ctx.getString(R.string.st_unlinked), false, opens = true)
        val n = ctx.stateDot(ctx.getString(R.string.st_naghm_off), false)
        link = l
        naghm = n
        return ctx.rows(
            ctx.settingRow(null, ctx.getString(R.string.st_link_app), null, l) { act.open(LinkPage()) },
            ctx.settingRow(null, ctx.getString(R.string.st_naghm), null, n),
            ctx.switchRow(null, ctx.getString(R.string.set_notices_messages), null, Notices.showMessages) { on -> Notices.showMessages = on; Notices.apply(ctx) },
            ctx.switchRow(null, ctx.getString(R.string.st_msg_text), null, Notices.showText) { on -> Notices.showText = on },
            if (!Calls.available) null else ctx.switchRow(null, ctx.getString(R.string.st_ring), null, Ringtone.enabled) { on ->
                Ringtone.enabled = on
                Ringtone.apply(ctx) { ok -> if (!ok) act.runOnUiThread { Snack.show(ctx, R.string.set_failed, long = true) } }
            },
            fill = act.fillH
        )
    }
}

/** A place set on the phone: the switch, what it is doing, the code that pairs a phone ([com.abdllh.aura.link.PhoneLinkCard]). */
class LinkPage : Page(R.string.link_title) {
    private var card: com.abdllh.aura.link.PhoneLinkCard? = null

    override fun onShow() { card?.attach() }
    override fun onHide() { card?.detach() }

    override fun build(ctx: Context): View = com.abdllh.aura.link.PhoneLinkCard(ctx).also { card = it }.view
}

// ---------------------------------------------------------------------------------------------- السيارة والنظام
class CarPage(private val act: SettingsPanel) : Page(R.string.st_car, R.drawable.ic_car) {
    override val scrolls: Boolean get() = false
    private var update: ValueChevron? = null
    private val updateHeard: (UpdateManager.State) -> Unit = { s -> update?.value = updateWord(act, s) }

    override fun onShow() { UpdateManager.observe(updateHeard) }

    override fun onHide() { UpdateManager.unobserve(updateHeard) }

    override fun build(ctx: Context): View {
        update = ctx.valueChevron("")
        return ctx.rows(
            ctx.settingRow(null, ctx.getString(R.string.st_update), null, update) { act.open(UpdatePage()) },
            ctx.switchRow(null, ctx.getString(R.string.set_wifi_on_start), null, WifiOnStart.enabled) { on ->
                WifiOnStart.enabled = on
                if (on) WifiOnStart.ensure("switch")
            },
            if (!Power.available && !BuildConfig.DEBUG) null
            else ctx.switchRow(null, ctx.getString(R.string.st_full_off), null, Power.fullOffOnAccOff) { on ->
                Power.fullOffOnAccOff = on
                Power.apply(ctx)
            },
            ctx.settingRow(null, ctx.getString(R.string.wayback_title), null, ctx.chevron()) { act.open(WaybackPage(act)) },
            // the numbers from this unit, and what the workshop's lines used to say: nothing runs until its button is pressed
            ctx.settingRow(null, ctx.getString(R.string.diag_title), null, ctx.chevron()) {
                ctx.startActivity(Intent(ctx, ScreenCheckActivity::class.java))
            },
            ctx.settingRow(null, ctx.getString(R.string.st_about), null, ctx.valueChevron("${BuildConfig.VERSION_NAME} · ${Device.model}")) { act.open(LicencesPage(act)) },
            fill = act.fillH
        )
    }
}

private fun updateWord(c: Context, s: UpdateManager.State): String = when (s) {
    is UpdateManager.State.Available -> c.getString(R.string.st_update_new, s.release.tag.removePrefix("v"))
    is UpdateManager.State.Checking -> c.getString(R.string.pack_checking)
    is UpdateManager.State.Downloading, is UpdateManager.State.Verifying, is UpdateManager.State.Installing -> c.getString(R.string.st_update_going)
    is UpdateManager.State.Failed -> c.getString(R.string.upd_failed)
    is UpdateManager.State.UpToDate -> c.getString(R.string.st_up_to_date)
    else -> BuildConfig.VERSION_NAME
}

/** On a trip: back from a page, the seven as a list. A row opens one, with back to here. */
class TripListPage(private val act: SettingsPanel) : Page(R.string.set_title) {
    override val scrolls: Boolean get() = false

    override fun build(ctx: Context): View {
        val rows = act.pagesForList().mapIndexed { i, p ->
            ctx.settingRow(p.iconRes, ctx.getString(p.titleRes), null, ctx.chevron()) { act.show(i, true) }.apply { minimumHeight = 0 }
        }
        return ctx.rows(*rows.toTypedArray(), fill = act.fillH)
    }
}

/** A view that scrolls, with no bar and no glow (the Screen check's page, which is an activity of its own). */
fun Context.scrolled(content: View): android.widget.ScrollView = android.widget.ScrollView(this).apply {
    isVerticalScrollBarEnabled = false
    overScrollMode = View.OVER_SCROLL_NEVER
    isFillViewport = false
    addView(content, com.abdllh.aura.ui.MATCH, com.abdllh.aura.ui.WRAP)
}
