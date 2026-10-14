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
import com.abdllh.aura.ui.Theme
import com.abdllh.aura.update.UpdateManager
import com.abdllh.aura.util.Dp
import com.abdllh.aura.util.Prefs

class AuraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // BootHome's process, right after a power-on: the user's storage is still closed (settings would throw) and
        // nothing of AMRI is wanted yet but that one waiting screen
        if (processName().endsWith(":boot")) return
        CrashGuard.install(this) // first: a crash while the rest of the start-up runs must be counted as well
        if (BuildConfig.DEBUG) {
            // emulator tests: the root shell through scripts/fake_adbd.py  (setprop debug.aura.adb 10.0.2.2:15555)
            SystemProps.get("debug.aura.adb").split(":").takeIf { it.size == 2 }?.let { (h, p) ->
                p.toIntOrNull()?.let { com.abdllh.aura.system.LocalAdb.host = h; com.abdllh.aura.system.LocalAdb.port = it }
            }
        }
        com.abdllh.aura.home.BootHome.ensure(this) // AMRI's home screen for the moment after a power-on (the unit only)
        Dp.init(this)
        Prefs.init(this)
        Theme.refresh()
        Fonts.init(this)
        // the map's own tile server and the offline map (before any map style is built)
        com.abdllh.aura.nav.offline.OfflineMaps.init(this)
        com.abdllh.aura.nav.MapExtras.init(this) // what the owner added to the map (cameras, bumps, signals, limits)
        UpdateManager.init(this)
        CarAudio.init(this)
        // the status bar, the volume display and the screen-change animations are Aura's to change only while Aura is
        // the home screen
        val disabled = SystemProps.get(Device.DISABLED_PROP) == "1"
        if (disabled) {
            SystemBars.release(this); VolumeHud.release(this); Animations.release(this)
            com.abdllh.aura.phone.Calls.release(this); MusicKeys.release()
        }
        else { SystemBars.apply(this); Animations.apply(this) }
        Animations.ensureAnimators()
        Player.init(this)
        BtMusic.init(this)
        StockMusic.apply(this)
        WifiKeeper.start(this)
        ZLinkGuard.init(this)
        com.abdllh.aura.system.WifiOnStart.init(this) // the Wi-Fi on when the car starts (the firmware's airplane mode off)
        Power.init(this) // after an "off now": the ACC-off behaviour goes back to the user's choice
        Front.init(this)
        if (!disabled) {
            VolumeHud.init(this)
            MusicKeys.init(this)
            HomeGuard.init(this) // a stock screen the firmware puts in front when the unit comes on: AMRI's instead
            NwdBridge.stopBootTip(this) // (one an earlier AMRI started this boot)
            com.abdllh.aura.system.BetaLeftovers.removeOnce(this) // what a 1.6.1 beta left over the Bluetooth daemon
            Gear.init(this)
            com.abdllh.aura.phone.Calls.init(this) // a phone call: AMRI's own card and ring, not the stock window
            BootAnim.ensure(this) // this build's boot animation, also after an update from the screen
            AssistiveBall.offOnce(this) // the stock floating circle the owner switched on by accident
            com.abdllh.aura.system.AppRemover.removeRequestedOnce(this) // the "Vivid" launcher the owner asked to remove
            com.abdllh.aura.voice.Amri.init(this) // the voice assistant (its wake word listener when switched on)
        }
    }

    private fun processName(): String =
        if (android.os.Build.VERSION.SDK_INT >= 28) getProcessName()
        else try { java.io.File("/proc/self/cmdline").readText().trim('\u0000', ' ', '\n') } catch (_: Throwable) { "" }
}
