package com.abdllh.aura.home

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings

/** System shortcuts shared by the car panel, the controls sheet and the dock. Each returns false when unavailable. */
object Actions {
    private fun start(ctx: Context, i: Intent): Boolean = try {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Throwable) {
        false
    }

    fun wifi(ctx: Context): Boolean =
        (android.os.Build.VERSION.SDK_INT >= 29 && start(ctx, Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))) ||
            start(ctx, Intent(Settings.ACTION_WIFI_SETTINGS))

    fun bluetooth(ctx: Context): Boolean = start(ctx, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))

    fun hasScreenOff(ctx: Context) = AppRepo.isInstalled(ctx, Known.STOCK_LAUNCHER)

    fun screenOff(ctx: Context): Boolean =
        AppRepo.launchComponent(ctx, Known.STOCK_LAUNCHER, Known.ACT_SCREEN_OFF) || start(ctx, Intent(Settings.ACTION_DISPLAY_SETTINGS))

    fun car(ctx: Context): Boolean = AppRepo.launch(ctx, Known.MYCAR) || AppRepo.launch(ctx, Known.CAR_SETTING)

    fun phoneLink(ctx: Context): Boolean =
        AppRepo.launch(ctx, if (AppRepo.isInstalled(ctx, Known.ZLINK)) Known.ZLINK else Known.PHONE)

    /** Opens Aura Maps (default) or the chosen navigation app. */
    fun nav(ctx: Context): Boolean {
        if (com.abdllh.aura.util.Prefs.builtInMaps) {
            com.abdllh.aura.nav.MapsActivity.open(ctx)
            return true
        }
        val pkg = AppRepo.navPackage(ctx) ?: return false
        return AppRepo.launch(ctx, pkg)
    }

    fun wifiConnected(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    } catch (_: Throwable) {
        false
    }
}
