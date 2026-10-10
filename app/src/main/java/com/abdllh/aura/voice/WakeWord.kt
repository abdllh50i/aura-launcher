package com.abdllh.aura.voice

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The wake word ("عمري") without a network or a downloaded model: the owner says it a few times ([WakeModel], the
 * enrolment in Settings) and every word heard afterwards is compared with those recordings. A word is a stretch of
 * sound between pauses ([Segmenter]); it is described by its MFCCs (the spectrum's shape every 10 ms, as speech
 * recognisers see it, [Features]) and compared by dynamic time warping ([Dtw]), which lines up a faster or slower
 * "عمري" with the recordings. A personal model in the classic way: tiny, instant, made for one voice.
 */
/** A word's mel band energies per 10 ms frame, its background taken off, and that background ([Features.spectrum]). */
internal class Spectrum(val bands: Array<FloatArray>, val noise: FloatArray) {
    val frames get() = bands.size
}

internal object Features {
    const val RATE = 16000
    const val HOP = 160             // 10 ms
    private const val WIN = 400     // 25 ms
    private const val NFFT = 512
    const val MELS = 26
    private const val CEPS = 12
    const val DIM = CEPS * 2        // cepstra and their deltas

    private val window = FloatArray(WIN) { (0.54 - 0.46 * cos(2 * PI * it / (WIN - 1))).toFloat() }
    private val bank: Array<FloatArray>
    private val dct: Array<FloatArray>

    init {
        fun mel(f: Double) = 2595.0 * log10(1.0 + f / 700.0)
        fun hz(m: Double) = 700.0 * (Math.pow(10.0, m / 2595.0) - 1.0)
        val lo = mel(64.0)
        val hi = mel(7600.0)
        val bins = IntArray(MELS + 2) { i -> ((NFFT + 1) * hz(lo + (hi - lo) * i / (MELS + 1)) / RATE).toInt() }
        bank = Array(MELS) { m ->
            FloatArray(NFFT / 2 + 1).also { w ->
                val a = bins[m]; val b = bins[m + 1]; val c = bins[m + 2]
                for (k in a until b) if (b > a) w[k] = (k - a).toFloat() / (b - a)
                for (k in b until c) if (c > b) w[k] = (c - k).toFloat() / (c - b)
            }
        }
        dct = Array(CEPS) { k -> FloatArray(MELS) { m -> cos(PI * (k + 1) * (m + 0.5) / MELS).toFloat() } }
    }

    /** The mel energies of each frame of [pcm]. */
    private fun melFrames(pcm: ShortArray): Array<DoubleArray> {
        val frames = if (pcm.size < WIN) 0 else 1 + (pcm.size - WIN) / HOP
        val re = FloatArray(NFFT)
        val im = FloatArray(NFFT)
        return Array(frames) { f ->
            val o = f * HOP
            var prev = if (o > 0) pcm[o - 1].toFloat() else 0f
            for (i in 0 until NFFT) {
                if (i < WIN) {
                    val s = pcm[o + i].toFloat()
                    re[i] = (s - 0.97f * prev) * window[i] // pre-emphasis, then the window
                    prev = s
                } else re[i] = 0f
                im[i] = 0f
            }
            fft(re, im)
            DoubleArray(MELS) { m ->
                val w = bank[m]
                var e = 0.0
                for (k in 0..NFFT / 2) if (w[k] > 0f) e += w[k] * (re[k] * re[k] + im[k] * im[k])
                e
            }
        }
    }

    /**
     * The spectrum of the word [pcm]. [noise]: the sound just before it; its spectrum is taken off the word's (the
     * road, the fan) and kept, so that [cepstra] can look at two words through the same background.
     */
    fun spectrum(pcm: ShortArray, noise: ShortArray? = null): Spectrum {
        val mels = melFrames(pcm)
        val n = noise?.let { melFrames(it) }?.takeIf { it.isNotEmpty() }
        val noiseMel = DoubleArray(MELS)
        if (n != null) for (fr in n) for (m in 0 until MELS) noiseMel[m] += fr[m] / n.size
        // over-subtraction with a spectral floor: what is left of a band never drops under a tenth of it
        val bands = Array(mels.size) { f -> FloatArray(MELS) { m -> max(mels[f][m] - 1.5 * noiseMel[m], 0.1 * mels[f][m]).toFloat() } }
        return Spectrum(bands, FloatArray(MELS) { noiseMel[it].toFloat() })
    }

    /**
     * Frames x [DIM]: MFCC c1..c12 of [s] seen through the background [common] (no band quieter than it), mean-
     * normalised over the word (which removes the microphone's colour), and their deltas. Two words compared through
     * the louder of their backgrounds look alike when they are the same word, however noisy either was: what the
     * noise hid in one is hidden in both.
     */
    fun cepstra(s: Spectrum, common: FloatArray): Array<FloatArray> {
        val frames = s.frames
        val ceps = Array(frames) { FloatArray(CEPS) }
        val logMel = FloatArray(MELS)
        for (f in 0 until frames) {
            val b = s.bands[f]
            for (m in 0 until MELS) logMel[m] = ln(max(b[m], common[m]) + 1.0).toFloat()
            val c = ceps[f]
            for (k in 0 until CEPS) {
                var sum = 0f
                val d = dct[k]
                for (m in 0 until MELS) sum += d[m] * logMel[m]
                c[k] = sum
            }
        }
        if (frames == 0) return emptyArray()
        val mean = FloatArray(CEPS)
        for (c in ceps) for (k in 0 until CEPS) mean[k] += c[k]
        for (k in 0 until CEPS) mean[k] /= frames
        for (c in ceps) for (k in 0 until CEPS) c[k] -= mean[k]
        return Array(frames) { f ->
            val a = ceps[max(0, f - 1)]
            val b = ceps[min(frames - 1, f + 1)]
            FloatArray(DIM).also { v ->
                for (k in 0 until CEPS) { v[k] = ceps[f][k]; v[CEPS + k] = (b[k] - a[k]) }
            }
        }
    }

    /** In-place radix-2 FFT ([re] and [im] of power-of-two length). */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang).toFloat()
            val wi = kotlin.math.sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val xr = re[i + k + len / 2]; val xi = im[i + k + len / 2]
                    val vr = xr * cr - xi * ci
                    val vi = xr * ci + xi * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/** Distance between two words: the cheapest alignment of their frames (Sakoe-Chiba band), per frame. */
internal object Dtw {
    fun distance(a: Array<FloatArray>, b: Array<FloatArray>): Float {
        val n = a.size
        val m = b.size
        if (n == 0 || m == 0) return Float.MAX_VALUE
        val band = max(abs(n - m), (max(n, m) * 0.3f).toInt()) + 1
        val inf = Float.MAX_VALUE / 4
        var prev = FloatArray(m + 1) { inf }
        var cur = FloatArray(m + 1)
        prev[0] = 0f
        for (i in 1..n) {
            cur.fill(inf)
            val jc = (i.toLong() * m / n).toInt()
            val j0 = max(1, jc - band)
            val j1 = min(m, jc + band)
            val x = a[i - 1]
            for (j in j0..j1) {
                val y = b[j - 1]
                var d = 0f
                for (k in x.indices) { val t = x[k] - y[k]; d += t * t }
                cur[j] = sqrt(d) + min(prev[j - 1], min(prev[j], cur[j - 1]))
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[m] / (n + m)
    }
}

/**
 * Splits the microphone's sound into words. The background is the quietest moment of the last 1.5 s, past a few odd
 * hops (a car's road noise, a fan: it follows them up and down within that time); a word starts when the level rises
 * 10 dB over it, and ends after 0.2 s that are back near the background or 24 dB under the word's own peak (a fading
 * tail is not the word). Words longer than [maxMs] are not passed on (sentences, music). [onLevel]: the level over the
 * background, 0..1, for meters.
 */
internal class Segmenter(
    private val maxMs: Int = 1600,
    private val onLevel: ((Float) -> Unit)? = null,
    /** A word, and the background just before it (for [Features.spectrum]'s noise). */
    private val onWord: (word: ShortArray, noise: ShortArray) -> Unit
) {
    private val ring = ShortArray(Features.RATE * 4)
    private var written = 0L          // samples ever written to the ring
    private val hop = ShortArray(Features.HOP)
    private var hopFill = 0
    private val history = FloatArray(HISTORY) // the last hops' levels (dB)
    private val sorted = FloatArray(HISTORY)
    private var hops = 0L
    private var settle = 0
    private var above = 0
    private var below = 0
    private var inWord = false
    private var start = 0L
    private var peak = 0f

    fun feed(buf: ShortArray, n: Int) {
        for (i in 0 until n) {
            ring[(written % ring.size).toInt()] = buf[i]
            written++
            hop[hopFill++] = buf[i]
            if (hopFill == hop.size) { hopFill = 0; step() }
        }
    }

    private var last = 0f

    private fun step() {
        // the level of the voice's frequencies: a first difference takes away the road's rumble (low, and loud)
        var e = 0.0
        for (s in hop) { val v = s - 0.95f * last; last = s.toFloat(); e += v.toDouble() * v }
        val db = (10 * log10(e / hop.size + 1.0)).toFloat()
        // a recording that is only starting gives near silence for a moment (half a second on the emulator)
        if (hops == 0L && db < SILENT_DB && settle++ < SETTLE_HOPS) return
        history[(hops % HISTORY).toInt()] = db
        hops++
        // the quietest moment, past a few odd hops (the one half filled as the sound starts, a stream's dropout): they
        // would hold the background too low for 1.5 s, and the next sound would look like a word
        val n = min(hops, HISTORY.toLong()).toInt()
        System.arraycopy(history, 0, sorted, 0, n)
        java.util.Arrays.sort(sorted, 0, n)
        val floor = max(sorted[min(OUTLIERS, n - 1)], 15f)
        onLevel?.invoke(((db - floor) / 30f).coerceIn(0f, 1f))
        if (hops < WARMUP) return // the background is not known yet
        if (!inWord) {
            if (db > floor + 10f) above++ else above = 0
            if (above >= 3) {
                inWord = true
                below = 0
                peak = db
                start = max(written - (3 + PRE_HOPS) * Features.HOP, written - ring.size + Features.HOP)
            }
            return
        }
        peak = max(peak, db)
        if (db < max(floor + 6f, peak - 24f)) below++ else below = 0
        val len = written - start
        if (below >= END_HOPS) {
            inWord = false
            above = 0
            val end = written - (below - POST_HOPS) * Features.HOP
            val n = (end - start).toInt()
            if (n in MIN_SAMPLES..(maxMs * Features.RATE / 1000)) {
                val word = ShortArray(n)
                for (i in 0 until n) word[i] = ring[((start + i) % ring.size).toInt()]
                // the background before it (what the ring still holds of it)
                val from = max(start - NOISE_SAMPLES, max(0L, written - ring.size))
                val noise = ShortArray((start - from).toInt().coerceAtLeast(0))
                for (i in noise.indices) noise[i] = ring[((from + i) % ring.size).toInt()]
                onWord(word, noise)
            }
        } else if (len > maxMs * Features.RATE / 1000 + END_HOPS * Features.HOP) {
            // longer than a word can be (a sentence, music): not passed on; listen again from here
            inWord = false
            above = 0
        }
    }

    companion object {
        private const val HISTORY = 150   // 1.5 s
        private const val OUTLIERS = 3    // the quietest hops passed over for the background
        private const val SILENT_DB = 6f  // under any microphone's own noise
        private const val SETTLE_HOPS = 200 // at most 2 s of silence while the recording starts
        private const val WARMUP = 30     // 0.3 s
        private const val END_HOPS = 20   // 0.2 s
        private const val PRE_HOPS = 8
        private const val POST_HOPS = 6
        private const val MIN_SAMPLES = Features.RATE / 5 // 0.2 s
        private const val NOISE_SAMPLES = Features.RATE * 3 / 10 // 0.3 s
    }
}

/**
 * The owner's recordings of the wake word, and how close a word must come to them. The threshold follows from how
 * much the recordings differ among themselves (the owner's own variation), times the sensitivity.
 */
internal class WakeModel(val words: List<Spectrum>) {
    /** The loudest background the recordings were made in. */
    private val background = FloatArray(Features.MELS).also { b -> for (w in words) for (m in b.indices) b[m] = max(b[m], w.noise[m]) }

    /** The distances between the recordings, through that background. */
    private val pairs: Array<FloatArray> = run {
        val c = words.map { Features.cepstra(it, background) }
        val d = Array(c.size) { FloatArray(c.size) }
        for (i in c.indices) for (j in i + 1 until c.size) { d[i][j] = Dtw.distance(c[i], c[j]); d[j][i] = d[i][j] }
        d
    }

    /** Mean distance between the recordings: the owner's own variation. */
    val spread: Float = run {
        var s = 0f
        var n = 0
        for (i in pairs.indices) for (j in i + 1 until pairs.size) { s += pairs[i][j]; n++ }
        if (n == 0) 0f else s / n
    }

    /**
     * The recording that is not like the others (a cough, another word), or -1: one whose mean distance to the
     * others is more than 1.6 times the others' mean distance among themselves. Kept, it would widen [spread] and let
     * other words wake the assistant.
     */
    fun outlier(): Int {
        val n = words.size
        if (n < 4) return -1
        var worst = -1
        var worstRatio = 1.6f
        for (k in 0 until n) {
            var mine = 0f
            var rest = 0f
            var restN = 0
            for (i in 0 until n) for (j in i + 1 until n) {
                if (i == k || j == k) mine += pairs[i][j] else { rest += pairs[i][j]; restN++ }
            }
            mine /= (n - 1)
            if (restN == 0 || rest <= 0f) continue
            val ratio = mine / (rest / restN)
            if (ratio > worstRatio) { worstRatio = ratio; worst = k }
        }
        return worst
    }

    /**
     * How far [w] is from the wake word: the mean of its two nearest recordings, each compared through the louder of
     * the two backgrounds (a word said on the motorway and one recorded in a quiet garage are seen through the same
     * road noise), relative to [spread]. Tried on recordings: "عمري" 0.6-0.95 and other words 1.4 and more, whether
     * it was recorded or said in quiet or in loud road noise.
     */
    fun score(w: Spectrum): Float {
        if (words.isEmpty() || spread <= 0f) return Float.MAX_VALUE
        // a word of a very different length is something else
        if (words.none { val r = w.frames.toFloat() / it.frames; r in 0.6f..1.7f }) return Float.MAX_VALUE
        val common = FloatArray(Features.MELS)
        val d = words.map { t ->
            for (m in common.indices) common[m] = max(w.noise[m], t.noise[m])
            Dtw.distance(Features.cepstra(w, common), Features.cepstra(t, common))
        }.sorted()
        val best = if (d.size >= 2) (d[0] + d[1]) / 2 else d[0]
        return best / spread
    }

    companion object {
        private const val TAG = "AuraAmri"
        private const val MAGIC = 0x414D5232 // "AMR2": spectra (the first test builds kept cepstra)

        fun file(ctx: Context) = File(ctx.filesDir, "amri-wake.bin")

        fun load(ctx: Context): WakeModel? = try {
            val f = file(ctx)
            if (!f.isFile) null else DataInputStream(f.inputStream().buffered()).use { inp ->
                if (inp.readInt() != MAGIC) { f.delete(); return null } // an older kind: to be recorded again
                val count = inp.readInt()
                val words = (0 until count).map {
                    val frames = inp.readInt()
                    val mels = inp.readInt()
                    val bands = Array(frames) { FloatArray(mels) { inp.readFloat() } }
                    Spectrum(bands, FloatArray(mels) { inp.readFloat() })
                }
                WakeModel(words).takeIf { it.words.isNotEmpty() }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "wake model: $t")
            null
        }

        /** Saved (false: not, e.g. the storage is full). */
        fun save(ctx: Context, words: List<Spectrum>): Boolean = try {
            val f = file(ctx)
            val tmp = File(f.path + ".tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(words.size)
                for (w in words) {
                    out.writeInt(w.frames)
                    out.writeInt(Features.MELS)
                    for (fr in w.bands) for (v in fr) out.writeFloat(v)
                    for (v in w.noise) out.writeFloat(v)
                }
            }
            tmp.renameTo(f).also { if (!it) tmp.delete() }
        } catch (t: Throwable) {
            Log.w(TAG, "wake model save: $t")
            try { File(file(ctx).path + ".tmp").delete() } catch (_: Throwable) { }
            false
        }

        fun clear(ctx: Context) { file(ctx).delete() }
    }
}

/**
 * The microphone, read on its own thread at 16 kHz and passed to [Segmenter]. [device]: a chosen input (null: the
 * system's choice). Stops with [quit] (the thread ends after the current read).
 */
internal class MicLoop(
    private val device: AudioDeviceInfo?,
    private val segmenter: Segmenter,
    /** Recording (on this thread). */
    private val onStarted: (() -> Unit)? = null,
    private val onError: (String) -> Unit
) : Thread("amri-mic") {
    @Volatile private var running = true
    @Volatile private var rec: AudioRecord? = null

    /** Ends the recording now (a read that waits for the microphone returns), and the thread right after. */
    fun quit() {
        running = false
        try { rec?.stop() } catch (_: Throwable) { }
    }

    override fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val min = AudioRecord.getMinBufferSize(Features.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = try {
            // (a size under zero is an error code: the half second alone then)
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, Features.RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(min, Features.RATE / 2 * 2))
        } catch (t: Throwable) {
            onError("mic: $t"); return
        }
        try {
            if (r.state != AudioRecord.STATE_INITIALIZED) { onError("mic not initialised"); return }
            if (device != null) r.preferredDevice = device
            rec = r
            if (!running) return // quit while it was being made
            r.startRecording()
            if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) { if (running) onError("mic did not start"); return }
            onStarted?.invoke()
            val buf = ShortArray(Features.HOP * 4)
            while (running) {
                val n = r.read(buf, 0, buf.size)
                if (n < 0) { if (running) onError("mic read $n"); break }
                if (n > 0 && running) segmenter.feed(buf, n)
            }
        } catch (t: Throwable) {
            if (running) onError("mic: $t")
        } finally {
            rec = null
            try { r.stop() } catch (_: Throwable) { }
            r.release()
        }
    }
}
