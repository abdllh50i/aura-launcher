package com.abdllh.aura.update

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.R
import com.abdllh.aura.util.Prefs
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** Update state machine: check GitHub → download → verify → install. All state changes are posted to the main thread. */
object UpdateManager {

    sealed class State {
        object Idle : State()
        object Checking : State()
        /** [noReleases] = the update source exists but has no published release yet. */
        data class UpToDate(val checkedAt: Long, val noReleases: Boolean = false) : State()
        data class Available(val release: Release) : State()
        data class Downloading(val release: Release, val done: Long, val total: Long, val bytesPerSec: Long) : State()
        data class Verifying(val release: Release) : State()
        data class Installing(val release: Release) : State()
        data class Failed(val message: String, val release: Release?) : State()
    }

    @Volatile
    var state: State = State.Idle
        private set

    private lateinit var app: Context
    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var cancelled = false
    private const val AUTO_INTERVAL_MS = 6 * 60 * 60 * 1000L

    fun init(ctx: Context) {
        app = ctx.applicationContext
        // Restore "an update is known" so the badge and the update page survive restarts.
        val cached = Release.fromJson(Prefs.releaseCache)
        val v = cached?.version
        if (cached != null && v != null && v > currentVersion) {
            state = State.Available(cached)
        } else if (Prefs.availableTag.isNotEmpty()) {
            Prefs.availableTag = ""
            Prefs.releaseCache = ""
        }
    }

    /** Registers a listener and immediately delivers the current state (must be called on the main thread). */
    fun observe(l: (State) -> Unit) {
        listeners.add(l)
        l(state)
    }

    fun unobserve(l: (State) -> Unit) {
        listeners.remove(l)
    }

    private fun post(s: State) {
        state = s
        main.post { listeners.forEach { it(s) } }
    }

    val busy: Boolean
        get() = when (state) {
            is State.Checking, is State.Downloading, is State.Verifying, is State.Installing -> true
            else -> false
        }

    val currentVersion: Version get() = Version.parse(BuildConfig.VERSION_NAME) ?: Version.parse("0.0.0")!!

    fun hasUpdateBadge(): Boolean {
        val tag = Prefs.availableTag
        val v = Version.parse(tag) ?: return false
        return v > currentVersion
    }

    fun isOnline(): Boolean = try {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.activeNetworkInfo?.isConnected == true
    } catch (_: Throwable) {
        true
    }

    /** Called when the launcher comes to the foreground: checks at most every few hours. */
    fun autoCheckIfDue() {
        if (!Prefs.updateAuto || busy) return
        if (System.currentTimeMillis() - Prefs.lastUpdateCheck < AUTO_INTERVAL_MS) return
        if (!isOnline()) return
        check()
    }

    fun check() {
        if (busy) return
        cancelled = false
        post(State.Checking)
        io.execute {
            try {
                val rel = GitHub.latest(Prefs.updateRepo, Prefs.updateBeta)
                val now = System.currentTimeMillis()
                Prefs.lastUpdateCheck = now
                val remote = rel?.version
                if (rel != null && remote != null && remote > currentVersion) {
                    Prefs.availableTag = rel.tag
                    Prefs.releaseCache = rel.toJson()
                    post(State.Available(rel))
                } else {
                    Prefs.availableTag = ""
                    Prefs.releaseCache = ""
                    post(State.UpToDate(now, rel == null))
                }
            } catch (e: UpdateException) {
                post(State.Failed(messageFor(e), null))
            } catch (t: Throwable) {
                post(State.Failed(app.getString(R.string.upd_err_generic, t.message ?: ""), null))
            }
        }
    }

    fun cancel() {
        cancelled = true
    }

    /** Download the available release, verify it, and hand it to the installer. */
    fun downloadAndInstall() {
        val seen = when (val s = state) {
            is State.Available -> s.release
            is State.Failed -> s.release
            else -> null
        } ?: return
        if (busy) return
        cancelled = false
        io.execute {
            var rel = seen
            val dir = File(app.cacheDir, "updates")
            try {
                post(State.Checking)
                // The asset URL, size or digest may have changed since the last check: always start from fresh metadata.
                try {
                    val fresh = GitHub.latest(Prefs.updateRepo, Prefs.updateBeta)
                    val fv = fresh?.version
                    if (fresh != null && fv != null && fv > currentVersion) {
                        rel = fresh
                        Prefs.availableTag = fresh.tag
                        Prefs.releaseCache = fresh.toJson()
                    }
                } catch (e: UpdateException) {
                    android.util.Log.w("AuraUpdate", "refresh before download failed (${e.kind}), using cached release")
                }
                val file = File(dir, "${rel.tag}-${rel.apkSize}-${rel.apkName}.part")
                dir.listFiles()?.filter { it.name.endsWith(".part") && it != file }?.forEach { it.delete() }
                post(State.Downloading(rel, 0, rel.apkSize, 0))
                var lastTs = System.currentTimeMillis()
                var lastBytes = 0L
                var speed = 0L
                GitHub.download(rel.apkUrl, file, rel.apkSize, { cancelled }) { done, total ->
                    val now = System.currentTimeMillis()
                    if (now - lastTs >= 400) {
                        speed = ((done - lastBytes) * 1000L / (now - lastTs)).coerceAtLeast(0)
                        lastTs = now
                        lastBytes = done
                        post(State.Downloading(rel, done, total, speed))
                    }
                }
                post(State.Verifying(rel))
                verify(rel, file)
                post(State.Installing(rel))
                ApkInstaller.install(app, file)
                // Result arrives via InstallResultReceiver (the process is usually replaced on success).
            } catch (e: UpdateException) {
                android.util.Log.w("AuraUpdate", "install flow failed: ${e.kind} ${e.message}", e)
                if (e.kind == UpdateException.Kind.CANCELLED) {
                    post(State.Available(rel))
                } else {
                    if (e.kind in listOf(UpdateException.Kind.HASH, UpdateException.Kind.SIGNATURE, UpdateException.Kind.INCOMPLETE, UpdateException.Kind.VERSION, UpdateException.Kind.PARSE)) {
                        dir.listFiles()?.forEach { if (it.name.endsWith(".part")) it.delete() }
                    }
                    post(State.Failed(messageFor(e), rel))
                }
            } catch (t: Throwable) {
                post(State.Failed(app.getString(R.string.upd_err_install, t.message ?: ""), rel))
            }
        }
    }

    private fun verify(rel: Release, file: File) {
        // 1) integrity
        var expected = rel.sha256
        if (expected == null && rel.sha256Url != null) expected = GitHub.fetchDigest(rel.sha256Url)
        if (expected != null) {
            val actual = GitHub.sha256(file)
            if (!actual.equals(expected, true)) throw UpdateException(UpdateException.Kind.HASH, "SHA-256 mismatch")
        }
        // 2) authenticity: same package, same signing certificate, newer version
        val pm = app.packageManager
        // Android 10 only reads an archive's certificates when GET_SIGNATURES is requested as well.
        @Suppress("DEPRECATION")
        val flags = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw UpdateException(UpdateException.Kind.PARSE, "Not an APK")
        if (archive.packageName != app.packageName) throw UpdateException(UpdateException.Kind.SIGNATURE, "Package mismatch: ${archive.packageName}")
        val mine = pm.getPackageInfo(app.packageName, flags)
        @Suppress("DEPRECATION")
        val a = signerDigests(archive.signingInfo?.apkContentsSigners ?: archive.signatures)
        @Suppress("DEPRECATION")
        val b = signerDigests(mine.signingInfo?.apkContentsSigners ?: mine.signatures)
        if (a.isEmpty() || a != b) throw UpdateException(UpdateException.Kind.SIGNATURE, "Signature mismatch")
        @Suppress("DEPRECATION")
        val newCode = if (android.os.Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        @Suppress("DEPRECATION")
        val curCode = if (android.os.Build.VERSION.SDK_INT >= 28) mine.longVersionCode else mine.versionCode.toLong()
        if (newCode <= curCode) throw UpdateException(UpdateException.Kind.VERSION, "Not newer ($newCode <= $curCode)")
    }

    private fun signerDigests(s: Array<android.content.pm.Signature>?): Set<String> {
        if (s == null) return emptySet()
        val md = MessageDigest.getInstance("SHA-256")
        return s.map { sig -> md.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    fun onInstalled() {
        // Usually unreachable: the process is replaced. Keep state sane if it is not.
        Prefs.availableTag = ""
        val now = System.currentTimeMillis()
        post(State.UpToDate(now))
    }

    fun onInstallFailed(msg: String?) {
        val rel = when (val s = state) {
            is State.Installing -> s.release
            is State.Failed -> s.release
            else -> null
        }
        post(State.Failed(if (msg.isNullOrBlank()) app.getString(R.string.upd_err_cancelled) else app.getString(R.string.upd_err_install, msg), rel))
    }

    private fun messageFor(e: UpdateException): String = when (e.kind) {
        UpdateException.Kind.NETWORK -> app.getString(R.string.upd_err_network)
        UpdateException.Kind.TLS -> app.getString(R.string.upd_err_tls)
        UpdateException.Kind.RATE_LIMIT -> app.getString(R.string.upd_err_rate)
        UpdateException.Kind.NOT_FOUND -> app.getString(R.string.upd_err_notfound)
        UpdateException.Kind.SERVER -> app.getString(R.string.upd_err_server, e.message ?: "")
        UpdateException.Kind.PARSE -> app.getString(R.string.upd_err_parse)
        UpdateException.Kind.NO_APK -> app.getString(R.string.upd_err_noapk)
        UpdateException.Kind.HASH -> app.getString(R.string.upd_err_hash)
        UpdateException.Kind.SIGNATURE -> app.getString(R.string.upd_err_signature)
        UpdateException.Kind.VERSION -> app.getString(R.string.upd_err_version)
        UpdateException.Kind.STORAGE -> app.getString(R.string.upd_err_storage)
        UpdateException.Kind.REPO -> app.getString(R.string.upd_err_repo)
        UpdateException.Kind.INCOMPLETE -> app.getString(R.string.upd_err_incomplete)
        UpdateException.Kind.CANCELLED -> app.getString(R.string.upd_err_cancelled)
        UpdateException.Kind.INSTALL -> app.getString(R.string.upd_err_install, e.message ?: "")
    }
}
