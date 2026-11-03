package com.abdllh.aura.diag

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.view.FrameMetrics
import android.view.Window
import com.mapbox.mapboxsdk.maps.MapView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * HWUI's frames over a stretch of time: every frame's times, as the window reports them, turned into percentiles and
 * a jank share. A frame counts as late over one [budgetMs] (the display's vsync), badly late over two.
 * Delivered on a thread of the caller's, a little after the frame: [end] waits a moment for the last ones.
 */
internal class FrameLog(private val budgetMs: Double) : Window.OnFrameMetricsAvailableListener {
    private val cap = 4096
    private val total = DoubleArray(cap)
    private val ui = DoubleArray(cap)
    private val render = DoubleArray(cap)
    private var n = 0
    private var dropped = 0
    private var since = 0L
    @Volatile private var on = false

    override fun onFrameMetricsAvailable(w: Window, m: FrameMetrics, drop: Int) {
        if (!on) return
        synchronized(this) {
            dropped += drop
            if (n >= cap) return
            total[n] = m.getMetric(FrameMetrics.TOTAL_DURATION) / 1e6
            ui[n] = (m.getMetric(FrameMetrics.INPUT_HANDLING_DURATION) + m.getMetric(FrameMetrics.ANIMATION_DURATION) +
                m.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION) + m.getMetric(FrameMetrics.DRAW_DURATION)) / 1e6
            render[n] = (m.getMetric(FrameMetrics.SYNC_DURATION) + m.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION) +
                m.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION)) / 1e6
            n++
        }
    }

    fun begin() {
        synchronized(this) { n = 0; dropped = 0 }
        since = SystemClock.elapsedRealtime()
        on = true
    }

    /** What came since [begin]; not on the main thread (it waits for the frames still on their way). */
    fun end(): JSONObject {
        val seconds = (SystemClock.elapsedRealtime() - since) / 1000.0
        Thread.sleep(150)
        on = false
        val o = JSONObject()
        synchronized(this) {
            o.put("frames", n)
            o.put("seconds", Num.of(seconds))
            o.put("fps", Num.of(n / seconds.coerceAtLeast(0.001)))
            if (dropped > 0) o.put("notReported", dropped)
            if (n > 0) {
                val t = total.copyOf(n)
                Num.spread(o, t)
                o.put("jankPct", Num.of(100.0 * t.count { it > budgetMs } / n))
                o.put("badPct", Num.of(100.0 * t.count { it > 2 * budgetMs } / n))
                val u = ui.copyOf(n).sortedArray()
                val r = render.copyOf(n).sortedArray()
                o.put("uiP50", Num.of(Num.pct(u, 50.0)))
                o.put("uiP95", Num.of(Num.pct(u, 95.0)))
                o.put("renderP50", Num.of(Num.pct(r, 50.0)))
                o.put("renderP95", Num.of(Num.pct(r, 95.0)))
            }
        }
        return o
    }
}

/** The map's own frames: the time between two drawn frames, and whether the map has everything it wanted to draw. */
internal class MapFrames : MapView.OnDidFinishRenderingFrameListener {
    private val cap = 8192
    private val at = LongArray(cap)
    private var n = 0
    @Volatile private var on = false
    private var fullyArmed = false
    /** When a frame last said that all it needs has come since [watchFully] (elapsedRealtime; 0: none yet). */
    @Volatile var fullyAt = 0L
        private set

    override fun onDidFinishRenderingFrame(fully: Boolean) {
        val now = SystemClock.elapsedRealtimeNanos()
        if (fully && fullyArmed) { fullyArmed = false; fullyAt = SystemClock.elapsedRealtime() }
        if (!on) return
        synchronized(this) { if (n < cap) at[n++] = now }
    }

    fun watchFully() { fullyAt = 0L; fullyArmed = true }

    fun begin() {
        synchronized(this) { n = 0 }
        on = true
    }

    fun end(seconds: Double): JSONObject {
        on = false
        val o = JSONObject()
        synchronized(this) {
            o.put("frames", n)
            o.put("fps", Num.of(n / seconds.coerceAtLeast(0.001)))
            if (n > 1) {
                val gaps = DoubleArray(n - 1) { (at[it + 1] - at[it]) / 1e6 }
                Num.spread(o, gaps)
                // second by second: whether the map held its rate, or fell to 30 on its way (AuraMap.measureFps)
                val perSec = JSONArray()
                val secs = ((at[n - 1] - at[0]) / 1_000_000_000L).toInt() + 1
                val counts = IntArray(secs)
                for (i in 0 until n) counts[((at[i] - at[0]) / 1_000_000_000L).toInt().coerceIn(0, secs - 1)]++
                for (c in counts) perSec.put(c)
                o.put("perSecond", perSec)
            }
        }
        return o
    }
}

/**
 * The processor's work over a stretch of time: this process, by thread, and the whole system. Not on the main thread:
 * where the app may not read /proc/stat (an enforcing SELinux, the emulator) the root shell is asked.
 */
internal class CpuSnap private constructor(
    private val wall: Long,
    private val proc: Long,
    private val sys: LongArray?,
    private val threads: Map<String, Long>
) {
    /** "cpuPct" (% of one core, this process), "sysPct" (% of all cores together), "threads" (the busiest, % of one core). */
    fun since(o: JSONObject) {
        val now = take()
        val ms = (now.wall - wall).coerceAtLeast(1L)
        o.put("cpuPct", Num.of(100.0 * (now.proc - proc) / ms))
        val a = sys
        val b = now.sys
        if (a != null && b != null) {
            val total = b.sum() - a.sum()
            val idle = (b[3] + b[4]) - (a[3] + a[4])
            if (total > 0) o.put("sysPct", Num.of(100.0 * (total - idle) / total))
        }
        val tick = ticksPerSecond()
        val busiest = JSONArray()
        now.threads.map { (name, t) -> name to t - (threads[name] ?: 0L) }.filter { it.second > 0 }.sortedByDescending { it.second }.take(6).forEach {
            busiest.put(JSONObject().put("name", it.first).put("pct", Num.of(100.0 * it.second * 1000.0 / tick / ms)))
        }
        o.put("threads", busiest)
    }

    companion object {
        fun take(): CpuSnap = CpuSnap(SystemClock.elapsedRealtime(), Process.getElapsedCpuTime(), readSystem(), readThreads())

        private fun ticksPerSecond(): Double = try { Os.sysconf(OsConstants._SC_CLK_TCK).toDouble().coerceAtLeast(1.0) } catch (_: Throwable) { 100.0 }

        /** user, nice, system, idle, iowait, irq, softirq, steal. */
        private fun readSystem(): LongArray? {
            val line = try { File("/proc/stat").bufferedReader().use { it.readLine() } } catch (_: Throwable) { null }
                ?: Sh.text("head -n 1 /proc/stat", 2000).takeIf { it.startsWith("cpu") } ?: return null
            val v = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
            return if (v.size >= 5) LongArray(8) { v.getOrElse(it) { 0L } } else null
        }

        /** This process's threads by name (MapLibre's ten workers as one), in clock ticks. */
        private fun readThreads(): Map<String, Long> {
            val out = HashMap<String, Long>()
            val dirs = try { File("/proc/self/task").listFiles() } catch (_: Throwable) { null } ?: return out
            for (d in dirs) {
                try {
                    val s = File(d, "stat").readText()
                    val open = s.indexOf('(')
                    val close = s.lastIndexOf(')')
                    if (open < 0 || close < open) continue
                    val rest = s.substring(close + 2).split(' ')
                    val ticks = (rest.getOrNull(11)?.toLongOrNull() ?: 0L) + (rest.getOrNull(12)?.toLongOrNull() ?: 0L)
                    val name = s.substring(open + 1, close).trimEnd { it.isDigit() || it == '-' || it == ' ' }.ifEmpty { "?" }
                    out[name] = (out[name] ?: 0L) + ticks
                } catch (_: Throwable) {
                }
            }
            return out
        }
    }
}

/** This process's memory as Android counts it. Not on the main thread: it reads all of smaps. */
internal fun processMemory(): JSONObject {
    val mi = Debug.MemoryInfo()
    Debug.getMemoryInfo(mi)
    val o = JSONObject().put("pssKb", mi.totalPss)
    fun stat(key: String, name: String) { mi.getMemoryStat("summary.$name")?.toIntOrNull()?.let { o.put(key, it) } }
    stat("javaKb", "java-heap")
    stat("nativeKb", "native-heap")
    stat("codeKb", "code")
    stat("graphicsKb", "graphics")
    stat("otherKb", "private-other")
    stat("systemKb", "system")
    return o
}

/**
 * What the Mali driver itself says about its memory, for when memtrack reads 0 (the Graphics row): by root, from where
 * kbase keeps it, wherever this firmware has it. Empty when nothing there can be read.
 */
internal fun maliMemory(): String = Num.cut(Sh.text(
    "for f in /sys/kernel/debug/mali0/gpu_memory /d/mali0/gpu_memory /sys/kernel/debug/mali/gpu_memory " +
        "/sys/module/mali_kbase/drivers/platform:mali/*/gpu_memory /sys/class/misc/mali0/device/gpuinfo; do " +
        "[ -r \"\$f\" ] && { echo \"# \$f\"; head -c 3000 \"\$f\"; }; done", 3000), 6000)
