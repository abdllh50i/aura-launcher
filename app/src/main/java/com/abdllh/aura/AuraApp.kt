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
        Dp.init(this)
        Prefs.init(this)
        Fonts.init(this)
        UpdateManager.init(this)
        CrashGuard.install(this)
    }
}
