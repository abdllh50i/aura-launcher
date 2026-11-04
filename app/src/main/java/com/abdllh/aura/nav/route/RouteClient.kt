package com.abdllh.aura.nav.route

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import com.abdllh.aura.nav.NavSession
import com.abdllh.aura.nav.RouteLimits
import java.io.File

/**
 * The main process's side of [RouteService]: bound when a route is asked for, and until [idleMs] after the last request, or while
 * a trip runs (the next reroute should not wait for a process to start). Then let go, and the process ends ([RouteProcess]).
 *
 * One request at a time: a newer one ends the wait for the older, whose caller hears [RouteAnswer.CANCELLED]. The service works on
 * through what the older one started: that cannot be stopped. A request gets [TIMEOUT_MS], and the process is killed then. Each
 * caller always hears exactly once, on the main thread, and nothing on the main thread waits for :route: the request goes one-way
 * and the answer is read on its own thread. A death shows as a pipe that ends before its answer does, which it does at once.
 *
 * Main thread.
 */
internal object RouteClient {
    private const val TAG = "AuraRouteClient"
    private const val TIMEOUT_MS = 30_000L
    private val main = Handler(Looper.getMainLooper())

    /** Debug builds shorten it, to see it let go. */
    var idleMs = 180_000L
    /** Debug builds: the service sits on each request this long first, marker down, so a test can kill it in the middle. */
    var debugDelayMs = 0L

    private class Call(val req: RouteRequest, val tar: String, val guide: Boolean, val done: (RouteAnswer) -> Unit) {
        var sent = false
        var retried = false
        var over = false
        var read: ParcelFileDescriptor? = null
        val timeout = Runnable { timedOut(this) }
    }

    private var app: Context? = null
    private var binder: IBinder? = null
    private var bound = false
    private var current: Call? = null
    /** This process's death is in the guard's count already (it can be seen twice: the pipe, the connection). */
    private var counted = false

    /** The route(s) for [req] from the tar at [tar]. */
    fun ask(ctx: Context, req: RouteRequest, tar: File, done: (RouteAnswer) -> Unit) {
        val c = ctx.applicationContext
        app = c
        current?.let { over(it, RouteAnswer(RouteAnswer.CANCELLED, "a newer request")) }
        val refusal = when {
            !RouteGuard.allowed(c) -> RouteAnswer(RouteAnswer.GUARDED, RouteGuard.status(c))
            !tar.isFile -> RouteAnswer(RouteAnswer.BAD_PACK, "no file: ${tar.path}")
            else -> null
        }
        if (refusal != null) { main.post { done(refusal) }; return }
        val call = Call(req, tar.path, RouteChain.guideOn, done)
        current = call
        main.postDelayed(call.timeout, TIMEOUT_MS)
        main.removeCallbacks(idle)
        val b = binder
        if (b != null) send(call, b) else bind(c)
    }

    private fun bind(c: Context) {
        if (bound) return
        try {
            bound = c.bindService(Intent(c, RouteService::class.java), conn, Context.BIND_AUTO_CREATE)
        } catch (t: Throwable) {
            Log.w(TAG, "$t")
        }
        if (!bound) current?.let { over(it, RouteAnswer(RouteAnswer.FAILED, "the service would not bind")) }
    }

    private fun unbind() {
        binder = null
        if (!bound) return
        bound = false
        try { app?.unbindService(conn) } catch (_: Throwable) { }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName?, b: IBinder?) {
            binder = b
            counted = false
            val call = current
            if (b != null && call != null && !call.sent) send(call, b)
        }
        override fun onServiceDisconnected(n: ComponentName?) = lost()
        override fun onBindingDied(n: ComponentName?) = lost()
        override fun onNullBinding(n: ComponentName?) = lost()
    }

    /** The process is gone. Let go at once: the system would start it again by itself for a connection that stays, and only a request should. */
    private fun lost() {
        unbind()
        val c = app ?: return
        // (work no caller waits for any more can be what killed it: its marker tells)
        if (!counted && RouteGuard.hasMarker(c)) { counted = true; RouteGuard.died(c, "the process ended while it worked") }
        val call = current ?: return
        if (!call.sent) over(call, RouteAnswer(RouteAnswer.DIED, "the process ended before it could be asked"))
        // (one that was sent hears it from its pipe)
    }

    private fun send(call: Call, b: IBinder) {
        call.sent = true
        var pipe: Array<ParcelFileDescriptor>? = null
        val data = Parcel.obtain()
        try {
            pipe = ParcelFileDescriptor.createPipe()
            data.writeString(call.req.json())
            data.writeInt(if (call.req.arabic) 1 else 0)
            data.writeString(call.tar)
            data.writeLong(debugDelayMs)
            data.writeInt(if (call.guide) 1 else 0)
            data.writeFileDescriptor(pipe[1].fileDescriptor)
            b.transact(RouteService.ASK, data, null, IBinder.FLAG_ONEWAY)
            call.read = pipe[0]
            read(call, pipe[0])
        } catch (t: Throwable) {
            Log.w(TAG, "send: $t")
            try { pipe?.get(0)?.close() } catch (_: Throwable) { }
            // a process that ended as it let go of its last client (an idle end): a new one, once
            if (!call.retried && !call.over) {
                call.retried = true
                call.sent = false
                unbind()
                app?.let { bind(it) }
            } else {
                over(call, RouteAnswer(RouteAnswer.FAILED, t.toString().take(200)))
            }
        } finally {
            data.recycle()
            // (this end must be closed here: the pipe ends only when no writer is left)
            try { pipe?.get(1)?.close() } catch (_: Throwable) { }
        }
    }

    private fun read(call: Call, pfd: ParcelFileDescriptor) {
        Thread({
            val inp = ParcelFileDescriptor.AutoCloseInputStream(pfd)
            val answer = try { RouteCodec.read(inp) } catch (t: Throwable) { Log.w(TAG, "read: $t"); null }
            main.post { answered(call, answer) }
            val route = answer?.route
            if (route != null) {
                // the limits come later; the route is in use by then, so only the finished object is handed over
                try {
                    val raw = if (call.guide) RouteCodec.readRaw(inp) else null
                    val rows = RouteCodec.readLimits(inp)
                    val limits = rows?.let { RouteLimits.of(route.line.points, it) }
                    if (limits != null) main.post { route.limits = limits }
                    if (raw != null) GuideLink.attach(route, answer, raw, rows, call.req)
                } catch (t: Throwable) {
                    Log.i(TAG, "limits: $t")
                }
            }
            try { inp.close() } catch (_: Throwable) { }
        }, "aura-route-read").apply { isDaemon = true }.start()
    }

    private fun answered(call: Call, a: RouteAnswer?) {
        if (call.over) return
        val c = app ?: return
        if (a == null) {
            if (!counted) { counted = true; RouteGuard.died(c, "the pipe ended before the answer") }
            over(call, RouteAnswer(RouteAnswer.DIED, "the process ended while it worked"))
            return
        }
        if (a.status == RouteAnswer.OK || a.status == RouteAnswer.NO_ROAD || a.status == RouteAnswer.NO_ROUTE) RouteGuard.fine(c)
        over(call, a)
    }

    /** No answer in time: the native call is stuck or the unit is too slow for it. Either way the process goes, and this counts as a death. */
    private fun timedOut(call: Call) {
        if (call.over) return
        val c = app
        if (c != null) {
            counted = true
            RouteGuard.died(c, "no answer in ${TIMEOUT_MS / 1000} s")
            try {
                (c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).runningAppProcesses
                    ?.firstOrNull { it.processName == c.packageName + ":route" }?.let { Process.killProcess(it.pid) }
            } catch (t: Throwable) {
                Log.w(TAG, "$t")
            }
        }
        unbind()
        over(call, RouteAnswer(RouteAnswer.TIMEOUT, "no answer in ${TIMEOUT_MS / 1000} s"))
    }

    private fun over(call: Call, a: RouteAnswer) {
        if (call.over) return
        call.over = true
        main.removeCallbacks(call.timeout)
        if (current === call) current = null
        // (after a good answer the pipe is still to bring the limits: its reader closes it)
        if (a.status != RouteAnswer.OK) try { call.read?.close() } catch (_: Throwable) { }
        main.removeCallbacks(idle)
        main.postDelayed(idle, idleMs)
        try {
            call.done(a)
        } catch (t: Throwable) {
            Log.w(TAG, "$t")
        }
    }

    private val idle: Runnable = object : Runnable {
        override fun run() {
            if (current != null || NavSession.state == NavSession.State.NAVIGATING) main.postDelayed(this, idleMs)
            else if (bound) { Log.i(TAG, "idle: letting go of :route"); unbind() }
        }
    }

    /** Debug: what the client holds. */
    fun describe() = "bound=$bound binder=${binder != null} asking=${current != null} idleMs=$idleMs"
}
