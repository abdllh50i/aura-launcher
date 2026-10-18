package com.abdllh.aura.music

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.util.Prefs
import com.abdllh.aura.voice.Amri
import com.abdllh.aura.voice.AmriService
import com.abdllh.aura.voice.MicTap
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

/**
 * Lyrics timed by listening (1.13, the owner's request): while a song plays on the music screen whose lyrics have no
 * times, the car's microphone hears it and what it hears goes, 12 s at a time, to the owner's own server
 * ([LyricsServer], tools/lyrics-server on his PC), which writes down the sung words with their times and matches them
 * to the lines. Since 1.16 also timed lyrics whose words have no real times (the word-by-word light is lit only by real
 * times): the server is given the lines' times as shown ("timed": [Song.times]) and works out only the words, each
 * line's around its own time; the words come back on the very lines on the screen. The sound goes nowhere else; the
 * server deletes it once the words are written down. A song from the unit's own storage is not listened to: its file is
 * sent once instead (the server decodes it the same way; in timed mode with the times).
 *
 * Only with the owner's switch on ([LyricsServer.listen], on by default since 1.15), a server set, such lyrics on the
 * screen, the song playing and its position known (a Bluetooth song's start seen or tapped: the server needs to know
 * where in the song each piece of sound was). Never while the assistant listens or answers, while Settings records the
 * wake word, in a call or while reversing — the rules the assistant's own listener keeps ([AmriService]). The
 * microphone is never opened twice: while the assistant is switched on its recording is lent to us ([MicTap]); only
 * when it is off does this record by itself, and it lets go the moment the assistant may need the microphone.
 *
 * Untimed lyrics: the server answers every piece with how much of the song is timed so far; from [SHOW_AT] on the screen
 * switches to the timing ([Lyrics.onTimed], kept by [Lyrics.keepAligned] so the next play is timed at once: the lines
 * already heard are timed by what was heard, the line being sung is the server's guess at the song's pace until the
 * next piece is worked on), and listening stops when the server says the song is done (nearly all of it timed, or
 * [DONE_AT] of it with no line still to come only guessed). Timed lyrics: the words heard go to the screen as they come,
 * and listening stops when the server says nearly all the lines have their words. Main thread, except [feed] (the
 * microphone's thread) and the uploads.
 */
object LyricsSync {
    private const val TAG = "AuraLyricsSync"
    /** The share of the lines timed from which the screen shows the timing (the server's own threshold too). */
    const val SHOW_AT = 0.7f
    /** ...and from which a timing by listening counts as complete (the next play does not listen again). */
    const val DONE_AT = 0.9f
    private const val RATE = 16000
    // 12 s a piece (1.16; 20 s before): the first words come back sooner, and a piece is worked on in a few seconds
    private const val CHUNK = RATE * 12
    private const val OVERLAP = RATE * 2         // the next starts 2 s before this one ends (a word cut in two)
    private const val MIN_PIECE = RATE * 6       // a piece cut short (pause, seek) is sent from 6 s on
    private const val JUMP_MS = 800L             // the song's clock moved by itself: a seek, a tap that set it
    private const val SILENT_PEAK = 32           // a piece that never goes over this (about -60 dBFS) is silence
    private const val START_WAIT_MS = 3000L      // the server's first answer waited for, at most, before hearing
    private const val MAX_FILE = 40L * 1024 * 1024
    private const val POLL_MS = 5000L

    /** How a piece was heard, as the server is told ([LyricsServer.syncChunk]'s `src`): the microphone. */
    const val SRC_MIC = "mic"

    /**
     * A song to time: as its lyrics were asked for ([durationSec] is the length the lookup used, often 0 for Bluetooth),
     * the lines the screen shows for it, the unit's own file when it plays from one, and its length when it is known
     * from anywhere ([lengthSec]: the server's alignment uses it a little).
     * Timed mode (1.16): [times], each shown line's time on the song's clock (the shown time minus the owner's
     * correction [offsetMs], null for a line before the clock's start); [shown], the lyrics as the screen shows them,
     * which the words heard are given to. Both null for untimed lyrics.
     */
    class Song(val title: String, val artist: String, val durationSec: Int, val lines: List<String>, val track: Track?, val lengthSec: Int = durationSec,
               val times: List<Long?>? = null, val shown: Lyrics.Result? = null, val offsetMs: Long = 0L) {
        /** The same listening (another correction moves every line on the song's clock: a new one). */
        fun same(o: Song) = title == o.title && artist == o.artist && durationSec == o.durationSec && lines == o.lines &&
            times == o.times && offsetMs == o.offsetMs
    }

    private class Job(val song: Song, val position: () -> Long?) {
        var serverId: String? = null        // (the upload thread's)
        @Volatile var covered = 0f
        var shownCovered = 0f
        /** The timing handed to the screen carried the server's word times (1.15). */
        var shownWords = false
        @Volatile var finished = false
        var fileAsked = false
        var polls = 0
        @Volatile var pending = 0
        /** Timed mode: the lines as last handed to the screen (the shown ones, with the words heard so far). */
        var timedLines: List<Lyrics.Line>? = null
        /** The server was asked about the song as listening began ([startEarly]), when, and whether it answered. */
        var startAsked = false
        var startAskedAt = 0L
        var answered = false
        /** When the words were last asked for apart ([askWords]), and whether that answer is still to come. */
        var wordsAskedAt = 0L
        var wordsAsking = false
    }

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    @Volatile private var job: Job? = null

    const val IDLE = 0
    /** The microphone hears the song for the server. */
    const val LISTENING = 1
    /** The server works on what it was sent (a song's file, the last pieces). */
    const val WORKING = 2

    /** What the screen shows of it ([IDLE], [LISTENING], [WORKING]) and how much of the song is timed. */
    var onStatus: ((what: Int, covered: Float) -> Unit)? = null

    /** The song on the screen is listened to for its words only: its lines are timed already (1.16). */
    val wordsOnly: Boolean get() = job?.song?.times != null

    /** One upload at a time; at most two pieces waiting (the oldest goes when the network is slower than the song). */
    private val uploads = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(2), ThreadPoolExecutor.DiscardOldestPolicy())
    /** Asking how far the server got: apart, so a question never pushes a piece of sound out of the line. */
    private val polls = java.util.concurrent.Executors.newSingleThreadExecutor()

    /**
     * The screen shows lyrics to time (untimed lines, a timing by listening that is not complete, or since 1.16 timed
     * lines without their words' times) for the song playing: listen to it when everything allows ([position]: where
     * the song is now in ms, null while it is not playing or not known). The same song again keeps what was done.
     */
    fun begin(c: Context, s: Song, position: () -> Long?) {
        app = c.applicationContext
        debugHook(c)
        val j = job
        if (j != null && j.song.same(s)) return
        end()
        if (!LyricsServer.configured || !LyricsServer.listen || s.lines.none { it.isNotBlank() }) return
        job = Job(s, position)
        if (s.times != null) Log.i(TAG, "'${s.title}': listening for its words (${s.times.count { it != null }} lines timed, correction ${s.offsetMs} ms)")
        main.removeCallbacks(tick)
        main.post(tick)
    }

    /** Another song, another source, or the screen gone: listening stops (what was sent is still worked on and kept). */
    fun end() {
        val j = job
        job = null
        main.removeCallbacks(tick)
        stopCapture()
        if (j == null) return
        cut(j)
        clock = null
        status(IDLE, 0f)
        // pieces still being worked on: their timing is kept for the next play (nothing sent: the poll finds no song)
        main.postDelayed({ poll(j) }, POLL_MS)
    }

    // ------------------------------------------------------------------------------------------ the song's clock
    /** Where the song was ([pos] ms) at [at] (elapsedRealtime), read on the main thread for the microphone's thread. */
    private class Clock(val pos: Long, val at: Long, val job: Job)
    @Volatile private var clock: Clock? = null
    @Volatile private var assistantOn = false

    private val tick = object : Runnable {
        override fun run() {
            val j = job ?: return
            val c = app ?: return
            val p = j.position()
            clock = if (p != null) Clock(p, SystemClock.elapsedRealtime(), j) else null
            assistantOn = Amri.shouldRun(c)
            if (j.song.track != null) {
                if (p != null && !j.fileAsked && !j.finished) sendFile(c, j)
            } else {
                when (val m = if (p == null || j.finished) OFF else mayListen(c)) {
                    OFF -> { stopCapture(); if (p == null) cut(j) }
                    else -> {
                        startEarly(j)
                        // (the server's first answer is waited for a moment: a song it already knows may need no
                        // listening at all — no microphone opened)
                        if ((j.answered && !j.wordsAsking) || SystemClock.elapsedRealtime() - j.startAskedAt > START_WAIT_MS) startCapture(c, m)
                    }
                }
            }
            status(when {
                capturing != OFF -> LISTENING
                j.pending > 0 -> WORKING
                else -> IDLE
            }, j.covered)
            main.postDelayed(this, 500L)
        }
    }

    private const val OFF = 0
    /** Listening through the assistant's recording ([MicTap]). */
    private const val TAP = 1
    /** A microphone recording of our own (the assistant is switched off). */
    private const val OWN = 2

    /** 0: not now; [TAP]: through the assistant's recording; [OWN]: a recording of our own (the assistant is off). */
    private fun mayListen(c: Context): Int {
        if (!LyricsServer.listen || !LyricsServer.configured) return OFF
        if (Amri.state != Amri.State.IDLE || Amri.enrolling || Amri.inCall || Amri.reversing || callAudio(c)) return OFF
        if (!Amri.hasMicPermission(c)) return OFF
        return if (assistantOn) TAP else OWN
    }

    /** A call the audio system knows of (the unit's own Bluetooth calls are [Amri.inCall]). */
    private fun callAudio(c: Context): Boolean {
        val m = (c.getSystemService(Context.AUDIO_SERVICE) as AudioManager).mode
        return m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_IN_COMMUNICATION || m == AudioManager.MODE_RINGTONE
    }

    // ------------------------------------------------------------------------------------------ the microphone
    private var capturing = OFF
    private var own: Recorder? = null
    /** A recording of our own that could not start (the microphone busy, refused): tried again 10 s later. */
    private var ownRetryAt = 0L
    private val tap: (ShortArray, Int, Int) -> Unit = { buf, n, rate -> feed(buf, n, rate) }

    private fun startCapture(c: Context, mode: Int) {
        if (capturing == mode && (mode == TAP || own?.isAlive == true || feeder?.isAlive == true)) return
        val now = SystemClock.elapsedRealtime()
        if (mode == OWN && capturing == OWN && own?.heard == false) ownRetryAt = now + 10_000L // (it ended without a sample)
        stopCapture()
        if (mode == OWN && now < ownRetryAt) return
        capturing = mode
        val wav = debugWav
        if (BuildConfig.DEBUG && wav != null) { feeder = Feeder(wav).also { it.start() }; Log.i(TAG, "listening to $wav (debug)"); return }
        if (mode == TAP) MicTap.listener = tap
        else own = Recorder(c, Amri.enabled).also { it.start() }
        Log.i(TAG, "listening (${if (mode == TAP) "the assistant's microphone" else "own recording"})")
    }

    private fun stopCapture() {
        if (capturing == OFF) return
        if (MicTap.listener === tap) MicTap.listener = null
        own?.let { it.quit(); try { it.join(300) } catch (_: InterruptedException) { } }
        own = null
        feeder?.running = false
        feeder = null
        capturing = OFF
    }

    // ------------------------------------------------------------------------------------------ debug builds
    // What the microphone would hear, from a file (the emulator's microphone hears nothing): a 16 kHz mono 16-bit WAV
    // of the whole song, played into [feed] in real time from where the song is when listening starts.
    //   am broadcast -a com.abdllh.aura.debug.LYRICS_FEED --es wav /sdcard/Download/song.wav   (--es wav "" : the mic)
    private const val DEBUG_ACTION = "com.abdllh.aura.debug.LYRICS_FEED"
    @Volatile private var debugWav: String? = null
    private var feeder: Feeder? = null
    private var debugReceiver = false

    private fun debugHook(c: Context) {
        if (!BuildConfig.DEBUG || debugReceiver) return
        debugReceiver = true
        try {
            c.applicationContext.registerReceiver(object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: Context, i: android.content.Intent) {
                    debugWav = i.getStringExtra("wav")?.takeIf { it.isNotBlank() }
                    Log.i(TAG, "debug feed: $debugWav")
                    stopCapture()
                }
            }, android.content.IntentFilter(DEBUG_ACTION))
        } catch (_: Throwable) { }
    }

    private class Feeder(private val path: String) : Thread("lyrics-wav") {
        @Volatile var running = true

        override fun run() {
            val pcm = try {
                val b = File(path).readBytes()
                var i = 12
                var data = -1
                var size = 0
                while (i + 8 <= b.size) { // the chunks after "RIFF....WAVE": "fmt ", then "data"
                    val id = String(b, i, 4, Charsets.US_ASCII)
                    val n = (b[i + 4].toInt() and 255) or ((b[i + 5].toInt() and 255) shl 8) or ((b[i + 6].toInt() and 255) shl 16) or ((b[i + 7].toInt() and 255) shl 24)
                    if (id == "data") { data = i + 8; size = minOf(n, b.size - data); break }
                    i += 8 + n
                }
                if (data < 0) return
                ShortArray(size / 2) { k -> ((b[data + 2 * k].toInt() and 255) or (b[data + 2 * k + 1].toInt() shl 8)).toShort() }
            } catch (t: Throwable) {
                Log.w(TAG, "debug feed: $t"); return
            }
            val c = clock ?: return
            var at = ((c.pos + SystemClock.elapsedRealtime() - c.at) * RATE / 1000).toInt().coerceAtLeast(0)
            val buf = ShortArray(RATE / 10)
            var next = SystemClock.elapsedRealtime()
            while (running && at < pcm.size) {
                // (the song restarted or was moved: the file follows it)
                clock?.let { k ->
                    val want = ((k.pos + SystemClock.elapsedRealtime() - k.at) * RATE / 1000).toInt()
                    if (abs(want - at) > RATE) at = want.coerceIn(0, pcm.size)
                }
                if (at >= pcm.size) break
                val n = minOf(buf.size, pcm.size - at)
                System.arraycopy(pcm, at, buf, 0, n)
                at += n
                next += 100L
                val wait = next - SystemClock.elapsedRealtime()
                if (wait > 0) try { sleep(wait) } catch (_: InterruptedException) { return }
                if (running) feed(buf, n, RATE)
            }
        }
    }

    /**
     * Our own recording, only while the assistant is switched off: the owner's chosen microphone, as the assistant
     * records it. It stops at once (checked at every read, ten times a second) when the assistant starts listening or
     * answering, Settings records, a call or the reverse gear comes, the assistant's recording starts, or the
     * assistant is switched on or off meanwhile.
     */
    private class Recorder(private val c: Context, private val enabledAtStart: Boolean) : Thread("lyrics-mic") {
        @Volatile private var running = true
        @Volatile private var rec: AudioRecord? = null
        /** Samples came (a recording that never gave one is not restarted at once). */
        @Volatile var heard = false

        fun quit() {
            running = false
            try { rec?.stop() } catch (_: Throwable) { }
        }

        private fun free() = Amri.state == Amri.State.IDLE && !Amri.enrolling && !Amri.inCall && !Amri.reversing &&
            !MicTap.active && Amri.enabled == enabledAtStart

        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            if (!free()) return
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val r = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, max(min, RATE))
            } catch (t: Throwable) {
                Log.w(TAG, "mic: $t"); return
            }
            try {
                if (r.state != AudioRecord.STATE_INITIALIZED) return
                AmriService.device(c, Amri.micId)?.let { r.preferredDevice = it }
                rec = r
                if (!running || !free()) return
                r.startRecording()
                if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) return
                val buf = ShortArray(RATE / 10)
                while (running && free()) {
                    val n = r.read(buf, 0, buf.size)
                    if (n < 0) break
                    if (n > 0 && running) { heard = true; feed(buf, n, RATE) }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "mic: $t")
            } finally {
                rec = null
                try { r.stop() } catch (_: Throwable) { }
                r.release()
            }
        }
    }

    // ------------------------------------------------------------------------------------------ pieces of 12 s
    private val lock = Any()
    private val piece = ShortArray(CHUNK)
    private var filled = 0
    private var piecePos = -1L                  // the song's ms at piece[0]
    private var pieceJob: Job? = null

    /** The microphone's samples (its own thread): into the current piece, which goes when it is full. */
    private fun feed(buf: ShortArray, n: Int, rate: Int) {
        if (rate != RATE || n <= 0) return
        val c = clock ?: return
        synchronized(lock) {
            val j = c.job
            if (j !== job || j.finished) return
            // where this buffer's first sample was in the song (it was recorded just before it was read)
            val pos = c.pos + (SystemClock.elapsedRealtime() - c.at) - n * 1000L / RATE
            if (pieceJob !== j || piecePos < 0) {
                pieceJob = j; piecePos = pos; filled = 0
            } else if (abs(pos - (piecePos + filled * 1000L / RATE)) > JUMP_MS) {
                // the song's clock moved (a seek, a tap that set it, a pause): the piece so far is sent as it is
                emit(j)
                piecePos = pos; filled = 0
            }
            var off = 0
            while (off < n) {
                val k = minOf(n - off, CHUNK - filled)
                System.arraycopy(buf, off, piece, filled, k)
                filled += k
                off += k
                if (filled == CHUNK) {
                    send(j, piece.copyOf(), CHUNK, piecePos)
                    System.arraycopy(piece, CHUNK - OVERLAP, piece, 0, OVERLAP)
                    piecePos += (CHUNK - OVERLAP) * 1000L / RATE
                    filled = OVERLAP
                }
            }
        }
    }

    /** The piece so far, sent if it is long enough, and a new one begun. */
    private fun cut(j: Job) = synchronized(lock) { if (pieceJob === j) emit(j); pieceJob = null; piecePos = -1L; filled = 0 }

    private fun emit(j: Job) {
        if (filled >= MIN_PIECE && piecePos >= 0) send(j, piece.copyOf(filled), filled, piecePos)
        filled = 0
        piecePos = -1L
    }

    private fun send(j: Job, pcm: ShortArray, count: Int, posMs: Long) {
        uploads.execute {
            if (j.finished) return@execute
            // (its level, for the log and Settings: a piece far under -50 dBFS was heard from nothing; summed in a Long)
            var sum = 0L
            var peak = 0
            for (i in 0 until count) { val v = pcm[i].toInt(); sum += v.toLong() * v; if (abs(v) > peak) peak = abs(v) }
            val rms = if (sum <= 0L) -96.0 else 10.0 * log10(sum.toDouble() / count) - 20.0 * log10(32768.0)
            Log.i(TAG, "piece at %.1f s (%.1f s): rms %.1f dBFS, peak %.1f dBFS%s".format(Locale.ROOT, posMs / 1000.0,
                count.toDouble() / RATE, rms, dbfs(peak), if (peak < SILENT_PEAK) ": silence, not sent" else ""))
            main.post { keepHeard(rms, newJob = j) }
            // (nothing in it but silence — the end of a song cut before the silence was told: nothing to hear there)
            if (peak < SILENT_PEAK) return@execute
            try {
                val id = started(j) ?: return@execute
                val st = try {
                    LyricsServer.syncChunk(id, posMs, pcm, count, RATE, SRC_MIC)
                } catch (e: IllegalStateException) {
                    // (the server forgot the song: a new home folder, a restart from scratch) told once more
                    if (e.message?.contains("409") != true) throw e
                    j.serverId = null
                    LyricsServer.syncChunk(started(j) ?: return@execute, posMs, pcm, count, RATE, SRC_MIC)
                }
                main.post { state(j, st) }
            } catch (t: Throwable) {
                Log.i(TAG, "piece at ${posMs / 1000} s: $t")
            }
        }
    }

    private fun dbfs(peak: Int): Double = if (peak <= 0) -96.0 else 20.0 * log10(peak / 32768.0)

    /**
     * The server told of the song as soon as it is listened to, not with the first piece 12 s later (1.16): what it
     * already made of it (a song heard before: its timing, its words) comes back at once, and listening may end
     * before any sound is sent.
     */
    private fun startEarly(j: Job) {
        if (j.startAsked) return
        j.startAsked = true
        j.startAskedAt = SystemClock.elapsedRealtime()
        uploads.execute {
            if (job !== j || j.finished) return@execute
            try { started(j) } catch (t: Throwable) { Log.i(TAG, "start: $t") } // (the first piece tells it again)
        }
    }

    /** The server knows the song and its lines (once per song; the upload thread's). */
    private fun started(j: Job): String? {
        j.serverId?.let { return it }
        val s = j.song
        val st = LyricsServer.syncStart(s.title, s.artist, s.lengthSec, s.lines, s.times)
        j.serverId = st.id
        if (s.times != null && st.mode != "timed") Log.i(TAG, "'${s.title}': the server did not take the times (mode '${st.mode}'): an older server")
        main.post { j.answered = true; state(j, st) }
        return st.id
    }

    // ------------------------------------------------------------------------------------------ a file of the unit's
    private fun sendFile(c: Context, j: Job) {
        j.fileAsked = true
        val t = j.song.track ?: return
        uploads.execute {
            try {
                val f = File(t.path)
                // (a USB stick's file may be readable only through the media store: its size from there)
                val size = if (f.isFile && f.canRead()) f.length()
                    else c.contentResolver.openFileDescriptor(t.uri, "r")?.use { it.statSize } ?: -1L
                if (size <= 0L || size > MAX_FILE) {
                    Log.i(TAG, "${t.title}: not sent ($size bytes; at most $MAX_FILE)")
                    return@execute
                }
                val id = started(j) ?: return@execute
                val name = t.path.substringAfterLast('/')
                val st = LyricsServer.syncFile(id, name, size) {
                    if (f.canRead()) FileInputStream(f) else c.contentResolver.openInputStream(t.uri) ?: throw IllegalStateException("unreadable")
                }
                main.post { state(j, st); main.postDelayed({ poll(j) }, POLL_MS) }
            } catch (t2: Throwable) {
                Log.i(TAG, "file: $t2")
            }
        }
    }

    // ------------------------------------------------------------------------------------------ the server's answers
    /** How far the server is (main thread): the timing (or the words) handed to the screen once there is enough. */
    private fun state(j: Job, st: LyricsServer.State) {
        j.covered = st.covered
        j.pending = st.pending
        st.diag?.toString()?.let { if (it != lastDiag) { lastDiag = it; Log.i(TAG, "server: $it") } }
        if (job === j && st.heard >= 0) keepHeard(words = st.heard)
        if (j.song.times != null) wordsHeard(j, st) else linesTimed(j, st)
        // (a server that answers a piece without the words it has — 1.15's — is asked for them apart)
        if (job === j && j.song.times != null && st.show && st.enhanced == null) askWords(j)
        // (the server's word: nearly all of it timed, or most of it with nothing still to come only guessed; timed
        // lyrics: nearly all their lines have their words)
        if (st.done) {
            if (!j.finished && job === j) Log.i(TAG, "'${j.song.title}': ${(st.covered * 100).toInt()} % ${if (j.song.times != null) "of the lines have their words" else "timed"}, listening stops")
            j.finished = true
            if (job === j) stopCapture()
        }
        if (job === j) status(if (capturing != OFF) LISTENING else if (st.pending > 0) WORKING else IDLE, st.covered)
    }

    private var lastDiag = ""

    /** Untimed lyrics: the lines' timing, handed to the screen from [SHOW_AT] on and kept. */
    private fun linesTimed(j: Job, st: LyricsServer.State) {
        val times = st.times
        val s = j.song
        // (1.15: also when the same coverage first comes with the words' times)
        val newWords = st.enhanced != null && !j.shownWords
        if (st.show && times != null && times.size == s.lines.size && (st.covered > j.shownCovered + 0.01f || (newWords && st.covered >= j.shownCovered))) {
            j.shownCovered = st.covered
            if (st.enhanced != null) j.shownWords = true
            var lines = s.lines.mapIndexed { k, text -> Lyrics.Line(times[k] ?: 0L, text) }
            if (st.enhanced != null) lines = Words.attach(lines, Lyrics.parseLrc(st.enhanced))
            app?.let { Lyrics.keepAligned(it, s.title, s.artist, s.durationSec, lines, st.covered, st.enhanced, st.words) }
            val r = Lyrics.Result(true, lines, source = Lyrics.SOURCE_ALIGNED, covered = st.covered,
                words = if (st.enhanced != null) st.words else "")
            Lyrics.onTimed?.invoke(s.title, s.artist, s.durationSec, r)
        }
    }

    /**
     * Timed lyrics (1.16): the words heard, given to the very lines on the screen — their texts and times stay as they
     * are shown, and so do the lyrics' source and the owner's correction; only the words are new. The server's times
     * are the song's clock: the screen's are that plus the correction.
     */
    private fun wordsHeard(j: Job, st: LyricsServer.State) {
        // (another song's by now: these words are the server's to keep, the screen shows other lyrics)
        if (job !== j) return
        val shown = j.song.shown ?: return
        val enhanced = st.enhanced ?: return
        if (!st.show || (j.shownWords && st.covered <= j.shownCovered + 0.001f)) return
        val before = j.timedLines ?: shown.lines
        val attached = Words.attach(before, Lyrics.parseLrc(shiftLrc(enhanced, j.song.offsetMs)))
        if (attached === before) return // (no line of the server's matched one of these: nothing new)
        // (each line keeps the time it is shown at, even where attach would move it to its first word: the owner's
        // correction belongs to these times, and the song's clock times the server was given are made of them — a
        // moved line would start the listening again with new times at every answer)
        val lines = attached.mapIndexed { k, l -> if (l.ms == before[k].ms) l else Lyrics.Line(before[k].ms, l.text, l.words, l.ends) }
        j.timedLines = lines
        j.shownWords = true
        j.shownCovered = st.covered
        val sung = lines.count { it.text.isNotBlank() }
        Log.i(TAG, "'${j.song.title}': words heard for ${lines.count { it.words != null }} of $sung lines (server: ${(st.covered * 100).toInt()} %)")
        val words = when {
            shown.words.isBlank() || "heard" in shown.words -> "heard"
            else -> "source+heard" // (some lines' words came with the lyrics, a word-timed source's)
        }
        val r = Lyrics.Result(true, lines, durationSec = shown.durationSec, source = shown.source, covered = shown.covered,
            words = words, offsetMs = j.song.offsetMs)
        Lyrics.onTimed?.invoke(j.song.title, j.song.artist, j.song.durationSec, r)
    }

    private val LRC_TIME = Regex("""([\[<])(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?([\]>])""")

    /** An LRC's line and word tags moved by [ms] (to the hundredth, as LRC writes them; nothing before 0). */
    private fun shiftLrc(text: String, ms: Long): String {
        if (ms == 0L) return text
        return LRC_TIME.replace(text) { m ->
            val frac = m.groupValues[4]
            val f = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
            val t = (m.groupValues[2].toLong() * 60_000 + m.groupValues[3].toLong() * 1000 + f + ms).coerceAtLeast(0L)
            val cs = (t + 5) / 10
            "%s%02d:%02d.%02d%s".format(Locale.ROOT, m.groupValues[1], cs / 6000, (cs / 100) % 60, cs % 100, m.groupValues[5])
        }
    }

    /** The words asked for once (at most every 5 s): the state with them, as [LyricsServer.syncState] gives it. */
    private fun askWords(j: Job) {
        val now = SystemClock.elapsedRealtime()
        if (now - j.wordsAskedAt < 5000L) return
        j.wordsAskedAt = now
        j.wordsAsking = true
        polls.execute {
            val st = try {
                j.serverId?.let { LyricsServer.syncState(it) }
            } catch (t: Throwable) {
                Log.i(TAG, "words: $t")
                null
            }
            main.post { j.wordsAsking = false; if (st != null) state(j, st) }
        }
    }

    /** After the last piece: asked again until the server has worked on everything (6 min at most). */
    private fun poll(j: Job) {
        if (j.polls++ > 72) return
        polls.execute {
            val id = j.serverId ?: return@execute
            try {
                val st = LyricsServer.syncState(id)
                main.post {
                    state(j, st)
                    if (st.pending > 0 && (job !== j || capturing == OFF)) main.postDelayed({ poll(j) }, POLL_MS)
                }
            } catch (t: Throwable) {
                Log.i(TAG, "state: $t")
            }
        }
    }

    private var shownWhat = -1
    private var shownCovered = -1f
    private var shownWordsOnly = false

    private fun status(what: Int, covered: Float) {
        val words = wordsOnly
        if (what == shownWhat && covered == shownCovered && words == shownWordsOnly) return
        shownWhat = what
        shownCovered = covered
        shownWordsOnly = words
        onStatus?.invoke(what, covered)
    }

    // ------------------------------------------------------------------------------------------ what was heard
    /**
     * The last listening, as Settings shows it under the switch (1.16): the last piece's level ([db], dBFS) and the
     * words the server wrote down for the song (-1: no answer yet). Always the microphone.
     */
    class Heard(val db: Double, val words: Int)

    private const val HEARD_KEY = "lyricsHeard"
    private var heardJob: Job? = null

    fun lastHeard(): Heard? = try {
        Prefs.raw.getString(HEARD_KEY, null)?.let { JSONObject(it) }?.let {
            Heard(it.optDouble("db", -96.0), it.optInt("words", -1))
        }
    } catch (_: Throwable) {
        null
    }

    /** What was heard, kept as it changes (main thread; a new song's first piece starts a new record). */
    private fun keepHeard(db: Double? = null, words: Int? = null, newJob: Job? = null) {
        val was = lastHeard()
        val fresh = newJob != null && newJob !== heardJob
        if (newJob != null) heardJob = newJob
        val h = Heard(db ?: was?.db ?: -96.0, words ?: (if (fresh) -1 else was?.words ?: -1))
        if (was != null && abs(was.db - h.db) < 0.05 && was.words == h.words) return
        try {
            Prefs.raw.edit().putString(HEARD_KEY, JSONObject()
                .put("db", if (h.db.isFinite()) h.db else -96.0).put("words", h.words).toString()).apply()
        } catch (_: Throwable) { }
    }
}
