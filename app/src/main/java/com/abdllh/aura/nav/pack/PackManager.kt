package com.abdllh.aura.nav.pack

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.R
import com.abdllh.aura.nav.NavSession
import com.abdllh.aura.system.SystemProps
import com.abdllh.aura.util.Fmt
import com.abdllh.aura.util.LocaleHelper
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/**
 * Gets the map pack (387 MB to fetch, 713 MB on the disk, tools/mapbuild/make_pack.py) onto the unit and never breaks a working map. It replaces OfflineMaps for 2.0. This object
 * is what the Settings page and the Maps screen talk to; [PackPaths] is what the map, the router and the search ask. Nothing starts by itself: not at a process start, not after a
 * reboot, not for a newer pack. Everything heavy runs on one low priority thread, and all of it stops during a trip (four A53 cores and one eMMC: gunzip and hashing
 * next to the map and the voice is not worth finding out on the road); it says so ([State.TRIP_HOLD]) and goes on when the trip ends.
 * No wake lock: on a head unit it could keep the car battery draining with the ignition off. A sleep just cuts the connection, and the retry takes it from there.
 *
 *   status, addListener    what to show (listeners on the main thread, at most about three times a second)
 *   checkUpdate()          asks the server for the newest manifest; a newer pack becomes [Status.offer]
 *   scanUsb()              looks at sticks and cards; a pack there becomes [Status.usbOffer]
 *   startDownload(), startUsb(), resume()    pause(), cancel()
 *   deleteCurrent()        frees the pack in use (the map falls back to the old map or to online)
 *   freeOldMap()           frees files/offline/map.db, offered once the new pack has worked ([Status.oldMapBytes])
 *   text(), line(), advice()    the words, in the app's language
 */
object PackManager {
    private const val TAG = "AuraPack"
    private const val NOTIFY_EVERY_MS = 300L

    enum class State {
        /** No pack on the unit and none known. */
        NONE,
        /** Asking the server or a stick. */
        CHECKING,
        /** A pack can be started: [Status.offer] or [Status.usbOffer]. */
        OFFER,
        /** A pack is known, or was going on, and the space is not there: refused before writing anything more. [Status.offer] has the numbers. */
        NO_SPACE,
        /** Bytes are coming ([Status.viaUsb]: from a stick). done, total and bytesPerSec are live. */
        FETCHING,
        /** The connection dropped ([Status.problem] says how); it tries again by itself. */
        WAITING,
        /** A trip is on: stopped, and goes on when the trip ends. */
        TRIP_HOLD,
        /** Stopped by the user, or by a restart of the unit. [resume] goes on from the last chunk. */
        PAUSED,
        /** Everything is here; the whole files' hashes and the last look. done and total are in bytes of those files. */
        VERIFYING,
        /** The pack in use is the newest known. */
        INSTALLED,
        /** Stopped with a reason ([Status.problem]). What was done stays: [resume] tries again, [cancel] throws it away. */
        FAILED
    }

    /** A pack that can be started, and whether it fits. */
    data class Offer(val id: String, val built: String, val downloadBytes: Long, val diskBytes: Long, val needBytes: Long, val freeBytes: Long, val isUpdate: Boolean, val viaUsb: Boolean) {
        val fits: Boolean get() = freeBytes >= needBytes
    }

    data class Status(
        val state: State = State.NONE,
        val done: Long = 0,
        val total: Long = 0,
        val bytesPerSec: Long = 0,
        /** Why it waits or stopped, or what the last check found. Say it with [text]. */
        val problem: PackProblem? = null,
        val viaUsb: Boolean = false,
        /** The id of the pack on its way, with done and total, also while paused. */
        val stagingId: String? = null,
        val offer: Offer? = null,
        val usbOffer: Offer? = null,
        val currentId: String? = null,
        /** When the pack in use was built ("2026-11-04T15:24Z") and how much it takes. */
        val currentBuilt: String? = null,
        val currentBytes: Long = 0,
        /** The old map can go: the new pack has worked once. 0 when it cannot, or is gone. */
        val oldMapBytes: Long = 0
    ) {
        val percent: Int get() = PackRules.percent(done, total)
        /** Work is going on, or waiting to go on: [pause] means something. */
        val busy: Boolean get() = state == State.FETCHING || state == State.WAITING || state == State.TRIP_HOLD || state == State.VERIFYING || state == State.CHECKING
    }

    @Volatile var status = Status(); private set

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val lock = Any()
    private var app: Context? = null
    @Volatile private var running = false
    @Volatile private var stop = false
    @Volatile private var cancelWanted = false
    @Volatile private var guiding = false
    private var next: Request? = null
    private var notifiedAt = 0L
    @Volatile private var publishedAt = 0L
    private var posted = false
    private val meter = SpeedMeter()
    private var doneNow = 0L
    private var totalNow = 0L
    /** Where the last check found the offered pack, with what it said. */
    @Volatile private var offered: HttpSource? = null
    @Volatile private var offeredManifest: PackManifest? = null
    @Volatile private var usbFind: PackUsb.Find? = null
    private val scanning = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Kill tests, debug builds only (`--es dieat after-unpack`). */
    @Volatile internal var dieAt: String? = null

    private class Request(val usb: Boolean = false, val resume: Boolean = false)

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    /** At start, on the main thread. Costs nothing here: the disk is read on a thread of its own. */
    fun init(ctx: Context) {
        if (app != null) return
        val c = ctx.applicationContext
        app = c
        try {
            guiding = NavSession.state != NavSession.State.IDLE
            NavSession.addListener { guiding = NavSession.state != NavSession.State.IDLE }
            PackPaths.addListener { background("aura-pack-tidy") { tidy(c); refreshFacts(c) } }
            // a stick plugged in while the unit runs: looked at, offered, never started
            c.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(x: Context, i: Intent) { scan(c, quiet = true) }
            }, IntentFilter(Intent.ACTION_MEDIA_MOUNTED).apply { addDataScheme("file") })
        } catch (t: Throwable) {
            Log.w(TAG, "init: $t")
        }
        background("aura-pack-init") { restore(c) }
    }

    private fun background(name: String, block: () -> Unit) {
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            try { block() } catch (t: Throwable) { Log.w(TAG, "$name: $t") }
        }, name).start()
    }

    // ---------------------------------------------------------------------------------------------------- what the screens call

    /** Asks the server for the newest manifest. A newer pack becomes an offer, and that is all it does. */
    fun checkUpdate() {
        val c = app ?: return
        if (running) return
        update { it.copy(state = State.CHECKING, problem = null) }
        background("aura-pack-check") {
            var why: PackProblem? = null
            try {
                val (s, text) = manifestFromServer()
                val m = PackManifest.parse(text, PackPaths.appCode(c))
                offered = s
                offeredManifest = m
            } catch (r: PackRefused) {
                Log.i(TAG, "check: ${r.message}")
                why = r.why
            } catch (e: PackMissing) {
                why = PackProblem.NOT_PUBLISHED
            } catch (t: Throwable) {
                Log.i(TAG, "check: $t")
                why = networkProblem(t)
            }
            if (!running) settle(c, why)
        }
    }

    /** Looks at sticks and cards. Off the main thread (a stick can be slow, the root shell takes its time). */
    fun scanUsb() { app?.let { scan(it, quiet = false) } }

    private fun scan(c: Context, quiet: Boolean) {
        if (running || !scanning.compareAndSet(false, true)) return
        if (!quiet) update { it.copy(state = State.CHECKING, problem = null) }
        background("aura-pack-usb") {
            try {
                val f = PackUsb.scan(c)
                usbFind = f?.takeIf { it.manifest != null }
                val why = when {
                    f == null -> if (quiet) null else PackProblem.NO_USB
                    f.manifest == null -> f.refused
                    else -> null
                }
                if (!running) settle(c, why)
            } finally {
                scanning.set(false)
            }
        }
    }

    /** Starts (or goes on with) the pack from the server. */
    fun startDownload() = launch(Request(usb = false))

    /** Starts (or goes on with) the pack on a stick or card. */
    fun startUsb() = launch(Request(usb = true))

    /** Goes on with what is on its way, from wherever it was coming. */
    fun resume() = launch(Request(resume = true))

    /** Stops; goes on from the last chunk when asked ([resume]), also after a restart. */
    fun pause() {
        val c = app ?: return
        synchronized(lock) {
            if (!running) return
            stop = true
            next = null
        }
        // (the thread may be in a read for up to its timeout: the screen need not wait for that)
        update { it.copy(state = State.PAUSED, bytesPerSec = 0, problem = null) }
        PackService.refresh(c)
    }

    /** Throws away the pack on its way, and what was fetched. The pack in use stays. */
    fun cancel() {
        val c = app ?: return
        val busy = synchronized(lock) {
            next = null
            if (running) { stop = true; cancelWanted = true }
            running
        }
        if (busy) {
            update { it.copy(state = State.PAUSED, bytesPerSec = 0) } // (the thread's way out then settles it)
            return
        }
        background("aura-pack-cancel") {
            discardStaging(c)
            settle(c, null)
        }
    }

    /** Frees every pack on the unit. False during a trip or while a pack is on its way: nothing is pulled from under a working map. */
    fun deleteCurrent(): Boolean {
        val c = app ?: return false
        if (running || guiding) return false
        background("aura-pack-delete") {
            PackPaths.packs(c).listFiles()?.forEach { PackFiles.discard(it, PackPaths.trash(c)) }
            PackPaths.pointer(c).delete()
            PackFiles.syncDir(PackPaths.root(c))
            PackPaths.changed()
            settle(c, null)
        }
        return true
    }

    /** Frees the old map of today's OfflineMaps, once the pack has worked. BRouter's squares stay: it is still the fallback router (maps.md 7.6). */
    fun freeOldMap(done: () -> Unit = {}) {
        val c = app ?: return
        if (!PackPaths.hasWorked(c)) return
        com.abdllh.aura.nav.offline.OfflineMaps.freeOldTiles {
            background("aura-pack-free") { refreshFacts(c) }
            done()
        }
    }

    /** A problem in plain words, in the app's language. */
    fun text(p: PackProblem): String {
        val c = LocaleHelper.wrap(app ?: return "")
        return c.getString(when (p) {
            PackProblem.NET -> R.string.pack_err_net
            PackProblem.CLOCK -> R.string.pack_err_clock
            PackProblem.NOT_PUBLISHED -> R.string.pack_err_not_published
            PackProblem.SPACE -> R.string.pack_err_space
            PackProblem.BAD_DATA -> R.string.pack_err_bad_data
            PackProblem.APP_TOO_OLD -> R.string.pack_err_app_old
            PackProblem.PACK_TOO_OLD -> R.string.pack_err_pack_old
            PackProblem.BAD_MANIFEST -> R.string.pack_err_manifest
            PackProblem.USB_GONE -> R.string.pack_err_usb_gone
            PackProblem.USB_DAMAGED -> R.string.pack_err_usb_damaged
            PackProblem.NO_USB -> R.string.pack_err_no_usb
            PackProblem.DISK -> R.string.pack_err_disk
            PackProblem.TRIP -> R.string.pack_err_trip
        })
    }

    /** The state in one line (the notification; a first version of the screen). */
    fun line(s: Status = status): String {
        val c = LocaleHelper.wrap(app ?: return "")
        return when (s.state) {
            State.NONE -> c.getString(R.string.pack_none)
            State.CHECKING -> c.getString(R.string.pack_checking)
            State.OFFER -> c.getString(if ((s.offer ?: s.usbOffer)?.isUpdate == true) R.string.pack_update else R.string.pack_offer)
            State.NO_SPACE -> c.getString(R.string.pack_err_space)
            State.FETCHING -> c.getString(if (s.viaUsb) R.string.pack_copying else R.string.pack_fetching, "${s.percent}", Fmt.bytes(s.bytesPerSec))
            State.WAITING -> c.getString(R.string.pack_waiting, "${s.percent}")
            State.TRIP_HOLD -> c.getString(R.string.pack_trip, "${s.percent}")
            State.PAUSED -> c.getString(R.string.pack_paused, "${s.percent}")
            State.VERIFYING -> c.getString(R.string.pack_verifying, "${s.percent}")
            State.INSTALLED -> c.getString(R.string.pack_installed)
            State.FAILED -> s.problem?.let { text(it) } ?: c.getString(R.string.pack_err_disk)
        }
    }

    /** Before starting: the sizes in plain numbers, and the owner's easy way when his internet is a phone's hotspot. */
    fun advice(o: Offer): String {
        val c = LocaleHelper.wrap(app ?: return "")
        val sizes = c.getString(R.string.pack_sizes, Fmt.bytes(o.downloadBytes), Fmt.bytes(o.diskBytes), Fmt.bytes(o.needBytes), Fmt.bytes(o.freeBytes))
        return if (o.viaUsb) sizes else sizes + "\n" + c.getString(R.string.pack_usb_advice)
    }

    /** What the last install took (fetch, hash, unpack, in seconds): the unit's real numbers for the owner's report. */
    fun lastInstall(): String? = app?.let { c ->
        try { File(PackPaths.root(c), "last-install.txt").takeIf { it.isFile }?.readText()?.trim() } catch (_: Throwable) { null }
    }

    // ---------------------------------------------------------------------------------------------------- the work

    private val host = object : PackJob.Host {
        override fun check() {
            if (stop) throw PackStopped()
            if (guiding) throw PackHold()
        }

        override fun bytes(n: Long) {
            val now = SystemClock.elapsedRealtime()
            meter.add(n, now)
            synchronized(lock) { doneNow += n }
            publishProgress(State.FETCHING)
        }

        override fun fetching(done: Long, total: Long) {
            synchronized(lock) { doneNow = done; totalNow = total }
            publishProgress(State.FETCHING)
        }

        override fun verifying(done: Long, total: Long) {
            synchronized(lock) { doneNow = done; totalNow = total }
            publishProgress(State.VERIFYING)
        }

        override fun waiting(why: PackProblem) {
            meter.reset()
            update { it.copy(state = State.WAITING, problem = why, bytesPerSec = 0) }
        }

        override fun point(name: String) {
            if (BuildConfig.DEBUG && name == dieAt) {
                Log.w(TAG, "dying at $name, as asked")
                Process.killProcess(Process.myPid())
            }
        }

        override fun freeBytes(): Long = PackManager.freeBytes()
    }

    private fun publishProgress(s: State) {
        val now = SystemClock.elapsedRealtime()
        if (s == status.state && now - publishedAt < 200L) return // (a call for every 64 KB)
        publishedAt = now
        update(throttled = true) { it.copy(state = s, done = synchronized(lock) { doneNow }, total = synchronized(lock) { totalNow }, bytesPerSec = meter.rate(now), problem = null) }
    }

    /** Free space where the pack goes. A debug build can say less (`setprop debug.aura.pack.free 500000000`): the refusal is tested on an emulator with room. */
    private fun freeBytes(): Long {
        val c = app ?: return 0L
        val real = PackPaths.root(c).let { if (it.isDirectory) it else c.filesDir }.usableSpace
        if (BuildConfig.DEBUG) SystemProps.get("debug.aura.pack.free").toLongOrNull()?.let { return it }
        return real
    }

    private fun launch(r: Request) {
        val c = app ?: return
        synchronized(lock) {
            if (running) {
                // still on its way out after a pause: goes straight on when it is out
                if (stop && !cancelWanted) next = r
                return
            }
            running = true
            stop = false
            cancelWanted = false
        }
        meter.reset()
        update { it.copy(state = State.CHECKING, problem = null) }
        PackService.start(c)
        Thread({ work(c, r) }, "aura-pack").start()
    }

    private fun work(c: Context, r: Request) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        var after: PackProblem? = null
        try {
            while (true) {
                try {
                    host.check()
                    val (m, source) = resolve(c, r)
                    update { it.copy(stagingId = m.id, viaUsb = source.usb) }
                    // (refused for space or stopped by a failure, the pack stays offered with its numbers)
                    if (source is HttpSource) { offered = source; offeredManifest = m }
                    PackJob(c, m, source, host).run()
                    Log.i(TAG, "switched to ${m.id}")
                    offered = null
                    offeredManifest = null
                    if (usbFind?.manifest?.id == m.id) usbFind = null
                    PackPaths.changed()
                    break
                } catch (h: PackHold) {
                    update { it.copy(state = State.TRIP_HOLD, bytesPerSec = 0) }
                    while (guiding) {
                        if (stop) throw PackStopped()
                        SystemClock.sleep(500)
                    }
                    meter.reset()
                }
            }
        } catch (s: PackStopped) {
            Log.i(TAG, "stopped")
        } catch (t: PackRefused) {
            Log.w(TAG, "refused: ${t.why} ${t.message}")
            after = t.why
        } catch (t: PackMissing) {
            Log.w(TAG, "work: $t")
            after = if (r.usb) PackProblem.USB_GONE else PackProblem.NOT_PUBLISHED
        } catch (t: Throwable) {
            // nothing in here may take the home screen down
            Log.w(TAG, "work: $t", t)
            after = if (PackRules.noSpace(t.message)) PackProblem.SPACE else PackProblem.DISK
        }
        val again = synchronized(lock) {
            val n = if (cancelWanted) null else next
            next = null
            n
        }
        try {
            if (cancelWanted) { discardStaging(c); after = null }
            settle(c, after, failed = after != null)
        } catch (t: Throwable) {
            Log.w(TAG, "settle: $t")
        }
        synchronized(lock) {
            running = false
            stop = false
            cancelWanted = false
        }
        main.post {
            PackService.refresh(c)
            if (again != null) launch(again)
        }
    }

    /** The manifest and the source a request means. Waits out a dead network when nothing is on the disk to go on with. */
    private fun resolve(c: Context, r: Request): Pair<PackManifest, PackSource> {
        val app = PackPaths.appCode(c)
        val staged = stagedManifest(c)
        if (r.usb) {
            val f = PackUsb.scan(c) ?: throw PackRefused(PackProblem.NO_USB, "nothing plugged in")
            val m = f.manifest ?: throw PackRefused(f.refused ?: PackProblem.BAD_MANIFEST, "${f.folder} refused")
            dropIfAnother(c, staged, m)
            noTwin(c, m)
            return m to f.source
        }
        if (r.resume && staged != null) {
            val key = PackProgress(File(File(PackPaths.staging(c), staged.id), "progress.json")).load().source
            if (key.startsWith("usb:")) {
                val s = PackUsb.sourceFor(c, staged.id) ?: throw PackRefused(PackProblem.USB_GONE, "${staged.id} not on a stick now")
                return staged to s
            }
            val s = HttpSource(key.removePrefix("url:").ifEmpty { HttpSource.debugBase() ?: HttpSource.RELEASE })
            // a pack rebuilt under the same id has other hashes: better found now than three failed chunks later. No network: on with what is known.
            try {
                val fresh = PackManifest.parse(s.manifestText(), app)
                if (!fresh.sameAs(staged)) {
                    Log.i(TAG, "the server's manifest differs from the one begun: ${fresh.id}, starting that")
                    dropStaging(c, staged)
                    noTwin(c, fresh)
                    return fresh to s
                }
            } catch (e: IOException) {
                Log.i(TAG, "resume without the server's manifest: $e")
            } catch (e: PackRefused) {
                Log.i(TAG, "the server's manifest is refused, going on with the one begun: ${e.message}")
            }
            return staged to s
        }
        // the offered pack, or the first one
        val known = offered
        val knownManifest = offeredManifest
        if (known != null && knownManifest != null) {
            dropIfAnother(c, staged, knownManifest)
            noTwin(c, knownManifest)
            return knownManifest to known
        }
        var attempt = 0
        while (true) {
            host.check()
            try {
                val (s, text) = manifestFromServer()
                val m = PackManifest.parse(text, app)
                dropIfAnother(c, staged, m)
                noTwin(c, m)
                return m to s
            } catch (e: PackMissing) {
                throw PackRefused(PackProblem.NOT_PUBLISHED, "no manifest on the server")
            } catch (e: IOException) {
                attempt++
                Log.i(TAG, "manifest: $e (failure $attempt)")
                host.waiting(networkProblem(e))
                val until = SystemClock.elapsedRealtime() + PackRules.backoffMs(attempt)
                while (SystemClock.elapsedRealtime() < until) { host.check(); SystemClock.sleep(200) }
            }
        }
    }

    /** The newest manifest: the debug server when set, GitHub's "latest" (which also says which tag), else the first release's own address. */
    private fun manifestFromServer(): Pair<HttpSource, String> {
        HttpSource.debugBase()?.let { val s = HttpSource(it); return s to s.manifestText() }
        return try {
            val s = HttpSource(HttpSource.LATEST)
            s to s.manifestText()
        } catch (e: PackMissing) {
            val s = HttpSource(HttpSource.RELEASE)
            s to s.manifestText()
        }
    }

    /** The manifest of the pack on its way, if one is. */
    private fun stagedManifest(c: Context): PackManifest? {
        val dirs = PackPaths.staging(c).listFiles() ?: return null
        for (d in dirs) {
            try {
                val m = PackManifest.parse(File(d, "manifest.json").readText(), PackPaths.appCode(c))
                if (m.id == d.name) return m
            } catch (t: Throwable) {
                Log.i(TAG, "staging ${d.name}: $t")
            }
        }
        return null
    }

    private fun dropIfAnother(c: Context, staged: PackManifest?, m: PackManifest) {
        if (staged != null && !staged.sameAs(m)) dropStaging(c, staged)
    }

    private fun dropStaging(c: Context, m: PackManifest) {
        Log.i(TAG, "dropping staged ${m.id}")
        PackFiles.discard(File(PackPaths.staging(c), m.id), PackPaths.trash(c))
    }

    private fun discardStaging(c: Context) {
        PackPaths.staging(c).listFiles()?.forEach { PackFiles.discard(it, PackPaths.trash(c)) }
    }

    /** The pack in use is not fetched over itself. One whose files went missing (it must not be used: [PackPaths] says null) makes way. */
    private fun noTwin(c: Context, m: PackManifest) {
        val cur = PackPaths.current(c) ?: return
        if (cur.id != m.id) return
        if (m.pieces.all { PackPaths.piece(c, it.name) != null }) throw PackStopped()
        PackFiles.discard(cur.dir, PackPaths.trash(c))
        PackPaths.pointer(c).delete()
        PackPaths.changed()
    }

    /** At a process start: what is on the disk. Nothing is started. */
    private fun restore(c: Context) {
        PackPaths.trash(c).deleteRecursively()
        // a kill between the rename into packs/ and the new pointer: that step comes now
        val cur = PackPaths.current(c)
        if (cur == null && PackPaths.pointer(c).exists()) PackPaths.pointer(c).delete()
        var best: PackManifest? = null
        for (d in PackPaths.packs(c).listFiles().orEmpty()) {
            if (!File(d, "ok").isFile || d.name == cur?.id) continue
            try {
                val m = PackManifest.parse(File(d, "manifest.json").readText(), PackPaths.appCode(c))
                if (m.id == d.name && PackRules.newer(m.built, best?.built ?: cur?.manifest?.built)) best = m
            } catch (t: Throwable) {
                Log.i(TAG, "${d.name}: $t")
            }
        }
        best?.let {
            Log.i(TAG, "finishing the switch to ${it.id}")
            PackFiles.writeAtomic(PackPaths.pointer(c), it.id)
            PackPaths.changed()
        }
        tidy(c)
        if (!running) settle(c, null)
    }

    /** A pack that has worked: what it replaced goes. Until then the old one stays, whatever it costs in space. */
    private fun tidy(c: Context) {
        val cur = PackPaths.current(c) ?: return
        if (!PackPaths.hasWorked(c)) return
        for (d in PackPaths.packs(c).listFiles().orEmpty()) {
            if (d.name != cur.id) {
                Log.i(TAG, "${cur.id} has worked: ${d.name} goes")
                PackFiles.discard(d, PackPaths.trash(c))
            }
        }
    }

    /** With nothing running: what state the files, the offer and the last problem add up to. */
    private fun settle(c: Context, problem: PackProblem?, failed: Boolean = false) {
        val cur = PackPaths.current(c)
        val free = freeBytes()
        val staged = stagedManifest(c)
        val m = offeredManifest?.takeIf { cur?.id != it.id && PackRules.newer(it.built, cur?.manifest?.built) }
        if (m == null) { offered = null; offeredManifest = null }
        val usb = usbFind?.manifest?.takeIf { cur?.id != it.id && PackRules.newer(it.built, cur?.manifest?.built) }
        fun offerOf(x: PackManifest, viaUsb: Boolean) = Offer(x.id, x.built, x.downloadBytes, x.diskBytes, x.needBytes, free + PackJob.staged(c, x), cur != null, viaUsb)
        val offer = m?.let { offerOf(it, false) } ?: staged?.takeIf { problem == PackProblem.SPACE }?.let { offerOf(it, false) }
        val usbOffer = usb?.let { offerOf(it, true) }
        val state = when {
            problem == PackProblem.SPACE -> State.NO_SPACE
            failed -> State.FAILED
            staged != null -> State.PAUSED
            offer != null || usbOffer != null -> if ((offer ?: usbOffer)!!.fits) State.OFFER else State.NO_SPACE
            cur != null -> State.INSTALLED
            else -> State.NONE
        }
        val fetched = staged?.let { PackJob.fetchedBytes(c, it) } ?: 0L
        val old = if (PackPaths.hasWorked(c)) oldMapBytes(c) else 0L
        synchronized(lock) { doneNow = fetched; totalNow = staged?.downloadBytes ?: 0L }
        meter.reset()
        update {
            it.copy(state = state, done = fetched, total = staged?.downloadBytes ?: 0L, bytesPerSec = 0, problem = problem, stagingId = staged?.id,
                offer = offer, usbOffer = usbOffer, currentId = cur?.id, currentBuilt = cur?.manifest?.built, currentBytes = cur?.manifest?.diskBytes ?: 0L, oldMapBytes = old)
        }
    }

    private fun refreshFacts(c: Context) {
        val cur = PackPaths.current(c)
        val old = if (PackPaths.hasWorked(c)) oldMapBytes(c) else 0L
        update { it.copy(currentId = cur?.id, currentBuilt = cur?.manifest?.built, currentBytes = cur?.manifest?.diskBytes ?: 0L, oldMapBytes = old) }
    }

    private fun oldMapBytes(c: Context): Long =
        File(c.filesDir, "offline").listFiles()?.filter { it.name.startsWith("map.db") }?.sumOf { it.length() } ?: 0L

    internal fun networkProblem(t: Throwable): PackProblem {
        // a head unit's clock is set late, and a certificate looks wrong until then
        val tls = t is SSLHandshakeException || t is SSLException || t.cause is java.security.cert.CertificateException
        return if (tls) PackProblem.CLOCK else PackProblem.NET
    }

    private fun update(throttled: Boolean = false, change: (Status) -> Status) {
        synchronized(this) { status = change(status) }
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            if (throttled && now - notifiedAt < NOTIFY_EVERY_MS) return
            notifiedAt = now
            if (posted) return
            posted = true
        }
        main.post {
            synchronized(lock) { posted = false }
            for (l in listeners) try { l() } catch (t: Throwable) { Log.w(TAG, "listener: $t") }
        }
    }

}
