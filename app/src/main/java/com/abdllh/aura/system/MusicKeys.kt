package com.abdllh.aura.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.util.Log
import com.abdllh.aura.home.Known
import com.abdllh.aura.media.MediaMonitor
import com.abdllh.aura.music.MusicActivity
import com.abdllh.aura.util.Prefs

/**
 * "Music buttons open Aura Music" (Settings → Display & sound → Music; on by default): when the stock Bluetooth music
 * screen or the stock music player comes up — the steering wheel's music button, the CAN box, the source key, the
 * app list — Aura Music opens on top of it, on the same source, right away. The same button pressed again inside
 * Aura Music goes out to the home screen (1.7), as it leaves the stock screen on the stock system. The firmware
 * decides that by the package in front (InputChangeSourceRule.singleChangeSource: the key's source is the current
 * one, but ProcessManager.getTopPackgeName is not its app — it is Aura — so "app is at top = false" and it starts
 * the stock screen once more instead of leaving). That stock screen coming up over Aura Music, on the source Aura
 * Music is playing, is the sign of the second press.
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

    /**
     * A stock music screen is in front, as [Front] tells it: the same answer as to its broadcasts, which are missed
     * when the screen came up before this process did (the unit coming on with that source).
     */
    fun stockScreen(c: Context, bt: Boolean) {
        if (!enabled || SystemProps.get(Device.DISABLED_PROP) == "1") return
        open(c, bt)
    }

    /** A press that came while the one before was still being answered: looked at when that is over. */
    private val later = Runnable {
        val c = app ?: return@Runnable
        Front.read()
        val bt = Front.pkg == Known.BT_MUSIC
        if (enabled && !Front.ownResumed && (bt || Front.pkg == Known.MUSIC)) open(c, bt)
    }

    private fun open(c: Context, bt: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (now - openedAt < ONCE_MS) {
            // one screen coming up, one answer (its broadcasts and [Front] both tell). But a quick second press must
            // not leave the stock screen in front: what is there is looked at again once this answer has had its time
            main.removeCallbacks(later)
            main.postDelayed(later, openedAt + ONCE_MS + 60L - now)
            return
        }
        // AMRI Music was what the owner looked at, on this very source: its button again, inside it. That is the way
        // out, to the home screen (the stock screen it brought up is covered like the first time, by the home screen
        // now). Another source's screen is its own button: AMRI Music changes source, as before.
        val same = bt == (Prefs.raw.getString("musicSource", "local") == "bt")
        if (same && Front.ownIs(MusicActivity::class.java, AGAIN_MS)) {
            if (!Front.ownResumed) { again(c); return }
            // (still in front as far as this process knows: only if something really comes up over it)
            main.postDelayed({ if (!Front.ownResumed && Front.ownIs(MusicActivity::class.java, AGAIN_MS)) again(c) }, 250L)
            return
        }
        openedAt = now
        Log.i(TAG, "stock ${if (bt) "Bluetooth music" else "music"} screen came up: Aura Music instead")
        MusicActivity.openInstead(c, bt)
    }

    private fun again(c: Context) {
        val now = SystemClock.elapsedRealtime()
        if (now - openedAt < ONCE_MS) return
        openedAt = now
        Log.i(TAG, "the music button again, inside Aura Music: the home screen")
        HomeGuard.showHome(c)
    }

    /** The signs of one stock screen coming up arrive within this long of each other. */
    private const val ONCE_MS = 800L
    /** AMRI Music left the front this long ago at most: what came up, came up over it. */
    private const val AGAIN_MS = 2500L
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
}
