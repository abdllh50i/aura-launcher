package com.abdllh.aura.system

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log
import com.abdllh.aura.util.Prefs
import java.io.File
import java.util.concurrent.Executors

/**
 * Android's own notifications kept off the screen, and the phone's messages shown instead (Settings → Display & sound
 * → Notifications).
 *
 * The unit's apps post notifications like those of any Android device (the Play Store's updates, "USB debugging",
 * Google's services...), and Android shows each one at the top over whatever is in front: a heads-up card over the map
 * or the music. The owner wants none of them. Two things keep them away: Android's heads-up cards are switched off
 * (Settings.Global heads_up_notifications_enabled, what Android's own developer option writes; the value from before is
 * kept and put back when the switch goes off or AMRI stops being the home screen), and AMRI's notification listener
 * ([com.abdllh.aura.media.AuraNotificationListener]) takes every notification another app posts away the moment it is posted. One an app keeps up while
 * it runs (ongoing: a foreground service, a download) cannot be taken away; it stays in the shade, which nothing opens
 * on this screen.
 *
 * The phone's messages: an iPhone gives WhatsApp's notifications only to CarPlay and to a Bluetooth LE accessory that
 * speaks ANCS, and this unit's Bluetooth module cannot (its GOC stack, gocsdk_8800, has a GATT server for ZLink's
 * pairing but no GATT client, so no ANCS). WhatsApp itself runs on the unit as a linked device of the owner's account
 * instead (on the unit "Link as companion device"; on the iPhone WhatsApp → Settings → Linked devices → Link a device):
 * its notifications then arrive like any app's, and the listener shows the messaging ones as AMRI's own card
 * ([com.abdllh.aura.phone.MessageBanner]) — also while Android's are hidden.
 */
object Notices {
    private const val TAG = "AuraNotices"
    private const val KEY_HIDE = "hideAndroidNotices"
    private const val KEY_MESSAGES = "showPhoneMessages"
    private const val KEY_TEXT = "showMessageText"
    /** The heads-up setting from before AMRI switched it off (no file: AMRI has not touched it). */
    private const val BEFORE_FILE = "heads-up-before"
    private const val HEADS_UP = "heads_up_notifications_enabled"

    private val worker = Executors.newSingleThreadExecutor() // the root shell: one change at a time, never on main

    /** Android's notifications off the screen (on by default). */
    var hideAndroid: Boolean
        get() = Prefs.raw.getBoolean(KEY_HIDE, true)
        set(v) { Prefs.raw.edit().putBoolean(KEY_HIDE, v).apply() }

    /** Messages (WhatsApp and the other messaging apps on the unit) shown as AMRI's card (on by default). */
    var showMessages: Boolean
        get() = Prefs.raw.getBoolean(KEY_MESSAGES, true)
        set(v) { Prefs.raw.edit().putBoolean(KEY_MESSAGES, v).apply() }

    /** The card shows what the message says, not only who sent it (on by default). */
    var showText: Boolean
        get() = Prefs.raw.getBoolean(KEY_TEXT, true)
        set(v) { Prefs.raw.edit().putBoolean(KEY_TEXT, v).apply() }

    private fun listener(c: Context) = ComponentName(c.packageName, com.abdllh.aura.media.AuraNotificationListener::class.java.name)

    /** Brings the unit in line with the switches (at every start of AMRI, and when one changes). */
    fun apply(ctx: Context) {
        val c = ctx.applicationContext
        val hide = hideAndroid
        val listen = hide || showMessages
        worker.execute {
            try {
                headsUp(c, off = hide)
                // (with both switches off it stays allowed and does nothing: the media sessions may still need it)
                if (listen) allowListener(c, true)
            } catch (t: Throwable) {
                Log.w(TAG, "apply: $t")
            }
        }
    }

    /** AMRI stops being the home screen (the kill switch): Android's notifications as they were. */
    fun release(ctx: Context) {
        val c = ctx.applicationContext
        worker.execute {
            try {
                headsUp(c, off = false)
                allowListener(c, false)
            } catch (t: Throwable) {
                Log.w(TAG, "release: $t")
            }
        }
    }

    /**
     * Heads-up cards off (what was set before is kept), or back to what they were — only when AMRI switched them off:
     * an owner who had turned them off himself keeps that. The value from before is kept in a file of its own
     * (files/heads-up-before), which the ROM's restore reads too, as it does the animation scales.
     */
    private fun headsUp(c: Context, off: Boolean) {
        val cr = c.contentResolver
        val now = try { Settings.Global.getInt(cr, HEADS_UP, 1) } catch (_: Throwable) { 1 }
        val kept = File(c.filesDir, BEFORE_FILE)
        val before = try { kept.readText().trim().toInt() } catch (_: Throwable) { -1 }
        if (off) {
            if (now == 0) return
            if (putGlobal(c, HEADS_UP, 0)) try { kept.writeText("$now") } catch (t: Throwable) { Log.w(TAG, "keep: $t") }
        } else {
            if (before < 0) return
            if (now != 0 || putGlobal(c, HEADS_UP, before)) kept.delete()
        }
    }

    /**
     * AMRI's listener allowed or not. Since Android 8 the allowed listeners are the notification service's own list
     * (the secure setting "enabled_notification_listeners" is only read once, when a phone is first set up, and
     * writing it does nothing): through the system's call where AMRI holds the permission for it (a privileged copy
     * may), else the unit's root shell (`cmd notification allow_listener`), which also binds it at once.
     */
    private fun allowListener(c: Context, on: Boolean) {
        val comp = listener(c)
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        fun granted() = try { nm.isNotificationListenerAccessGranted(comp) } catch (_: Throwable) { !on }
        if (granted() == on) return
        try {
            NotificationManager::class.java.getMethod("setNotificationListenerAccessGranted", ComponentName::class.java,
                Boolean::class.javaPrimitiveType).invoke(nm, comp, on)
            if (granted() == on) { Log.i(TAG, "listener ${if (on) "allowed" else "removed"}"); return }
        } catch (t: Throwable) {
            Log.i(TAG, "listener through the API: ${t.cause ?: t}")
        }
        val r = LocalAdb.run("cmd notification ${if (on) "allow_listener" else "disallow_listener"} ${comp.flattenToString()}")
        Log.i(TAG, "listener through the shell (${if (on) "allow" else "disallow"}): ${r.ok} ${r.output.trim()} -> ${granted()}")
    }

    /** A Settings.Global value: through the API when AMRI may write secure settings, else the unit's root shell. */
    private fun putGlobal(c: Context, key: String, value: Int): Boolean {
        try {
            if (Settings.Global.putInt(c.contentResolver, key, value)) return true
        } catch (_: SecurityException) {
        }
        val r = LocalAdb.run("settings put global $key $value")
        if (!r.ok) Log.w(TAG, "settings put global $key: ${r.output}")
        return r.ok
    }
}
