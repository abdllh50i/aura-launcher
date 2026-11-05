package com.abdllh.aura.system

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.abdllh.aura.util.Prefs
import java.util.concurrent.Executors

/**
 * Turning the unit off for real with the car (Settings → Car and system). The MCU, not Android, holds the power, so it is
 * asked over the firmware's own channel: broadcast com.nwd.action.EMU_SEND_DATA_TO_MCU with byte[] "protocal", which
 * KernelService writes as is to the MCU's serial line. A frame is F0 LEN TYPE SUB 00 DATA... SUM (KernelProtocal:
 * LEN = data + 3, SUM = low byte of the sum of every byte after F0 but itself).
 * What ACC off does is the factory option "sleep_power_off" (TYPE 7B SUB 1F, DATA [mode, hours]): 0 sleep (the
 * stock setting), 1 power off. The firmware sends it again from /data/nwdappconfig/app/FactoryConfig.ini at every
 * boot (the setting service's initFactory), so the switch is written there too (as root; the file is kept as
 * FactoryConfig.ini.pre-aura first, which the ROM's restore puts back) and sent again once the boot has settled.
 * The MCU's firmware is not in the dump: what it does with these is the firmware's documented intent, not tested.
 */
object Power {
    private const val TAG = "AuraPower"
    private const val ACTION_TO_MCU = "com.nwd.action.EMU_SEND_DATA_TO_MCU"
    private const val INI = "/data/nwdappconfig/app/FactoryConfig.ini"
    private const val KEY_FULL_OFF = "fullOffOnAccOff"   // the user's switch
    private const val KEY_APPLIED = "accOffModeSent"     // the ACC-off mode Aura last set (-1: never touched)
    private const val KEY_PENDING = "powerOffAskedAt"    // an "off now" of a version that had that button
    private const val KEY_PENDING_BOOT = "powerOffAskedBoot"
    private const val MODE_SLEEP = 0
    private const val MODE_OFF = 1

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor() // the root shell, one change at a time
    private var app: Context? = null

    /** The switch: power off completely when the car is switched off, instead of sleeping (off by default). */
    var fullOffOnAccOff: Boolean
        get() = Prefs.raw.getBoolean(KEY_FULL_OFF, false)
        set(v) { Prefs.raw.edit().putBoolean(KEY_FULL_OFF, v).apply() }

    /** Can this unit do it at all (an NWD unit with the firmware's kernel service)? */
    val available: Boolean get() = Device.isNwd

    /** At every start: an "off now" left by an older version is over (the mode goes back to the switch's), then the switch. */
    fun init(ctx: Context) {
        if (app != null) return
        val c = ctx.applicationContext
        app = c
        if (!available) return
        val at = Prefs.raw.getLong(KEY_PENDING, 0L)
        if (at != 0L) Prefs.raw.edit().remove(KEY_PENDING).remove(KEY_PENDING_BOOT).apply()
        apply(c, force = at != 0L)
        // the firmware sends the factory file's mode at every boot, possibly after this: once more when it has settled
        if (fullOffOnAccOff) afterBoot { apply(c) }
    }

    /**
     * Brings the unit in line with the switch: "power off" while it is on (sent, and kept in the factory file), "sleep"
     * only to undo Aura's own "power off" — a unit Aura never changed keeps its own setting.
     */
    fun apply(ctx: Context, force: Boolean = false) {
        if (!available) return
        val c = ctx.applicationContext
        val want = if (fullOffOnAccOff) MODE_OFF else MODE_SLEEP
        if (!force && want == MODE_SLEEP && Prefs.raw.getInt(KEY_APPLIED, -1) != MODE_OFF) return
        send(c, sleepMode(want))
        Prefs.raw.edit().putInt(KEY_APPLIED, want).apply()
        worker.execute { keepInFactoryFile(want) }
    }

    /**
     * The mode in the factory file, which the firmware sends at every boot: "power off" written in (the original kept
     * as .pre-aura once), "sleep" written back only where Aura wrote it.
     */
    private fun keepInFactoryFile(mode: Int) {
        val cmd = if (mode == MODE_OFF) {
            "F=$INI; [ -f \$F ] || exit 0; [ -f \$F.pre-aura ] || cp -p \$F \$F.pre-aura; " +
                "if grep -q '^sleep_power_off=' \$F; then sed -i 's/^sleep_power_off=.*/sleep_power_off=1/' \$F; " +
                "else echo 'sleep_power_off=1' >> \$F; fi; grep '^sleep_power_off=' \$F"
        } else {
            "F=$INI; [ -f \$F.pre-aura ] || exit 0; sed -i 's/^sleep_power_off=.*/sleep_power_off=0/' \$F; grep '^sleep_power_off=' \$F"
        }
        val r = LocalAdb.run(cmd)
        Log.i(TAG, "factory file: ${r.ok} ${r.output}")
    }

    /** Runs [then] about half a minute and a minute and a half after the boot has completed. */
    private fun afterBoot(then: () -> Unit) {
        val poll = object : Runnable {
            var tries = 0
            override fun run() {
                if (SystemProps.get("sys.boot_completed") == "1" || ++tries > 60) {
                    main.postDelayed(then, 30_000L)
                    main.postDelayed(then, 90_000L)
                } else {
                    main.postDelayed(this, 3000L)
                }
            }
        }
        main.post(poll)
    }

    private fun sleepMode(mode: Int) = frame(0x7B, 0x1F, byteArrayOf(mode.toByte(), 0))

    /** An MCU frame the way KernelProtocal builds it. */
    fun frame(type: Int, sub: Int, data: ByteArray): ByteArray {
        val f = ByteArray(data.size + 6)
        f[0] = 0xF0.toByte()
        f[1] = (data.size + 3).toByte()
        f[2] = type.toByte()
        f[3] = sub.toByte()
        f[4] = 0
        data.copyInto(f, 5)
        var sum = 0
        for (i in 1 until f.size - 1) sum += f[i].toInt() and 0xFF
        f[f.size - 1] = sum.toByte()
        return f
    }

    private fun send(c: Context, frame: ByteArray) {
        try {
            c.sendBroadcast(Intent(ACTION_TO_MCU).putExtra("protocal", frame))
            Log.i(TAG, "to MCU: " + frame.joinToString(" ") { String.format(java.util.Locale.ROOT, "%02X", it.toInt() and 0xFF) })
        } catch (t: Throwable) {
            Log.w(TAG, "send: $t")
        }
    }
}
