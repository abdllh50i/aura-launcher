package com.abdllh.aura.nav.route

import android.os.Handler
import android.os.Looper
import android.os.Process

/**
 * The process :route, where Valhalla's native code runs: a crash in it kills this process and not the home screen, and its
 * 32-bit address space is shared with nothing (the tar is mapped whole). AuraApp does nothing here, as for :boot. When the last
 * service in it has gone the process ends, so its memory is back at once and not when the low-memory killer comes round to
 * it. Main thread.
 */
internal object RouteProcess {
    /** A client may bind again in this moment: then the process is still wanted. */
    private const val END_AFTER_MS = 1000L
    /** Native code on a Java thread gets what the thread was given: more than the default, which costs nothing until it is used. */
    const val STACK_BYTES = 4L * 1024 * 1024
    private val main = Handler(Looper.getMainLooper())
    private var users = 0
    private val end = Runnable { if (users == 0) Process.killProcess(Process.myPid()) }

    fun enter() {
        users++
        main.removeCallbacks(end)
    }

    fun leave() {
        users--
        if (users <= 0) main.postDelayed(end, END_AFTER_MS)
    }
}
