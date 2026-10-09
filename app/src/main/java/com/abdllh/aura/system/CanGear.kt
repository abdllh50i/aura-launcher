package com.abdllh.aura.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log
import java.util.concurrent.Executors

/**
 * The gear as the car's CAN box reports it, through the firmware's CAN app (com.nwd.can.setting, exported CanService,
 * binder com.nwd.can.sdk.outer.adil.ICanRemote4OuterFeature), the way the NWD apps get their car info:
 *  - initSdkCfg (transaction 2: "nwdapp", the CAN app's key for NWD apps, 0, "") lets the car-info frames out,
 *  - addCarInfoCallBack (17) adds a com.nwd.can.sdk.outer.adil.ICanRemoteModelCallback;
 *  - the CAN app then calls onDistributeCanData (1) with the packed car info, a frame 6E 02 71 <113 bytes> FF whose byte
 *    74 is the gear (RemoteProtocalPack.packCarInfo). Clients that registered for CarInfo objects (27) switch the CAN
 *    app to calling onDistributeCarInfo (2) for everybody instead; those are read too ([CARINFO_V26], the CarInfo
 *    parcel of CAN app v.26: its 105th value is the gear). Aura never registers 27 itself: the stock clients only
 *    understand the frames.
 * CAN app v.24 (the copy in /system) has no gear; v.26 fills it only for some cars and boxes — the Raise boxes (Changan
 * among them) give 1 P, 2 R, 3 N, 4 D (11..15: the forward gears of S / M). Frames of another layout are ignored.
 */
object CanGear {
    private const val TAG = "AuraCanGear"
    private const val PKG = "com.nwd.can.setting"
    private const val ACTION = "com.nwd.can.service.ACTION_CAN_SERVICE"
    private const val FEATURE = "com.nwd.can.sdk.outer.adil.ICanRemote4OuterFeature"
    private const val CALLBACK = "com.nwd.can.sdk.outer.adil.ICanRemoteModelCallback"
    private const val TX_INIT_SDK = 2
    private const val TX_ADD_CAR_INFO_CALLBACK = 17
    private const val TX_ON_CAN_DATA = 1
    private const val TX_ON_CAR_INFO = 2
    private const val SDK_APP = "nwdapp"
    private const val SDK_KEY = "d6049e9ae396480cbd8358ed3d07df21"
    private const val FRAME_SIZE = 117
    private const val FRAME_GEAR = 74
    private const val GEAR_AT = 104 // in CARINFO_V26
    private const val CARINFO_V26 =
        "IIIIIIIFFFIIIFFIIIIBIIIIFFFFFBFBFFBIBBBBIIIIIBBBBBBBBBBBBBBIBBBBIBIBBIBIBBBIBIBBSSIIBBFFFFFFBBBBBBBBBBBB" +
            "BBBBBBBBBFFIIIIIBBBBBBBBBBBBBBBBBBFSSB"

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor() // calls into the CAN app wait on its lock: never on main
    private var app: Context? = null
    private var onGear: ((Int) -> Unit)? = null

    /** The last raw gear value the CAN app sent (-1: none), for the settings page. */
    @Volatile var raw = -1; private set

    /** Is a CAN app with a gear in its car info installed (v.26 or later)? */
    fun supported(c: Context): Boolean = try {
        val v = c.packageManager.getPackageInfo(PKG, 0).versionName.orEmpty() // "v.26.04.09A_..."
        (Regex("""v\.(\d+)\.""").find(v)?.groupValues?.get(1)?.toIntOrNull() ?: 0) >= 26
    } catch (_: Throwable) {
        false
    }

    /** Starts listening; [gear] gets each new raw value (-1: gone) on the main thread. */
    fun start(ctx: Context, gear: (Int) -> Unit) {
        if (app != null) return
        val c = ctx.applicationContext
        if (!supported(c)) return
        app = c
        onGear = gear
        bind()
    }

    private fun bind() {
        val c = app ?: return
        try {
            c.bindService(Intent(ACTION).setPackage(PKG), connection, Context.BIND_AUTO_CREATE)
        } catch (t: Throwable) {
            Log.w(TAG, "bind: $t")
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
            val s = b ?: return
            worker.execute {
                call(s, TX_INIT_SDK) { it.writeString(SDK_APP); it.writeString(SDK_KEY); it.writeByte(0); it.writeString("") }
                if (call(s, TX_ADD_CAR_INFO_CALLBACK) { it.writeStrongBinder(callback) }) Log.i(TAG, "listening to the CAN car info")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) = gone() // the system binds again when it is back

        override fun onBindingDied(name: ComponentName?) {
            gone()
            try { app?.unbindService(this) } catch (_: Throwable) { }
            main.postDelayed({ bind() }, 5000)
        }
    }

    private fun gone() {
        raw = -1
        main.post { onGear?.invoke(-1) } // an old gear must not stay on screen
    }

    private fun call(b: IBinder, code: Int, write: (Parcel) -> Unit): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(FEATURE)
            write(data)
            b.transact(code, data, reply, 0)
            reply.readException()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "call $code: $t")
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private val callback = object : Binder() {
        init { attachInterface(null, CALLBACK) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString(CALLBACK); return true }
            if (code < FIRST_CALL_TRANSACTION || code > LAST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
            try {
                data.enforceInterface(CALLBACK)
                val g = when (code) {
                    TX_ON_CAN_DATA -> fromFrame(data.createByteArray())
                    TX_ON_CAR_INFO -> if (data.readInt() != 0) fromCarInfo(data) else null
                    else -> null
                }
                if (g != null && g != raw) { raw = g; main.post { onGear?.invoke(g) } }
            } catch (t: Throwable) {
                Log.w(TAG, "car info: $t")
            }
            reply?.writeNoException()
            return true
        }
    }

    /** The packed car info: 6E 02 71, 113 data bytes, FF; anything else (other kinds of data, another layout) is not it. */
    private fun fromFrame(f: ByteArray?): Int? {
        if (f == null || f.size != FRAME_SIZE) return null
        if (f[0] != 0x6E.toByte() || f[1] != 2.toByte() || f[2] != 113.toByte() || f[FRAME_SIZE - 1] != 0xFF.toByte()) return null
        val g = f[FRAME_GEAR].toInt()
        return if (g in 0..31) g else null
    }

    /** The CarInfo parcel of CAN app v.26, all of it: a parcel of another length is another layout and is not used. */
    private fun fromCarInfo(p: Parcel): Int? {
        var gear = -1
        for ((i, t) in CARINFO_V26.withIndex()) {
            if (p.dataAvail() <= 0) return null
            when (t) {
                'I' -> p.readInt()
                'F' -> p.readFloat()
                'B' -> { val v = p.readByte().toInt(); if (i == GEAR_AT) gear = v }
                'S' -> p.readString()
            }
        }
        if (p.dataAvail() != 0) return null
        return if (gear in 0..31) gear else null
    }
}
