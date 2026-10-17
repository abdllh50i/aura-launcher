package com.abdllh.aura.voice

import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.max

/**
 * Sounds the owner showed were not the wake word (1.13). When "عمري" wakes the assistant and then nothing is asked
 * (the recogniser hears nothing, the time runs out) or the owner taps the pill away within a few seconds, or answers
 * "خلاص", the word that woke it is kept here; a later word much closer to one of these than to any of the owner's
 * recordings is not taken for the name. What a car hears again and again (a song's line, a chime, a phrase on the
 * radio, a word the owner says often) wakes it once.
 *
 * Kept from turning against the owner (a real "عمري" followed by silence would otherwise teach it to ignore him):
 * - only weak matches are learned (near the sensitivity's limits, [weak]) and only weak matches are refused: a "عمري"
 *   as close as most of the owner's own is never learned and never refused;
 * - refused only when nearer to the learned sound than [ALPHA] times its distance to the nearest recording;
 * - a wake that is confirmed (a request followed and was done) takes away every learned sound about as close to it as
 *   the recordings are ([BETA]): one real call outvotes a mistake;
 * - at most [MAX] (the oldest that never refused anything goes first), and Settings → Voice assistant forgets them.
 * Simulated on the test corpus (docs/notes-1.13-amri.md): of ten real "عمري" answered with silence about four (the
 * weak ones) were learned and cost 4 points of true accepts until the next confirmed wakes brought them back; false
 * wakes heard again later woke 30% of the time instead of 50%.
 *
 * In "amri-wake-neg.bin" ("AMRN": count, then per sound: when, refusals, frames, mels, bands, background), spectra as
 * [WakeModel] keeps them. All calls are synchronised: the microphone's thread asks, the main thread learns.
 */
internal object WakeNegatives {
    private const val TAG = "AuraAmri"
    private const val MAGIC = 0x414D524E // "AMRN"
    const val MAX = 24
    /** Refused when its distance to a learned sound is under this times its distance to the nearest recording. */
    private const val ALPHA = 0.8f
    /**
     * Learned only when it matched weakly: its score this share of the sensitivity's limit or more. (Weighing the
     * score through its own background too, [Verdict.masked], let loud-noise mistakes be learned, but then real calls
     * in noise were refused: true accepts fell from 67% to 28% in the simulation. Not done.)
     */
    private const val WEAK = 0.75f
    /** Words scoring under this share of the limit are never refused (a strong match is the owner). */
    private const val REFUSE_FROM = 0.65f

    private fun weak(v: Verdict, lim: Limits, share: Float) = v.score >= share * lim.score
    /** A confirmed wake takes away learned sounds nearer to it than this times its distance to the recordings. */
    private const val BETA = 1.2f

    private class Item(val spectrum: Spectrum, val learnedAt: Long, var hits: Int)

    private var cache: MutableList<Item>? = null
    private var cachePath: String? = null

    fun file(ctx: android.content.Context) = File(ctx.filesDir, "amri-wake-neg.bin")

    private fun items(f: File): MutableList<Item> {
        cache?.let { if (cachePath == f.path) return it }
        val list = read(f)
        cache = list
        cachePath = f.path
        return list
    }

    @Synchronized
    fun count(f: File): Int = items(f).size

    /** Is [w] (judged [v] under [lim]) one of the learned sounds rather than the name? */
    @Synchronized
    fun rejects(f: File, w: Spectrum, v: Verdict, lim: Limits): Boolean {
        val list = items(f)
        if (list.isEmpty() || !weak(v, lim, REFUSE_FROM)) return false
        var best = Float.MAX_VALUE
        var hit: Item? = null
        for (it in list) {
            val d = distance(w, it.spectrum)
            if (d < best) { best = d; hit = it }
        }
        if (hit == null || best >= ALPHA * v.nearest) return false
        hit.hits++
        Log.d(TAG, "learned sound: %.2f against the recordings' %.2f".format(java.util.Locale.ROOT, best, v.nearest))
        return true
    }

    /** The wake [c] was not meant (nothing asked, tapped away): kept when it matched weakly. True: learned. */
    @Synchronized
    fun learn(f: File, c: WakeCandidate, lim: Limits, now: Long = System.currentTimeMillis()): Boolean {
        if (!weak(c.verdict, lim, WEAK)) return false
        val list = items(f)
        if (list.size >= MAX) list.removeAt(list.indexOfFirst { it.hits == 0 }.takeIf { it >= 0 } ?: 0)
        list.add(Item(c.spectrum, now, 0))
        save(f, list)
        return true
    }

    /** The wake [c] was meant (a request followed): learned sounds about as close to it as the recordings go. */
    @Synchronized
    fun confirm(f: File, c: WakeCandidate) {
        val list = items(f)
        if (list.isEmpty()) return
        val before = list.size
        list.removeAll { distance(c.spectrum, it.spectrum) < BETA * c.verdict.nearest }
        if (list.size != before) {
            Log.i(TAG, "learned sounds: ${before - list.size} forgotten (a confirmed wake was close to them)")
            save(f, list)
        }
    }

    @Synchronized
    fun clear(f: File) {
        f.delete()
        cache = mutableListOf()
        cachePath = f.path
    }

    /** As [WakeModel.distances] compares a word with a recording: through the louder of the two backgrounds. */
    private fun distance(a: Spectrum, b: Spectrum): Float {
        val common = FloatArray(Features.MELS) { max(a.noise[it], b.noise[it]) }
        return Dtw.distance(Features.cepstra(a, common), Features.cepstra(b, common))
    }

    private fun read(f: File): MutableList<Item> = try {
        if (!f.isFile) mutableListOf() else DataInputStream(f.inputStream().buffered()).use { inp ->
            if (inp.readInt() != MAGIC) return mutableListOf()
            val n = inp.readInt()
            MutableList(n) {
                val at = inp.readLong()
                val hits = inp.readInt()
                val frames = inp.readInt()
                val mels = inp.readInt()
                val bands = Array(frames) { FloatArray(mels) { inp.readFloat() } }
                Item(Spectrum(bands, FloatArray(mels) { inp.readFloat() }), at, hits)
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "learned sounds: $t")
        mutableListOf()
    }

    private fun save(f: File, list: List<Item>) {
        try {
            val tmp = File(f.path + ".tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(list.size)
                for (it in list) {
                    out.writeLong(it.learnedAt)
                    out.writeInt(it.hits)
                    out.writeInt(it.spectrum.frames)
                    out.writeInt(Features.MELS)
                    for (fr in it.spectrum.bands) for (v in fr) out.writeFloat(v)
                    for (v in it.spectrum.noise) out.writeFloat(v)
                }
            }
            if (!tmp.renameTo(f)) tmp.delete()
        } catch (t: Throwable) {
            Log.w(TAG, "learned sounds save: $t")
        }
    }
}
