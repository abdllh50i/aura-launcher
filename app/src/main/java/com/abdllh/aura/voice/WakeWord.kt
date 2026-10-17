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

    /**
     * How far the word [s] stands over its background in the voice's band (mel bands 4..20, about 500 Hz - 4 kHz),
     * dB: the louder half of its frames (the background taken off them already) against the background before it.
     */
    fun snr(s: Spectrum): Float {
        if (s.frames == 0) return 0f
        val e = FloatArray(s.frames) { f -> var x = 0f; for (m in 4..20) x += s.bands[f][m]; x }
        e.sort()
        var top = 0.0
        for (i in e.size / 2 until e.size) top += e[i]
        top /= (e.size - e.size / 2)
        var n = 0.0
        for (m in 4..20) n += s.noise[m]
        return (10 * log10((top + 1e-3) / (n + 1e-3))).toFloat()
    }

    /** In-place radix-2 FFT ([re] and [im] of power-of-two length). */
    internal fun fft(re: FloatArray, im: FloatArray) {
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

/**
 * Is a sound a voice: mostly voiced (a pitch between 70 and 400 Hz: the vocal cords) and with its energy in the
 * voice's band (250 Hz - 4 kHz: the vowels' resonances)? A bump in the road is a low thump or a ringing panel (all
 * below 250 Hz) or a rattle or a bang (no pitch); "عمري" is voiced from end to end. Tried on recordings and synthetic
 * bumps: "عمري" 0.87-1.0 voiced with 0.66-0.80 of its energy in the band, quiet or in road noise; thumps, double bumps
 * and panel rings 0.00-0.02 in the band; rattles and bangs 0.00-0.21 voiced.
 */
internal object VoiceCheck {
    private const val WIN = 400        // 25 ms
    private const val HOP = 160
    private const val LAG_MIN = 40     // 400 Hz
    private const val LAG_MAX = 228    // 70 Hz
    private const val NFFT = 512
    private val hann = FloatArray(WIN) { (0.5 - 0.5 * kotlin.math.cos(2 * PI * it / (WIN - 1))).toFloat() }

    /** The power spectrum of the frame [x], Hann-windowed, into [out] (bins 0..128: up to 4 kHz). */
    private fun power(x: DoubleArray, re: FloatArray, im: FloatArray, out: DoubleArray) {
        for (i in 0 until NFFT) { re[i] = if (i < WIN) (x[i] * hann[i]).toFloat() else 0f; im[i] = 0f }
        Features.fft(re, im)
        for (k in 0..128) out[k] = re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
    }

    /**
     * The share of voiced frames in the word's body, and the share of the voiced frames' own energy (the road's, from
     * [noise] just before the word, taken off: a car's rumble sits right below 250 Hz) in 250 Hz-4 kHz.
     */
    fun measure(pcm: ShortArray, noise: ShortArray? = null): Pair<Float, Float> {
        val n = if (pcm.size < WIN) 0 else 1 + (pcm.size - WIN) / HOP
        if (n == 0) return 0f to 0f
        val re = FloatArray(NFFT)
        val im = FloatArray(NFFT)
        val p = DoubleArray(129)
        // the background's spectrum
        val bg = DoubleArray(129)
        val nz = noise?.takeIf { it.size >= WIN }
        if (nz != null) {
            val nf = 1 + (nz.size - WIN) / HOP
            val y = DoubleArray(WIN)
            for (f in 0 until nf) {
                var mean = 0.0
                for (i in 0 until WIN) { y[i] = nz[f * HOP + i].toDouble(); mean += y[i] }
                mean /= WIN
                for (i in 0 until WIN) y[i] -= mean
                power(y, re, im, p)
                for (k in 0..128) bg[k] += p[k] / nf
            }
        }
        val energy = DoubleArray(n) { f -> var e = 0.0; for (i in 0 until WIN) { val v = pcm[f * HOP + i].toDouble(); e += v * v }; e }
        val loud = energy.maxOrNull()!! * 0.25 // the word's body, not its fading edges
        val x = DoubleArray(WIN)
        var body = 0
        var voiced = 0
        var band = 0.0
        var all = 0.0
        for (f in 0 until n) {
            if (energy[f] < loud) continue
            body++
            var mean = 0.0
            for (i in 0 until WIN) { x[i] = pcm[f * HOP + i].toDouble(); mean += x[i] }
            mean /= WIN
            for (i in 0 until WIN) x[i] -= mean
            // the strongest normalised autocorrelation over the pitch lags
            var best = 0.0
            for (k in LAG_MIN..LAG_MAX) {
                var xy = 0.0; var xx = 0.0; var yy = 0.0
                for (i in 0 until WIN - k) { val a = x[i]; val b = x[i + k]; xy += a * b; xx += a * a; yy += b * b }
                val r = xy / (sqrt(xx * yy) + 1e-9)
                if (r > best) best = r
            }
            if (best <= 0.45) continue
            voiced++
            power(x, re, im, p)
            for (k in 2..128) { // 62.5 Hz .. 4 kHz (31.25 Hz a bin)
                // the background three times over: a rumble's spectrum swings around its mean from frame to frame,
                // and what is left of it once is still far more than a voice's lows
                val own = (p[k] - 3.0 * bg[k]).coerceAtLeast(0.0)
                all += own
                if (k >= 8) band += own // from 250 Hz
            }
        }
        return (if (body == 0) 0f else voiced.toFloat() / body) to (if (all <= 0.0) 0f else (band / all).toFloat())
    }

    /**
     * A voice: at least 15% of its own energy in the band (the bumps' gate: thumps, double bumps and ringing panels
     * measured 0-2% quiet, in road noise and in a loud low rumble; "عمري" 66% and more, and still 28-63% under a rumble
     * louder than the voice), and at least 25% voiced (bangs and clicks 0%; rattles reach 30% in a loud rumble, and are
     * refused by the comparison with the recordings anyway: 1.65-2.49). A voice in a loud rumble is 38-50% voiced.
     */
    fun isVoice(pcm: ShortArray, noise: ShortArray? = null): Boolean = measure(pcm, noise).let { (v, b) -> ok(v, b) }

    fun ok(voiced: Float, band: Float) = voiced >= 0.25f && band >= 0.15f
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

    /**
     * The same alignment, and the cost of each step along it (from the words' starts to their ends): where two words
     * differ, not only how much. Kept whole (n x m), so only for the few words that come close to the recordings.
     */
    fun path(a: Array<FloatArray>, b: Array<FloatArray>): FloatArray {
        val n = a.size
        val m = b.size
        if (n == 0 || m == 0) return FloatArray(0)
        val band = max(abs(n - m), (max(n, m) * 0.3f).toInt()) + 1
        val inf = Float.MAX_VALUE / 4
        val d = Array(n + 1) { FloatArray(m + 1) { inf } }
        val c = Array(n + 1) { FloatArray(m + 1) }
        d[0][0] = 0f
        for (i in 1..n) {
            val jc = (i.toLong() * m / n).toInt()
            val x = a[i - 1]
            for (j in max(1, jc - band)..min(m, jc + band)) {
                val y = b[j - 1]
                var s = 0f
                for (k in x.indices) { val t = x[k] - y[k]; s += t * t }
                val cost = sqrt(s)
                c[i][j] = cost
                d[i][j] = cost + min(d[i - 1][j - 1], min(d[i - 1][j], d[i][j - 1]))
            }
        }
        val out = FloatArray(n + m)
        var k = 0
        var i = n
        var j = m
        while (i > 0 && j > 0) {
            out[k++] = c[i][j]
            val diag = d[i - 1][j - 1]
            val up = d[i - 1][j]
            val left = d[i][j - 1]
            if (diag <= up && diag <= left) { i--; j-- } else if (up <= left) i-- else j--
        }
        out.reverse(0, k)
        return out.copyOf(k)
    }
}

/** Takes the microphone's sound as it comes ([MicLoop] reads into it). */
internal fun interface SoundSink {
    fun feed(buf: ShortArray, n: Int)
}

/**
 * The voice's band, 500 Hz - 3 kHz (Butterworth, 2nd order band-pass: two biquads, coefficients for 16 kHz from
 * scipy.signal.butter(2, [500, 3000], "band", fs=16000, output="sos")), sample by sample (transposed direct form II).
 */
internal class VoiceBand {
    private var a1 = 0.0
    private var a2 = 0.0
    private var b1 = 0.0
    private var b2 = 0.0

    fun next(x: Double): Double {
        val y = 0.13993928686685161 * x + a1
        a1 = 0.27987857373370323 * x + 0.6282667885001253 * y + a2
        a2 = 0.13993928686685161 * x - 0.33247170708425344 * y
        val z = y + b1
        b1 = -2.0 * y + 1.738073384438362 * z + b2
        b2 = y - 0.7805030329242779 * z
        return z
    }
}

/**
 * Splits the microphone's sound into words. The background is the quietest moment of the last 1.5 s, past a few odd
 * hops (a car's road noise, a fan: it follows them up and down within that time); a word starts when the level rises
 * 10 dB over it, and ends after 0.2 s that are back near the background or 24 dB under the word's own peak (a fading
 * tail is not the word). Words longer than [maxMs] are not passed on (sentences, music). [onLevel]: the level over the
 * background, 0..1, for meters.
 *
 * The level is a first difference's (it takes away the road's rumble). [band] (the wake word's second listener, 1.13)
 * follows the voice's band ([VoiceBand]) beside it: a word also starts when that rises 10 dB over its own floor, and
 * goes on while every level that rose like a word for it (10 dB, 3 hops) is still up. On the motorway a voice stands
 * 4-5 dB higher over the noise in that band than in the first difference: "عمري" said 5 dB(A) over the road was found
 * in 44-61% of the tries by the first difference alone and in nearly all by the band (Python copy, synthetic road).
 * In quiet both levels rise together and the word ends where the first difference ends it (the owner's recordings
 * were cut that way); with music the band stays up longer, which is why the plain listener keeps running beside it.
 */
internal class Segmenter(
    private val maxMs: Int = 1600,
    private val onLevel: ((Float) -> Unit)? = null,
    /** Hops of quiet that end a word (a request of two words takes longer pauses than the wake word). */
    private val endHops: Int = END_HOPS,
    /** Also follow the voice's band (see the class). */
    private val band: Boolean = false,
    /** A word, and the background just before it (for [Features.spectrum]'s noise); [info] describes it meanwhile. */
    private val onWord: (word: ShortArray, noise: ShortArray) -> Unit
) : SoundSink {
    /** The word being passed to [onWord]: where it is in the stream and how what came before it stood. */
    class Info {
        /** Sample positions (since the first sample this listener got). */
        var start = 0L
        var end = 0L
        /** The background before the word: the 4th quietest level of the 1.5 s before it (dB, first difference). */
        var floor = 0f
        /** How far the 0.3 s before the word (the "background" it is measured against) stood over that, mean dB. */
        var preRise = 0f
    }

    val info = Info()

    private val ring = ShortArray(Features.RATE * 4)
    private var written = 0L          // samples ever written to the ring
    private val hop = ShortArray(Features.HOP)
    private var hopFill = 0
    private val history = FloatArray(HISTORY) // the last hops' levels (dB)
    private val sorted = FloatArray(HISTORY)
    private val levels = FloatArray(LEVELS)   // every hop's level by its number, for [Info]
    private var hops = 0L
    private var settle = 0
    private var above = 0
    private var below = 0
    private var inWord = false
    private var start = 0L
    private var peak = 0f
    // the voice's band (with [band])
    private val filter = if (band) VoiceBand() else null
    private var bandEnergy = 0.0
    private val history2 = if (band) FloatArray(HISTORY) else null
    private val sorted2 = if (band) FloatArray(HISTORY) else null
    private var above2 = 0
    private var peak2 = 0f
    private var use1 = true                   // the levels that end this word
    private var use2 = false

    override fun feed(buf: ShortArray, n: Int) {
        for (i in 0 until n) {
            ring[(written % ring.size).toInt()] = buf[i]
            written++
            hop[hopFill++] = buf[i]
            if (filter != null) { val y = filter.next(buf[i].toDouble()); bandEnergy += y * y }
            if (hopFill == hop.size) { hopFill = 0; step(); bandEnergy = 0.0 }
        }
    }

    private var last = 0f

    private fun step() {
        // the level of the voice's frequencies: a first difference takes away the road's rumble (low, and loud)
        var e = 0.0
        for (s in hop) { val v = s - 0.95f * last; last = s.toFloat(); e += v.toDouble() * v }
        val db = (10 * log10(e / hop.size + 1.0)).toFloat()
        val db2 = if (filter != null) (10 * log10(bandEnergy / hop.size + 1.0)).toFloat() else 0f
        levels[((written / Features.HOP - 1) % LEVELS).toInt()] = db
        // a recording that is only starting gives near silence for a moment (half a second on the emulator)
        if (hops == 0L && db < SILENT_DB && settle++ < SETTLE_HOPS) return
        history[(hops % HISTORY).toInt()] = db
        history2?.set((hops % HISTORY).toInt(), db2)
        hops++
        // the quietest moment, past a few odd hops (the one half filled as the sound starts, a stream's dropout): they
        // would hold the background too low for 1.5 s, and the next sound would look like a word
        val n = min(hops, HISTORY.toLong()).toInt()
        System.arraycopy(history, 0, sorted, 0, n)
        java.util.Arrays.sort(sorted, 0, n)
        val floor = max(sorted[min(OUTLIERS, n - 1)], 15f)
        var floor2 = 0f
        if (history2 != null && sorted2 != null) {
            System.arraycopy(history2, 0, sorted2, 0, n)
            java.util.Arrays.sort(sorted2, 0, n)
            floor2 = max(sorted2[min(OUTLIERS, n - 1)], 15f)
        }
        onLevel?.invoke(((db - floor) / 30f).coerceIn(0f, 1f))
        if (hops < WARMUP) return // the background is not known yet
        if (!inWord) {
            if (db > floor + 10f) above++ else above = 0
            if (filter != null) { if (db2 > floor2 + 10f) above2++ else above2 = 0 }
            if (above >= 3 || above2 >= 3) {
                inWord = true
                below = 0
                peak = db
                peak2 = db2
                use1 = above >= 3
                use2 = above2 >= 3
                start = max(written - (3 + PRE_HOPS) * Features.HOP, written - ring.size + Features.HOP)
            }
            return
        }
        peak = max(peak, db)
        peak2 = max(peak2, db2)
        val down = if (filter == null) {
            db < max(floor + 6f, peak - 24f)
        } else {
            // a level counts for the word's end once it has risen like a word in it
            if (!use1) { above = if (db > floor + 10f) above + 1 else 0; use1 = above >= 3 }
            if (!use2) { above2 = if (db2 > floor2 + 10f) above2 + 1 else 0; use2 = above2 >= 3 }
            (!use1 || db < max(floor + 6f, peak - 24f)) && (!use2 || db2 < max(floor2 + 6f, peak2 - 24f))
        }
        if (down) below++ else below = 0
        val len = written - start
        if (below >= endHops) {
            inWord = false
            above = 0
            above2 = 0
            val end = written - (below - POST_HOPS) * Features.HOP
            val n = (end - start).toInt()
            if (n in MIN_SAMPLES..(maxMs * Features.RATE / 1000)) {
                val word = ShortArray(n)
                for (i in 0 until n) word[i] = ring[((start + i) % ring.size).toInt()]
                // the background before it (what the ring still holds of it)
                val from = max(start - NOISE_SAMPLES, max(0L, written - ring.size))
                val noise = ShortArray((start - from).toInt().coerceAtLeast(0))
                for (i in noise.indices) noise[i] = ring[((from + i) % ring.size).toInt()]
                describe(start, end, from)
                onWord(word, noise)
            }
        } else if (len > maxMs * Features.RATE / 1000 + endHops * Features.HOP) {
            // longer than a word can be (a sentence, music): not passed on; listen again from here
            inWord = false
            above = 0
            above2 = 0
        }
    }

    /** [info] for the word from [start] to [end], its background from [from]. */
    private fun describe(start: Long, end: Long, from: Long) {
        info.start = start
        info.end = end
        val h0 = start / Features.HOP
        val lo = max(0L, h0 - HISTORY)
        val k = (h0 - lo).toInt()
        info.floor = if (k <= 0) 15f else {
            val tmp = FloatArray(k) { levels[((lo + it) % LEVELS).toInt()] }
            tmp.sort()
            max(tmp[min(OUTLIERS, k - 1)], 15f)
        }
        val h1 = from / Features.HOP
        var sum = 0.0
        for (h in h1 until h0) sum += levels[(h % LEVELS).toInt()]
        info.preRise = if (h0 > h1) (sum / (h0 - h1) - info.floor).toFloat() else 0f
    }

    companion object {
        private const val HISTORY = 150   // 1.5 s
        private const val LEVELS = 512    // 5.1 s of levels (the ring holds 4 s)
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
        if (n < 3) return -1 // (three: the odd one is far from both others, which are close to each other)
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
    fun score(w: Spectrum): Float = score(w, null)

    /** [score] from [d], [w]'s [distances] when they are known already. */
    fun score(w: Spectrum, d: FloatArray?): Float {
        if (words.isEmpty() || spread <= 0f) return Float.MAX_VALUE
        // a word of a very different length is something else
        if (!lengthOk(w)) return Float.MAX_VALUE
        val s = (d ?: distances(w)).sortedArray()
        val best = if (s.size >= 2) (s[0] + s[1]) / 2 else s[0]
        return best / spread
    }

    private fun lengthOk(w: Spectrum) = words.any { val r = w.frames.toFloat() / it.frames; r in 0.6f..1.7f }

    /** [w]'s distance to each recording, through the louder of the two backgrounds (DTW units, in their order). */
    fun distances(w: Spectrum): FloatArray {
        val common = FloatArray(Features.MELS)
        return FloatArray(words.size) { i ->
            val t = words[i]
            for (m in common.indices) common[m] = max(w.noise[m], t.noise[m])
            Dtw.distance(Features.cepstra(w, common), Features.cepstra(t, common))
        }
    }

    /**
     * The recordings' own mean distance seen through [noise] too (pair by pair, the loudest of it and the two
     * recordings' backgrounds). Loud noise hides most of what tells words apart: through it the recordings come closer
     * together, and so does everything else; [spread] alone (their distance through their own, quieter backgrounds)
     * then makes any word in the noise look like the wake word.
     */
    fun spreadThrough(noise: FloatArray): Float {
        var s = 0f
        var n = 0
        val common = FloatArray(Features.MELS)
        for (i in words.indices) for (j in i + 1 until words.size) {
            for (m in common.indices) common[m] = max(noise[m], max(words[i].noise[m], words[j].noise[m]))
            s += Dtw.distance(Features.cepstra(words[i], common), Features.cepstra(words[j], common))
            n++
        }
        return if (n == 0) 0f else s / n
    }

    /**
     * The worst fifth of [w]'s alignment with the recording [i] (the mean step cost over it), relative to [spread]:
     * a word that is the wake word in most of its length and something else in one part ("عمرين", a sentence's
     * piece) is far there, however close it is on average.
     */
    fun worstPart(w: Spectrum, i: Int): Float {
        val t = words[i]
        val common = FloatArray(Features.MELS) { max(w.noise[it], t.noise[it]) }
        val costs = Dtw.path(Features.cepstra(w, common), Features.cepstra(t, common))
        if (costs.isEmpty() || spread <= 0f) return Float.MAX_VALUE
        val k = max(1, costs.size / 5)
        var sum = 0.0
        for (x in 0 until k) sum += costs[x]
        var worst = sum
        for (x in k until costs.size) {
            sum += costs[x] - costs[x - k]
            if (sum > worst) worst = sum
        }
        return (worst / k / spread).toFloat()
    }

    /**
     * Is [w] (the word [pcm], its background [noise], [info] from the listener) the wake word? Stage 1 is [score]
     * (cheap: six alignments, every word). The few words that come close then have to pass the rest, and every cue
     * was measured on the test corpus (TTS voices enrolled like the owner, near words, sentences, songs, car sounds
     * on road, idle, city and music backgrounds; docs/notes-1.13-amri.md):
     * - [VoiceCheck]: a voice at all (bumps, bangs, clicks).
     * - The score again with the recordings seen through the word's own background ([spreadThrough]): on the
     *   motorway and with music, words that were not the wake word scored as close as the wake word itself (masking);
     *   relative to how close the recordings themselves come through that noise, they stand apart again.
     * - The worst part of the alignment ([worstPart]).
     * - Context: a word barely over its "background" (under 6 dB in the voice band) while that background stood
     *   well over the listener's floor (over 4 dB) is a piece of continuous speech or song; the owner says the name
     *   after a pause.
     * Rejections say why ([Verdict.why]); [lim] holds the sensitivity's limits.
     */
    fun judge(w: Spectrum, pcm: ShortArray, noise: ShortArray, info: Segmenter.Info?, lim: Limits): Verdict {
        if (words.isEmpty() || spread <= 0f || !lengthOk(w)) return Verdict(Float.MAX_VALUE, why = "length")
        val d = distances(w)
        val score = score(w, d)
        var nearest = 0
        for (i in d.indices) if (d[i] < d[nearest]) nearest = i
        if (score >= lim.score) return Verdict(score, d[nearest], why = "far")
        val (voiced, band) = VoiceCheck.measure(pcm, noise)
        if (!VoiceCheck.ok(voiced, band)) return Verdict(score, d[nearest], voiced = voiced, band = band, why = "not a voice")
        val s = d.sortedArray()
        val through = spreadThrough(w.noise)
        val masked = if (through > 0f) (if (s.size >= 2) (s[0] + s[1]) / 2 else s[0]) / through else Float.MAX_VALUE
        val worst = worstPart(w, nearest)
        val snr = Features.snr(w)
        val rise = info?.preRise ?: 0f
        val why = when {
            masked >= lim.masked -> "background"
            worst > lim.worst -> "part"
            snr < Limits.CONTEXT_SNR && rise > Limits.CONTEXT_RISE -> "mid-speech"
            else -> ""
        }
        return Verdict(score, d[nearest], masked, worst, snr, rise, voiced, band, why)
    }

    companion object {
        private const val TAG = "AuraAmri"
        private const val MAGIC = 0x414D5232 // "AMR2": spectra (the first test builds kept cepstra)

        fun file(ctx: Context) = File(ctx.filesDir, "amri-wake.bin")

        fun load(ctx: Context): WakeModel? = load(file(ctx))

        /** The owner's recordings in [f] (the wake word's, or a request's taught for use without the internet). */
        fun load(f: File): WakeModel? = try {
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
        fun save(ctx: Context, words: List<Spectrum>): Boolean = save(file(ctx), words)

        fun save(f: File, words: List<Spectrum>): Boolean = try {
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
            try { File(f.path + ".tmp").delete() } catch (_: Throwable) { }
            false
        }

        fun clear(ctx: Context) { file(ctx).delete() }
    }
}

/** How close a word must come, per sensitivity ([WakeModel.judge]). */
internal class Limits(
    /** Stage 1: [WakeModel.score] under this (until 1.13 the only test: 1.05 / 1.15 / 1.30). */
    val score: Float,
    /** The score with the recordings seen through the word's background ([WakeModel.spreadThrough]) under this. */
    val masked: Float,
    /** The worst fifth of the alignment ([WakeModel.worstPart]) at most this. */
    val worst: Float
) {
    companion object {
        val STRICT = Limits(1.05f, 1.40f, 2.85f)
        val NORMAL = Limits(1.15f, 1.50f, 3.0f)
        val RELAXED = Limits(1.30f, 1.65f, 3.3f)

        /** 0 strict, 1 normal, 2 relaxed (Settings). */
        fun of(sensitivity: Int) = when (sensitivity) { 0 -> STRICT; 2 -> RELAXED; else -> NORMAL }

        /** A word under this over its background (dB, voice band) ... */
        const val CONTEXT_SNR = 6f
        /** ... while that background stood this far over the floor (dB) is in the middle of speech. */
        const val CONTEXT_RISE = 4f
    }
}

/** What [WakeModel.judge] made of a word: its measures (for the log), and [why] it was refused ("" when it was not). */
internal class Verdict(
    val score: Float,
    /** The nearest recording's distance (DTW units): learned sounds are weighed against it ([WakeNegatives]). */
    val nearest: Float = Float.MAX_VALUE,
    val masked: Float = Float.NaN,
    val worst: Float = Float.NaN,
    val snr: Float = Float.NaN,
    val preRise: Float = Float.NaN,
    val voiced: Float = Float.NaN,
    val band: Float = Float.NaN,
    val why: String
) {
    val ok get() = why.isEmpty()

    override fun toString() = "score %.2f masked %.2f worst %.2f snr %.1f pre %.1f voiced %.2f band %.2f%s".format(
        java.util.Locale.ROOT, score, masked, worst, snr, preRise, voiced, band, if (ok) "" else " -> $why")
}

/**
 * A word that woke the assistant, kept until the owner shows whether he meant it ([WakeNegatives]); [start], [end]:
 * where it was in the listener's stream (samples).
 */
internal class WakeCandidate(val spectrum: Spectrum, val verdict: Verdict, val start: Long = 0, val end: Long = 0)

/**
 * The wake word in a stream of sound, on the microphone's thread: two listeners (the plain [Segmenter] and the one
 * that also follows the voice's band, which finds the name in road noise), every word they pass on judged by the
 * owner's recordings ([WakeModel.judge]) and then the sounds learned not to be it ([WakeNegatives]); 1.5 s at least
 * between two wakes. Both listeners cut most words alike (in quiet, always): such a word is judged once.
 */
internal class WakeDetector(
    private val model: WakeModel,
    private val limits: () -> Limits,
    /** The learned sounds' file (null: none). */
    private val negatives: java.io.File?,
    /** Every judged word (the log). */
    private val onVerdict: ((Verdict) -> Unit)? = null,
    private val onWake: (WakeCandidate) -> Unit
) : SoundSink {
    private val plain = Segmenter(maxMs = 1500) { w, n -> word(w, n, 0) }
    private val banded = Segmenter(maxMs = 1500, band = true) { w, n -> word(w, n, 1) }
    private var lastStart = -1L
    private var lastEnd = -1L
    private var lastWake = -REFRACTORY

    override fun feed(buf: ShortArray, n: Int) {
        plain.feed(buf, n)
        banded.feed(buf, n)
    }

    private fun word(pcm: ShortArray, noise: ShortArray, which: Int) {
        val info = if (which == 0) plain.info else banded.info
        if (info.end - lastWake < REFRACTORY) return
        // the other listener's cut of the same word (within 20 ms at both ends): judged already
        if (abs(info.start - lastStart) <= 2 * Features.HOP && abs(info.end - lastEnd) <= 2 * Features.HOP) return
        lastStart = info.start
        lastEnd = info.end
        val s = Features.spectrum(pcm, noise)
        val lim = limits()
        val v = model.judge(s, pcm, noise, info, lim)
        if (v.ok && negatives != null && WakeNegatives.rejects(negatives, s, v, lim)) {
            onVerdict?.invoke(Verdict(v.score, v.nearest, v.masked, v.worst, v.snr, v.preRise, v.voiced, v.band, "learned"))
            return
        }
        onVerdict?.invoke(v)
        if (!v.ok) return
        lastWake = info.end
        onWake(WakeCandidate(s, v, info.start, info.end))
    }

    companion object {
        private const val REFRACTORY = Features.RATE * 3L / 2 // 1.5 s
    }
}

/**
 * The microphone, read on its own thread at 16 kHz and passed to [sink] (a [Segmenter], a [WakeDetector]). [device]:
 * a chosen input (null: the system's choice). Stops with [quit] (the thread ends after the current read).
 */
internal class MicLoop(
    private val device: AudioDeviceInfo?,
    private val sink: SoundSink,
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
            MicTap.active = true
            val buf = ShortArray(Features.HOP * 4)
            while (running) {
                val n = r.read(buf, 0, buf.size)
                if (n < 0) { if (running) onError("mic read $n"); break }
                if (n > 0 && running) {
                    sink.feed(buf, n)
                    MicTap.listener?.invoke(buf, n, Features.RATE)
                }
            }
        } catch (t: Throwable) {
            if (running) onError("mic: $t")
        } finally {
            MicTap.active = false
            rec = null
            try { r.stop() } catch (_: Throwable) { }
            r.release()
        }
    }
}
