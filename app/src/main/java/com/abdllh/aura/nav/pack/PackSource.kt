package com.abdllh.aura.nav.pack

import android.util.Log
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.system.LocalAdb
import com.abdllh.aura.system.SystemProps
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/** What a source tells the job about its work: throws to stop it, counts its bytes (-n when kept bytes were dropped). */
internal interface PackTicker {
    fun check()
    fun bytes(n: Long)
}

/** A chunk's file on a source is not there. On a server: not published (yet), or a release that was changed. */
internal class PackMissing(what: String) : IOException(what)

/** Where the chunks come from. Same names, same hashes, whichever it is. [fetch] blocks, and throws IOException on any failure: the job retries. */
internal interface PackSource {
    /** "url:<base>" or "usb:<folder>": kept in progress.json, to go on from there after a restart. */
    val key: String
    val usb: Boolean
    fun manifestText(): String
    /** Brings the part up to [bytes] from where it ends. Whatever is in it stays until the job checks its hash. */
    fun fetch(name: String, part: File, bytes: Long, t: PackTicker)
}

/**
 * A release on GitHub or the test server. github.com answers 302 to its CDN; the hops are followed here (http to https or a Range lost on the way is
 * not left to the platform). No If-Range, ETag or Last-Modified: GitHub's CDN answers 206 to a wrong validator (measured, maps.md 15.1), so nothing would
 * come of them. A wrong splice is found by the chunk's hash, and costs that chunk. `Accept-Encoding: identity`: the platform would otherwise ask for gzip,
 * unpack what it gets, and count the bytes wrong for a Range.
 */
internal class HttpSource(base: String) : PackSource {
    /** Under the tag the manifest was found in, once it is known ([manifestText]). */
    var base: String = base.trimEnd('/') + "/"
        private set
    override val key: String get() = "url:$base"
    override val usb = false

    private fun open(url: String, from: Long, hops: MutableList<String>? = null): HttpURLConnection {
        var u = url
        for (i in 0 until MAX_HOPS) {
            if (!PackRules.urlAllowed(u, BuildConfig.DEBUG)) throw IOException("not https: $u")
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 12_000
            c.readTimeout = 25_000
            c.setRequestProperty("User-Agent", "Aura-Launcher/${BuildConfig.VERSION_NAME}")
            c.setRequestProperty("Accept-Encoding", "identity")
            if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
            val code = c.responseCode
            if (code !in 301..308 || code == 304 || code == 305 || code == 306) return c
            val next = c.getHeaderField("Location")
            c.disconnect()
            if (next.isNullOrEmpty()) throw IOException("HTTP $code with no Location")
            u = PackRules.resolve(u, next)
            hops?.add(u)
        }
        throw IOException("too many redirects: $url")
    }

    override fun manifestText(): String {
        val hops = ArrayList<String>()
        val c = open(PackRules.join(base, "manifest.json"), 0L, hops)
        try {
            val code = c.responseCode
            if (code == 404) throw PackMissing("manifest.json")
            if (code != 200) throw IOException("manifest: HTTP $code")
            val text = c.inputStream.use { readCapped(it, PackManifest.MAX_TEXT) }
            PackRules.releaseBase(hops)?.let { base = it }
            return text
        } finally {
            c.disconnect()
        }
    }

    override fun fetch(name: String, part: File, bytes: Long, t: PackTicker) {
        var have = if (part.isFile) part.length() else 0L
        if (have > bytes) { t.bytes(-have); part.delete(); have = 0L }
        if (have == bytes) return
        // (the end of what is kept is fetched again: PackRules.OVERLAP)
        val from = PackRules.resumeAt(have)
        if (from != have) t.bytes(from - have)
        val c = open(PackRules.join(base, name), from)
        try {
            val code = c.responseCode
            val start = when (code) {
                200 -> { t.bytes(-from); 0L } // the Range was not listened to: the whole chunk comes
                206 -> {
                    val r = c.getHeaderField("Content-Range")
                    if (!PackRules.rangeFits(PackRules.contentRange(r), from, bytes)) {
                        Log.i(TAG, "$name: asked from $from, got $r: anew")
                        t.bytes(-from)
                        part.delete()
                        throw IOException("wrong range")
                    }
                    from
                }
                416 -> { t.bytes(-from); part.delete(); throw IOException("416") }
                404 -> { t.bytes(have - from); throw PackMissing(name) }
                else -> { t.bytes(have - from); throw IOException("HTTP $code") }
            }
            val left = bytes - start
            val length = c.contentLengthLong
            if (length >= 0 && length != left) { // an error page, a hotspot's sign-in page: not this chunk
                t.bytes(have - start)
                throw IOException("HTTP $code length $length, wanted $left")
            }
            RandomAccessFile(part, "rw").use { raf ->
                raf.setLength(start)
                raf.seek(start)
                var got = 0L
                var unsynced = 0L
                val buf = ByteArray(64 * 1024)
                c.inputStream.use { inp ->
                    while (true) {
                        t.check()
                        val n = inp.read(buf)
                        if (n < 0) break
                        if (got + n > left) throw IOException("more than promised")
                        raf.write(buf, 0, n)
                        got += n
                        unsynced += n
                        t.bytes(n.toLong())
                        if (unsynced >= PackRules.SYNC_EVERY) { raf.fd.sync(); unsynced = 0 }
                    }
                }
                raf.fd.sync()
                if (got != left) throw IOException("short: $got of $left")
            }
        } finally {
            c.disconnect()
        }
    }

    companion object {
        private const val TAG = "AuraPack"
        private const val MAX_HOPS = 6
        /** The first release. */
        const val RELEASE = "https://github.com/abdllh50i/amri-maps/releases/download/data-2026.11/"
        /** What GitHub calls the newest release: it answers 302 to the tag, and that tag is where its chunks are then fetched from. */
        const val LATEST = "https://github.com/abdllh50i/amri-maps/releases/latest/download/"

        /** The test server, in a debug build (`setprop debug.aura.pack.base http://127.0.0.1:8791/releases/download/data-2026.11/`). */
        fun debugBase(): String? = if (BuildConfig.DEBUG) SystemProps.get("debug.aura.pack.base").takeIf { it.startsWith("http") } else null
    }
}

/** A stick or a card this process can read itself (through /storage with the storage permission, or wherever the firmware lets it). */
internal class FileSource(private val dir: File) : PackSource {
    override val key: String get() = "usb:${dir.path}"
    override val usb = true

    override fun manifestText(): String = FileInputStream(File(dir, "manifest.json")).use { readCapped(it, PackManifest.MAX_TEXT) }

    override fun fetch(name: String, part: File, bytes: Long, t: PackTicker) {
        var have = if (part.isFile) part.length() else 0L
        if (have > bytes) { t.bytes(-have); part.delete(); have = 0L }
        if (have == bytes) return
        val src = File(dir, name)
        if (!src.isFile) throw PackMissing(name)
        if (src.length() != bytes) throw IOException("$name: ${src.length()} bytes, wanted $bytes")
        RandomAccessFile(part, "rw").use { raf ->
            raf.setLength(have)
            raf.seek(have)
            FileInputStream(src).use { inp ->
                var skip = have
                while (skip > 0) { val s = inp.skip(skip); if (s <= 0) throw IOException("seek"); skip -= s }
                val buf = ByteArray(256 * 1024)
                var unsynced = 0L
                while (true) {
                    t.check()
                    val n = inp.read(buf)
                    if (n < 0) break
                    raf.write(buf, 0, n)
                    unsynced += n
                    t.bytes(n.toLong())
                    if (unsynced >= PackRules.SYNC_EVERY) { raf.fd.sync(); unsynced = 0 }
                }
            }
            raf.fd.sync()
        }
    }
}

/**
 * A stick only the root shell can read: vold mounts it media_rw:media_rw 0770, and this app is not in that group (its only way in without the storage
 * permission, and the one kept here for when /storage shows nothing). The shell copies 8 MB at a time into the part this app already owns, with `dd`
 * ([LocalAdb] has no binary channel): the bytes never pass through the shell's text, the file stays this app's, and a pause or a progress bar waits
 * at most for one step.
 */
internal class RootSource(private val dir: String) : PackSource {
    override val key: String get() = "usb:$dir"
    override val usb = true

    override fun manifestText(): String {
        val r = LocalAdb.run("cat ${quote("$dir/manifest.json")}", 15_000)
        if (!r.ok) throw IOException("root cat: ${r.output}")
        return r.output
    }

    override fun fetch(name: String, part: File, bytes: Long, t: PackTicker) {
        var have = if (part.isFile) part.length() else 0L
        if (have > bytes) { t.bytes(-have); part.delete(); have = 0L }
        if (have == bytes) return
        // dd counts in blocks: back to the last whole one
        val blocks = have / STEP
        val from = blocks * STEP
        RandomAccessFile(part, "rw").use { it.setLength(from) }
        t.bytes(from - have)
        have = from
        val src = quote("$dir/$name")
        val dst = quote(part.absolutePath)
        var block = blocks
        while (have < bytes) {
            t.check()
            val r = LocalAdb.run("dd if=$src of=$dst bs=$STEP skip=$block seek=$block count=$COUNT conv=notrunc 2>&1 && echo DONE", 120_000)
            if (!r.ok || !r.output.contains("DONE")) throw IOException("root dd: ${r.output.takeLast(200)}")
            val now = part.length()
            if (now <= have && now < bytes) throw IOException("root dd brought nothing: ${r.output.takeLast(120)}")
            t.bytes(now - have)
            have = now
            block += COUNT
            if (now > bytes) throw IOException("$name is longer than the manifest says")
        }
        FileInputStream(part).use { it.fd.sync() }
    }

    private companion object {
        const val STEP = 1024L * 1024
        const val COUNT = 8
    }
}

/** For the root shell's command line: one word, whatever is in the path. */
internal fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"

internal fun readCapped(inp: java.io.InputStream, cap: Int): String {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    while (true) {
        val n = inp.read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
        if (out.size() > cap) throw IOException("longer than $cap bytes: not a manifest")
    }
    return out.toString("UTF-8")
}
