package com.abdllh.aura.guard

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.abdllh.aura.system.SystemProps

/**
 * Debug builds only (every caller stands behind BuildConfig.DEBUG): the failures the guard exists for, on demand, since
 * the emulator has none of them by itself. Read by AuraApp's first lines, so a property set before a start decides it.
 *   setprop debug.aura.guard.throw 1       a throw in AuraApp.onCreate (the crash CrashGuard's old check never saw)
 *   setprop debug.aura.guard.hangstart 1   the main thread stuck in AuraApp.onCreate
 *   setprop debug.aura.guard.hang 1        the main thread stuck right after home's first frame
 *   setprop debug.aura.guard.slow 150000   AuraApp.onCreate takes this many ms (a first start while dexopt runs)
 *   setprop debug.aura.guard.trialmin 2    the guard's trial takes this many minutes of use instead of 30
 */
internal object GuardTest {
    private const val TAG = "AuraGuardTest"

    fun atStart() {
        SystemProps.get("debug.aura.guard.slow").toLongOrNull()?.let {
            Log.w(TAG, "a slow start: $it ms")
            Thread.sleep(it)
        }
        if (SystemProps.get("debug.aura.guard.hangstart") == "1") stuck()
        if (SystemProps.get("debug.aura.guard.throw") == "1") throw IllegalStateException("debug.aura.guard.throw")
    }

    fun atDrawn() {
        if (SystemProps.get("debug.aura.guard.hang") == "1") Handler(Looper.getMainLooper()).post { stuck() }
    }

    fun trialMinutes(): Int? = SystemProps.get("debug.aura.guard.trialmin").toIntOrNull()

    private fun stuck() {
        Log.w(TAG, "the main thread is stuck on purpose")
        while (true) Thread.sleep(60_000L)
    }
}
