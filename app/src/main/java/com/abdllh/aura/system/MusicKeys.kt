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
 *
 * All of that answers a stock screen that is already in front: the owner saw it for a moment before Aura Music covered
 * it. Nothing tells of the press earlier (the key's own broadcast is not sent for a key that has a rule, and the
 * source's bookkeeping changes in the same call that starts the screen). So since 1.9 the two music keys are taken
 * out of the firmware's key rules ([applyRules]): /data/nwdappconfig/app/KeyRuleConfig.xml maps key 83 to the music
 * player (app 2) and 99 to Bluetooth music (15), each a <KeyItem> of its own; a key with no rule is only announced
 * (InputChangeSourceRule.isCanPanelChegeSource: "no keyItem can response" → sendKey → com.nwd.action.ACTION_KEY_VALUE,
 * byte extra_key_value — the way the volume keys arrive) and nothing is started. Aura Music then opens at the press
 * itself ([key]), with no stock screen under it. KernelService reads the rules once, when it starts: the change works
 * from the unit's next start (until then, and for every other way a stock music screen comes up — the MODE key's
 * round of sources, a CAN box that starts the screen itself, the ignition, the app list — the answer above stays).
 * The file is kept as KeyRuleConfig.xml.pre-aura, which the switch going off and the ROM's restore put back.
 */
object MusicKeys {
    private const val TAG = "AuraMusicKeys"
    private const val KEY = "musicKeysAura"
    private const val PAIR_MS = 1500L
    private const val RULES = "/data/nwdappconfig/app/KeyRuleConfig.xml"
    private const val KEY_RULES = "musicKeyRulesOut" // "on" while the two keys are out of the firmware's rules
    private const val KEY_NOTE = "musicKeyNote"
    private const val KEY_MUSIC = 83
    private const val KEY_BT_MUSIC = 99
    /**
     * One press, one answer: the firmware's own pace for a key that changes the source (isAllowChangeSource, "press
     * too fast"). A key held down is announced again and again, and must not open the screen and leave it at once.
     */
    private const val KEY_ONCE_MS = 1500L
    private const val END = "__aura_end__"

    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var keyAt = -1_000_000L

    /** How the music button last reached AMRI, for Settings → About ("" when it never did). */
    val note: String get() = Prefs.raw.getString(KEY_NOTE, "").orEmpty()

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
                addAction("com.nwd.action.ACTION_KEY_VALUE")
            })
        } catch (t: Throwable) {
            Log.w(TAG, "receiver: $t")
        }
        // (not in the middle of the start-up; the rules are only read at the unit's next start anyway)
        main.postDelayed({ applyRules() }, 15_000L)
    }

    // ------------------------------------------------------------------------------------------ the key itself
    private val unit get() = Device.isNwd || (com.abdllh.aura.BuildConfig.DEBUG && SystemProps.get("debug.aura.unit") == "1")

    /**
     * Brings the firmware's key rules in line with the switch: the two music keys out of them while it is on, the
     * rules as they were when it is off (or when AMRI is not the home screen). [done]: true when it worked.
     */
    fun applyRules(done: ((Boolean) -> Unit)? = null) {
        if (!unit) { done?.invoke(true); return }
        worker.execute {
            val ok = try {
                if (enabled && SystemProps.get(Device.DISABLED_PROP) != "1") takeKeys() else giveKeysBack()
            } catch (t: Throwable) {
                Log.w(TAG, "rules: $t")
                false
            }
            done?.invoke(ok)
        }
    }

    /** AMRI stops being the home screen (the kill switch): the firmware's own rules again. */
    fun release() {
        if (!unit) return
        worker.execute { try { giveKeysBack() } catch (t: Throwable) { Log.w(TAG, "release: $t") } }
    }

    private fun sh(script: String): String? {
        val r = LocalAdb.run("(\n$script\n)\necho $END", 20_000)
        if (!r.ok) return null
        val out = r.output.trimEnd()
        return if (out.endsWith(END)) out.removeSuffix(END).trimEnd() else null
    }

    /**
     * The two <KeyItem> blocks cut out of a copy, the copy checked (every rule still opens and closes, the list ends,
     * the other keys are there: a file the firmware cannot read loses every key's rule without a word), then written
     * through the file itself, which keeps its owner and label.
     */
    private fun takeKeys(): Boolean {
        val q = "\""
        // no root shell at every start: the file itself says whether there is anything to do, where it can be read
        // (a reset of the firmware's settings brings the stock rules back); else what was done last
        val there = try {
            java.io.File(RULES).readText().let { "<KeyItem Key=$q$KEY_MUSIC$q" in it || "<KeyItem Key=$q$KEY_BT_MUSIC$q" in it }
        } catch (_: Throwable) {
            null
        }
        if (there == false || (there == null && Prefs.raw.getString(KEY_RULES, "") == "on")) return true
        val out = sh("""
            F=$RULES; T=${'$'}F.aura-tmp
            [ -f ${'$'}F ] || { echo no-file; exit 0; }
            grep -q '<KeyItem Key=${q}$KEY_MUSIC$q' ${'$'}F || grep -q '<KeyItem Key=${q}$KEY_BT_MUSIC$q' ${'$'}F || { echo out; exit 0; }
            [ -f ${'$'}F.pre-aura ] || cp -p ${'$'}F ${'$'}F.pre-aura || { echo failed backup; exit 1; }
            sed -e '/<KeyItem Key=${q}$KEY_MUSIC$q/,/<\/KeyItem>/d' -e '/<KeyItem Key=${q}$KEY_BT_MUSIC$q/,/<\/KeyItem>/d' ${'$'}F > ${'$'}T || { rm -f ${'$'}T; echo failed sed; exit 1; }
            O=${'$'}(grep -c '<KeyItem Key=$q' ${'$'}T); C=${'$'}(grep -c '</KeyItem>' ${'$'}T)
            if [ "${'$'}O" = "${'$'}C" ] && [ "${'$'}O" -ge 5 ] && grep -q '</KeyRuleList>' ${'$'}T && grep -q '<KeyItem Key=${q}23$q' ${'$'}T \
               && ! grep -q '<KeyItem Key=${q}$KEY_MUSIC$q' ${'$'}T && ! grep -q '<KeyItem Key=${q}$KEY_BT_MUSIC$q' ${'$'}T; then
              cat ${'$'}T > ${'$'}F && rm -f ${'$'}T && sync && echo out
            else
              rm -f ${'$'}T; echo "failed check ${'$'}O ${'$'}C"; exit 1
            fi
        """.trimIndent()) ?: return false
        Log.i(TAG, "key rules: $out")
        val lines = out.lines()
        if ("no-file" in lines) return true // not this firmware's layout: the keys stay the firmware's
        if ("out" !in lines) return false
        Prefs.raw.edit().putString(KEY_RULES, "on").apply()
        return true
    }

    private fun giveKeysBack(): Boolean {
        if (Prefs.raw.getString(KEY_RULES, "") != "on") return true // never touched
        val out = sh("""
            F=$RULES
            if [ -f ${'$'}F.pre-aura ]; then cat ${'$'}F.pre-aura > ${'$'}F && rm -f ${'$'}F.pre-aura && sync && echo back; else echo back; fi
        """.trimIndent()) ?: return false
        Log.i(TAG, "key rules: $out")
        if ("back" !in out.lines()) return false
        Prefs.raw.edit().putString(KEY_RULES, "off").apply()
        return true
    }

    /**
     * The wheel's (or the panel's) music key itself, announced because its rule is gone: AMRI Music on that key's
     * source, at once; pressed again inside it on that source, out to the home screen. What the firmware refused for
     * a key of its own is refused here: not during a phone call, not while reversing.
     */
    private fun key(c: Context, bt: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (now - keyAt < KEY_ONCE_MS) return
        keyAt = now
        note("key ${if (bt) KEY_BT_MUSIC else KEY_MUSIC}")
        if (com.abdllh.aura.phone.Calls.busy || Gear.current == Gear.Pos.R) return
        if (!enabled || SystemProps.get(Device.DISABLED_PROP) == "1") {
            // the switch went off since the unit started (the rules come back at its next start): the stock screen,
            // as the firmware would have opened it
            com.abdllh.aura.home.AppRepo.launchComponent(c, if (bt) Known.BT_MUSIC else Known.MUSIC,
                (if (bt) Known.BT_MUSIC else Known.MUSIC) + ".home_horizontalActivity")
            return
        }
        val same = bt == (Prefs.raw.getString("musicSource", "local") == "bt")
        if (same && Front.ownResumed && Front.own == MusicActivity::class.java.name) {
            Log.i(TAG, "the music key inside Aura Music: the home screen")
            openedAt = now
            HomeGuard.showHome(c)
            return
        }
        openedAt = now
        Log.i(TAG, "the ${if (bt) "Bluetooth music" else "music"} key: Aura Music")
        MusicActivity.openInstead(c, bt)
    }

    private fun note(what: String) {
        try {
            val at = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.ROOT).format(java.util.Date())
            Prefs.raw.edit().putString(KEY_NOTE, "$at  $what").apply()
        } catch (_: Throwable) {
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            try { // anything can send these: a broadcast with a bad extra must not take the home screen down
                if (i.getBooleanExtra(MediaMonitor.EXTRA_SELF, false)) return
                val now = SystemClock.elapsedRealtime()
                when (i.action) {
                    "com.nwd.action.ACTION_KEY_VALUE" -> {
                        // (a byte; only keys without a rule are announced: the two music keys once theirs are gone)
                        val k = (i.extras?.get("extra_key_value") as? Number)?.toInt() ?: return
                        if (k == KEY_MUSIC || k == KEY_BT_MUSIC) key(c, k == KEY_BT_MUSIC)
                    }
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
        note("stock ${if (bt) "BT music" else "music"} screen")
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
