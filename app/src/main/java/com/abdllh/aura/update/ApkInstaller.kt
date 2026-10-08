package com.abdllh.aura.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import java.io.File

/**
 * Installs an APK through the platform PackageInstaller. On the car (Aura is a privileged system app with
 * INSTALL_PACKAGES) this is silent; elsewhere the system confirmation screen is shown automatically.
 */
object ApkInstaller {
    const val ACTION = "com.abdllh.aura.INSTALL_RESULT"

    fun install(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        params.setSize(apk.length())
        val id = pi.createSession(params)
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
}

class InstallResultReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(ctx: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { ctx.startActivity(confirm) } catch (t: Throwable) { UpdateManager.onInstallFailed(t.message ?: msg) }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> UpdateManager.onInstalled()
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateManager.onInstallFailed(null)
            else -> UpdateManager.onInstallFailed(msg)
        }
    }
}

/** After an update the process is replaced; make sure the home screen comes back. */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        try {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setPackage(ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(home)
        } catch (_: Throwable) {
        }
    }
}
