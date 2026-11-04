package com.abdllh.aura.nav.pack

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException

/** Thrown out of a job when it is told to stop ([PackManager.pause]); the staging stays as it is. Not an IOException: no retry catches it. */
internal class PackStopped : Exception("stopped")
/** Same, because a trip began: the job begins again when it ends, from the part on the disk. */
internal class PackHold : Exception("trip")

/**
 * One pack from a source into files/maps2, on one low priority thread, and every step can be cut at any moment without losing more than the chunk in hand:
 *  1. a chunk's gzip file comes into `<chunk>.part` (a Range from where it stopped), its SHA-256 is checked, it is unpacked at its offset in the piece and synced,
 *  2. it is marked in progress.json by an atomic rename, and its part goes. Unpacking again what was already unpacked does no harm, so a kill anywhere is fine.
 *  3. Every piece's whole SHA-256 ([verify]), each file opened read only ([PackVerify]), and only then the rename into packs/ and the new pointer.
 * The pack in use is not touched by any of it.
 */
internal class PackJob(private val ctx: Context, private val m: PackManifest, private val source: PackSource, private val host: Host) {
    interface Host : PackTicker {
        fun fetching(done: Long, total: Long)
        fun verifying(done: Long, total: Long)
        fun waiting(why: PackProblem)
        /** Debug builds: dies here when told to (the kill tests). */
        fun point(name: String)
        fun freeBytes(): Long
    }

    private val stage = File(PackPaths.staging(ctx), m.id)
    private val progress = PackProgress(File(stage, "progress.json"))
    private var fetchMs = 0L
    private var hashMs = 0L
    private var unpackMs = 0L
    private var verifyMs = 0L
    private var openMs = 0L
    private var refetched = 0

    fun run() {
        host.check()
        // first of all: a pack that cannot fit does not write one byte
        if (!PackRules.fits(host.freeBytes(), m.needBytes, stagedBytes())) throw PackRefused(PackProblem.SPACE, "free ${host.freeBytes()}, need ${m.needBytes}, staged ${stagedBytes()}")
        stage.mkdirs()
        val man = File(stage, "manifest.json")
        if (!man.isFile) PackFiles.writeAtomic(man, m.text)
        progress.load()
        progress.source = source.key
        progress.save()
        for (p in m.pieces) for (c in p.chunks) if (c.url in progress.done) File(stage, c.url + ".part").delete() // (a kill between the mark and the delete)
        try {
            for (p in m.pieces) PackFiles.allocate(File(stage, p.name), p.size)
        } catch (r: PackRefused) {
            // someone took the room after the check. With nothing fetched yet, the room taken here goes back at once
            if (progress.done.isEmpty()) PackFiles.discard(stage, PackPaths.trash(ctx))
            throw r
        }
        var rounds = 0
        while (true) {
            fetchChunks()
            fetchNotices()
            if (verify() == 0) break
            if (++rounds >= 3) throw PackRefused(PackProblem.BAD_DATA, "pieces still wrong after $rounds rounds")
        }
        lastLook()
        switchTo()
        val line = "${m.id} from ${if (source.usb) "usb" else "net"}: ${m.downloadBytes / 1_048_576} MB, fetch ${fetchMs / 1000} s, hash ${hashMs / 1000} s, unpack ${unpackMs / 1000} s, " +
            "whole hash ${verifyMs / 1000} s, open ${openMs / 1000} s, chunks fetched again $refetched"
        Log.i(TAG, "installed $line")
        try { PackFiles.writeAtomic(File(PackPaths.root(ctx), "last-install.txt"), "$line\n") } catch (_: Throwable) { }
    }

    /** What the staging already holds of what the manifest asks for. */
    private fun stagedBytes(): Long = staged(ctx, m)

    private fun fetchChunks() {
        var doneBase = 0L
        for (p in m.pieces) for (c in p.chunks) if (c.url in progress.done) doneBase += c.bytes
        for (p in m.pieces) for (c in p.chunks) {
            if (c.url in progress.done) continue
            fetchChunk(p, c, doneBase)
            doneBase += c.bytes
            host.fetching(doneBase, m.downloadBytes)
        }
    }

    private fun fetchChunk(p: PackPiece, c: PackChunk, doneBase: Long) {
        val part = File(stage, c.url + ".part")
        var attempt = 0
        var wrong = 0
        while (true) {
            host.check()
            // this much must stay free for the rest of the unit, whoever else is filling the disk
            val left = c.bytes - (if (part.isFile) part.length() else 0L)
            if (host.freeBytes() < left + PackRules.FLOOR) throw PackRefused(PackProblem.SPACE, "${host.freeBytes()} free, ${c.url} needs $left more")
            host.fetching(doneBase + (if (part.isFile) part.length() else 0L), m.downloadBytes)
            val had = if (part.isFile) part.length() else 0L
            try {
                val t0 = SystemClock.elapsedRealtime()
                source.fetch(c.url, part, c.bytes, host)
                fetchMs += SystemClock.elapsedRealtime() - t0
            } catch (e: IOException) {
                attempt = failed(e, c.url, part, had, attempt)
                continue
            }
            attempt = 0
            host.point("after-fetch")
            val t1 = SystemClock.elapsedRealtime()
            val sha = PackFiles.sha256(part) { host.check() }
            hashMs += SystemClock.elapsedRealtime() - t1
            if (sha != c.sha256 || part.length() != c.bytes) {
                // a server's splice, a wrong byte, a stick going bad: this chunk again, nothing else
                wrong++
                refetched++
                Log.w(TAG, "${c.url}: hash $sha, wanted ${c.sha256} (${part.length()} bytes), time $wrong")
                part.delete()
                if (wrong >= 3) throw PackRefused(if (source.usb) PackProblem.USB_DAMAGED else PackProblem.BAD_DATA, "${c.url}: wrong $wrong times")
                continue
            }
            val t2 = SystemClock.elapsedRealtime()
            val crc = unpack(part, File(stage, p.name), c)
            unpackMs += SystemClock.elapsedRealtime() - t2
            host.point("after-unpack")
            progress.done[c.url] = crc
            progress.save()
            host.point("after-mark")
            part.delete()
            Log.i(TAG, "${c.url} in: ${progress.done.size} of ${m.chunkCount}")
            return
        }
    }

    /** A failed fetch: decides between going on and giving up; returns the new count of failures in a row, after waiting. */
    private fun failed(e: IOException, name: String, part: File, had: Long, before: Int): Int {
        if (PackRules.noSpace(e.message)) throw PackRefused(PackProblem.SPACE, "$name: $e")
        if (e is PackDamaged) throw PackRefused(PackProblem.USB_DAMAGED, "$name: $e")
        // it brought something: the link works, the drops are its nature, and the next wait is short again
        val attempt = if ((if (part.isFile) part.length() else 0L) > had) 1 else before + 1
        Log.i(TAG, "$name: $e (failure $attempt, part ${part.length()})")
        if (source.usb) {
            if (attempt >= 3) throw PackRefused(if (e is PackMissing && readable()) PackProblem.USB_DAMAGED else PackProblem.USB_GONE, "$name: $e")
        } else if (e is PackMissing && attempt >= 4) {
            throw PackRefused(PackProblem.NOT_PUBLISHED, "$name: $e")
        }
        host.waiting(if (source.usb) PackProblem.USB_GONE else PackManager.networkProblem(e))
        nap(if (source.usb) 1000L else PackRules.backoffMs(attempt))
        return attempt
    }

    private fun readable(): Boolean = try { source.manifestText().isNotEmpty() } catch (_: Throwable) { false }

    /** A pause that ends early when the job is stopped. */
    private fun nap(ms: Long) {
        val until = SystemClock.elapsedRealtime() + ms
        while (SystemClock.elapsedRealtime() < until) {
            host.check()
            SystemClock.sleep(200)
        }
    }

    private fun unpack(part: File, piece: File, c: PackChunk): Long {
        val crc = CRC32()
        try {
            RandomAccessFile(piece, "rw").use { out ->
                out.seek(c.offset)
                val file = FileInputStream(part)
                (if (c.gz) GZIPInputStream(file, 64 * 1024) else file).use { inp ->
                    var left = c.raw
                    var unsynced = 0L
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        host.check()
                        val n = inp.read(buf)
                        if (n < 0) break
                        if (n > left) throw PackRefused(PackProblem.BAD_DATA, "${c.url}: longer than ${c.raw}")
                        out.write(buf, 0, n)
                        crc.update(buf, 0, n)
                        left -= n
                        unsynced += n
                        if (unsynced >= PackRules.SYNC_EVERY) { out.fd.sync(); unsynced = 0 }
                    }
                    if (left != 0L) throw PackRefused(PackProblem.BAD_DATA, "${c.url}: $left bytes short")
                }
                out.fd.sync()
            }
        } catch (e: ZipException) { // its hash was right: the pack itself is wrong
            throw PackRefused(PackProblem.BAD_DATA, "${c.url}: $e")
        } catch (e: IOException) {
            if (PackRules.noSpace(e.message)) throw PackRefused(PackProblem.SPACE, "${c.url}: $e")
            throw e
        }
        return crc.value
    }

    private fun fetchNotices() {
        var base = m.pieces.sumOf { p -> p.chunks.sumOf { it.bytes } }
        for (n in m.notices) {
            host.check()
            val f = File(stage, n.url)
            if (f.isFile && f.length() == n.bytes && PackFiles.sha256(f) { host.check() } == n.sha256) { base += n.bytes; continue }
            f.delete()
            val part = File(stage, n.url + ".part")
            var attempt = 0
            var wrong = 0
            while (true) {
                host.check()
                host.fetching(base, m.downloadBytes)
                val had = if (part.isFile) part.length() else 0L
                try {
                    source.fetch(n.url, part, n.bytes, host)
                } catch (e: IOException) {
                    attempt = failed(e, n.url, part, had, attempt)
                    continue
                }
                if (PackFiles.sha256(part) { host.check() } == n.sha256 && part.renameTo(f)) break
                part.delete()
                if (++wrong >= 3) throw PackRefused(if (source.usb) PackProblem.USB_DAMAGED else PackProblem.BAD_DATA, "${n.url}: wrong $wrong times")
            }
            base += n.bytes
        }
        host.fetching(m.downloadBytes, m.downloadBytes)
    }

    /**
     * The whole of every piece against its hash, and its chunks against the CRC of what was written. Returns how many chunks are to be fetched again: a block a power
     * cut or a bad disk damaged after its sync costs that chunk, not the piece.
     */
    private fun verify(): Int {
        val t0 = SystemClock.elapsedRealtime()
        var again = 0
        var done = m.pieces.filter { it.name in progress.verified }.sumOf { it.size }
        val buf = ByteArray(64 * 1024)
        for (p in m.pieces) {
            if (p.name in progress.verified) continue
            val md = MessageDigest.getInstance("SHA-256")
            val bad = ArrayList<String>()
            FileInputStream(File(stage, p.name)).use { inp ->
                val crc = CRC32()
                for (c in p.chunks) {
                    crc.reset()
                    var left = c.raw
                    while (left > 0) {
                        host.check()
                        val n = inp.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
                        if (n < 0) throw IOException("${p.name} ends early")
                        md.update(buf, 0, n)
                        crc.update(buf, 0, n)
                        left -= n
                        done += n
                        host.verifying(done, m.diskBytes)
                    }
                    if (progress.done[c.url] != crc.value) bad.add(c.url)
                }
            }
            if (PackFiles.hex(md.digest()) == p.sha256) {
                progress.verified.add(p.name)
                progress.save()
                continue
            }
            // the CRCs agree and the hash does not: which chunk it is cannot be told, so all of them
            val redo = if (bad.isEmpty()) p.chunks.map { it.url } else bad
            Log.w(TAG, "${p.name}: wrong hash, fetching again: $redo")
            for (u in redo) progress.done.remove(u)
            progress.save()
            again += redo.size
            refetched += redo.size
            done -= p.size
        }
        verifyMs += SystemClock.elapsedRealtime() - t0
        return again
    }

    /** Can this unit open what came? On the job's thread, read only. */
    private fun lastLook() {
        host.check()
        val t0 = SystemClock.elapsedRealtime()
        val problem = PackVerify.problem(stage, m)
        openMs += SystemClock.elapsedRealtime() - t0
        if (problem != null) throw PackRefused(PackProblem.BAD_DATA, problem)
        for (p in m.pieces) File(stage, p.name).setReadOnly() // nothing in the app is to write to a verified file
    }

    /** The switch: a rename into packs/, a rename of the pointer. A kill between the two is finished at the next start ([PackManager.restore]). */
    private fun switchTo() {
        host.point("before-ok")
        PackFiles.writeAtomic(File(stage, "ok"), m.id)
        host.point("before-rename")
        val packs = PackPaths.packs(ctx).apply { mkdirs() }
        val dst = File(packs, m.id)
        if (dst.exists()) PackFiles.discard(dst, PackPaths.trash(ctx)) // (left by a failed try; the pack in use never gets here)
        if (!stage.renameTo(dst)) throw IOException("rename into packs")
        PackFiles.syncDir(packs)
        PackFiles.syncDir(stage.parentFile)
        host.point("before-pointer")
        PackFiles.writeAtomic(PackPaths.pointer(ctx), m.id)
        host.point("after-pointer")
    }

    companion object {
        private const val TAG = "AuraPack"

        /** The bytes of pack [m] the staging holds: they count as space already found when a stopped job goes on. */
        fun staged(ctx: Context, m: PackManifest): Long {
            val stage = File(PackPaths.staging(ctx), m.id)
            if (!stage.isDirectory) return 0L
            return m.pieces.sumOf { File(stage, it.name).length().coerceAtMost(it.size) }
        }

        /** What is fetched already of pack [m]: for the screen after a restart. */
        fun fetchedBytes(ctx: Context, m: PackManifest): Long {
            val stage = File(PackPaths.staging(ctx), m.id)
            val done = PackProgress(File(stage, "progress.json")).load().done
            var n = 0L
            for (p in m.pieces) for (c in p.chunks) {
                n += if (c.url in done) c.bytes else File(stage, c.url + ".part").length().coerceAtMost(c.bytes)
            }
            return n
        }
    }
}
