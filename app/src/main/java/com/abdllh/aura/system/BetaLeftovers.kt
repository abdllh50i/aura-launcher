package com.abdllh.aura.system

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.abdllh.aura.R
import com.abdllh.aura.util.LocaleHelper
import com.abdllh.aura.util.Prefs
import java.io.File

/**
 * What the 1.6.1 betas left on a unit that ran one. To ask the phone where its song is, they put a changed copy of the
 * Bluetooth daemon over /system/bin/gocsdk_8800: a bind mount from /data/local/tmp/amri-bt-timing, made again by the
 * beta at every start (a mount does not outlive a restart of the unit). It did not work on the unit, and 1.7 is
 * 1.6.0's code again, so nothing would make that mount any more; what is still there is cleared once:
 * the mount is let go (the daemon that runs now runs on as it is; the unit's own comes back with its next start, and
 * the owner is told to restart once), the copy and the beta's files are removed.
 */
object BetaLeftovers {
    private const val TAG = "AuraBeta"
    private const val KEY = "betaLeftoversGone"
    private const val DAEMON = "/system/bin/gocsdk_8800"
    private const val DIR = "/data/local/tmp/amri-bt-timing"

    fun removeOnce(ctx: Context) {
        if (Prefs.raw.getBoolean(KEY, false)) return
        val c = ctx.applicationContext
        Thread {
            try { File(c.filesDir, "bt-timing-prepared").delete() } catch (_: Throwable) { }
            if (!Device.isNwd) return@Thread
            // as root (the unit's own adbd): the mount was made there, and the copy belongs to root
            val r = LocalAdb.run("if grep -q ' $DAEMON ' /proc/mounts; then umount -l $DAEMON && echo was-mounted; fi; rm -rf $DIR; echo cleared")
            val out = r.output.trim()
            if (!r.ok || !out.endsWith("cleared")) { Log.w(TAG, "not cleared yet: $out"); return@Thread } // (the next start tries again)
            Prefs.raw.edit().putBoolean(KEY, true).remove("btPhoneTiming").apply()
            Log.i(TAG, "the 1.6.1 betas' Bluetooth experiment is cleared: $out")
            if (out.contains("was-mounted")) Handler(Looper.getMainLooper()).post {
                try { Toast.makeText(LocaleHelper.wrap(c), R.string.beta_cleared_restart, Toast.LENGTH_LONG).show() } catch (_: Throwable) { }
            }
        }.start()
    }
}
