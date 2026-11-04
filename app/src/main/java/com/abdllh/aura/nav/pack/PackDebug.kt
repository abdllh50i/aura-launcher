package com.abdllh.aura.nav.pack

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import android.util.Log
import com.abdllh.aura.nav.LatLon
import com.abdllh.aura.nav.NavSession
import com.abdllh.aura.nav.Place
import com.abdllh.aura.nav.Polyline
import com.abdllh.aura.nav.Route
import com.abdllh.aura.nav.Step
import org.json.JSONObject
import java.io.File

/**
 * Debug builds only, the tests' way into the pack manager (am broadcast -a com.abdllh.aura.debug.PACK; scripts/pack_e2e.py drives it). Log tag AuraPackDebug.
 *   --ez check true | scan true | start true | startusb true | resume true | pause true | cancel true | delete true | free true    what the screens can do
 *   --ez worked true          the pack in use "has served": what a real user of the pack will say (PackPaths.noteWorked)
 *   --ez dump true            the status and where the five files are, one line each
 *   --es dieat <point>        the process dies at that point of the work: after-fetch, after-unpack, after-mark, before-ok, before-rename, before-pointer, after-pointer
 *   --ez trip true|false      a real trip begins or ends in NavSession (no route server needed)
 *   --ez reader true|false    a reader asks PackPaths twice a second, opens places.db read only, keeps what it opened: shows who it reads through a switch
 *   --ez selftest true        the parser against a server's real manifest, spoiled in every way
 * Props: debug.aura.pack.base (a server), .usb (a folder standing in for a mount), .free (free bytes it believes), .root (another folder than maps2)
 */
internal object PackDebug {
    private const val TAG = "AuraPackDebug"
    private const val ACTION = "com.abdllh.aura.debug.PACK"
    @Volatile private var reader: Thread? = null
    private var lastDump = 0L

    fun init(ctx: Context) {
        val app = ctx.applicationContext
        try {
            app.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    try { handle(app, i) } catch (t: Throwable) { Log.w(TAG, "$t") }
                }
            }, IntentFilter(ACTION))
            PackManager.addListener {
                val now = SystemClock.elapsedRealtime()
                if (now - lastDump >= 5000L) { lastDump = now; dumpLine() }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "$t")
        }
    }

    private fun handle(app: Context, i: Intent) {
        i.getStringExtra("dieat")?.let { PackManager.dieAt = it.ifEmpty { null }; Log.i(TAG, "dieat=$it") }
        if (i.getBooleanExtra("check", false)) PackManager.checkUpdate()
        if (i.getBooleanExtra("scan", false)) PackManager.scanUsb()
        if (i.getBooleanExtra("start", false)) PackManager.startDownload()
        if (i.getBooleanExtra("startusb", false)) PackManager.startUsb()
        if (i.getBooleanExtra("resume", false)) PackManager.resume()
        if (i.getBooleanExtra("pause", false)) PackManager.pause()
        if (i.getBooleanExtra("cancel", false)) PackManager.cancel()
        if (i.getBooleanExtra("delete", false)) Log.i(TAG, "delete taken: ${PackManager.deleteCurrent()}")
        if (i.getBooleanExtra("free", false)) PackManager.freeOldMap { Log.i(TAG, "old map freed") }
        if (i.getBooleanExtra("worked", false)) Thread { PackPaths.noteWorked(app) }.start()
        if (i.hasExtra("trip")) trip(app, i.getBooleanExtra("trip", false))
        if (i.hasExtra("reader")) reader(app, i.getBooleanExtra("reader", false))
        if (i.getBooleanExtra("selftest", false)) Thread { selftest(app) }.start()
        if (i.getBooleanExtra("dump", false)) { dumpLine(); paths(app) }
    }

    private fun dumpLine() {
        val s = PackManager.status
        Log.i(TAG, "status state=${s.state} percent=${s.percent} done=${s.done} total=${s.total} speed=${s.bytesPerSec} problem=${s.problem} usb=${s.viaUsb} staging=${s.stagingId} current=${s.currentId} " +
            "built=${s.currentBuilt} oldMap=${s.oldMapBytes} offer=${s.offer?.let { "${it.id},dl=${it.downloadBytes},disk=${it.diskBytes},need=${it.needBytes},free=${it.freeBytes},fits=${it.fits},update=${it.isUpdate}" }} " +
            "usbOffer=${s.usbOffer?.let { "${it.id},fits=${it.fits}" }}")
    }

    private fun paths(app: Context) {
        Log.i(TAG, "paths base=${PackPaths.baseMbtiles(app)} route=${PackPaths.routeTar(app)} places=${PackPaths.placesDb(app)} pois=${PackPaths.placesMbtiles(app)} glyphs=${PackPaths.glyphsDb(app)} worked=${PackPaths.hasWorked(app)}")
        Log.i(TAG, "lastInstall=${PackManager.lastInstall()}")
        PackManager.status.problem?.let { Log.i(TAG, "problem text: ${PackManager.text(it)}") }
        Log.i(TAG, "line: ${PackManager.line()}")
        PackManager.status.offer?.let { Log.i(TAG, "advice: ${PackManager.advice(it)}") }
    }

    private fun trip(app: Context, on: Boolean) {
        if (!on) { NavSession.stop(); Log.i(TAG, "trip ended"); return }
        val a = LatLon(26.30, 50.10)
        val b = LatLon(26.31, 50.11)
        val steps = listOf(Step("depart", "", 0, "", "", a, 1500.0, 90.0), Step("arrive", "", 0, "", "", b, 0.0, 0.0, 1500.0))
        NavSession.start(app, Place("pack test", "", b), Route(Polyline(listOf(a, b)), steps, 1500.0, 90.0))
        Log.i(TAG, "trip on: ${NavSession.state}")
    }

    /** Asks like a real user of the pack. Each file opened stays open, so what the old pack's readers do after a switch shows too. */
    private fun reader(app: Context, on: Boolean) {
        val old = reader
        reader = null
        old?.interrupt()
        if (!on) return
        val t = Thread({
            val open = LinkedHashMap<String, SQLiteDatabase>()
            var reads = 0L
            var errors = 0L
            try {
                var lastPath = "?"
                while (reader === Thread.currentThread()) {
                    val f = PackPaths.placesDb(app)
                    val path = f?.path ?: "none"
                    if (path != lastPath) { Log.i(TAG, "reader now on $path (after $reads reads, $errors errors)"); lastPath = path }
                    if (f != null && f.path !in open) open[f.path] = SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
                    for ((p, d) in open) {
                        try {
                            val n = d.rawQuery("SELECT count(*) FROM (SELECT id FROM p LIMIT 1000)", null).use { c -> c.moveToFirst(); c.getInt(0) }
                            if (n != 1000) { errors++; Log.w(TAG, "reader $p: $n rows") }
                            reads++
                        } catch (e: Throwable) {
                            errors++
                            Log.w(TAG, "reader $p: $e")
                        }
                    }
                    Thread.sleep(500)
                }
            } catch (_: InterruptedException) {
            } catch (t: Throwable) {
                errors++
                Log.w(TAG, "reader: $t")
            }
            for (d in open.values) try { d.close() } catch (_: Throwable) { }
            Log.i(TAG, "reader ended: $reads reads, $errors errors, open: ${open.keys}")
        }, "aura-pack-reader")
        reader = t
        t.start()
    }

    /** Runs on the phone's own org.json: the JVM tests have none. */
    private fun selftest(app: Context) {
        val base = HttpSource.debugBase() ?: return Unit.also { Log.w(TAG, "selftest: no debug.aura.pack.base") }
        val good = HttpSource(base).manifestText()
        val code = PackPaths.appCode(app)
        var failed = 0
        fun refused(name: String, want: PackProblem, change: (JSONObject) -> Unit) {
            val o = JSONObject(good)
            change(o)
            val got = try { PackManifest.parse(o.toString(), code); null } catch (r: PackRefused) { r.why }
            val ok = got == want
            if (!ok) failed++
            Log.i(TAG, "selftest ${if (ok) "ok  " else "FAIL"} $name: $got")
        }
        val m = PackManifest.parse(good, code)
        Log.i(TAG, "selftest the real manifest: ${m.id} built ${m.built}, ${m.pieces.size} pieces, ${m.chunkCount} chunks, disk ${m.diskBytes}, download ${m.downloadBytes}, need ${m.needBytes}, valhalla ${m.valhalla}, norm ${m.normVersion}, attribution ${m.attribution}")
        if (m.diskBytes != 713123840L || m.downloadBytes != 386591369L + 22048L || m.needBytes != 989947904L) { failed++; Log.i(TAG, "selftest FAIL sizes") }
        refused("format 2", PackProblem.APP_TOO_OLD) { it.put("format", 2) }
        refused("no format", PackProblem.APP_TOO_OLD) { it.remove("format") }
        refused("minApp above this app", PackProblem.APP_TOO_OLD) { it.put("minApp", code + 1) }
        refused("maxApp below this app", PackProblem.PACK_TOO_OLD) { it.put("maxApp", code - 1) }
        refused("id with a slash", PackProblem.BAD_MANIFEST) { it.put("id", "../../x") }
        refused("piece name climbing out", PackProblem.BAD_MANIFEST) { it.getJSONArray("pieces").getJSONObject(0).put("name", "../../../shared_prefs/aura.xml") }
        refused("piece named like the manifest", PackProblem.BAD_MANIFEST) { it.getJSONArray("pieces").getJSONObject(0).put("name", "manifest.json") }
        refused("chunk url with a path", PackProblem.BAD_MANIFEST) { it.getJSONArray("pieces").getJSONObject(0).getJSONArray("chunks").getJSONObject(0).put("url", "a/b.gz") }
        refused("chunk hash not hex", PackProblem.BAD_MANIFEST) { it.getJSONArray("pieces").getJSONObject(0).getJSONArray("chunks").getJSONObject(0).put("sha256", "abc") }
        refused("chunk offset with a hole", PackProblem.BAD_MANIFEST) { it.getJSONArray("pieces").getJSONObject(0).getJSONArray("chunks").getJSONObject(1).put("offset", 5) }
        refused("size that does not add up", PackProblem.BAD_MANIFEST) { it.getJSONArray("pieces").getJSONObject(0).put("size", 5) }
        refused("no route.tar", PackProblem.BAD_MANIFEST) { o -> val a = o.getJSONArray("pieces"); for (k in 0 until a.length()) if (a.getJSONObject(k).getString("name") == "route.tar") { a.remove(k); break } }
        refused("no pieces", PackProblem.BAD_MANIFEST) { it.remove("pieces") }
        val text = "this is a page from a hotspot"
        val got = try { PackManifest.parse(text, code); null } catch (r: PackRefused) { r.why }
        if (got != PackProblem.BAD_MANIFEST) failed++
        Log.i(TAG, "selftest ${if (got == PackProblem.BAD_MANIFEST) "ok  " else "FAIL"} not JSON: $got")
        val low = JSONObject(good).put("needBytes", 5)
        val raised = PackManifest.parse(low.toString(), code).needBytes
        if (raised != m.needBytes) failed++
        Log.i(TAG, "selftest ${if (raised == m.needBytes) "ok  " else "FAIL"} a needBytes too small is raised to $raised")
        val unknown = JSONObject(good).put("newField", "x")
        val extra = try { PackManifest.parse(unknown.toString(), code); true } catch (_: PackRefused) { false }
        if (!extra) failed++
        Log.i(TAG, "selftest ${if (extra) "ok  " else "FAIL"} an unknown field is ignored")
        // what lands where
        val c = PackPaths.current(app)
        Log.i(TAG, "selftest done, failed=$failed (current ${c?.id}, ${c?.notice("NOTICE-PACK.md")?.let { File(it.path).length() }} bytes of notices)")
    }
}
