package com.abdllh.aura.home

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * A class name that keeps working for a screen that is now a panel of the one window: anyone may start it (adb, a script,
 * an outside intent, the firmware for Music), nothing shows, and [Screens] takes it from there. No window (Theme.NoDisplay,
 * which also means it must have finished by the end of this onCreate), no history, no place in Recents, no task of its
 * own to come back to.
 *
 * A class and not an activity-alias of HomeActivity: an alias started by name with no home screen alive would build it as
 * an ordinary activity, the first half of the trap in [Screens]. This always goes through the home intent.
 */
abstract class Entry(private val panel: String) : Activity() {
    // (starting any activity puts the system's language back as Java's default, and nothing else in the one window would set it again: the strip's ص, every label's direction. See LocaleHelper.holdDefault)
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(com.abdllh.aura.util.LocaleHelper.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            beforeOpen(intent)
            Screens.forward(this, panel, intent.extras)
        } catch (t: Throwable) {
            Log.w("AuraScreens", "entry $panel: $t") // (extras from outside that do not unpack: nothing opens)
        } finally {
            finish()
        }
    }

    /** What the start asks for that is not for the panel (debug hooks). */
    protected open fun beforeOpen(i: Intent) {}
}
