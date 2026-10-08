package com.abdllh.aura.home

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.abdllh.aura.util.Prefs
import java.text.Collator
import java.util.concurrent.Executors

data class AppInfo(val pkg: String, val cls: String, val label: String, val system: Boolean)

/** Package names of the apps shipped on the NWD firmware (see the firmware dump) plus common navigation apps. */
object Known {
    const val PHONE = "com.nwd.android.phone"
    const val MUSIC = "com.nwd.android.music.ui"
    const val BT_MUSIC = "com.nwd.bt.music"
    const val RADIO = "com.nwd.radio"
    const val VIDEO = "com.nwd.android.video.ui"
    const val CAM360 = "com.nwd.carkit360camera"
    const val ZLINK = "com.zjinnova.zlink"
    const val CAR_SETTING = "com.android.car.setting"
    const val ANDROID_SETTINGS = "com.android.settings"
    const val MYCAR = "com.nwd.mycar"
    const val FILES = "com.nwd.filemanager"
    const val AUX = "com.nwd.auxin"
    const val STOCK_LAUNCHER = "com.android.launcher"
    const val MAPS = "com.google.android.apps.maps"

    val NAV_CANDIDATES = listOf(
        MAPS, "com.waze", "ru.yandex.yandexnavi", "com.sygic.aura", "com.here.app.maps",
        "app.organicmaps", "net.osmand", "net.osmand.plus", "com.mapswithme.maps.pro", "com.tomtom.gplay.navapp"
    )

    /** Activities that live inside the stock launcher package (NWD tools). */
    const val ACT_SCREEN_OFF = "com.nwd.screenoff.ScreenOffActivity"
    const val ACT_TOOLBOX = "com.nwd.android.toolsmanager.ToolsManagerActivity"
    const val ACT_WHEEL = "com.nwd.wheel.learning.MainActivity"
    const val ACT_REBOOT = "com.nwd.tools.reboot.RebootActivity"
    const val ACT_LOCK = "com.nwd.android.lock.screen.ui.LockScreenActivity"
}

object AppRepo {
    private val collator: Collator = Collator.getInstance()
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Last list produced by [loadAsync] (hidden apps filtered out); shown instantly while a fresh one is being built. */
    @Volatile
    var cached: List<AppInfo>? = null
        private set

    /** Queries the package manager on a background thread and delivers the list on the main thread. */
    fun loadAsync(ctx: Context, done: (List<AppInfo>) -> Unit) {
        val app = ctx.applicationContext
        worker.execute {
            val list = try { load(app) } catch (_: Throwable) { emptyList() }
            if (list.isNotEmpty()) cached = list
            main.post { done(list) }
        }
    }

    fun preload(ctx: Context) = loadAsync(ctx) { }

    fun isInstalled(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getApplicationInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** All launchable apps, alphabetically. Hidden ones are filtered unless [includeHidden]. */
    fun load(ctx: Context, includeHidden: Boolean = false): List<AppInfo> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val hidden = Prefs.hiddenApps
        val seen = HashSet<String>()
        val out = ArrayList<AppInfo>()
        for (ri in pm.queryIntentActivities(i, 0)) {
            val ai = ri.activityInfo ?: continue
            if (!seen.add(ai.packageName)) continue
            if (!includeHidden && ai.packageName in hidden) continue
            val sys = (ai.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            out.add(AppInfo(ai.packageName, ai.name, ri.loadLabel(pm).toString(), sys))
        }
        out.sortWith { a, b -> collator.compare(a.label, b.label) }
        return out
    }

    fun launchIntent(ctx: Context, pkg: String): Intent? =
        ctx.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    fun launch(ctx: Context, pkg: String): Boolean = try {
        val i = launchIntent(ctx, pkg)
        if (i != null) { ctx.startActivity(i); true } else false
    } catch (_: Throwable) {
        false
    }

    fun launchComponent(ctx: Context, pkg: String, cls: String): Boolean = try {
        ctx.startActivity(Intent().setComponent(ComponentName(pkg, cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Throwable) {
        false
    }

    /** The user's preferred navigation app, or the first installed one from the known list. */
    fun navPackage(ctx: Context): String? {
        val chosen = Prefs.navPackage
        if (chosen.isNotEmpty() && isInstalled(ctx, chosen)) return chosen
        return Known.NAV_CANDIDATES.firstOrNull { isInstalled(ctx, it) }
    }

    fun installedNavApps(ctx: Context): List<AppInfo> {
        val pm = ctx.packageManager
        return Known.NAV_CANDIDATES.filter { isInstalled(ctx, it) }.mapNotNull { p ->
            try {
                val ai = pm.getApplicationInfo(p, 0)
                AppInfo(p, "", pm.getApplicationLabel(ai).toString(), (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0)
            } catch (_: Throwable) {
                null
            }
        }
    }
}

/** Loads app icons off the main thread and caches them as bitmaps (cheap to draw on a weak GPU). */
object IconLoader {
    private val cache = object : LruCache<String, Bitmap>(80) {}
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun get(ctx: Context, pkg: String, sizePx: Int, onReady: (Bitmap?) -> Unit) {
        val key = "$pkg@$sizePx"
        cache.get(key)?.let { onReady(it); return }
        val app = ctx.applicationContext
        pool.execute {
            val bmp = try {
                toBitmap(app.packageManager.getApplicationIcon(pkg), sizePx)
            } catch (_: Throwable) {
                null
            }
            if (bmp != null) cache.put(key, bmp)
            main.post { onReady(bmp) }
        }
    }

    private fun toBitmap(d: Drawable, size: Int): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        d.setBounds(0, 0, size, size)
        d.draw(c)
        return b
    }
}
