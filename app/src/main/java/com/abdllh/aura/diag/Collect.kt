package com.abdllh.aura.diag

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Point
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognitionService
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.music.LyricsServer
import com.abdllh.aura.music.PhoneLink
import com.abdllh.aura.nav.CarLocation
import com.abdllh.aura.nav.Places
import com.abdllh.aura.system.CanGear
import com.abdllh.aura.system.Device
import com.abdllh.aura.system.Gear
import com.abdllh.aura.system.SystemProps
import com.abdllh.aura.ui.CarStage
import com.abdllh.aura.util.Prefs
import com.abdllh.aura.voice.VoiceSetup
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * What the screen check reads in the main process: nothing here loads GL or native code, every call is off the main
 * thread and has an end, and a value that cannot be had is left out or says so. Only what is listed here goes out: no
 * lyrics key, no coordinates of any kind, no contacts, no song names, no message text.
 */
internal object Collect {
    private fun prop(k: String) = SystemProps.get(k)

    private fun read(path: String, cap: Int = 64 * 1024): String? = try {
        FileInputStream(path).use { inp ->
            val b = ByteArray(cap)
            var n = 0
            while (n < cap) {
                val got = inp.read(b, n, cap - n)
                if (got < 0) break
                n += got
            }
            String(b, 0, n, Charsets.UTF_8)
        }
    } catch (_: Throwable) {
        null
    }

    private fun first(regex: String, text: String): String? = Regex(regex, RegexOption.MULTILINE).find(text)?.groupValues?.getOrNull(1)?.trim()

    // ------------------------------------------------------------------------------------------ A: who and what
    fun identity(c: Context): JSONObject {
        val o = JSONObject()
        val ai = c.applicationInfo
        o.put("appVersion", BuildConfig.VERSION_NAME).put("appCode", BuildConfig.VERSION_CODE).put("debugBuild", BuildConfig.DEBUG)
        o.put("romVersion", prop("ro.aura.rom.version"))
        o.put("updatedSystemApp", ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0)
        o.put("systemApp", ai.flags and ApplicationInfo.FLAG_SYSTEM != 0)
        o.put("privileged", Device.isPrivileged(c))
        o.put("apkPath", ai.sourceDir)
        o.put("fingerprint", Build.FINGERPRINT).put("buildId", Device.buildId).put("model", Device.model)
        o.put("android", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT).put("securityPatch", Build.VERSION.SECURITY_PATCH)
        o.put("hardware", Build.HARDWARE).put("board", Build.BOARD).put("isNwd", Device.isNwd)
        o.put("kernel", read("/proc/version", 512)?.trim() ?: System.getProperty("os.version"))
        o.put("rootShell", Sh.root())
        val props = JSONObject()
        for (k in listOf("ro.debuggable", "ro.secure", "ro.adb.secure", "sys.task.monitor.enable", "sys.whitelist.enable",
            "sys.cpu.usage.monitor.enable", "ro.opengles.version", "ro.sf.lcd_density", "ro.board.platform", "ro.sys.cputype",
            "ro.product.firmware", "persist.lowmem.1024m", "ro.config.low_ram", "dalvik.vm.heapgrowthlimit", "dalvik.vm.heapsize",
            "persist.aura.disabled", "persist.nwd.launcher.default", "ro.hwui.use_vulkan", "ro.nwd.internal.path", "zj.phonelink.type",
            "persist.nwd.bms.enable", "ro.nwd.config.path")) {
            props.put(k, prop(k))
        }
        o.put("props", props)
        // the firmware's force-stop of whatever is not home and not on its list (guard/BackgroundList.kt): whether it runs
        // here, and its lists as it holds them now. Package names only.
        val killer = Sh.text("dumpsys nwdbackground | head -c 12000; echo; settings get system kill_background_services; settings get system whitelist_packagename; grep -c 'white:com.abdllh.aura' /data/nwdappconfig/app/BackgroundManager.ini", 6000)
        if (killer.isNotEmpty()) o.put("nwdBackground", Num.cut(killer, 12000))
        // what an app may not read by itself (the emulator, an enforcing SELinux)
        val shProps = Sh.text("getprop | grep -E 'hwui|egl|gralloc|opengles|lowmem|low_ram|lmk|zram|dalvik.vm.heap|dexopt|task.monitor|whitelist|cpu.usage|aura|nwd.platform' | head -c 12000")
        if (shProps.isNotEmpty()) o.put("propsShell", shProps)
        val enforce = read("/sys/fs/selinux/enforce", 8)?.trim()
        o.put("selinux", when {
            enforce == "1" -> "Enforcing"
            enforce == "0" -> "Permissive"
            else -> Sh.text("getenforce", 3000).trim().ifEmpty { "unknown" }
        })
        // the copy in the ROM: dumpsys names it under "Hidden system packages" once an update is in /data
        val dump = Sh.text("dumpsys package ${c.packageName} | head -c 80000")
        if (dump.isNotEmpty()) {
            val hiddenAt = dump.indexOf("Hidden system packages:")
            val active = if (hiddenAt >= 0) dump.substring(0, hiddenAt) else dump
            fun pack(t: String) = JSONObject()
                .put("codePath", first("^\\s*codePath=(.*)$", t).orEmpty())
                .put("versionName", first("^\\s*versionName=(.*)$", t).orEmpty())
                .put("versionCode", first("^\\s*versionCode=(\\d+)", t).orEmpty())
            val runningPack = pack(active)
            o.put("activeCopy", runningPack)
            when {
                hiddenAt >= 0 -> o.put("romCopy", pack(dump.substring(hiddenAt)))
                runningPack.optString("codePath").startsWith("/system") -> o.put("romCopy", runningPack)
                else -> o.put("romCopy", JSONObject.NULL)
            }
            val dex = dump.indexOf("Dexopt state:")
            if (dex >= 0) {
                val rest = dump.substring(dex)
                val end = Regex("\\n\\S").find(rest, 16)?.range?.first ?: rest.length
                o.put("dexopt", Num.cut(rest.substring(0, end).trim(), 2000))
            }
        }
        val wm = c.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        @Suppress("DEPRECATION") val d = wm?.defaultDisplay
        if (d != null) {
            val size = Point()
            @Suppress("DEPRECATION") d.getRealSize(size)
            o.put("display", JSONObject().put("width", size.x).put("height", size.y)
                .put("refreshRate", Num.of(d.refreshRate.toDouble())).put("density", Num.of(c.resources.displayMetrics.density.toDouble(), 2)))
        }
        return o
    }

    // ------------------------------------------------------------------------------------------ B: how much there is
    fun capacity(c: Context): JSONObject {
        val o = JSONObject()
        val mem = read("/proc/meminfo") ?: Sh.text("cat /proc/meminfo")
        o.put("meminfo", Num.cut(mem, 3000))
        for (k in listOf("MemTotal", "MemFree", "MemAvailable", "Cached", "SwapTotal", "SwapFree", "CmaTotal", "CmaFree")) {
            first("^$k:\\s+(\\d+)", mem)?.toLongOrNull()?.let { o.put(k + "Kb", it) }
        }
        (c.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.let { am ->
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            o.put("androidTotalMb", mi.totalMem / 1_048_576).put("androidAvailMb", mi.availMem / 1_048_576)
                .put("lowMemoryUnderMb", mi.threshold / 1_048_576).put("lowMemoryNow", mi.lowMemory)
                .put("heapClassMb", am.memoryClass).put("largeHeapClassMb", am.largeMemoryClass).put("lowRamDevice", am.isLowRamDevice)
        }
        o.put("zram", Sh.text("cat /proc/swaps; for f in disksize mm_stat comp_algorithm max_comp_streams; do echo \"\$f: \$(cat /sys/block/zram0/\$f 2>/dev/null)\"; done", 3000))
        val stat = JSONObject()
        fun free(name: String, path: String?) {
            if (path == null) return
            try {
                val s = StatFs(path)
                stat.put(name, JSONObject().put("path", path).put("totalMb", s.totalBytes / 1_048_576).put("freeMb", s.availableBytes / 1_048_576))
            } catch (_: Throwable) {
            }
        }
        free("data", c.filesDir.path)
        @Suppress("DEPRECATION") free("external", Environment.getExternalStorageDirectory()?.path)
        free("nwdInternal", prop("ro.nwd.internal.path").ifEmpty { null })
        o.put("storage", stat)
        o.put("df", Sh.text("df -k /data /cache /sdcard /mnt/media_rw/* 2>/dev/null | head -c 2000", 4000))
        // the processor
        o.put("cores", Runtime.getRuntime().availableProcessors())
        o.put("abis", JSONArray().apply { for (a in Build.SUPPORTED_ABIS) put(a) })
        val info = read("/proc/cpuinfo") ?: ""
        o.put("cpuModel", (first("^Hardware\\s*:\\s*(.*)$", info) ?: first("^model name\\s*:\\s*(.*)$", info) ?: first("^Processor\\s*:\\s*(.*)$", info)).orEmpty())
        o.put("cpuPart", first("^CPU part\\s*:\\s*(.*)$", info).orEmpty())
        o.put("cpuinfo", Num.cut(info, 1500))
        val freq = JSONArray()
        val cpus = try { File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(Regex("cpu\\d+")) } } catch (_: Throwable) { null }
        for (d in cpus.orEmpty().sortedBy { it.name }) {
            val f = JSONObject().put("cpu", d.name)
            fun khz(key: String, name: String) { read(File(d, "cpufreq/$name").path, 32)?.trim()?.let { f.put(key, it) } }
            khz("minKhz", "cpuinfo_min_freq")
            khz("maxKhz", "cpuinfo_max_freq")
            khz("nowKhz", "scaling_cur_freq")
            khz("governor", "scaling_governor")
            freq.put(f)
        }
        o.put("cpuFreq", freq)
        o.put("gpuFreq", Sh.text("for d in /sys/class/devfreq/*; do echo \"\$d now=\$(cat \$d/cur_freq 2>/dev/null) max=\$(cat \$d/max_freq 2>/dev/null) all=\$(cat \$d/available_frequencies 2>/dev/null) gov=\$(cat \$d/governor 2>/dev/null)\"; done | head -c 2000", 3000))
        o.put("thermal", thermal())
        o.put("write", writeTest(c))
        return o
    }

    /** Every thermal zone: its name and °C. */
    fun thermal(): JSONArray {
        val out = JSONArray()
        val zones = try { File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") } } catch (_: Throwable) { null }
        if (!zones.isNullOrEmpty()) {
            for (z in zones.sortedBy { it.name }) {
                val t = read(File(z, "temp").path, 16)?.trim()?.toDoubleOrNull() ?: continue
                out.put(JSONObject().put("zone", read(File(z, "type").path, 64)?.trim().orEmpty()).put("c", Num.of(if (t > 1000) t / 1000 else t)))
            }
        }
        if (out.length() == 0) {
            for (l in Sh.text("for z in /sys/class/thermal/thermal_zone*; do echo \"\$(cat \$z/type) \$(cat \$z/temp)\"; done", 3000).lineSequence()) {
                val p = l.trim().split(' ')
                val t = p.lastOrNull()?.toDoubleOrNull() ?: continue
                out.put(JSONObject().put("zone", p.first()).put("c", Num.of(if (t > 1000) t / 1000 else t)))
            }
        }
        return out
    }

    /** 32 MB of noise into /data, to the disk and back. Deleted after, also when it fails. */
    private fun writeTest(c: Context): JSONObject {
        val o = JSONObject()
        val dir = DiagFiles.dir(c)
        val free = try { StatFs(dir.path).availableBytes } catch (_: Throwable) { 0L }
        if (free < 256L * 1_048_576) return o.put("skipped", "free space ${free / 1_048_576} MB")
        val f = File(dir, "write-test.bin")
        val mb = 32
        try {
            val chunk = Random(7).nextBytes(1_048_576)
            val t0 = SystemClock.elapsedRealtime()
            FileOutputStream(f).use { out ->
                repeat(mb) { out.write(chunk) }
                out.flush()
                val t1 = SystemClock.elapsedRealtime()
                out.fd.sync()
                val t2 = SystemClock.elapsedRealtime()
                o.put("mb", mb).put("writeMs", t1 - t0).put("syncMs", t2 - t1)
                o.put("mbPerSecond", Num.of(mb * 1000.0 / (t2 - t0).coerceAtLeast(1L)))
            }
            val t3 = SystemClock.elapsedRealtime()
            FileInputStream(f).use { inp -> while (inp.read(chunk) > 0) { /* (read only) */ } }
            o.put("readCachedMs", SystemClock.elapsedRealtime() - t3)
        } catch (t: Throwable) {
            o.put("error", t.toString())
        } finally {
            f.delete()
        }
        return o
    }

    // ------------------------------------------------------------------------------------------ D: AMRI's own memory
    /** The "App Summary" of a dumpsys meminfo, in KB. */
    private fun summary(t: String): JSONObject {
        val o = JSONObject()
        for (m in Regex("^\\s*(Java Heap|Native Heap|Code|Stack|Graphics|Private Other|System|TOTAL PSS|TOTAL):\\s+(\\d+)", RegexOption.MULTILINE).findAll(t)) {
            val k = m.groupValues[1].replace(" ", "")
            if (!o.has(k)) o.put(k, m.groupValues[2].toLong())
        }
        return o
    }

    fun memory(c: Context): JSONObject {
        val o = JSONObject()
        val status = read("/proc/self/status") ?: ""
        o.put("threads", first("^Threads:\\s+(\\d+)", status)?.toIntOrNull() ?: -1)
        o.put("vmRssKb", first("^VmRSS:\\s+(\\d+)", status)?.toLongOrNull() ?: -1L)
        o.put("vmPeakRssKb", first("^VmHWM:\\s+(\\d+)", status)?.toLongOrNull() ?: -1L)
        // twice, 3 s apart: the first call reports the natural state, and the GC it runs is what the second one shows.
        // By pid: by name, Android sometimes answers with the whole system's list and no app summary (seen on the emulator
        // with one process of that name running, and the same call by pid right)
        val pid = android.os.Process.myPid()
        val one = Sh.text("dumpsys meminfo $pid | head -c 20000", 10_000)
        Thread.sleep(3000)
        val two = Sh.text("dumpsys meminfo $pid | head -c 20000", 10_000)
        if (one.isEmpty()) o.put("available", false)
        else {
            o.put("available", true)
            o.put("first", summary(one)).put("second", summary(two))
            o.put("firstRaw", Num.cut(one, 9000)).put("secondRaw", Num.cut(two, 9000))
        }
        val gfx = Sh.text("dumpsys gfxinfo $pid | head -c 40000", 8000)
        if (gfx.isNotEmpty()) {
            o.put("gfxinfo", Num.cut(gfx.lineSequence().filter { !it.startsWith("HISTOGRAM") }.joinToString("\n"), 8000))
            first("^\\s*([\\d,]+ bytes, [\\d.]+ MB.*)$", gfx)?.let { o.put("hwuiGpuMemory", it) }
            o.put("hwuiTextureLines", Num.lines(gfx.lineSequence().filter { it.contains("Texture") || it.contains("Layers Total") }.joinToString("\n")))
            first("^Janky frames:\\s*(.*)$", gfx)?.let { o.put("hwuiJanky", it) }
        }
        o.put("mali", maliMemory())
        // (these two say that the GL view has drawn, and how fast its last run of draws went, which the entrance's fade is too: with no frame baked they can read true and 60. Whether it turns is "car")
        o.put("carFps", Num.of(CarStage.lastFps.toDouble())).put("carOnGpu", CarStage.gpuDrawing)
        o.put("car", CarStage.report())
        com.abdllh.aura.system.CrashGuard.lastCrash(c)?.let { o.put("lastCrash", Num.cut(it, 1500)) }
        return o
    }

    /** What the buffers still hold: how long the screens took to come up, and our crashes (after the tests: theirs too). */
    fun logs(c: Context): JSONObject {
        val o = JSONObject()
        val pkg = c.packageName
        o.put("displayed", Num.lines(Sh.text("logcat -d -s ActivityTaskManager:I ActivityManager:I AuraCar:I | grep -E 'Displayed $pkg|AuraCar' | tail -n 40 | cut -c1-160")))
        o.put("tombstones", Num.lines(Sh.text("ls -l /data/tombstones 2>/dev/null | tail -n 14")))
        o.put("ourTombstones", Sh.text("for f in \$(ls -t /data/tombstones/tombstone_* 2>/dev/null | head -n 12); do " +
            "if head -n 10 \"\$f\" | grep -q '>>> $pkg'; then echo \"== \$f\"; head -n 34 \"\$f\" | cut -c1-180; fi; done | head -c 12000", 8000))
        o.put("crashLines", Num.lines(crashLines(pkg)))
        return o
    }

    /** The crash buffer's lines of our package: of every process that named it, a crash_dump's lines included. */
    private fun crashLines(pkg: String): String {
        val all = Sh.text("logcat -d -b crash -v threadtime | tail -n 800 | cut -c1-200", 8000)
        if (all.isEmpty()) return ""
        fun pid(l: String) = l.trim().split(Regex("\\s+")).getOrNull(2)
        val pids = all.lineSequence().filter { it.contains(pkg) }.mapNotNull { pid(it) }.toSet()
        return Num.cut(all.lineSequence().filter { pid(it) in pids }.toList().takeLast(160).joinToString("\n"), 14000)
    }

    // ------------------------------------------------------------------------------------------ G: voice and sound
    private fun version(pm: PackageManager, pkg: String): Any = try { pm.getPackageInfo(pkg, 0).versionName ?: "?" } catch (_: Throwable) { JSONObject.NULL }

    fun voice(c: Context): JSONObject {
        val o = JSONObject()
        val pm = c.packageManager
        fun names(action: String) = JSONArray().apply {
            for (r in pm.queryIntentServices(Intent(action), 0)) put(r.serviceInfo.packageName + "/" + r.serviceInfo.name.substringAfterLast('.'))
        }
        o.put("ttsEngines", names(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE))
        o.put("recognisers", names(RecognitionService.SERVICE_INTERFACE))
        o.put("googleTts", version(pm, VoiceSetup.GOOGLE_TTS))
        o.put("googleApp", version(pm, VoiceSetup.GOOGLE_APP))
        o.put("defaultTts", Settings.Secure.getString(c.contentResolver, "tts_default_synth") ?: JSONObject.NULL)
        o.put("defaultRecogniser", Settings.Secure.getString(c.contentResolver, "voice_recognition_service") ?: JSONObject.NULL)
        // the assistant's own: what its pages no longer say. The microphone it holds to, by id, among the inputs there are (id:type)
        o.put("amriEnrolled", VoiceSetup.enrolled(c)).put("amriLearned", com.abdllh.aura.voice.Amri.learnedSounds(c))
        o.put("amriTaught", com.abdllh.aura.voice.OfflineCommands.items.count { com.abdllh.aura.voice.OfflineCommands.taught(c, it.id) })
        o.put("amriMic", com.abdllh.aura.voice.Amri.micId)
        o.put("inputs", JSONArray(try {
            (c.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager).getDevices(android.media.AudioManager.GET_DEVICES_INPUTS).map { "${it.id}:${it.type}" }
        } catch (_: Throwable) { emptyList<String>() }))
        // whether a voice can really speak Arabic here (the engine asked, nothing is said)
        val ready = CountDownLatch(1)
        var status = -99
        val t0 = SystemClock.elapsedRealtime()
        var tts: TextToSpeech? = null
        try {
            tts = TextToSpeech(c.applicationContext) { s -> status = s; ready.countDown() }
            val came = ready.await(5, TimeUnit.SECONDS)
            o.put("ttsInit", if (!came) "no answer in 5 s" else if (status == TextToSpeech.SUCCESS) "ok" else "failed")
            o.put("ttsInitMs", SystemClock.elapsedRealtime() - t0)
            if (came && status == TextToSpeech.SUCCESS) {
                o.put("ttsEngineUsed", tts.defaultEngine ?: "")
                o.put("ttsArabic", tts.isLanguageAvailable(Locale("ar")))
                o.put("ttsArabicSaudi", tts.isLanguageAvailable(Locale("ar", "SA")))
                o.put("ttsEnglish", tts.isLanguageAvailable(Locale.ENGLISH))
            }
        } catch (t: Throwable) {
            o.put("ttsInit", t.toString())
        } finally {
            try { tts?.shutdown() } catch (_: Throwable) { }
        }
        return o
    }

    // ------------------------------------------------------------------------------------------ K: the settings
    /** The switches and choices on this unit, by name: an allow list, so nothing new ever goes out unasked. */
    private val SETTINGS = listOf(
        "theme", "accent", "clock24", "dockLabels", "lang", "updateAuto", "updateBeta", "updateRepo", "hideStatusBar", "screenAnimations", "pressTick", "saherTone", "mapView",
        "volumeHud", "musicKeysAura", "btCoverOnline", "lyricsWords", "replaceStockMusic", "btDelayMs", "lyricsListen", "callCard", "auraRing",
        "hideAndroidNotices", "showPhoneMessages", "showMessageText", "builtInMaps", "navPackage", "offlineMapState", "offlineMapUpdated",
        "mapExtrasFetched", "markCameraButton", "wifiKeeper", "wifiOnStart", "zlinkGuard", "fullOffOnAccOff", "amriOn", "amriSens", "amriSpeak",
        "amriMic", "navVoice", "musicSource", "musicSourceOwn", "musicLyrics", "musicLyricsFull", "keyRouting", "tripRecOn", "handbrakeSeen", "handbrakeFake",
        "crashes", "crashDisabled", "stockMusicApplied", "volumeHudStockOff", "callWindowStockOff", "lastUpdateCheck", "availableTag")

    fun settings(c: Context): JSONObject {
        val o = JSONObject()
        val all = Prefs.raw.all
        val set = JSONObject()
        for (k in SETTINGS) all[k]?.let { set.put(k, it) } // (not in the list: still at its default)
        o.put("set", set)
        o.put("systemLanguage", android.content.res.Resources.getSystem().configuration.locales[0].toLanguageTag())
        // that they are there, and no more
        o.put("homeSet", all.containsKey("placeHome")).put("workSet", all.containsKey("placeWork"))
        o.put("lyricsServerSet", LyricsServer.base() != null).put("lyricsKeySet", LyricsServer.key.isNotBlank())
        o.put("dockPins", (all["dockPins"] as? String)?.lineSequence()?.count { it.isNotBlank() } ?: 0)
        val g = JSONObject()
        for (k in listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale", "heads_up_notifications_enabled", "policy_control")) {
            g.put(k, Settings.Global.getString(c.contentResolver, k) ?: JSONObject.NULL)
        }
        for (k in listOf("screen_brightness", "screen_brightness_mode", "font_scale")) {
            g.put(k, Settings.System.getString(c.contentResolver, k) ?: JSONObject.NULL)
        }
        o.put("system", g)
        o.put("notes", notes(c))
        val w = com.abdllh.aura.update.Rollback.info(c)
        o.put("wayback", JSONObject().put("version", w.payload?.version ?: JSONObject.NULL).put("usable", com.abdllh.aura.update.Rollback.usable(w.payload))
            .put("why", w.why).put("detail", w.detail).put("lastExit", w.lastExit).put("lastFrom", w.lastFrom).put("lastTo", w.lastTo)
            .put("lastDataStayed", w.lastDataStayed).put("lastPm", w.lastPm))
        o.put("guardLog", JSONArray(com.abdllh.aura.guard.GuardLog.tail(c, 40)))
        return o
    }

    /**
     * What the unit noted by itself, at any time: what Settings' workshop showed up to 2.0.0-beta.2 (docs/DEV.md, "Far fewer settings"). The notes that
     * hold a song's or a place's name give only what came of it: nothing of that goes out.
     */
    private fun notes(c: Context): JSONObject {
        val cover = com.abdllh.aura.music.CoverSearch.note
        val extras = com.abdllh.aura.nav.MapExtras
        val heard = com.abdllh.aura.music.LyricsSync.lastHeard()
        return JSONObject()
            .put("homeGuard", com.abdllh.aura.system.HomeGuard.note)
            .put("musicKey", com.abdllh.aura.system.MusicKeys.note)
            .put("homeStart", com.abdllh.aura.home.Screens.note)
            .put("panelOpen", com.abdllh.aura.home.PanelStack.lastOpen)
            .put("panelFailed", com.abdllh.aura.home.PanelStack.failedNote(c))
            .put("windowLost", com.abdllh.aura.home.DrawWatch.note(c))
            .put("cover", if (cover.isEmpty()) "" else cover.take(11) + cover.substringAfter("→", ""))
            .put("youtube", com.abdllh.aura.music.YtMusic.note)
            .put("place", com.abdllh.aura.link.PlaceInbox.lastOutcome)
            .put("phoneServer", if (com.abdllh.aura.link.PhoneServer.enabled) "${com.abdllh.aura.link.PhoneServer.status} ${com.abdllh.aura.link.PhoneServer.failure} ${com.abdllh.aura.link.PhoneServer.lastRequest}".trim() else "")
            .put("nav", com.abdllh.aura.nav.NavNote.text.replace(Regex("trip to .*? (resumed|not resumed)"), "trip $1"))
            .put("mapLib", com.abdllh.aura.nav.MapLib.note)
            .put("mapState", com.abdllh.aura.nav.MapController.note)
            .put("idleGc", com.abdllh.aura.system.IdleGc.note)
            .put("compile", com.abdllh.aura.system.DexCompile.note)
            .put("packLast", com.abdllh.aura.nav.pack.PackManager.lastInstall().orEmpty())
            .put("mapExtras", "cameras ${extras.count(com.abdllh.aura.nav.MapExtras.Kind.CAMERA)}, bumps ${extras.count(com.abdllh.aura.nav.MapExtras.Kind.BUMP)}, " +
                "signals ${extras.count(com.abdllh.aura.nav.MapExtras.Kind.SIGNAL)}, limits ${extras.speeds.size}, marked ${extras.mine.size}, published ${extras.updatedAt}")
            .put("lyricsHeard", if (heard == null) "" else "${heard.db} dB, ${heard.words} words")
    }

    // ------------------------------------------------------------------------------------------ H: network and phone
    private fun ip(i: Int) = "${i and 0xFF}.${i shr 8 and 0xFF}.${i shr 16 and 0xFF}.${i ushr 24 and 0xFF}"

    private val probes = Executors.newCachedThreadPool { r -> Thread(r, "aura-diag-net").apply { isDaemon = true } }

    /** Whether [url] answers, and how long each part took: the name, the connection, the whole request. Sends no key. */
    private fun reach(url: String): JSONObject {
        val o = JSONObject()
        val t0 = SystemClock.elapsedRealtime()
        try {
            val u = URL(url)
            val addr = InetAddress.getByName(u.host)
            val t1 = SystemClock.elapsedRealtime()
            o.put("dnsMs", t1 - t0)
            Socket().use { it.connect(InetSocketAddress(addr, if (u.port > 0) u.port else 443), 3500) }
            val t2 = SystemClock.elapsedRealtime()
            o.put("connectMs", t2 - t1)
            val h = u.openConnection() as HttpURLConnection
            try {
                h.connectTimeout = 4000
                h.readTimeout = 4000
                h.requestMethod = "HEAD"
                h.instanceFollowRedirects = false
                h.setRequestProperty("User-Agent", Places.USER_AGENT)
                h.setRequestProperty("Connection", "close")
                o.put("http", h.responseCode)
                o.put("totalMs", SystemClock.elapsedRealtime() - t0)
            } finally {
                h.disconnect()
            }
            o.put("reached", true)
        } catch (t: Throwable) {
            o.put("reached", false).put("error", t.javaClass.simpleName).put("afterMs", SystemClock.elapsedRealtime() - t0)
        }
        return o
    }

    @SuppressLint("MissingPermission")
    fun network(c: Context): JSONObject {
        val o = JSONObject()
        val wifi = c.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        o.put("wifiOn", wifi?.isWifiEnabled ?: false)
        o.put("airplane", Settings.Global.getInt(c.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1)
        @Suppress("DEPRECATION") val info = try { wifi?.connectionInfo } catch (_: Throwable) { null }
        if (info != null) o.put("rssi", info.rssi).put("linkMbps", info.linkSpeed).put("frequency", info.frequency)
        @Suppress("DEPRECATION") val dhcp = try { wifi?.dhcpInfo } catch (_: Throwable) { null }
        val gw = dhcp?.gateway ?: 0
        if (dhcp != null) {
            o.put("ip", ip(dhcp.ipAddress)).put("gateway", ip(gw)).put("dns", ip(dhcp.dns1))
        }
        (c.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.let { cm ->
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            o.put("network", when {
                caps == null -> "none"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                else -> "other"
            })
            o.put("validated", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ?: false)
        }
        val tasks = LinkedHashMap<String, java.util.concurrent.Future<JSONObject>>()
        for (h in listOf("https://lyric.abod50i.online/health", "https://tiles.openfreemap.org/", "https://router.project-osrm.org/", "https://brouter.de/")) {
            tasks[URL(h).host] = probes.submit(Callable { reach(h) })
        }
        if (gw != 0) {
            val gateway = ip(gw)
            o.put("gatewayPing", ping(gateway))
            // A connection to Naghm's port is not made while the link is up: it might take the place of the link's own.
            if (PhoneLink.connected) o.put("port47100", "open (Naghm is linked now)")
            else o.put("port47100", try {
                Socket().use { it.connect(InetSocketAddress(gateway, PhoneLink.PORT), 1500) }
                "open"
            } catch (t: java.net.SocketTimeoutException) {
                "no answer"
            } catch (t: Throwable) {
                "closed"
            })
        }
        o.put("naghmLinked", PhoneLink.connected).put("naghmNews", PhoneLink.note)
        // the keeper: what it finds now, and its last events with their causes (a network's name stays out)
        val keeper = com.abdllh.aura.system.WifiKeeper
        o.put("keeper", JSONObject().put("state", keeper.state.name).put("cause", keeper.cause?.name ?: JSONObject.NULL).put("rssi", keeper.rssi))
        val at = java.text.SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT)
        o.put("keeperLog", JSONArray(keeper.log().takeLast(30).map { e ->
            "${at.format(java.util.Date(e.at))} ${e.type} ${if (e.type == "join" || e.type == "online") "" else e.a} ${e.b}".replace("  ", " ").trim()
        }))
        val hosts = JSONObject()
        for ((h, f) in tasks) {
            hosts.put(h, try { f.get(9, TimeUnit.SECONDS) } catch (t: Throwable) { f.cancel(true); JSONObject().put("reached", false).put("error", "no answer in 9 s") })
        }
        o.put("hosts", hosts)
        return o
    }

    private fun ping(to: String): JSONObject {
        val o = JSONObject()
        try {
            val p = ProcessBuilder("/system/bin/ping", "-c", "4", "-i", "0.25", "-w", "3", to).redirectErrorStream(true).start()
            val finished = p.waitFor(4, TimeUnit.SECONDS)
            if (!finished) p.destroy()
            val t = p.inputStream.bufferedReader().use { it.readText() }
            first("(\\d+) received", t)?.let { o.put("received", it.toInt()) }
            o.put("sent", 4)
            Regex("= ([\\d.]+)/([\\d.]+)/([\\d.]+)").find(t)?.let { m ->
                o.put("minMs", m.groupValues[1].toDouble()).put("avgMs", m.groupValues[2].toDouble()).put("maxMs", m.groupValues[3].toDouble())
            }
        } catch (t: Throwable) {
            o.put("error", t.toString())
        }
        return o
    }

    // ------------------------------------------------------------------------------------------ I: GPS, 10 s
    /** Counts what comes while it runs (fixes arrive on the main thread). Never a position: rate, age, accuracy, speed. */
    class GpsWatch(private val c: Context) {
        private val main = Handler(Looper.getMainLooper())
        private val t0 = SystemClock.elapsedRealtime()
        private var fixes = 0
        private var lastAge = -1L
        private var lastSpeed = Double.NaN
        private var withSpeed = 0
        private var withBearing = 0
        private val accuracy = ArrayList<Double>()
        private var provider = ""
        private var seen = 0
        private var used = 0
        private val priorAgeMs: Long = CarLocation.last?.let { (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000L } ?: -1L

        private val onFix: (Location) -> Unit = { l ->
            fixes++
            lastAge = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000L
            provider = l.provider.orEmpty()
            if (l.hasAccuracy()) accuracy.add(l.accuracy.toDouble())
            if (l.hasSpeed()) { withSpeed++; lastSpeed = l.speed * 3.6 }
            if (l.hasBearing()) withBearing++
        }
        private val gnss = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(s: GnssStatus) {
                seen = maxOf(seen, s.satelliteCount)
                var u = 0
                for (i in 0 until s.satelliteCount) if (s.usedInFix(i)) u++
                used = maxOf(used, u)
            }
        }
        private val lm = c.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        private val allowed = CarLocation.hasPermission(c)

        @SuppressLint("MissingPermission")
        fun start() {
            CarLocation.addListener(onFix)
            if (allowed) try { lm?.registerGnssStatusCallback(gnss, main) } catch (_: Throwable) { }
        }

        fun stop(): JSONObject {
            CarLocation.removeListener(onFix)
            try { lm?.unregisterGnssStatusCallback(gnss) } catch (_: Throwable) { }
            val seconds = (SystemClock.elapsedRealtime() - t0) / 1000.0
            val o = JSONObject().put("seconds", Num.of(seconds))
            o.put("permission", allowed)
            o.put("providers", JSONArray().apply { try { lm?.allProviders?.forEach { put(it) } } catch (_: Throwable) { } })
            o.put("gpsOn", try { lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) ?: false } catch (_: Throwable) { false })
            o.put("fixes", fixes).put("rateHz", Num.of(fixes / seconds.coerceAtLeast(0.1), 2))
            o.put("noFix", fixes == 0)
            o.put("satellitesSeen", seen).put("satellitesUsed", used)
            if (priorAgeMs >= 0) o.put("lastFixBeforeMs", priorAgeMs)
            if (fixes > 0) {
                o.put("lastAgeMs", lastAge).put("provider", provider)
                accuracy.sort()
                if (accuracy.isNotEmpty()) o.put("accuracyM", Num.of(accuracy[accuracy.size / 2]))
                o.put("speedKmh", Num.of(lastSpeed))
                o.put("withSpeed", withSpeed).put("withBearing", withBearing)
            }
            return o
        }
    }

    // ------------------------------------------------------------------------------------------ J: the car, the wheel
    /**
     * The firmware's own names for the MCU's keys (KernelConstant$McuKeyValue in KernelService), those a wheel or a panel
     * may have; -108 and -107 are not in that class: the names are what Player.kt does with them.
     */
    private val KEY_NAMES: Map<Int, String> = mapOf(
        -108 to "PLAY", -107 to "PAUSE", -105 to "START_SIRI", -15 to "SWITCH_ARM", -14 to "SWITCH_CAN", 0 to "POWER", 1 to "TONE", 2 to "MUTE", 3 to "DVD",
        4 to "RADIO", 5 to "NEXT", 6 to "PREVIOUS", 7 to "PLAY", 8 to "SETUP", 9 to "MENU", 10 to "PHONE", 11 to "NAVI", 12 to "EJECT", 13 to "LOUD",
        14 to "VOL_UP", 15 to "VOL_DOWN", 16 to "SEEK_INCREASE", 17 to "SEEK_DECREASE", 18 to "ANDROID_BACK", 19 to "ANDROID_MENU", 20 to "ANDROID_HOME",
        21 to "ACCEPT_CALL", 22 to "CANCEL_CALL", 23 to "MODE", 24 to "ANDROID_UP", 25 to "ANDROID_DOWN", 26 to "ANDROID_LEFT", 27 to "ANDROID_RIGHT",
        28 to "ANDROID_OK", 29 to "ZOOM", 30 to "EQ", 31 to "STOP", 32 to "RANDOM", 33 to "REPEAT", 44 to "ST", 45 to "LOC", 46 to "AMS", 58 to "DISPLAY",
        59 to "SEARCH_DECREASE", 60 to "SEARCH_INCREASE", 61 to "INTRO", 62 to "PREFEB_INCREASE", 63 to "PREFEB_DECREASE", 65 to "CLOUD_NAVI", 66 to "LIGHT",
        69 to "TV", 70 to "AUX", 71 to "CAN_SETTING", 72 to "FM", 73 to "AM", 74 to "TIME", 81 to "BACK_LIGHT_ADJUST", 82 to "SCREEN_PROTECT", 83 to "MUSIC",
        84 to "VIDEO", 85 to "TPMS", 86 to "VIDEO_SURVEILLANCE", 88 to "MESSAGE", 89 to "SPEECH", 90 to "TA", 91 to "AF", 92 to "TURN_LEFT", 93 to "TURN_RIGHT",
        94 to "AUX2", 96 to "ANDROID_RECENT", 97 to "MEDIA", 98 to "IPOD", 99 to "BT_MUSIC", 102 to "SYNC", 108 to "REC", 110 to "ALL_APP", 113 to "CAN_AC",
        114 to "CAN_WARM_SEAT", 115 to "CAN_FAN_INCREASE", 116 to "CAN_FAN_DECREASE", 122 to "CAR_360")

    /** The step "press your wheel buttons now": every key or MCU code that comes while it runs, by every way there is. */
    class KeyWatch(private val c: Context, private val changed: () -> Unit) {
        private class Seen(val code: Int, val via: String) { var count = 0 }

        private val seen = LinkedHashMap<String, Seen>()
        private val since = java.text.SimpleDateFormat("MM-dd HH:mm:ss.000", Locale.ROOT).format(java.util.Date())

        private fun note(via: String, code: Int) {
            synchronized(seen) { seen.getOrPut("$via:$code") { Seen(code, via) }.count++ }
            changed()
        }

        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, i: Intent) {
                try {
                    val k = (i.extras?.get("extra_key_value") as? Number)?.toInt() ?: return
                    note("mcu", k)
                } catch (_: Throwable) { // (anything can send these)
                }
            }
        }

        fun start() {
            c.registerReceiver(receiver, IntentFilter("com.nwd.action.ACTION_KEY_VALUE"))
        }

        /** A key that reached the screen as an Android key. */
        fun keyEvent(code: Int) = note("android", code)

        /** "83 MUSIC · 14 VOL_UP" so far. */
        fun line(): String = synchronized(seen) {
            seen.values.joinToString(" · ") { s -> if (s.via == "mcu") "${s.code} ${KEY_NAMES[s.code].orEmpty()}".trim() else android.view.KeyEvent.keyCodeToString(s.code) }
        }

        /** Off the main thread: it asks the root shell for what the firmware logged. */
        fun stop(rawInput: String): JSONObject {
            try { c.unregisterReceiver(receiver) } catch (_: Throwable) { }
            val o = JSONObject()
            val list = JSONArray()
            synchronized(seen) {
                for (s in seen.values) {
                    list.put(JSONObject().put("code", s.code).put("via", s.via).put("times", s.count)
                        .put("name", if (s.via == "mcu") KEY_NAMES[s.code].orEmpty() else android.view.KeyEvent.keyCodeToString(s.code)))
                }
            }
            o.put("keys", list)
            o.put("rawInput", Num.lines(rawInput))
            // what the firmware itself logged about keys meanwhile (a key with a rule of its own is never announced)
            o.put("firmwareLog", Num.lines(Sh.text("logcat -d -t '$since' | grep -iE 'keyvalue|key_value|keycode|mcukey|sendkey|KeyRule' | tail -n 50 | cut -c1-170", 6000)))
            return o
        }
    }

    /** The raw input devices for [seconds] by root, only keys (a touch of the screen is not one). Blocks that long. */
    fun rawKeys(seconds: Int): String = Sh.text("timeout $seconds getevent -lq 2>/dev/null | grep EV_KEY | grep -v BTN_ | head -n 60 | cut -c1-100", (seconds + 4) * 1000)

    fun car(c: Context): JSONObject {
        val o = JSONObject()
        o.put("gear", Gear.current?.name ?: JSONObject.NULL).put("gearSource", Gear.source.name).put("gearSure", Gear.sure).put("canReportsGear", Gear.canReports)
        o.put("handbrake", when (Gear.handbrakeState) { null -> "never reported"; true -> "pulled"; else -> "released" })
        o.put("handbrakeTrusted", Gear.handbrakeTrusted).put("handbrakeFake", Gear.handbrakeFake)
        val sys = JSONObject()
        for (k in listOf("mcu_backcar_state", "mcu_hand_break", "mcu_system_volume", "mcu_max_volume", "mcu_mute_state", "mcu_current_source",
            "nwd_arm_volume_type", "key_bt_state", "key_bt_a2dp_state", "phone_connect_style", "mcu_light_state", "activity_starting")) {
            sys.put(k, Settings.System.getString(c.contentResolver, k) ?: JSONObject.NULL)
        }
        o.put("firmwareSettings", sys)
        o.put("bluetoothOn", Settings.Global.getString(c.contentResolver, "bluetooth_on") ?: JSONObject.NULL)
        o.put("btPhoneNamed", !Settings.System.getString(c.contentResolver, "bt_phone_name").isNullOrBlank())
        val manager = CanGear.manager(c)
        o.put("canManager", manager ?: JSONObject.NULL)
        o.put("canBoxVersion", CanGear.boxVersion(c) ?: JSONObject.NULL)
        o.put("canBox", CanGear.boxInfo(c) ?: JSONObject.NULL).put("canAppKnowsGear", CanGear.supported(c)).put("canGearPossible", CanGear.gearPossible(manager) ?: JSONObject.NULL)
        o.put("canMessages", CanGear.messages).put("canGearRaw", CanGear.raw)
        o.put("volume", try { volume(c) } catch (t: Throwable) { JSONObject().put("error", t.toString()) })
        return o
    }

    /**
     * The volume (2.0 beta 4): the level, the boost's effect as it reads back, the last 200 events ([com.abdllh.aura.system.VolumeLog]),
     * the firmware's and the CAN box's volume keys, every Settings.System and Settings.Global key with "vol" or "amp" in its name,
     * and where audioflinger has the boost (by root).
     */
    fun volume(c: Context): JSONObject {
        val o = com.abdllh.aura.system.CarAudio.report()
        val named = JSONObject()
        for (k in listOf("can_amp_max_volume_value", "can_use_amp_volume_seekbar_enable_key", "can_use_amp_volume_key", "mcu_max_volume",
            "mcu_system_volume", "mcu_mute_state", "mcu_bt_volume", "mcu_other_setting_default_volume", "mcu_other_setting_volume_mix",
            "mcu_max_gain_volume", "nwd_media_mix_navi_vol", "nwd_navi_vol", "nwd_arm_volume_type", "mcu_amplifier_switch", "car_amp_state",
            "key_can_audio_channel", "mcu_current_source", "mcu_backcar_state")) {
            named.put(k, Settings.System.getString(c.contentResolver, k) ?: JSONObject.NULL)
        }
        o.put("namedKeys", named)
        o.put("systemKeys", settingsWith(c, Settings.System.CONTENT_URI, "system"))
        o.put("globalKeys", settingsWith(c, Settings.Global.CONTENT_URI, "global"))
        val af = Sh.text("dumpsys media.audio_flinger", 8000)
        if (af.isNotEmpty()) {
            val keep = JSONArray()
            for (l in af.lineSequence()) {
                val s = l.trim()
                if (s.startsWith("Output thread") || s.contains("Effect Chain") || s.startsWith("Effect ID") || s.startsWith("- name:") ||
                    s.startsWith("- TYPE:") || s.contains("Session State") || Regex("^\\d{5}\\s+0x").containsMatchIn(s)) keep.put(Num.cut(s, 160))
                if (keep.length() >= 120) break
            }
            o.put("audioFlingerEffects", keep)
        } else o.put("audioFlingerEffects", "no root shell")
        return o
    }

    /** Every key of one settings table whose name has "vol" or "amp" in it, with its value: the provider first, the root shell if it says nothing. */
    private fun settingsWith(c: Context, uri: android.net.Uri, table: String): JSONObject {
        val out = JSONObject()
        fun want(name: String) = name.contains("vol", true) || name.contains("amp", true)
        try {
            c.contentResolver.query(uri, arrayOf("name", "value"), null, null, null)?.use { cur ->
                while (cur.moveToNext()) {
                    val name = cur.getString(0) ?: continue
                    if (want(name)) out.put(name, cur.getString(1) ?: JSONObject.NULL)
                }
            }
        } catch (t: Throwable) {
            out.put("_queryError", t.toString())
        }
        if (out.length() == 0 || out.has("_queryError")) {
            val text = Sh.text("settings list $table", 6000)
            for (l in text.lineSequence()) {
                val i = l.indexOf('=')
                if (i <= 0) continue
                val name = l.substring(0, i)
                if (want(name)) out.put(name, l.substring(i + 1))
            }
            if (text.isNotEmpty()) out.put("_via", "root shell")
        }
        return out
    }
}

/**
 * What the compositor does with the layers while the map runs: which it gives to the hardware (HWC, a plane of its own)
 * and which to the GPU ("CLIENT"), by root. The whole dump is long: only this and what it says of itself is kept.
 */
internal fun surfaceFlinger(): JSONObject {
    val o = JSONObject()
    val dump = Sh.run("dumpsys SurfaceFlinger | head -c 600000", 10_000)
    if (!dump.ok || dump.text.isEmpty()) return o.put("available", false)
    o.put("available", true)
    val lines = dump.text.lines()
    val layers = JSONArray()
    var client = 0
    var hwc = 0
    var name = ""
    var ourLayer: String? = null
    val start = lines.indexOfFirst { it.contains("HWC layers:") }
    if (start >= 0) {
        for (l in lines.subList(start + 1, lines.size)) {
            if (l.contains("h/w composer") || l.contains("Display ") && l.contains("HWC layers")) break
            if (l.startsWith("---") || l.contains("Comp Type") || l.trim() == "Layer name") continue
            if (!l.contains('|')) { if (l.isNotBlank()) name = l.trim(); continue }
            val type = l.split('|').getOrNull(2)?.trim().orEmpty()
            if (type == "CLIENT") client++ else hwc++
            layers.put(JSONObject().put("name", name.take(90)).put("type", type))
            if (name.contains("SurfaceView") && ourLayer == null) ourLayer = name
        }
        // (as the compositor wrote it: this parsing has only met the emulator's)
        o.put("raw", Num.cut(lines.subList(start, minOf(start + 40, lines.size)).joinToString("\n"), 3500))
    }
    o.put("layers", layers).put("client", client).put("hwc", hwc)
    lines.firstOrNull { it.startsWith("GLES:") }?.let { o.put("gles", it.removePrefix("GLES:").trim().take(200)) }
    lines.firstOrNull { it.contains("Total allocated") }?.let { o.put("buffers", it.trim()) }
    lines.firstOrNull { it.contains("refresh-rate") }?.let { o.put("refresh", it.trim()) }
    // the map's own layer as the display showed it: when each of its last frames really came on the screen
    ourLayer?.let { o.put("latency", layerLatency(it)) }
    return o
}

private fun layerLatency(layer: String): JSONObject {
    val o = JSONObject().put("layer", layer.take(90))
    val t = Sh.text("dumpsys SurfaceFlinger --latency '${layer.replace("'", "")}'", 4000)
    val rows = t.lineSequence().drop(1).map { it.trim().split(Regex("\\s+")) }.filter { it.size == 3 }
        .mapNotNull { it[1].toLongOrNull() }.filter { it in 1 until Long.MAX_VALUE }.toList()
    if (rows.size < 3) return o.put("frames", rows.size)
    val gaps = DoubleArray(rows.size - 1) { (rows[it + 1] - rows[it]) / 1e6 }
    o.put("frames", rows.size).put("fps", Num.of(1000.0 / gaps.average()))
    Num.spread(o, gaps)
    return o
}
