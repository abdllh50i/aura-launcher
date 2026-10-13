package com.abdllh.aura.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * The unit has started. Being asked is the point: AMRI's process is up from here on, whatever screen the firmware put
 * in front, and [HomeGuard] (started by AuraApp) looks at it — a stock home screen in AMRI's place is covered without
 * the owner pressing anything. [com.abdllh.aura.home.BootHome] is what keeps the stock home screen from starting at
 * all; this is for the day that does not work.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Log.i("AuraBoot", "started: ${i.action}")
    }
}
