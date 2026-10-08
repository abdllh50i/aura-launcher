package com.abdllh.aura.system

import android.content.Context
import android.content.pm.PackageManager

/**
 * Thin wrappers around hidden platform APIs. Everything is best-effort (reflection, try/catch):
 * on the car unit SELinux is permissive so property writes work for the system app; on other
 * devices they simply return false.
 */
object SystemProps {
    private val cls: Class<*>? by lazy {
        try { Class.forName("android.os.SystemProperties") } catch (_: Throwable) { null }
    }

    fun get(key: String, def: String = ""): String = try {
        cls?.getMethod("get", String::class.java, String::class.java)?.invoke(null, key, def) as? String ?: def
    } catch (_: Throwable) {
        def
    }

    fun set(key: String, value: String): Boolean = try {
        cls?.getMethod("set", String::class.java, String::class.java)?.invoke(null, key, value)
        get(key) == value
    } catch (_: Throwable) {
        false
    }
}

/** Facts about the device/ROM this launcher is running on. */
object Device {
    const val STOCK_LAUNCHER = "com.android.launcher"
    const val HOME_PROP = "persist.nwd.launcher.default"
    const val DISABLED_PROP = "persist.aura.disabled"

    /** True when running on an NWD head unit (the framework exposes its custom launcher property). */
    val isNwd: Boolean
        get() = SystemProps.get("ro.nwd.platform.name").isNotEmpty() || SystemProps.get("ro.product.nwd.platform").isNotEmpty()

    val model: String
        get() = SystemProps.get("ro.nwd.platform.name").ifEmpty { android.os.Build.MODEL }

    val buildId: String
        get() = SystemProps.get("ro.build.display.id").ifEmpty { android.os.Build.DISPLAY }

    fun homePackage(): String = SystemProps.get(HOME_PROP, "")

    fun isPrivileged(ctx: Context): Boolean =
        ctx.checkSelfPermission("android.permission.INSTALL_PACKAGES") == PackageManager.PERMISSION_GRANTED

    fun isSystemApp(ctx: Context): Boolean = try {
        (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
    } catch (_: Throwable) {
        false
    }

    /** Is Aura the active home app right now? */
    fun isDefaultHome(ctx: Context): Boolean {
        val i = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)
        val ri = ctx.packageManager.resolveActivity(i, 0)
        return ri?.activityInfo?.packageName == ctx.packageName
    }

    /** Make Aura (or the stock launcher) the home app on an NWD unit. */
    fun setHome(pkg: String): Boolean = SystemProps.set(HOME_PROP, pkg)
}
