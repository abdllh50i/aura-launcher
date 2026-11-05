package com.abdllh.aura.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.View
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.R
import com.abdllh.aura.home.AppRepo
import com.abdllh.aura.home.Known
import com.abdllh.aura.home.Screens
import com.abdllh.aura.link.Pairing
import com.abdllh.aura.link.PhoneServer
import com.abdllh.aura.music.PhoneLink
import com.abdllh.aura.nav.Places
import com.abdllh.aura.nav.pack.PackManager
import com.abdllh.aura.phone.Calls
import com.abdllh.aura.phone.Ringtone
import com.abdllh.aura.system.Controls
import com.abdllh.aura.system.Notices
import com.abdllh.aura.system.Power
import com.abdllh.aura.system.SystemProps
import com.abdllh.aura.ui.AuraSlider
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
 * The seven pages of SPEC.md section 6.6, each its rows in the panel's one card, and the small sub-pages they open. What did not stay on them is in [WorkshopPage], with its pref untouched.
 */

// ---------------------------------------------------------------------------------------------- الشاشة والصوت
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
        val views = listOf(Prefs.VIEW_TILTED, Prefs.VIEW_ABOVE, Prefs.VIEW_NORTH)
        val view = Segmented(ctx, listOf(ctx.getString(R.string.st_view_tilted), ctx.getString(R.string.st_view_above), ctx.getString(R.string.st_view_north)),
            equal = true, maxW = act.controlW) { i -> Prefs.mapView = views[i] }
        shownAgain = {
            bright.setProgress(Controls.brightnessPercent(ctx), false)
            theme.select(modes.indexOf(Prefs.theme).coerceAtLeast(0), false)
            view.select(views.indexOf(Prefs.mapView).coerceAtLeast(0), false)
        }
        shownAgain?.invoke()
        return ctx.rows(
            ctx.controlRow(ctx.getString(R.string.ctl_brightness), ctx.sliderControl(bright, dim, sun), act.controlW),
            ctx.controlRow(ctx.getString(R.string.set_theme), theme, act.controlW),
            ctx.controlRow(ctx.getString(R.string.st_map_view), view, act.controlW),
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
    private var offline: ValueChevron? = null
    private var navApp: ValueChevron? = null
    private var places: ValueChevron? = null
    private val packHeard: () -> Unit = { showPack() }

    override fun onShow() {
        PackManager.removeListener(packHeard)
        PackManager.addListener(packHeard)
        showPack()
        navApp?.value = navAppName(act)
        places?.value = placesWord(act)
    }

    override fun onHide() { PackManager.removeListener(packHeard) }

    private fun showPack() { offline?.value = packWord(act) }

    override fun build(ctx: Context): View {
        offline = ctx.valueChevron(packWord(ctx))
        navApp = ctx.valueChevron(navAppName(ctx))
        places = ctx.valueChevron(placesWord(ctx))
        return ctx.rows(
            ctx.settingRow(null, ctx.getString(R.string.off_section), ctx.getString(R.string.st_offline_sub), offline) { act.open(PackPage(act)) },
            ctx.switchRow(null, ctx.getString(R.string.st_saher), ctx.getString(R.string.st_saher_sub), Prefs.saherTone) { Prefs.saherTone = it },
            ctx.settingRow(null, ctx.getString(R.string.set_nav_app), null, navApp) { act.open(NavAppPage(act)) },
            ctx.settingRow(null, ctx.getString(R.string.st_places), ctx.getString(R.string.st_places_sub), places) { act.open(PlacesPage()) },
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

private fun navAppName(c: Context): String =
    if (Prefs.builtInMaps) c.getString(R.string.amri_name) else AppRepo.label(c, Prefs.navPackage)

private fun placesWord(c: Context): String {
    val out = ArrayList<String>()
    if (Places.home != null || Prefs.homeAddress.isNotBlank()) out.add(c.getString(R.string.st_place_home))
    if (Places.work != null || Prefs.workAddress.isNotBlank()) out.add(c.getString(R.string.st_place_work))
    return if (out.isEmpty()) c.getString(R.string.st_places_none) else out.joinToString(" · ")
}

/** Which app the map's buttons open. AMRI first; `تلقائي` is gone, and a stored one reads as AMRI ([Prefs.builtInMaps]). */
class NavAppPage(private val act: SettingsPanel) : Page(R.string.set_nav_app) {
    override val keep: Boolean get() = false

    override fun build(ctx: Context): View {
        val col = ctx.pageColumn()
        val tick = { on: Boolean -> if (on) ctx.iconView(R.drawable.ic_check, 26) { Palette.accentInk } else null }
        val builtIn = Prefs.builtInMaps
        val apps = AppRepo.installedNavApps(ctx)
        val rows = ArrayList<View?>()
        rows.add(ctx.settingRow(null, ctx.getString(R.string.amri_name), ctx.getString(R.string.st_nav_amri_sub), tick(builtIn)) {
            if (builtIn) return@settingRow
            Prefs.builtInMaps = true
            act.rebuild()
        })
        for (a in apps) {
            val on = !builtIn && a.pkg == Prefs.navPackage
            // (the firmware's navigation-app property, written again by a tap on the chosen app: something may have reset it since)
            val push = { SystemProps.set("persist.sys.navi_set_by_app", a.pkg) }
            rows.add(ctx.settingRow(null, a.label, null, tick(on)) {
                if (on) { push(); return@settingRow }
                Prefs.navPackage = a.pkg
                Prefs.builtInMaps = false
                push()
                act.rebuild()
            })
        }
        col.addView(ctx.rows(*rows.toTypedArray()))
        // Google's own map, roads and shops, without internet: only its app can keep them (how, in one place)
        if (AppRepo.isInstalled(ctx, Known.MAPS)) col.addNote(ctx.getString(R.string.set_nav_google_hint))
        return col
    }
}

/** Home and Work: chosen on the map (the row only opens it, and comes back here). Saved values untouched. */
class PlacesPage : Page(R.string.st_places) {
    override val keep: Boolean get() = false

    override fun build(ctx: Context): View {
        fun row(title: Int, which: String): View {
            val saved = if (which == "home") Places.home else Places.work
            val legacy = if (which == "home") Prefs.homeAddress else Prefs.workAddress
            return ctx.settingRow(null, ctx.getString(title), (saved?.name ?: legacy).ifBlank { ctx.getString(R.string.home_tap_to_set) }, ctx.chevron()) {
                Screens.mapsPick(ctx, which)
            }
        }
        val col = ctx.pageColumn()
        col.addView(ctx.rows(row(R.string.st_place_home, "home"), row(R.string.st_place_work, "work")))
        col.addNote(ctx.getString(R.string.set_places_hint))
        return col
    }
}

// ---------------------------------------------------------------------------------------------- الموسيقى
class MusicPage(private val act: SettingsPanel) : Page(R.string.st_music, R.drawable.ic_music) {
    override val scrolls: Boolean get() = false
    override val keep: Boolean get() = false // (whether the switch can be on follows the microphone's permission)

    override fun build(ctx: Context): View {
        val lyrics = LyricsCard(act, ctx)
        return ctx.rows(
            lyrics.listenRow,
            lyrics.keyRow,
            // (the word being sung lights on the lyrics' lit line; off: whole lines)
            ctx.switchRow(null, ctx.getString(R.string.kar_words), ctx.getString(R.string.st_words_sub), com.abdllh.aura.music.Words.on) { on -> com.abdllh.aura.music.Words.on = on },
            fill = act.fillH
        )
    }
}

// ---------------------------------------------------------------------------------------------- عمري
class AmriPage(private val act: SettingsPanel) : Page(R.string.amri_name, R.drawable.ic_mic) {
    override val scrolls: Boolean get() = false
    override val keep: Boolean get() = false

    override fun build(ctx: Context): View {
        // what the unit's firmware lacks, from the Play Store: requests need a speech recogniser, spoken answers a voice
        val needGoogle = !Amri.canRecognize(ctx)
        val needVoice = !VoiceSetup.hasVoice(ctx)
        val enrolled = VoiceSetup.enrolled(ctx)
        return ctx.rows(
            ctx.switchRow(null, ctx.getString(R.string.st_wake), ctx.getString(R.string.st_wake_sub), Amri.enabled && enrolled) { on ->
                if (!on) { Amri.enabled = false; return@switchRow }
                // on only with the microphone and the owner's voice
                act.withMic {
                    if (VoiceSetup.enrolled(ctx)) { Amri.enabled = true; act.rebuild() }
                    else ctx.parked { VoiceSetup.enroll(act.shell) { ok -> if (ok) Amri.enabled = true; act.rebuild() } }
                }
            },
            ctx.switchRow(null, ctx.getString(R.string.st_speak), null, Amri.spoken) { Amri.spoken = it },
            ctx.settingRow(null, ctx.getString(R.string.voice_enroll), null, ctx.valueChevron(ctx.getString(if (enrolled) R.string.st_enrolled else R.string.st_six_times))) {
                // (set even when it already was: the setter starts the listener with the new recordings)
                ctx.parked { act.withMic { VoiceSetup.enroll(act.shell) { ok -> if (ok) Amri.enabled = true; act.rebuild() } } }
            },
            if (!needGoogle) null else ctx.settingRow(null, ctx.getString(R.string.voice_get_google), null, ctx.chevron()) { VoiceSetup.openStore(ctx, VoiceSetup.GOOGLE_APP) },
            if (!needVoice) null else ctx.settingRow(null, ctx.getString(R.string.voice_get_tts), null, ctx.chevron()) { VoiceSetup.openStore(ctx, VoiceSetup.GOOGLE_TTS) },
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
    private var language: ValueChevron? = null
    private val updateHeard: (UpdateManager.State) -> Unit = { s -> update?.value = updateWord(act, s) }

    override fun onShow() {
        UpdateManager.observe(updateHeard)
        language?.value = languageWord(act)
    }

    override fun onHide() { UpdateManager.unobserve(updateHeard) }

    override fun build(ctx: Context): View {
        update = ctx.valueChevron("")
        language = ctx.valueChevron(languageWord(ctx))
        return ctx.rows(
            if (!Power.available && !BuildConfig.DEBUG) null
            else ctx.switchRow(null, ctx.getString(R.string.st_full_off), null, Power.fullOffOnAccOff) { on ->
                Power.fullOffOnAccOff = on
                Power.apply(ctx)
            },
            ctx.settingRow(null, ctx.getString(R.string.st_update), null, update) { act.open(UpdatePage(act)) },
            ctx.settingRow(null, ctx.getString(R.string.st_lang), null, language) { act.open(LanguagePage(act)) },
            ctx.settingRow(null, ctx.getString(R.string.v_android_settings), null, ctx.valueChevron("")) {
                try { ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) { Snack.show(ctx, R.string.err_app_missing) }
            },
            ctx.settingRow(null, ctx.getString(R.string.st_workshop), ctx.getString(R.string.st_workshop_sub), ctx.valueChevron("")) { act.open(WorkshopPage(act)) },
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

private fun languageWord(c: Context): String = when (Prefs.lang) {
    "ar" -> "العربية"
    "en" -> "English"
    else -> c.getString(R.string.lang_system)
}

/** The language: a whole new window, as it has always been ([SettingsPanel.takeLanguage]). */
class LanguagePage(private val act: SettingsPanel) : Page(R.string.st_lang) {
    override val keep: Boolean get() = false

    override fun build(ctx: Context): View {
        fun row(code: String, name: String): View {
            val on = Prefs.lang == code
            return ctx.settingRow(null, name, null, if (on) ctx.iconView(R.drawable.ic_check, 26) { Palette.accentInk } else null) {
                if (on) return@settingRow
                Prefs.lang = code
                act.takeLanguage()
            }
        }
        return ctx.rows(row("system", ctx.getString(R.string.lang_system)), row("ar", "العربية"), row("en", "English"))
    }
}

// ---------------------------------------------------------------------------------------------- عن النظام
class AboutPage(private val act: SettingsPanel) : Page(R.string.st_about, R.drawable.ic_info) {
    override val scrolls: Boolean get() = false
    override val keep: Boolean get() = false // (whose map it is follows the pack)

    override fun build(ctx: Context): View {
        // the credit whole, as the map's data asks, for the data that draws: the pack's is too long for a row's far end. On its own line, reading left to right in Arabic as well.
        val credit = "⁦${com.abdllh.aura.nav.MapStyle.attribution(null)}⁩"
        return ctx.rows(
            ctx.settingRow(null, ctx.getString(R.string.st_version), null, ctx.valueText("AMRI OS ${BuildConfig.VERSION_NAME}")),
            ctx.settingRow(null, ctx.getString(R.string.st_maps), credit),
            ctx.settingRow(null, ctx.getString(R.string.st_licences), null, ctx.valueChevron("")) { act.open(LicencesPage(act)) },
            // the first real numbers from this unit: a page of its own, nothing runs until its button is pressed
            ctx.settingRow(null, ctx.getString(R.string.st_report), ctx.getString(R.string.st_report_sub), ctx.valueChevron("")) {
                ctx.startActivity(Intent(ctx, ScreenCheckActivity::class.java))
            },
            fill = act.fillH
        )
    }
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