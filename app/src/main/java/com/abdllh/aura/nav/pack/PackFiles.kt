package com.abdllh.aura.nav.pack

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** Files that must survive a power cut. The unit's /data is ext4 with noauto_da_alloc (vendor fstab.sun50iw9p1): a rename without a sync before it can leave an empty file. */
internal object PackFiles {
    private const val TAG = "AuraPack"

    /** [f] holds [text] after this, or still what it held before; never a part of it. */
    fun writeAtomic(f: File, text: String) {
        val tmp = File(f.path + ".tmp")
        FileOutputStream(tmp).use { o ->
            o.write(text.toByteArray(Charsets.UTF_8))
            o.flush()
            o.fd.sync()
        }
        if (!tmp.renameTo(f)) throw IOException("rename ${f.name}")
        syncDir(f.parentFile)
    }

    /** So that a rename done is a rename kept. Best effort: a file system that refuses this still has the rename in its journal. */
    fun syncDir(dir: File?) {
        if (dir == null) return
        try {
            val fd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
            try { Os.fsync(fd) } finally { Os.close(fd) }
        } catch (t: Throwable) {
            Log.d(TAG, "sync ${dir.name}: $t")
        }
    }

    /**
     * Takes the room for a piece now, so that a full disk shows before bytes are fetched instead of at the end, and the file is not in
     * fragments of 64 MB. [PackRefused] with SPACE when there is none; a file system that cannot do this just gets a file of that length.
     */
    fun allocate(f: File, size: Long) {
        if (f.isFile && f.length() == size) return
        FileOutputStream(f, true).use { o ->
            try {
                Os.posix_fallocate(o.fd, 0L, size)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.ENOSPC) throw PackRefused(PackProblem.SPACE, "allocate ${f.name}: $e")
                o.channel.let { ch -> if (ch.size() < size) java.io.RandomAccessFile(f, "rw").use { it.setLength(size) } }
            }
        }
    }

    fun sha256(f: File, check: () -> Unit): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { i ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                check()
                val n = i.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return hex(md.digest())
    }

    fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) {
            sb.append(Character.forDigit((x.toInt() shr 4) and 15, 16))
            sb.append(Character.forDigit(x.toInt() and 15, 16))
        }
        return sb.toString()
    }

    /** Out of the way at once (one rename), gone a moment later: a kill in the middle of deleting leaves nothing that looks like a pack. */
    fun discard(dir: File, trash: File) {
        if (!dir.exists()) return
        trash.mkdirs()
        val to = File(trash, "${dir.name}-${System.nanoTime()}")
        if (dir.renameTo(to)) { syncDir(trash); to.deleteRecursively() } else dir.deleteRecursively()
    }
}

/**
 * What is done of a pack on its way: `progress.json` in its staging folder, replaced by an atomic rename after each chunk. A chunk here is
 * on the disk and synced; everything else starts again from its `.part`. The CRC of what was written is the check that finds, at the end,
 * which chunk a damaged piece must fetch again: the manifest's hashes are of the gzip files, which are gone by then.
 */
internal class PackProgress(private val file: File) {
    val done = HashMap<String, Long>()
    val verified = HashSet<String>()
    /** Where it was coming from ("url:<base>" or "usb:<folder>"), to go on from there. */
    var source = ""

    fun load(): PackProgress {
        done.clear()
        verified.clear()
        try {
            if (!file.isFile) return this
            val o = JSONObject(file.readText())
            source = o.optString("source")
            o.optJSONObject("done")?.let { d -> d.keys().forEach { k -> done[k] = d.getLong(k) } }
            o.optJSONArray("verified")?.let { v -> for (i in 0 until v.length()) verified.add(v.getString(i)) }
        } catch (t: Throwable) {
            // (not expected: every write is atomic. The price of a surprise is fetching again, never a wrong map.)
            Log.w("AuraPack", "progress: $t")
            done.clear()
            verified.clear()
        }
        return this
    }

    fun save() {
        val d = JSONObject()
        for ((k, v) in done) d.put(k, v)
        val o = JSONObject().put("source", source).put("done", d).put("verified", org.json.JSONArray(verified.toList()))
        PackFiles.writeAtomic(file, o.toString())
    }
}
