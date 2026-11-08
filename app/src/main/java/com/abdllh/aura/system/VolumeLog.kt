package com.abdllh.aura.system

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * The last [CAP] volume events, in memory and in files/diag/volume.jsonl (the Screen check's report carries them): a wheel key,
 * the firmware's callback and its setting (observer) with their value, AMRI's own set, a boost change, a glitch and what was done.
 * One line each in the log too (AuraVolume). The file is written whole, a little after the last event, on a thread of its own.
 */
object VolumeLog {
    private const val TAG = "AuraVolume"
    private const val CAP = 200
    private const val WRITE_DELAY_MS = 1500L

    private val ring = ArrayDeque<JSONObject>(CAP)
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "aura-volume-log").apply { isDaemon = true } }
    private var file: File? = null
    @Volatile private var dirty = false
    @Volatile private var writeAt = 0L

    fun init(c: Context) {
        if (file != null) return
        val f = File(File(c.filesDir, "diag").apply { mkdirs() }, "volume.jsonl")
        file = f
        io.execute {
            try { // what the last run left: the ring carries on across restarts
                if (f.isFile) {
                    val old = f.readLines().takeLast(CAP).mapNotNull { try { JSONObject(it) } catch (_: Throwable) { null } }
                    synchronized(ring) {
                        val now = ArrayList(ring)
                        ring.clear()
                        for (o in old) ring.addLast(o)
                        for (o in now) ring.addLast(o)
                        while (ring.size > CAP) ring.removeFirst()
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "volume log read: $t")
            }
        }
    }

    /** One event. [src]: key-up, key-down, key-mute, cb, obs, self, boost, glitch, note. */
    fun add(src: String, value: Int? = null, why: String? = null, level: Int? = null, fw: Int? = null, boost: Int? = null) {
        val o = JSONObject().put("t", System.currentTimeMillis()).put("up", SystemClock.elapsedRealtime()).put("src", src)
        if (value != null) o.put("v", value)
        if (level != null) o.put("lvl", level)
        if (fw != null) o.put("fw", fw)
        if (boost != null) o.put("b", boost)
        if (why != null) o.put("why", why)
        synchronized(ring) {
            ring.addLast(o)
            while (ring.size > CAP) ring.removeFirst()
        }
        Log.i(TAG, "$src${value?.let { " $it" } ?: ""}${why?.let { ": $it" } ?: ""} (level ${level ?: "?"}, fw ${fw ?: "?"}, boost ${boost ?: "?"})")
        schedule()
    }

    fun json(): JSONArray = JSONArray().apply { synchronized(ring) { for (o in ring) put(o) } }

    private fun schedule() {
        writeAt = SystemClock.elapsedRealtime() + WRITE_DELAY_MS
        if (dirty) return
        dirty = true
        io.execute(object : Runnable {
            override fun run() {
                val wait = writeAt - SystemClock.elapsedRealtime()
                if (wait > 0) { try { Thread.sleep(wait) } catch (_: InterruptedException) { } ; run(); return }
                dirty = false
                write()
            }
        })
    }

    private fun write() {
        val f = file ?: return
        try {
            val text = StringBuilder()
            synchronized(ring) { for (o in ring) text.append(o.toString()).append('\n') }
            val tmp = File(f.path + ".tmp")
            tmp.writeText(text.toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (t: Throwable) {
            Log.w(TAG, "volume log write: $t")
        }
    }
}
