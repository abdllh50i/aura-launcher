package com.abdllh.aura

import android.app.Application
import com.abdllh.aura.system.CrashGuard
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.update.UpdateManager
import com.abdllh.aura.util.Dp
import com.abdllh.aura.util.Prefs

class AuraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashGuard.install(this) // first: a crash while the rest of the start-up runs must be counted as well
        Dp.init(this)
        Prefs.init(this)
        Fonts.init(this)
        UpdateManager.init(this)
    }
}
