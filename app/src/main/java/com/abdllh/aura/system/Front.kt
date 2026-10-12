package com.abdllh.aura.system

import android.app.Activity
import android.app.Application
import android.content.Context
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What is in front. The firmware's framework writes every activity that comes to the front to Settings.System
 * "activity_starting" as "package,class" (NwdPackageChecker.checkResumedPackageName, for its own KernelService), so
 * AMRI knows which screen the owner is looking at, any app's, without usage access. A state, not an event: it can be
 * read after the fact (a process that started late still sees what came up before it).
 *
 * Also AMRI's own screens ([own]): the one in front, or the last one that was and since when it is not.
 */
object Front {
    private const val TAG = "AuraFront"
    private const val KEY = "activity_starting"

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var app: Context? = null

    var pkg = ""
        private set
    var cls = ""
        private set

    /** AMRI's own activity in front (or the last one that was), by class name. */
    var own = ""
        private set
    /** When [own] left the front (elapsedRealtime); 0 while it is there. */
    var ownLeftAt = 0L
        private set
    val ownResumed get() = own.isNotEmpty() && ownLeftAt == 0L

    /** [own] is [activity] and is in front, or was until less than [withinMs] ago. */
    fun ownIs(activity: Class<*>, withinMs: Long): Boolean =
        own == activity.name && (ownLeftAt == 0L || SystemClock.elapsedRealtime() - ownLeftAt < withinMs)

    fun addListener(l: () -> Unit) { listeners.add(l) }

    fun init(ctx: Context) {
        if (app != null) return
        val application = ctx.applicationContext as? Application ?: return
        app = application
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { own = a.javaClass.name; ownLeftAt = 0L }
            override fun onActivityPaused(a: Activity) { if (own == a.javaClass.name) ownLeftAt = SystemClock.elapsedRealtime() }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        try {
            application.contentResolver.registerContentObserver(Settings.System.getUriFor(KEY), false, object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean) = read()
            })
        } catch (t: Throwable) {
            Log.w(TAG, "observer: $t") // (not this firmware: nothing is ever in front as far as AMRI knows)
        }
        read()
    }

    /** Reads the setting again (also for a caller that wants what is in front now, not what was at the last change). */
    fun read() {
        val v = try { Settings.System.getString(app?.contentResolver ?: return, KEY) } catch (_: Throwable) { null } ?: return
        val comma = v.indexOf(',')
        val p = if (comma < 0) v else v.substring(0, comma)
        val c = if (comma < 0) "" else v.substring(comma + 1)
        if (p == pkg && c == cls) return
        pkg = p
        cls = c
        for (l in listeners) try { l() } catch (t: Throwable) { Log.w(TAG, "listener: $t") }
    }
}
