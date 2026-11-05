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

    /** Android's page for the unit's own hotspot: hidden from apps by name, so by its class, and the wireless page where that is not there. */
    fun hotspot(ctx: Context): Boolean =
        start(ctx, Intent(Intent.ACTION_MAIN).setClassName("com.android.settings", "com.android.settings.TetherSettings")) ||
            start(ctx, Intent(Intent.ACTION_MAIN).setClassName("com.android.settings", "com.android.settings.Settings\$TetherSettingsActivity")) ||
            start(ctx, Intent(Settings.ACTION_WIRELESS_SETTINGS))

    /** The unit's own hotspot is on: `WifiManager.isWifiApEnabled`, hidden, greylisted on Android 10. */
    fun hotspotOn(ctx: Context): Boolean = try {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE)
        wm.javaClass.getMethod("isWifiApEnabled").invoke(wm) == true
    } catch (_: Throwable) {
        false
    }

    fun hasScreenOff(ctx: Context) = AppRepo.isInstalled(ctx, Known.STOCK_LAUNCHER)

    /**
     * The backlight off, as the stock launcher's "screen off" icon does it: it never opens that icon's activity
     * (com.nwd.screenoff.ScreenOffActivity is empty), it asks the setting service (KernelUtils.requestSetBackLight-
     * Switch → AbsBackLightManager), which darkens the panel until it is touched. Opening the activity, as AMRI did
     * up to 1.6, left an empty stock screen in front instead.
     */
    fun screenOff(ctx: Context): Boolean = try {
        ctx.sendBroadcast(Intent("com.nwd.action.ACTION_SET_BACKLIGHT_SWITCH").putExtra("extra_backlight_switch", false))
        true
    } catch (_: Throwable) {
        false
    }

    fun car(ctx: Context): Boolean = AppRepo.launch(ctx, Known.MYCAR) || AppRepo.launch(ctx, Known.CAR_SETTING)

    fun phoneLink(ctx: Context): Boolean =
        AppRepo.launch(ctx, if (AppRepo.isInstalled(ctx, Known.ZLINK)) Known.ZLINK else Known.PHONE)

    /** Opens AMRI Maps. */
    fun nav(ctx: Context): Boolean {
        Screens.maps(ctx)
        return true
    }

    fun wifiConnected(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    } catch (_: Throwable) {
        false
    }
}
