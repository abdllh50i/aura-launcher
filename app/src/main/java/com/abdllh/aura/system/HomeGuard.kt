package com.abdllh.aura.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.abdllh.aura.home.HomeActivity
import com.abdllh.aura.home.Known
import com.abdllh.aura.util.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AMRI's home screen in front when the unit comes on, not a stock screen.
 *
 * The firmware finds the home screen through the home intent, which its patched package manager answers with AMRI
 * (persist.nwd.launcher.default), so the stock launcher itself is not started. But other stock screens are, by name:
 *  - when the ignition comes on (and after a boot), KernelService brings back the app of the source that played when
 *    it went off (SourceMgr.accOn → resumeSource → startApp with the component from ApplicationList.xml): the stock
 *    radio, video, AUX, TV, Bluetooth music or music player;
 *  - the stock launcher's package has more screens than its home (its screen saver clock, its app list...).
 * What came up shows in [Front], also when AMRI's process started after it (broadcasts would have been missed).
 *
 * So, for a short while after the unit comes on, the first stock screen that takes the front is covered by AMRI's
 * home screen (its source keeps playing: the same as pressing Home on it). The stock music screens go to
 * [MusicKeys] at any time (AMRI Music in their place) while that switch is on. The stock launcher's home screen,
 * should anything start it by name, is always covered. A screen the owner opened from AMRI is left alone.
 */
object HomeGuard {
    private const val TAG = "AuraHomeGuard"
    private const val ACTION_MCU_STATE = "com.nwd.action.ACTION_MCU_STATE_CHANGE"
    /** After a boot: until the unit has been up this long (KernelService restores the source once the MCU answers). */
    private const val BOOT_WINDOW_MS = 90_000L
    /** After the ignition comes on. */
    private const val ACC_WINDOW_MS = 12_000L
    /** A screen AMRI itself opened this long ago at most is the owner's choice. */
    private const val OWN_LAUNCH_MS = 8_000L
    private const val KEY_NOTE = "homeGuardNote"

    /** The stock apps of the sources the firmware brings back (ApplicationList.xml: radio 8, video 3, AUX 9, TV 11). */
    private val SOURCE_SCREENS = setOf(Known.RADIO, Known.VIDEO, Known.AUX, "com.nwd.tv")

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var windowUntil = 0L
    private var covered = false       // one screen per window (the next one is somebody's choice)
    private var mcuOn: Boolean? = null // (not known until the firmware says: a first "on" counts as coming on)
    private var launchedPkg = ""
    private var launchedAt = -1_000_000L

    /** What was covered last, for Settings → About ("" when nothing ever was). */
    val note: String get() = Prefs.raw.getString(KEY_NOTE, "").orEmpty()

    /** The unit came on a moment ago: what comes to the front now was put there by the firmware, not by the owner. */
    val comingOn get() = SystemClock.elapsedRealtime() < windowUntil

    /** AMRI opens [pkg] itself (the app list, a dock button, the voice assistant): the owner's choice, left alone. */
    fun launching(pkg: String) {
        launchedPkg = pkg
        launchedAt = SystemClock.elapsedRealtime()
    }

    fun init(ctx: Context) {
        if (app != null) return
        val c = ctx.applicationContext
        app = c
        if (SystemClock.elapsedRealtime() < BOOT_WINDOW_MS) windowUntil = BOOT_WINDOW_MS
        try {
            c.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context, i: Intent) {
                    try {
                        // (a byte; 1 = on, the others are the ways of being off)
                        val on = ((i.extras?.get("extra_mcu_state") as? Number)?.toInt() ?: return) == 1
                        if (on && mcuOn != true) {
                            windowUntil = SystemClock.elapsedRealtime() + ACC_WINDOW_MS
                            covered = false
                            Log.i(TAG, "the unit came on")
                        }
                        mcuOn = on
                    } catch (t: Throwable) {
                        Log.w(TAG, "mcu state: $t")
                    }
                }
            }, IntentFilter(ACTION_MCU_STATE))
        } catch (t: Throwable) {
            Log.w(TAG, "receiver: $t")
        }
        Front.addListener { check() }
        // what came up before this process did: looked at once AMRI's own first screen has had the time to take the
        // front (the home screen being started is why the process starts, most of the time)
        main.postDelayed({ Front.read(); check() }, 1500L)
    }

    private fun check() {
        val c = app ?: return
        if (SystemProps.get(Device.DISABLED_PROP) == "1") return // the stock launcher is the home screen: all its own
        if (Front.ownResumed) return // one of AMRI's screens is in front, whatever the setting still says
        val pkg = Front.pkg
        val cls = Front.cls
        if (pkg.isEmpty() || pkg == c.packageName) return
        val music = pkg == Known.BT_MUSIC || pkg == Known.MUSIC
        when {
            music && MusicKeys.enabled -> MusicKeys.stockScreen(c, pkg == Known.BT_MUSIC)
            // the stock home screen itself (com.android.launcher2.Launcher): never while AMRI is the home screen
            pkg == Device.STOCK_LAUNCHER && cls.endsWith(".Launcher") -> if (Device.isDefaultHome(c)) home(c, pkg, cls)
            comingOn && !covered && (pkg == Device.STOCK_LAUNCHER || pkg in SOURCE_SCREENS || music) -> {
                if (pkg == launchedPkg && SystemClock.elapsedRealtime() - launchedAt < OWN_LAUNCH_MS) return
                if (!Device.isDefaultHome(c)) return
                covered = true
                home(c, pkg, cls)
            }
        }
    }

    private fun home(c: Context, pkg: String, cls: String) {
        Log.i(TAG, "$pkg/$cls came to the front: AMRI's home screen instead")
        try {
            val at = SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(Date())
            Prefs.raw.edit().putString(KEY_NOTE, "$at  ${cls.substringAfterLast('.').ifEmpty { pkg }}").apply()
        } catch (_: Throwable) {
        }
        showHome(c)
    }

    /** AMRI's home screen to the front (from anywhere: AMRI may start screens from the background). */
    fun showHome(c: Context) {
        try {
            c.startActivity(Intent(c, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            Log.w(TAG, "home: $t")
        }
    }
}
