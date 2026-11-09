package com.abdllh.aura.system

import android.content.Context
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.LoudnessEnhancer
import android.util.Log
import org.json.JSONObject

/**
 * The digital boost above the firmware's top: [VolumeRules.BOOST_STEPS] steps of [DB_PER_STEP] dB on Android's output mix
 * (session 0, the output the music plays on), so 41..50 are louder than 40 while the amplifier stays at its top.
 *
 * LoudnessEnhancer (a gain with its own soft compressor, so the loud parts stay under full scale), and when it cannot attach,
 * DynamicsProcessing (API 28) with only its input gain and a gentle limiter at [LIMIT_DB]. The effect exists only while the boost
 * is above 0; at 0 it is released.
 *
 * What it can raise is what goes through Android's mixer: AMRI's own music, the apps, a Bluetooth stream the firmware plays through
 * Android. Sound the MCU routes past Android (the radio, a source on the audio IC's own inputs) is not reached.
 * MODIFY_AUDIO_SETTINGS is in the manifest (session 0 needs it).
 */
object VolumeBoost {
    private const val TAG = "AuraVolume"
    const val DB_PER_STEP = 0.8f
    private const val LIMIT_DB = -1f
    private const val PRIORITY = 1000 // above the normal 0: the boost keeps control of its engine
    private const val PREFS = "aura_volume" // a file of its own: no key of the old files changes (PrefTypes)
    private const val KEY = "boost"

    private var dp: DynamicsProcessing? = null
    private var le: LoudnessEnhancer? = null
    @Volatile var steps = 0
        private set
    @Volatile var kind = "none"
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile private var createdAt = 0L

    fun saved(c: Context): Int = try {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, 0)
    } catch (_: Throwable) {
        0
    }

    /** The boost to [n] steps (0 releases the effect); saved for the next start. Main thread. */
    fun apply(c: Context, n: Int) {
        val k = n.coerceIn(0, VolumeRules.BOOST_STEPS)
        steps = k
        try { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, k).apply() } catch (_: Throwable) { }
        if (k <= 0) { release(); return }
        val db = k * DB_PER_STEP
        if (!gain(db)) { release(); create(db) }
    }

    fun gainDb(): Float = steps * DB_PER_STEP

    /** The gain the effect's engine holds now, read back from it (dB); null when there is no effect. */
    fun readBack(): Float? = try {
        dp?.getInputGainByChannelIndex(0) ?: le?.targetGain?.div(100f)
    } catch (t: Throwable) {
        null
    }

    /**
     * LoudnessEnhancer first: a gain and a soft compressor in the time domain, cheap on the A53s. beta.4 put DynamicsProcessing
     * first, with its frequency-resolution variant and a 10 ms frame: on the unit the sound cut in and out as soon as the boost
     * came on (his report), the mixer thread missing its period. DynamicsProcessing stays the fallback, in its time-resolution
     * variant at its default frame, and with a gentler limiter, so it neither costs that much nor pumps.
     */
    private fun create(db: Float) {
        lastError = null
        try {
            val l = LoudnessEnhancer(0)
            l.setTargetGain(Math.round(db * 100))
            l.enabled = true
            le = l
            kind = "loudness"
            createdAt = System.currentTimeMillis()
            Log.i(TAG, "boost: LoudnessEnhancer on session 0, id ${idOf(l)}, +$db dB")
            return
        } catch (t: Throwable) {
            lastError = "loudness: $t"
            Log.w(TAG, "boost: LoudnessEnhancer failed: $t")
            try { le?.release() } catch (_: Throwable) { }
            le = null
        }
        try {
            val cfg = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION, 2,
                false, 0, false, 0, false, 0, true
            ).build()
            val d = DynamicsProcessing(PRIORITY, 0, cfg)
            d.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 5f, 200f, 4f, LIMIT_DB, 0f))
            d.setInputGainAllChannelsTo(db)
            d.enabled = true
            dp = d
            kind = "dynamics"
            createdAt = System.currentTimeMillis()
            Log.i(TAG, "boost: DynamicsProcessing on session 0, id ${idOf(d)}, +$db dB")
        } catch (t: Throwable) {
            lastError = (lastError ?: "") + "; dynamics: $t"
            Log.w(TAG, "boost: DynamicsProcessing failed too: $t")
            try { dp?.release() } catch (_: Throwable) { }
            dp = null
            kind = "none"
        }
    }

    /** The new gain on the effect that is there; false when there is none or it no longer answers (then it is made again). */
    private fun gain(db: Float): Boolean {
        dp?.let { d ->
            return try {
                d.setInputGainAllChannelsTo(db)
                if (!d.enabled) d.enabled = true
                true
            } catch (t: Throwable) {
                Log.w(TAG, "boost: gain on DynamicsProcessing: $t")
                false
            }
        }
        le?.let { l ->
            return try {
                l.setTargetGain(Math.round(db * 100))
                if (!l.enabled) l.enabled = true
                true
            } catch (t: Throwable) {
                Log.w(TAG, "boost: gain on LoudnessEnhancer: $t")
                false
            }
        }
        return false
    }

    fun release() {
        val had = dp != null || le != null
        try { dp?.release() } catch (_: Throwable) { }
        try { le?.release() } catch (_: Throwable) { }
        dp = null
        le = null
        kind = "none"
        if (had) Log.i(TAG, "boost: effect released")
    }

    private fun idOf(e: AudioEffect): Int = try { e.id } catch (_: Throwable) { -1 }

    /** For the Screen check and the log: what is attached and what it reads back. */
    fun state(): JSONObject {
        val o = JSONObject().put("steps", steps).put("gainDb", gainDb().toDouble()).put("kind", kind).put("dbPerStep", DB_PER_STEP.toDouble())
        lastError?.let { o.put("error", it) }
        dp?.let { d ->
            try {
                o.put("id", d.id).put("enabled", d.enabled).put("hasControl", d.hasControl())
                o.put("inputGainCh0", d.getInputGainByChannelIndex(0).toDouble()).put("channels", d.channelCount)
                val lim = d.getLimiterByChannelIndex(0)
                o.put("limiter", JSONObject().put("enabled", lim.isEnabled).put("thresholdDb", lim.threshold.toDouble()).put("ratio", lim.ratio.toDouble()))
            } catch (t: Throwable) { o.put("readBack", t.toString()) }
        }
        le?.let { l ->
            try { o.put("id", l.id).put("enabled", l.enabled).put("hasControl", l.hasControl()).put("targetGainMb", l.targetGain.toDouble()) } catch (t: Throwable) { o.put("readBack", t.toString()) }
        }
        if (createdAt > 0) o.put("createdAt", createdAt)
        return o
    }
}
