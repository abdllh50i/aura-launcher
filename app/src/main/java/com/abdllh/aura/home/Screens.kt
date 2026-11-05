package com.abdllh.aura.home

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.util.Log
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.music.MusicActivity
import com.abdllh.aura.nav.MapsActivity
import com.abdllh.aura.settings.SettingsActivity
import com.abdllh.aura.system.Device
import com.abdllh.aura.util.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The one way to AMRI's own screens: nothing else in the app names their classes to start them. The callers say which
 * screen they want and with what, not how it is reached. A screen that is a panel of the home activity (the single window
 * of 2.0; [PANELS], all of them by now) is shown in place when that window is in front, with no activity start at all, and
 * otherwise through a home intent that names it.
 *
 * The home screen is always reached through a home intent limited to this package, never by its class. The firmware
 * makes an activity of the home type only when no component was named in the start (ActivityRecord.setActivityType;
 * extras and a package are allowed, one category, no data). By name, with no home screen alive, it becomes an ordinary
 * activity in a stack of its own; any other app over it, then the Home key, and the system finds nothing of the home
 * type to bring back: a second home activity, a second map and a second car, until the next power-on (measured on the
 * emulator, docs/DEV.md). HomeGuard (the very case it exists for), BootHome's last resort, the music key's second press
 * and dev-install.ps1 all walked into it.
 *
 * Also called from BootHome's process, where AuraApp sets up nothing and Prefs does not exist: [homeIntent] uses neither.
 */
object Screens {
    private const val TAG = "AuraScreens"
    private const val KEY_NOTE = "homeStartNote"

    /** The screens, by the names they keep as panels ([com.abdllh.aura.system.Front.panel] too). */
    const val HOME = "home"
    const val MAPS = "maps"
    const val MUSIC = "music"
    const val SETTINGS = "settings"
    const val PHONE = "phone"
    const val APPS = "apps"

    /** The screens that are panels of the home activity. */
    private val PANELS = setOf(SETTINGS, MAPS, MUSIC, APPS)

    /** Which screen a home intent is for. */
    const val EXTRA_PANEL = "panel"
    /** Who sent it: for the log and for [note]; nothing depends on it. */
    private const val EXTRA_FROM = "from"
    const val FROM_BOOT = "boot"
    private const val FROM_BY_NAME = "by name"

    // ------------------------------------------------------------------------------------------ the home screen
    /** AMRI's home screen to the front (with panel [panel] on top of it), from anywhere (AMRI may start screens from the background). */
    fun home(c: Context, from: String, panel: String = HOME, args: Bundle? = null): Boolean = try {
        // (not the home app, by the kill switch or before it is chosen: no home start ever finds AMRI, so there is no
        // trap, and a home intent would build a second home activity beside the ordinary one that may be running)
        val i = if (Device.isDefaultHome(c)) homeIntent(c, from) else Intent(c, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (args != null) i.putExtras(args)
        c.startActivity(i.putExtra(EXTRA_PANEL, panel).putExtra(EXTRA_FROM, from))
        Log.i(TAG, "home, from $from")
        true
    } catch (t: Throwable) {
        Log.w(TAG, "home, from $from: $t")
        false
    }

    /** The panel an intent to the home activity asks for (null: none, the home screen itself). */
    fun panelIn(i: Intent?): String? = try {
        // (from Recents, a by-name start's first intent comes back: it asked for this once)
        if (i == null || i.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) null else i.getStringExtra(EXTRA_PANEL)
    } catch (_: Throwable) {
        null // (extras from outside that do not unpack)
    }

    /** The intent itself, for who must look at what it finds before sending it ([BootHome]). */
    fun homeIntent(c: Context, from: String): Intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        .setPackage(c.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .putExtra(EXTRA_PANEL, HOME).putExtra(EXTRA_FROM, from)

    /**
     * The home activity is being built by a start by name (no home category in its intent: adb, an app list) while AMRI
     * is the home app: it would be an ordinary activity, the first half of the trap above. It finishes itself and this
     * asks again the right way. In this order: while it is on top, a home intent is only delivered to it.
     */
    fun homeByName(a: Activity): Boolean = try {
        if (a.intent?.hasCategory(Intent.CATEGORY_HOME) == true || !Device.isDefaultHome(a)) false
        else {
            a.finish()
            home(a, FROM_BY_NAME, panelIn(a.intent) ?: HOME, a.intent?.extras)
            true
        }
    } catch (t: Throwable) {
        Log.w(TAG, "by name: $t") // (built as it was asked for: a home screen comes first)
        false
    }

    /**
     * A home activity is built, with [others] alive already. Nothing in the app can make that happen any more: what
     * still could (a start by name with the home category from outside, or AMRI made the home app while an ordinary
     * home activity runs) stops a debug build at once, and on the unit shows in the Screen check's report.
     */
    fun homeBuilt(i: Intent?, others: Int) {
        val from = try {
            i?.getStringExtra(EXTRA_FROM).also { i?.removeExtra(EXTRA_FROM) } // (once: a recreate brings the same intent back)
        } catch (_: Throwable) {
            null // (extras from outside that do not unpack)
        } ?: "the system"
        Log.i(TAG, "home activity built, from $from, others alive: $others")
        when {
            others > 0 -> {
                note("second home activity, from $from")
                if (BuildConfig.DEBUG) throw IllegalStateException("a second HomeActivity while $others alive, from $from (home/Screens.kt)")
            }
            from == FROM_BOOT -> note("the boot screen's last resort")
            from == FROM_BY_NAME -> note("started by name, asked again as a home intent")
        }
    }

    /** The last start of the home screen that was not the usual one, for the Screen check's report ("" when there never was one). */
    val note: String get() = Prefs.raw.getString(KEY_NOTE, "").orEmpty()

    private fun note(what: String) {
        try {
            val at = SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(Date())
            Prefs.raw.edit().putString(KEY_NOTE, "$at  $what").apply()
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------------------------------ the other screens
    fun maps(c: Context) = open(c, MAPS)

    /** Maps with its search open, on [query] with its results showing when there is one. */
    fun mapsSearch(c: Context, query: String? = null) = open(c, MAPS, Bundle().apply {
        putBoolean("search", true)
        if (query != null) putString("q", query)
    })

    /** Maps driving to the saved place "home" or "work". */
    fun mapsGo(c: Context, which: String) = open(c, MAPS, Bundle().apply { putString("go", which) })

    /** Maps to choose the saved place "home" or "work" on the map, and back to where the owner came from. */
    fun mapsPick(c: Context, which: String) = open(c, MAPS, Bundle().apply { putString("pick", which) })

    /** Maps showing a place from outside the screen, with its route and Go. [keep] false: not for the recent places. */
    fun mapsPlace(c: Context, lat: Double, lon: Double, name: String, detail: String, keep: Boolean) = open(c, MAPS, Bundle().apply {
        putDouble(MapsActivity.EXTRA_LAT, lat)
        putDouble(MapsActivity.EXTRA_LON, lon)
        putString(MapsActivity.EXTRA_NAME, name)
        putString(MapsActivity.EXTRA_DETAIL, detail)
        putBoolean(MapsActivity.EXTRA_KEEP, keep)
    })

    fun music(c: Context) = open(c, MUSIC)

    /** Music on that source and playing, in place of the stock Bluetooth music screen ([bt]) or the stock music player. */
    fun musicOn(c: Context, bt: Boolean) = open(c, MUSIC, Bundle().apply { putString(MusicActivity.EXTRA_SOURCE, if (bt) "bt" else "local") })

    fun apps(c: Context) = open(c, APPS)

    /** Settings, on a page; with none asked for, the last one (SPEC.md section 6.6). */
    fun settings(c: Context, page: Int = -1) =
        open(c, SETTINGS, if (page < 0) null else Bundle().apply { putInt(SettingsActivity.EXTRA_PAGE, page) })

    /** For a notification. */
    fun pending(c: Context, panel: String, request: Int): PendingIntent =
        PendingIntent.getActivity(c, request, intentOf(c, panel, null), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** The name of the screen [a] shows ("" for none of them: a screen of its own that is not one of these). */
    fun panelOf(a: Activity): String = when (a) {
        is HomeActivity -> a.topPanel
        else -> ""
    }

    /** From an [Entry]: what a start by its old name asks for. */
    fun forward(c: Context, panel: String, args: Bundle?) = open(c, panel, args)

    private fun open(c: Context, panel: String, args: Bundle? = null): Boolean = try {
        if (panel in PANELS) {
            // Only with the window in front: anything over it (even an Entry, or a translucent panel of Android's) means
            // a home intent, which brings the shell forward and clears what is above it, as the Home key does.
            val s = HomeActivity.current
            if (s != null && s.resumedNow && Looper.myLooper() == Looper.getMainLooper()) s.panels.show(panel, args)
            else home(c, "$panel panel", panel, args)
        } else c.startActivity(intentOf(c, panel, args))
        Log.i(TAG, "$panel ${args?.keySet()?.joinToString().orEmpty()}") // (the names only: a search is the owner's own)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "$panel: $t")
        false
    }

    private fun intentOf(c: Context, panel: String, args: Bundle?): Intent {
        val i = Intent(c, when (panel) {
            MAPS -> MapsActivity::class.java
            MUSIC -> MusicActivity::class.java
            else -> SettingsActivity::class.java
        })
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (args != null) i.putExtras(args)
        return i
    }
}
