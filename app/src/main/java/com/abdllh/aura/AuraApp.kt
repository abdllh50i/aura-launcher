package com.abdllh.aura

import android.app.Application
import com.abdllh.aura.system.AssistiveBall
import com.abdllh.aura.system.BootAnim
import com.abdllh.aura.system.CarAudio
import com.abdllh.aura.system.CrashGuard
import com.abdllh.aura.system.Device
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
        CrashGuard.install(this) // first: a crash while the rest of the start-up runs must be counted as well
        if (BuildConfig.DEBUG) {
            // emulator tests: the root shell through scripts/fake_adbd.py  (setprop debug.aura.adb 10.0.2.2:15555)
            SystemProps.get("debug.aura.adb").split(":").takeIf { it.size == 2 }?.let { (h, p) ->
                p.toIntOrNull()?.let { com.abdllh.aura.system.LocalAdb.host = h; com.abdllh.aura.system.LocalAdb.port = it }
            }
        }
        Dp.init(this)
        Prefs.init(this)
        Theme.refresh()
        Fonts.init(this)
        UpdateManager.init(this)
        CarAudio.init(this)
        // the status bar and the volume display are Aura's to change only while Aura is the home screen
        val disabled = SystemProps.get(Device.DISABLED_PROP) == "1"
        if (disabled) { SystemBars.release(this); VolumeHud.release(this) } else SystemBars.apply(this)
        Player.init(this)
        BtMusic.init(this)
        StockMusic.apply(this)
        WifiKeeper.start(this)
        ZLinkGuard.init(this)
        Power.init(this) // after an "off now": the ACC-off behaviour goes back to the user's choice
        if (!disabled) {
            VolumeHud.init(this)
            MusicKeys.init(this)
            Gear.init(this)
            BootAnim.ensure(this) // this build's boot animation, also after an update from the screen
            AssistiveBall.offOnce(this) // the stock floating circle the owner switched on by accident
        }
    }
}
