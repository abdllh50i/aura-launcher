package com.abdllh.aura

import android.app.Application
import com.abdllh.aura.system.Animations
import com.abdllh.aura.system.AssistiveBall
import com.abdllh.aura.system.BootAnim
import com.abdllh.aura.system.CarAudio
import com.abdllh.aura.system.CrashGuard
import com.abdllh.aura.system.Device
import com.abdllh.aura.system.Front
import com.abdllh.aura.system.HomeGuard
import com.abdllh.aura.system.NwdBridge
import com.abdllh.aura.system.SystemBars
import com.abdllh.aura.system.SystemProps
import com.abdllh.aura.system.WifiKeeper
import com.abdllh.aura.system.ZLinkGuard
import com.abdllh.aura.system.StockMusic
import com.abdllh.aura.system.Gear
import com.abdllh.aura.system.MusicKeys
import com.abdllh.aura.system.Power
import com.abdllh.aura.system.VolumeHud
import com.abdllh.aura.music.BtMusic
import com.abdllh.aura.music.Player
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Snack
import com.abdllh.aura.ui.Theme
import com.abdllh.aura.ui.ThemeSwap
import com.abdllh.aura.update.UpdateManager
import com.abdllh.aura.util.Dp
import com.abdllh.aura.util.Prefs

class AuraApp : Application() {
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        com.abdllh.aura.util.LocaleHelper.reassert()
    }

    override fun onCreate() {
        // Valhalla's process (nav/route/RouteProcess.kt): its crash is not the home screen's, and it has no use for any of this
        if (processName().endsWith(":route")) { super.onCreate(); return }
        super.onCreate()
        val process = processName()
        // BootHome's process, right after a power-on: the user's storage is still closed (settings would throw) and
        // nothing of AMRI is wanted yet but that one waiting screen. The screen check's process (diag/): only its own
        // tests, which bring up what they use; a crash there is theirs to report, not the crash guard's to count.
        if (process.endsWith(":boot") || process.endsWith(":diag")) return
        // the guard's process: it must stand when everything below is what fails, so none of it runs there
        if (process.endsWith(":guard")) {
            com.abdllh.aura.guard.GuardLog.quietCrashes(this)
            if (BuildConfig.DEBUG) testShell()
            return
        }
        // first of all, before anything native is loaded: a crash that no handler sees is told by this mark, unanswered
        com.abdllh.aura.guard.GuardMarks.started(this)
        CrashGuard.install(this) // first: a crash while the rest of the start-up runs must be counted as well
        if (BuildConfig.DEBUG) testShell()
        // right here, not in HomeActivity: a throw further down this method would never get there
        if (CrashGuard.isCrashLoop()) { CrashGuard.bailOutAtStart(this); return }
        if (BuildConfig.DEBUG) com.abdllh.aura.guard.GuardTest.atStart()
        com.abdllh.aura.home.BootHome.ensure(this) // AMRI's home screen for the moment after a power-on (the unit only)
        Dp.init(this)
        Prefs.init(this)
        if (BuildConfig.DEBUG) com.abdllh.aura.system.PrefTypes.check(this)
        Theme.refresh()
        Fonts.init(this)
        com.abdllh.aura.chrome.Chrome.warm(this)
        if (BuildConfig.DEBUG) Palette.contrastFaults().forEach { android.util.Log.e("AuraPalette", it) }
        Snack.waitFor = { com.abdllh.aura.voice.AmriOverlay.showing }
        ThemeSwap.holdOff = {
            com.abdllh.aura.phone.Calls.state == com.abdllh.aura.phone.Calls.State.RINGING ||
                com.abdllh.aura.nav.NavSession.progress?.let { it.toNext < ThemeSwap.NEAR_TURN_M } == true
        }
        // the map's own tile server and the offline map (before any map style is built)
        com.abdllh.aura.nav.offline.OfflineMaps.init(this)
        com.abdllh.aura.nav.pack.PackManager.init(this) // the 2.0 map pack: what is on the disk, on a thread of its own; nothing starts by itself
        com.abdllh.aura.nav.MapExtras.init(this) // what the owner added to the map (cameras, bumps, signals, limits)
        if (BuildConfig.DEBUG) com.abdllh.aura.nav.NavDebug.init(this)
        if (BuildConfig.DEBUG) com.abdllh.aura.nav.route.RouteProbe.debugInit(this)
        if (BuildConfig.DEBUG) com.abdllh.aura.nav.route.RouteDebug.init(this)
        com.abdllh.aura.nav.places.PlaceSearch.init(this) // only remembers the app: places.db is opened by the first use, off the main thread
        com.abdllh.aura.nav.places.PlaceSearch.locate = { com.abdllh.aura.nav.pack.PackPaths.placesDb(it) }
        com.abdllh.aura.nav.places.PlaceSearch.worked = { com.abdllh.aura.nav.pack.PackPaths.noteWorked(it) }
        if (BuildConfig.DEBUG) com.abdllh.aura.nav.places.PlacesDebug.init(this)
        if (BuildConfig.DEBUG) com.abdllh.aura.nav.pack.PackDebug.init(this)
        UpdateManager.init(this)
        CarAudio.init(this)
        // the status bar, the volume display and the screen-change animations are Aura's to change only while Aura is
        // the home screen
        val disabled = SystemProps.get(Device.DISABLED_PROP) == "1"
        if (disabled) {
            SystemBars.release(this); VolumeHud.release(this); Animations.release(this)
            com.abdllh.aura.phone.Calls.release(this); MusicKeys.release(); com.abdllh.aura.system.Notices.release(this)
        }
        else { SystemBars.apply(this); Animations.apply(this) }
        Animations.ensureAnimators()
        Player.init(this)
        BtMusic.init(this)
        com.abdllh.aura.music.PhoneLink.init(this) // Naghm on the phone: the song's real time, and seeking (over the hotspot)
        StockMusic.apply(this)
        WifiKeeper.start(this)
        ZLinkGuard.init(this)
        com.abdllh.aura.system.WifiOnStart.init(this) // the Wi-Fi on when the car starts (the firmware's airplane mode off)
        Power.init(this) // after an "off now": the ACC-off behaviour goes back to the user's choice
        Front.init(this)
        com.abdllh.aura.diag.TripRecorder.init(this) // (does nothing unless its switch is on)
        com.abdllh.aura.update.Rollback.prepare(this) // the way back in place, a while after the start
        if (!disabled) {
            VolumeHud.init(this)
            MusicKeys.init(this)
            HomeGuard.init(this) // a stock screen the firmware puts in front when the unit comes on: AMRI's instead
            NwdBridge.stopBootTip(this) // (one an earlier AMRI started this boot)
            com.abdllh.aura.system.BetaLeftovers.removeOnce(this) // what a 1.6.1 beta left over the Bluetooth daemon
            Gear.init(this)
            com.abdllh.aura.system.DriveState.init(this) // Parked or Moving, the one source (after the gear it reads)
            com.abdllh.aura.phone.Calls.init(this) // a phone call: AMRI's own card and ring, not the stock window
            com.abdllh.aura.system.Notices.apply(this) // Android's notifications off the screen, the phone's messages on it
            BootAnim.ensure(this) // this build's boot animation, also after an update from the screen
            com.abdllh.aura.system.DexCompile.ensure(this) // this build's code compiled, once, with the car parked
            AssistiveBall.offOnce(this) // the stock floating circle the owner switched on by accident
            com.abdllh.aura.system.AppRemover.removeRequestedOnce(this) // the "Vivid" launcher the owner asked to remove
            com.abdllh.aura.voice.Amri.init(this) // the voice assistant (its wake word listener when switched on)
            com.abdllh.aura.link.PhoneServer.init(this) // a place set on the phone (nothing runs until it is switched on)
            // a trip that was being driven when the process went (an update, a crash, a fuel stop); after the start's rush
            android.os.Handler(mainLooper).postDelayed({ com.abdllh.aura.nav.TripMemory.resume(this) }, 4000L)
        }
    }

    /** Emulator tests: the root shell through scripts/fake_adbd.py  (setprop debug.aura.adb 10.0.2.2:15555). */
    private fun testShell() {
        SystemProps.get("debug.aura.adb").split(":").takeIf { it.size == 2 }?.let { (h, p) ->
            p.toIntOrNull()?.let { com.abdllh.aura.system.LocalAdb.host = h; com.abdllh.aura.system.LocalAdb.port = it }
        }
    }

    private fun processName(): String =
        if (android.os.Build.VERSION.SDK_INT >= 28) getProcessName()
        else try { java.io.File("/proc/self/cmdline").readText().trim('\u0000', ' ', '\n') } catch (_: Throwable) { "" }
}
