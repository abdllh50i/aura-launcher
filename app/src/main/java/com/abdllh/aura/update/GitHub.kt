package com.abdllh.aura.update

import com.abdllh.aura.BuildConfig
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** A published GitHub release that carries an installable APK. */
data class Release(
    val tag: String,
    val name: String,
    val notes: String,
    val prerelease: Boolean,
    val publishedAt: String,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
    val sha256: String?,
    val sha256Url: String?
) {
    val version: Version? get() = Version.parse(tag)

    fun toJson(): String = JSONObject()
        .put("tag", tag).put("name", name).put("notes", notes).put("pre", prerelease).put("at", publishedAt)
        .put("apkName", apkName).put("apkUrl", apkUrl).put("size", apkSize)
        .put("sha", sha256 ?: JSONObject.NULL).put("shaUrl", sha256Url ?: JSONObject.NULL)
        .toString()

    companion object {
        fun fromJson(s: String): Release? = try {
            val o = JSONObject(s)
            Release(
                o.getString("tag"), o.getString("name"), o.optString("notes"), o.optBoolean("pre"), o.optString("at"),
                o.getString("apkName"), o.getString("apkUrl"), o.optLong("size"),
                if (o.isNull("sha")) null else o.getString("sha"), if (o.isNull("shaUrl")) null else o.getString("shaUrl")
            )
        } catch (_: Throwable) {
            null
        }
    }
}

class UpdateException(val kind: Kind, message: String) : Exception(message) {
    enum class Kind { NETWORK, TLS, RATE_LIMIT, NOT_FOUND, SERVER, PARSE, NO_APK, HASH, SIGNATURE, VERSION, STORAGE, INSTALL, CANCELLED, REPO, INCOMPLETE, TIMEOUT }
}

/** Minimal GitHub Releases client (public repositories, unauthenticated). */
object GitHub {
    private const val DEFAULT_API = "https://api.github.com"
    private val repoRegex = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")

    /** API root. Only a debug build may point elsewhere (local mock server); release builds always use GitHub over HTTPS. */
    private val API: String
        get() {
            val custom = com.abdllh.aura.util.Prefs.apiBase
            return if (BuildConfig.DEBUG && custom.startsWith("http")) custom.trimEnd('/') else DEFAULT_API
        }

    fun isValidRepo(repo: String) = repoRegex.matches(repo.trim())

    private fun open(url: String, json: Boolean): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12_000
        c.readTimeout = 25_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Aura-Launcher/${BuildConfig.VERSION_NAME}")
        if (json) {
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        } else {
            c.setRequestProperty("Accept", "application/octet-stream")
        }
        return c
    }

    /**
     * Maps what the platform throws to something the UI can explain. Only certificate problems mean "check the clock";
     * a connection that dies mid-handshake or mid-download is an ordinary network error.
     */
    private fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: UpdateException) {
        throw e
    } catch (e: SSLHandshakeException) {
        throw UpdateException(UpdateException.Kind.TLS, e.message ?: "TLS error")
    } catch (e: SSLPeerUnverifiedException) {
        throw UpdateException(UpdateException.Kind.TLS, e.message ?: "TLS error")
    } catch (e: CertificateException) {
        throw UpdateException(UpdateException.Kind.TLS, e.message ?: "TLS error")
    } catch (e: JSONException) {
        throw UpdateException(UpdateException.Kind.PARSE, e.message ?: "Bad JSON")
    } catch (e: UnknownHostException) {
        throw UpdateException(UpdateException.Kind.NETWORK, e.message ?: "No connection")
    } catch (e: SocketTimeoutException) {
        throw UpdateException(UpdateException.Kind.NETWORK, "Timed out")
    } catch (e: IOException) {
        android.util.Log.w("AuraUpdate", "io error: $e", e)
        val m = e.message ?: ""
        if (m.contains("ENOSPC") || m.contains("No space left", ignoreCase = true)) throw UpdateException(UpdateException.Kind.STORAGE, m)
        throw UpdateException(UpdateException.Kind.NETWORK, m.ifEmpty { "I/O error" })
    }

    private fun readBody(c: HttpURLConnection): String {
        val code = c.responseCode
        if (code == 403 || code == 429) {
            val remaining = c.getHeaderField("X-RateLimit-Remaining")
            if (remaining == "0" || code == 429) throw UpdateException(UpdateException.Kind.RATE_LIMIT, "Rate limited")
            throw UpdateException(UpdateException.Kind.SERVER, "HTTP $code")
        }
        if (code == 404) throw UpdateException(UpdateException.Kind.NOT_FOUND, "Not found")
        if (code !in 200..299) throw UpdateException(UpdateException.Kind.SERVER, "HTTP $code")
        return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /** Release builds only ever download over HTTPS; a debug build may use the local mock server over HTTP. */
    private fun secureUrl(u: String) = u.startsWith("https://") || (BuildConfig.DEBUG && u.startsWith("http://"))

    /** `optString` turns a JSON null into the text "null"; this returns "" instead. */
    private fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key, "")

    /**
     * Newest installable release. Stable channel: no pre-releases (neither flagged on GitHub nor tagged "-beta.N").
     * Releases without an APK (for example one that only carries the ROM pack) are skipped, so they can never block
     * updates. Returns null when no usable release exists.
     */
    fun latest(repo: String, includePre: Boolean): Release? = guard {
        if (!isValidRepo(repo)) throw UpdateException(UpdateException.Kind.REPO, "Bad repository: $repo")
        val c = open("$API/repos/$repo/releases?per_page=30", true)
        try {
            val arr = JSONArray(readBody(c))
            var best: Release? = null
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optBoolean("draft")) continue
                if (!includePre && o.optBoolean("prerelease")) continue
                val r = try { parseRelease(o) } catch (_: UpdateException) { null } ?: continue
                val v = r.version ?: continue
                if (!includePre && v.isPrerelease) continue
                val b = best
                if (b == null || v > b.version!!) best = r
            }
            best
        } catch (e: UpdateException) {
            if (e.kind == UpdateException.Kind.NOT_FOUND) null else throw e
        } finally {
            c.disconnect()
        }
    }

    private fun parseRelease(o: JSONObject): Release {
        val tag = o.str("tag_name")
        if (Version.parse(tag) == null) throw UpdateException(UpdateException.Kind.PARSE, "Bad tag: $tag")
        val assets = o.optJSONArray("assets") ?: JSONArray()
        var apk: JSONObject? = null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val n = a.str("name")
            if (n.endsWith(".apk", true) && !n.contains("debug", true)) {
                if (apk == null || n.startsWith("aura", true)) apk = a
            }
        }
        val chosen = apk ?: throw UpdateException(UpdateException.Kind.NO_APK, "No APK attached to $tag")
        val name = chosen.str("name")
        val url = chosen.str("browser_download_url")
        if (!secureUrl(url)) throw UpdateException(UpdateException.Kind.PARSE, "Bad download URL")

        // Expected SHA-256, strongest source first: the digest GitHub computed for the asset, then the companion
        // "<apk>.sha256" file, and only if neither exists a hash quoted in the release notes.
        var sha: String? = null
        val digest = chosen.str("digest")
        if (digest.startsWith("sha256:")) sha = digest.removePrefix("sha256:").lowercase()
        var shaUrl: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            if (a.str("name").equals("$name.sha256", true)) shaUrl = a.str("browser_download_url").takeIf { secureUrl(it) }
        }
        val notes = o.str("body")
        if (sha == null && shaUrl == null) {
            val m = Regex("(?i)sha-?256[^0-9a-f]{0,6}([0-9a-f]{64})").find(notes)
            if (m != null) sha = m.groupValues[1].lowercase()
        }
        return Release(
            tag = tag,
            name = o.str("name").ifBlank { tag },
            notes = notes,
            prerelease = o.optBoolean("prerelease"),
            publishedAt = o.str("published_at"),
            apkName = name,
            apkUrl = url,
            apkSize = chosen.optLong("size"),
            sha256 = sha,
            sha256Url = shaUrl
        )
    }

    /**
     * Reads the expected digest from a companion "<apk>.sha256" asset. A release that publishes one must be checked
     * against it, so a failure here is an error and never silently skips the check.
     */
    fun fetchDigest(url: String): String = guard {
        val c = open(url, false)
        try {
            val code = c.responseCode
            if (code !in 200..299) throw UpdateException(UpdateException.Kind.SERVER, "HTTP $code")
            val txt = c.inputStream.bufferedReader().use { it.readText() }
            Regex("([0-9a-fA-F]{64})").find(txt)?.groupValues?.get(1)?.lowercase()
                ?: throw UpdateException(UpdateException.Kind.PARSE, "No SHA-256 in $url")
        } finally {
            c.disconnect()
        }
    }

    /**
     * Downloads [url] into [dest], resuming a partial file when the server supports it.
     * [progress] receives (bytesDone, bytesTotal). [isCancelled] is polled between chunks.
     */
    fun download(url: String, dest: File, expectedSize: Long, isCancelled: () -> Boolean, progress: (Long, Long) -> Unit) {
        guard {
            dest.parentFile?.mkdirs()
            var have = if (dest.exists()) dest.length() else 0L
            if (expectedSize > 0 && have > expectedSize) { dest.delete(); have = 0 }
            val c = open(url, false)
            try {
                if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
                val code = c.responseCode
                val append: Boolean
                when (code) {
                    200 -> { have = 0; append = false }
                    206 -> append = true
                    416 -> { // stale partial file: start over once
                        dest.delete()
                        c.disconnect()
                        download(url, dest, expectedSize, isCancelled, progress)
                        return@guard
                    }
                    else -> throw UpdateException(UpdateException.Kind.SERVER, "HTTP $code")
                }
                val total = if (expectedSize > 0) expectedSize else (c.contentLengthLong.takeIf { it > 0 }?.plus(have) ?: -1L)
                var done = have
                c.inputStream.use { input ->
                    FileOutputStream(dest, append).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (isCancelled()) throw UpdateException(UpdateException.Kind.CANCELLED, "Cancelled")
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (expectedSize > 0 && done > expectedSize) {
                                throw UpdateException(UpdateException.Kind.INCOMPLETE, "More data than announced ($done > $expectedSize)")
                            }
                            progress(done, total)
                        }
                        out.fd.sync()
                    }
                }
                if (expectedSize > 0 && dest.length() != expectedSize) {
                    throw UpdateException(UpdateException.Kind.INCOMPLETE, "Size mismatch (${dest.length()}/$expectedSize)")
                }
            } finally {
                c.disconnect()
            }
        }
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { i ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
