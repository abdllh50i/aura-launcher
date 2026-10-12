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

    /**
     * Home or Work ([which]) handed to the navigation app chosen in Settings instead of AMRI Maps (Google Maps, Waze...),
     * which starts driving there: the place kept with its pin goes as coordinates, an address typed long ago as text.
     * False when there is no such place yet, or no app to give it to.
     */
    fun driveWithApp(ctx: Context, which: String): Boolean {
        val pkg = AppRepo.navPackage(ctx) ?: return false
        val saved = if (which == "home") com.abdllh.aura.nav.Places.home else com.abdllh.aura.nav.Places.work
        val address = if (which == "home") com.abdllh.aura.util.Prefs.homeAddress else com.abdllh.aura.util.Prefs.workAddress
        val at = saved?.let { String.format(java.util.Locale.ROOT, "%.6f,%.6f", it.pos.lat, it.pos.lon) }
        val uri = when {
            at != null && pkg == "com.waze" -> "waze://?ll=$at&navigate=yes"
            at != null -> "google.navigation:q=$at"
            address.isBlank() -> return false
            pkg == "com.waze" -> "waze://?q=${android.net.Uri.encode(address)}&navigate=yes"
            else -> "google.navigation:q=${android.net.Uri.encode(address)}"
        }
        com.abdllh.aura.system.HomeGuard.launching(pkg)
        return start(ctx, Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)).setPackage(pkg)) || nav(ctx)
    }

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
