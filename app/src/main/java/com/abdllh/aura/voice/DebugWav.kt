package com.abdllh.aura.voice

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * Debug builds: the wake word fed from WAV files (16 kHz, 16-bit mono) through the same word splitting, features and
 * comparison as the microphone, so the emulator can be tested with recordings.
 */
internal object DebugWav {
    private const val TAG = "AuraAmri"

    private fun words(path: String): List<Pair<ShortArray, ShortArray>> {
        val b = try { File(path).readBytes() } catch (t: Throwable) { Log.i(TAG, "wav $path: $t"); return emptyList() }
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        // the "data" chunk
        var p = 12
        while (p + 8 <= b.size) {
            val id = String(b, p, 4, Charsets.US_ASCII)
            val len = bb.getInt(p + 4)
            if (id == "data") {
                val n = minOf(len, b.size - p - 8) / 2
                val pcm = ShortArray(n) { bb.getShort(p + 8 + it * 2) }
                val out = ArrayList<Pair<ShortArray, ShortArray>>()
                val seg = Segmenter(maxMs = 1600) { w, noise -> out.add(w to noise) }
                seg.feed(pcm, pcm.size)
                seg.feed(ShortArray(Features.RATE / 2), Features.RATE / 2) // a pause to end the last word
                return out
            }
            p += 8 + len + (len and 1)
        }
        return emptyList()
    }

    fun enroll(c: Context, files: List<String>) {
        val feats = files.mapNotNull { f -> words(f).maxByOrNull { it.first.size }?.let { Features.spectrum(it.first, it.second) } }
        Log.i(TAG, "enroll: ${feats.map { it.frames }} frames from ${files.size} files")
        if (feats.isNotEmpty()) WakeModel.save(c, feats)
        WakeModel.load(c)?.let { Log.i(TAG, "enroll: spread %.3f".format(Locale.ROOT, it.spread)) }
    }

    /** 1.5 s from the microphone: its level (all zeros: no sound reaches the app, e.g. an emulator without host audio). */
    fun micLevel(c: Context) {
        Thread {
            val buf = ShortArray(Features.RATE * 3 / 2)
            var got = 0
            var peak = 0
            var sum = 0.0
            try {
                val rec = android.media.AudioRecord(android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION, Features.RATE,
                    android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, Features.RATE)
                rec.startRecording()
                while (got < buf.size) { val n = rec.read(buf, got, buf.size - got); if (n <= 0) break; got += n }
                rec.stop(); rec.release()
            } catch (t: Throwable) { Log.i(TAG, "mic level: $t"); return@Thread }
            for (i in 0 until got) { val v = buf[i].toInt(); peak = maxOf(peak, kotlin.math.abs(v)); sum += v.toDouble() * v }
            val rms = if (got > 0) kotlin.math.sqrt(sum / got) else 0.0
            Log.i(TAG, "mic level: $got samples, peak $peak, rms %.1f (%.1f dBFS)".format(Locale.ROOT, rms, 20 * kotlin.math.log10(rms / 32768 + 1e-9)))
        }.start()
    }

    /**
     * [ms] of the microphone as the wake word listener hears it (which is off meanwhile), saved as files/[name].wav
     * (16 kHz mono): to look on a PC at what reached the app.
     */
    fun record(c: Context, name: String, ms: Int) {
        Amri.enrolling = true
        AmriService.pauseMic()
        Thread {
            val buf = ShortArray(Features.RATE / 1000 * ms)
            var got = 0
            try {
                val min = android.media.AudioRecord.getMinBufferSize(Features.RATE, android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT)
                val rec = android.media.AudioRecord(android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION, Features.RATE,
                    android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, maxOf(min, Features.RATE / 2 * 2))
                rec.startRecording()
                while (got < buf.size) { val n = rec.read(buf, got, minOf(Features.HOP * 4, buf.size - got)); if (n <= 0) break; got += n }
                rec.stop(); rec.release()
                val out = ByteBuffer.allocate(44 + got * 2).order(ByteOrder.LITTLE_ENDIAN)
                out.put("RIFF".toByteArray()).putInt(36 + got * 2).put("WAVE".toByteArray())
                out.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(Features.RATE).putInt(Features.RATE * 2)
                    .putShort(2).putShort(16)
                out.put("data".toByteArray()).putInt(got * 2)
                for (i in 0 until got) out.putShort(buf[i])
                val f = File(c.filesDir, "$name.wav")
                f.writeBytes(out.array())
                Log.i(TAG, "rec: $got samples -> $f")
            } catch (t: Throwable) {
                Log.i(TAG, "rec: $t")
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post { Amri.enrolling = false; AmriService.resumeMic() }
        }.start()
    }

    fun detect(c: Context, files: List<String>) {
        val m = WakeModel.load(c) ?: run { Log.i(TAG, "detect: no model"); return }
        for (f in files) {
            val ws = words(f)
            val scores = ws.map { m.score(Features.spectrum(it.first, it.second)) }
            val best = scores.minOrNull()
            Log.i(TAG, "detect ${File(f).name}: ${ws.size} word(s) ${ws.map { it.first.size * 1000 / Features.RATE }} ms, " +
                "score ${best?.let { "%.2f".format(Locale.ROOT, it) } ?: "-"} -> ${if (best != null && best < Amri.threshold()) "WAKE" else "no"}")
        }
    }
}
