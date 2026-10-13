package com.abdllh.aura.phone

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.music.BtMusic
import com.abdllh.aura.system.Device
import com.abdllh.aura.system.LocalAdb
import com.abdllh.aura.system.SystemProps
import com.abdllh.aura.util.Prefs
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * The phone call going on, if any (the phone's, over Bluetooth): who, and whether it rings, is being dialled or is
 * being talked on; and what to do about it. [CallBanner] shows it (Settings → Display & sound → Calls; on by default)
 * in place of the stock window.
 *
 * Calls are the Bluetooth service's (com.bt.bc03, the same one as the phone's music: [BtMusic]); it tells everybody
 * with plain broadcasts (its HFPManager):
 *  - com.bt.ACTION_BT_INCOMING_CALL, then _INCOMING_NUMBER (extra_phone_number, extra_phone_name when the number is
 *    in the phone book it synced) and _INCOMING_NAME; the same three with OUTGOING for a call made from the phone;
 *  - com.bt.ACTION_BT_BEGIN_CALL_ONLINE when it is answered, com.bt.ACTION_BT_END_CALL when it is over;
 *  - Settings.System key_bt_state (3 ringing, 4 dialling, 5 talking; less: no call) and key_call_num, which also tell
 *    a process that started in the middle of one.
 * Answering is BTFeature.answerCall, declining and hanging up both cancelCall; or, when the service is not bound, its
 * broadcast com.nwd.bluetooth.hfp.operation (extra_hfp_operation 2 answer, 3 hang up).
 * The ring, the switch of the sound to the call and the mute of the music are the firmware's own (the service's
 * PlayerService, KernelService's MCU status): nothing of that depends on a window.
 *
 * The stock window is com.nwd.android.phone's FloatBar service: the phone app asks it (broadcast
 * com.nwd.android.phone.createFloat) for its small call dialog, drawn in one of the firmware's own top window layers
 * (type 2402), above anything an app may add. So it is turned off the way the stock volume bar is ([StockCallWindow]):
 * the component disabled through the root shell, enabled again by the switch, the kill switch and the ROM's restore.
 * The phone app itself stays as it is: its dialler, its contacts, its call log, the panel's answer / hang-up keys.
 */
object Calls {
    private const val TAG = "AuraCalls"
    private const val KEY = "callCard"
    /** Debug builds (the emulator has no Bluetooth module): --es state ring|dial|talk|end [--es number] [--es name] */
    private const val ACTION_DEBUG = "com.abdllh.aura.debug.CALL"
    private const val ENDED_MS = 1500L
    private const val ANSWER_MS = 4000L
    private const val QUIET_MS = 3000L
    private const val BT = "com.bt.ACTION_BT_"

    enum class State { IDLE, RINGING, DIALING, TALKING, ENDED }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    var context: Context? = null
        private set
    var state = State.IDLE
        private set
    var number = ""
        private set
    var name = ""
        private set
    /** When the call was answered (elapsedRealtime). */
    var talkingSince = 0L
        private set

    /** The user's switch (on by default): AMRI's card, not the stock window. */
    var enabled: Boolean
        get() = Prefs.raw.getBoolean(KEY, true)
        set(v) { Prefs.raw.edit().putBoolean(KEY, v).apply() }

    internal val unit get() = Device.isNwd || (BuildConfig.DEBUG && SystemProps.get("debug.aura.unit") == "1")
    val available get() = unit

    /** A call rings, is dialled or is talked on. */
    val busy get() = state == State.RINGING || state == State.DIALING || state == State.TALKING

    fun addListener(l: () -> Unit) { listeners.add(l) }

    fun init(ctx: Context) {
        if (context != null) return
        val c = ctx.applicationContext
        context = c
        CallBanner.init()
        try {
            c.registerReceiver(receiver, IntentFilter().apply {
                for (a in listOf("INCOMING_CALL", "INCOMING_NUMBER", "INCOMING_NAME", "OUTGOING_CALL", "OUTGOING_NUMBER",
                    "OUTGOING_NAME", "BEGIN_CALL_ONLINE", "END_CALL", "HFP_RELEASE")) addAction(BT + a)
                if (BuildConfig.DEBUG) addAction(ACTION_DEBUG)
            })
        } catch (t: Throwable) {
            Log.w(TAG, "receiver: $t")
        }
        if (!unit) return
        try {
            c.contentResolver.registerContentObserver(Settings.System.getUriFor("key_bt_state"), false, object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean) = readState()
            })
        } catch (_: Throwable) {
        }
        readState() // a call that was going on before this process started
        // not in the middle of the start-up, and after the volume display's turn (each restarts a stock process)
        main.postDelayed({ apply(c) }, 11_000L)
        main.postDelayed(watchKillSwitch, 2000L)
    }

    /** Brings the stock window and the ring in line with the switches; [done] true when both worked. */
    fun apply(ctx: Context, done: ((Boolean) -> Unit)? = null) {
        val c = ctx.applicationContext
        StockCallWindow.apply(c) { a -> Ringtone.apply(c) { b -> done?.invoke(a && b) } }
    }

    /** AMRI stops being the home screen (the kill switch): the stock window and the ring that was there come back. */
    fun release(ctx: Context) {
        StockCallWindow.release(ctx.applicationContext)
        Ringtone.release()
    }

    /** Everything AMRI changed in the firmware for itself that an older restore does not know of. */
    private fun giveBack(c: Context) {
        release(c)
        com.abdllh.aura.system.MusicKeys.release()
    }

    /**
     * The kill switch set while AMRI runs is the ROM's restore at work (its first step; it removes AMRI a few seconds
     * later): what AMRI turned off for itself goes back now, also where the restore is an older pack's that does not
     * know about it.
     */
    private val watchKillSwitch = object : Runnable {
        var was = SystemProps.get(Device.DISABLED_PROP) == "1"
        override fun run() {
            val off = SystemProps.get(Device.DISABLED_PROP) == "1"
            if (off && !was) context?.let { Log.i(TAG, "the kill switch: giving back"); giveBack(it) }
            was = off
            main.postDelayed(this, 2000L)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            try { // anything can send these: a broadcast with a bad extra must not take the home screen down
                val num = i.getStringExtra("extra_phone_number")
                val who = i.getStringExtra("extra_phone_name")
                when (i.action) {
                    BT + "INCOMING_CALL", BT + "INCOMING_NUMBER", BT + "INCOMING_NAME" ->
                        // (a number may come once more after the call was answered: it does not ring again)
                        set(if (state == State.TALKING) State.TALKING else State.RINGING, num, who)
                    BT + "OUTGOING_CALL", BT + "OUTGOING_NUMBER", BT + "OUTGOING_NAME" ->
                        set(if (state == State.TALKING) State.TALKING else State.DIALING, num, who)
                    BT + "BEGIN_CALL_ONLINE" -> set(State.TALKING, null, null)
                    BT + "END_CALL", BT + "HFP_RELEASE" -> set(State.IDLE, null, null)
                    ACTION_DEBUG -> if (BuildConfig.DEBUG) set(when (i.getStringExtra("state")) {
                        "ring" -> State.RINGING
                        "dial" -> State.DIALING
                        "talk" -> State.TALKING
                        else -> State.IDLE
                    }, i.getStringExtra("number"), i.getStringExtra("name"))
                }
            } catch (t: Throwable) {
                Log.w(TAG, "receive: $t")
            }
        }
    }

    /** What the service's own state says: for a call missed as a broadcast, and for one that ended unheard. */
    private fun readState() {
        val cr = context?.contentResolver ?: return
        val st = try { Settings.System.getInt(cr, "key_bt_state", 0) } catch (_: Throwable) { return }
        val next = when (st) { 3 -> State.RINGING; 4 -> State.DIALING; 5 -> State.TALKING; else -> State.IDLE }
        if (next == state || (next == State.IDLE && state == State.ENDED)) return
        val num = if (next == State.IDLE) null else try { Settings.System.getString(cr, "key_call_num") } catch (_: Throwable) { null }
        set(next, num, null)
    }

    /** Answered from the card, the phone has not said so yet: the card stops offering it (a few seconds at most). */
    var answering = false
        private set
    private val notAnswered = Runnable { if (answering) { answering = false; changed() } }
    /** Hung up from the card: what the phone still says about that call for a moment is not a new one. */
    private var quietUntil = 0L

    fun answer() {
        if (state != State.RINGING || answering) return
        Log.i(TAG, "answer")
        if (!BtMusic.answerCall()) operation(2)
        answering = true
        main.removeCallbacks(notAnswered)
        main.postDelayed(notAnswered, ANSWER_MS)
        changed()
        if (BuildConfig.DEBUG && !unit) main.postDelayed({ set(State.TALKING, null, null) }, 600L) // (the emulator: nobody answers back)
    }

    /**
     * Declines a call that rings, ends one that is dialled or talked on. The card says so at once — the press is
     * answered — and what the phone really did is looked at a moment later: a call that is still there shows again.
     */
    fun hangUp() {
        if (!busy) return
        Log.i(TAG, "hang up")
        if (!BtMusic.cancelCall()) operation(3)
        quietUntil = SystemClock.elapsedRealtime() + QUIET_MS
        set(State.IDLE, null, null)
        main.postDelayed({ quietUntil = 0L; readState() }, QUIET_MS + 200L)
    }

    private fun operation(op: Int) {
        try { context?.sendBroadcast(Intent("com.nwd.bluetooth.hfp.operation").putExtra("extra_hfp_operation", op)) } catch (_: Throwable) { }
    }

    private val forget = Runnable { if (state == State.ENDED) { state = State.IDLE; number = ""; name = ""; changed() } }

    /**
     * What the phone says now. [State.IDLE] after a call: "ended" shows for a moment first. A number or a name that is
     * not told again (null) is the one already known.
     */
    private fun set(next: State, num: String?, who: String?) {
        // switched off, or the stock window is still there (no root shell yet, no leave to draw over apps): it shows
        // the call, not AMRI — never the two together
        if (!enabled || context?.let { StockCallWindow.shows(it) } == true) {
            if (state != State.IDLE) { main.removeCallbacks(forget); state = State.IDLE; changed() }
            return
        }
        val was = state
        if (next != State.IDLE && SystemClock.elapsedRealtime() < quietUntil) return // (the call just hung up here)
        if (next != State.RINGING || was != State.RINGING) { answering = false; main.removeCallbacks(notAnswered) }
        if (next == State.IDLE) {
            if (was == State.IDLE || was == State.ENDED) return
            state = State.ENDED
            main.removeCallbacks(forget)
            main.postDelayed(forget, ENDED_MS)
        } else {
            main.removeCallbacks(forget)
            if (was == State.IDLE || was == State.ENDED) { number = ""; name = "" } // another call
            val sameState = next == was
            val n = num?.trim().orEmpty()
            val w = who?.trim().orEmpty()
            if (sameState && (n.isEmpty() || n == number) && (w.isEmpty() || w == name)) return // nothing new
            if (n.isNotEmpty()) number = n
            if (w.isNotEmpty()) name = w
            if (next == State.TALKING && was != State.TALKING) talkingSince = SystemClock.elapsedRealtime()
            state = next
        }
        if (was != state) Log.i(TAG, "$was -> $state")
        changed()
    }

    private fun changed() { for (l in listeners) try { l() } catch (t: Throwable) { Log.w(TAG, "listener: $t") } }
}

/**
 * The stock call window on or off (see [Calls]): its service component, disabled through the root shell while AMRI
 * shows calls itself — only when AMRI may draw over other apps, which the same shell allows; otherwise the stock
 * window stays.
 */
internal object StockCallWindow {
    private const val TAG = "AuraCallWindow"
    private const val APPLIED = "callWindowStockOff" // "on" while the stock window is off because of AMRI
    private const val PKG = "com.nwd.android.phone"
    private const val CLS = "com.nwd.android.phone.FloatBar"

    private val worker = Executors.newSingleThreadExecutor()

    fun apply(c: Context, done: ((Boolean) -> Unit)? = null) {
        if (!Calls.unit) { done?.invoke(true); return }
        worker.execute {
            val ok = try {
                // (not in the middle of a call: the change restarts the phone app's process)
                var waited = 0
                while (Calls.busy && waited++ < 120) Thread.sleep(5000L)
                if (Calls.enabled && SystemProps.get(Device.DISABLED_PROP) != "1") takeOver(c) else giveBack(c)
            } catch (t: Throwable) {
                Log.w(TAG, "apply: $t")
                false
            }
            done?.invoke(ok)
        }
    }

    fun release(c: Context) {
        if (!Calls.unit) return
        worker.execute { try { giveBack(c) } catch (t: Throwable) { Log.w(TAG, "release: $t") } }
    }

    private fun takeOver(c: Context): Boolean {
        if (!Settings.canDrawOverlays(c)) LocalAdb.run("appops set ${c.packageName} SYSTEM_ALERT_WINDOW allow")
        if (!Settings.canDrawOverlays(c)) return false // no card of AMRI's own: the stock window stays
        if (stockOn(c) != true) return true
        Prefs.raw.edit().putString(APPLIED, "on").apply()
        val r = LocalAdb.run("pm disable $PKG/$CLS")
        Log.i(TAG, "stock call window off: ${r.ok} ${r.output}")
        return stockOn(c) != true
    }

    private fun giveBack(c: Context): Boolean {
        if (Prefs.raw.getString(APPLIED, "") != "on") return true // never touched
        if (stockOn(c) == false) {
            val r = LocalAdb.run("pm enable $PKG/$CLS")
            Log.i(TAG, "stock call window back: ${r.ok} ${r.output}")
            if (stockOn(c) == false) return false
        }
        Prefs.raw.edit().putString(APPLIED, "off").apply()
        return true
    }

    /** The stock window will show a call (its service is there and enabled). */
    fun shows(c: Context) = Calls.unit && stockOn(c) == true

    /** Is the stock window's service enabled (null: not on this unit)? */
    private fun stockOn(c: Context): Boolean? = try {
        c.packageManager.getComponentEnabledSetting(ComponentName(PKG, CLS)) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    } catch (_: Throwable) {
        null
    }
}
