package com.abdllh.aura.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.util.Log
import com.abdllh.aura.media.MediaMonitor
import com.abdllh.aura.music.MusicActivity
import com.abdllh.aura.util.Prefs

/**
 * "Music buttons open Aura Music" (Settings → Display & sound → Music; on by default): when the stock Bluetooth music
 * screen or the stock music player comes up — the steering wheel's music button, the CAN box, the source key, the
 * app list — Aura Music opens on top of it, on the same source, right away.
 *
 * Why not the firmware's replace_source_list.xml: an app named there becomes a "source" app, and the firmware
 * force-stops the app of a source it leaves (MCU "pop source" → ACTION_STOP_APP). That would be Aura — the home screen,
 * with the navigation in it. And a CAN box's music button may start the stock screen directly, past that list.
 *
 * The stock screens announce themselves when they come to the front (onResume, not when started in the background):
 *  - Bluetooth music: com.nwd.music.stop, video.stop, ipod.stop, com.music.action.STOP_QQ_MUSIC, then
 *    com.nwd.ACTION_MEDIA_PLAY (extra_app_id 15);
 *  - music player:   com.bt.ACTION_A2DP_MUTE, video.stop, ipod.stop, then com.nwd.ACTION_MEDIA_PLAY (2).
 * The firmware also sends MEDIA_PLAY 15 alone when a phone-projection app starts, and Aura sends its own (marked with
 * [MediaMonitor.EXTRA_SELF]): the broadcast just before tells the stock screens apart.
 */
object MusicKeys {
    private const val TAG = "AuraMusicKeys"
    private const val KEY = "musicKeysAura"
    private const val PAIR_MS = 1500L

    private var app: Context? = null
    private var qqStopAt = -1_000_000L   // the stock Bluetooth music screen's first sign
    private var a2dpMuteAt = -1_000_000L // the stock music player's (not Aura's)
    private var openedAt = -1_000_000L

    /** The user's switch (on by default). */
    var enabled: Boolean
        get() = Prefs.raw.getBoolean(KEY, true)
        set(v) { Prefs.raw.edit().putBoolean(KEY, v).apply() }

    fun init(ctx: Context) {
        if (app != null) return
        val c = ctx.applicationContext
        app = c
        try {
            c.registerReceiver(receiver, IntentFilter().apply {
                addAction("com.music.action.STOP_QQ_MUSIC")
                addAction("com.bt.ACTION_A2DP_MUTE")
                addAction("com.nwd.ACTION_MEDIA_PLAY")
            })
        } catch (t: Throwable) {
            Log.w(TAG, "receiver: $t")
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            try { // anything can send these: a broadcast with a bad extra must not take the home screen down
                if (i.getBooleanExtra(MediaMonitor.EXTRA_SELF, false)) return
                val now = SystemClock.elapsedRealtime()
                when (i.action) {
                    "com.music.action.STOP_QQ_MUSIC" -> qqStopAt = now
                    "com.bt.ACTION_A2DP_MUTE" -> a2dpMuteAt = now
                    "com.nwd.ACTION_MEDIA_PLAY" -> {
                        // off, or Aura is not the home app (the kill switch): the stock screens stay
                        if (!enabled || SystemProps.get(Device.DISABLED_PROP) == "1") return
                        when (i.getIntExtra("extra_app_id", -1)) {
                            15 -> if (now - qqStopAt < PAIR_MS) open(c, bt = true)
                            2 -> if (now - a2dpMuteAt < PAIR_MS) open(c, bt = false)
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "receive: $t")
            }
        }
    }

    private fun open(c: Context, bt: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (now - openedAt < 1500L) return // one screen coming up, one Aura Music
        openedAt = now
        Log.i(TAG, "stock ${if (bt) "Bluetooth music" else "music"} screen came up: Aura Music instead")
        MusicActivity.openInstead(c, bt)
    }
}
