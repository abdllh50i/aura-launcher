package com.abdllh.aura

import android.app.Application
import com.abdllh.aura.system.CarAudio
import com.abdllh.aura.system.CrashGuard
import com.abdllh.aura.system.Device
import com.abdllh.aura.system.SystemBars
import com.abdllh.aura.system.SystemProps
import com.abdllh.aura.system.StockMusic
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
        Dp.init(this)
        Prefs.init(this)
        Theme.refresh()
        Fonts.init(this)
        UpdateManager.init(this)
        CarAudio.init(this)
        // the status bar is Aura's to hide only while Aura is the home screen
        if (SystemProps.get(Device.DISABLED_PROP) == "1") SystemBars.release(this) else SystemBars.apply(this)
        Player.init(this)
        BtMusic.init(this)
        StockMusic.apply(this)
    }
}
