package com.abdllh.aura.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.abdllh.aura.system.CrashGuard
import java.io.File

/**
 * Installs an APK through the platform PackageInstaller. On the car (Aura is a privileged system app with
 * INSTALL_PACKAGES) this is silent; elsewhere the system confirmation screen is shown automatically.
 */
object ApkInstaller {
    const val ACTION = "com.abdllh.aura.INSTALL_RESULT"

    /**
     * Streams [apk] into a new install session and commits it. [onSession] is called with the session id *before* the
     * commit, so the result receiver can always match the answer to this session.
     */
    fun install(ctx: Context, apk: File, onSession: (Int) -> Unit) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        params.setSize(apk.length())
        val id = pi.createSession(params)
        onSession(id)
        val session = pi.openSession(id)
        try {
            apk.inputStream().use { input ->
                session.openWrite("aura.apk", 0, apk.length()).use { out ->
                    input.copyTo(out, 64 * 1024)
                    session.fsync(out)
                }
            }
            val intent = Intent(ACTION).setPackage(ctx.packageName)
            val pending = PendingIntent.getBroadcast(ctx, id, intent, PendingIntent.FLAG_UPDATE_CURRENT)
            session.commit(pending.intentSender)
        } catch (t: Throwable) {
            try { session.abandon() } catch (_: Throwable) { }
            throw t
        } finally {
            session.close()
        }
    }

    fun abandon(ctx: Context, id: Int) {
        if (id < 0) return
        try { ctx.packageManager.packageInstaller.abandonSession(id) } catch (_: Throwable) { }
    }
}

class InstallResultReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(ctx: Context, intent: Intent) {
        try {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
            val sid = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    if (confirm == null) {
                        UpdateManager.onInstallFailed(sid, msg.ifBlank { "no confirmation screen" })
                    } else {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try { ctx.startActivity(confirm) } catch (t: Throwable) { UpdateManager.onInstallFailed(sid, t.message ?: msg) }
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> UpdateManager.onInstalled()
                PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateManager.onInstallFailed(sid, null)
                else -> UpdateManager.onInstallFailed(sid, msg)
            }
        } catch (t: Throwable) {
            android.util.Log.w("AuraUpdate", "install result: $t")
        }
    }
}

/** After an update the process is replaced; make sure the home screen comes back. */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // A build that replaces one which was shut down by the crash guard gets another chance at being the home app.
        CrashGuard.retryAfterUpdate(ctx)
        try {
            // only when Aura is the home app (someone who chose the stock launcher should stay on it)
            if (!com.abdllh.aura.system.Device.isDefaultHome(ctx)) return
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setPackage(ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(home)
        } catch (_: Throwable) {
        }
    }
}
