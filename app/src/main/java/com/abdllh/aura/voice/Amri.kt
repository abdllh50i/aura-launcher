package com.abdllh.aura.voice

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.R
import com.abdllh.aura.media.MediaMonitor
import com.abdllh.aura.system.CarAudio
import com.abdllh.aura.system.Device
import com.abdllh.aura.system.Gear
import com.abdllh.aura.system.SystemProps
import com.abdllh.aura.util.LocaleHelper
import com.abdllh.aura.util.Prefs
import java.util.Calendar
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/**
 * "عمري", the voice assistant. The owner says the wake word ([AmriService] listens for it, [WakeModel]) or taps the
 * microphone; a chime, and Android's speech recogniser (the Google app's, in Arabic: the unit's firmware has none, the
 * owner installs it from the Play Store) hears the request; [Command] tells what it was; it is done and answered with a
 * few spoken words (Arabic text to speech, with Speech Services by Google) and a line on screen ([AmriOverlay]). Music
 * playing is paused while it listens and resumed afterwards.
 */
object Amri {
    private const val TAG = "AuraAmri"
    const val DEBUG_ACTION = "com.abdllh.aura.debug.AMRI" // emulator: --es say "ارفع الصوت الى 20" | --ez wake true

    enum class State { IDLE, LISTENING, ANSWERING }

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    @Volatile var state = State.IDLE; private set
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pausedMedia = false
    private var session = 0
    /** Set while Settings records the wake word: the background listener keeps off the microphone. */
    @Volatile var enrolling = false

    // ------------------------------------------------------------------------------------------ settings
    /** The wake word is listened for (needs the microphone permission and the owner's recordings). */
    var enabled: Boolean
        get() = Prefs.raw.getBoolean("amriOn", false)
        set(v) { Prefs.raw.edit().putBoolean("amriOn", v).apply(); app?.let { refresh(it) } }

    /** Answers are spoken (text to speech); otherwise only shown. */
    var spoken: Boolean
        get() = Prefs.raw.getBoolean("amriSpeak", true)
        set(v) { Prefs.raw.edit().putBoolean("amriSpeak", v).apply() }

    /** 0 strict, 1 normal, 2 relaxed: how close to the recordings a word must be. */
    var sensitivity: Int
        get() = Prefs.raw.getInt("amriSens", 1)
        set(v) { Prefs.raw.edit().putInt("amriSens", v.coerceIn(0, 2)).apply() }

    /** The microphone (an AudioDeviceInfo id; 0: the system's choice). */
    var micId: Int
        get() = Prefs.raw.getInt("amriMic", 0)
        set(v) { Prefs.raw.edit().putInt("amriMic", v).apply(); app?.let { AmriService.restartMic() } }

    // tried with Windows' Arabic voice through the emulator's microphone (recorded in quiet; said in quiet, in road noise
    // and in loud road noise): "عمري" scores 0.55-0.95; "عمر", "عمرين", "سمري", "مرحبا"... 1.4 and more, but the
    // near-homophone "حمري" 1.2 in road noise
    fun threshold(): Float = when (sensitivity) { 0 -> 1.05f; 2 -> 1.30f; else -> 1.15f }

    fun hasMicPermission(c: Context) = c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Is there a speech recogniser on this device (the Google app's once installed; none in the unit's firmware)? */
    fun canRecognize(c: Context) = try { SpeechRecognizer.isRecognitionAvailable(c) } catch (_: Throwable) { false }

    fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        refresh(ctx)
        try {
            ctx.applicationContext.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    val now = SystemClock.elapsedRealtime()
                    callUntil = when (i.action) {
                        "com.bt.ACTION_BT_END_CALL" -> 0L
                        "com.bt.ACTION_BT_BEGIN_CALL_ONLINE" -> now + 60 * 60_000L
                        else -> maxOf(callUntil, now + 2 * 60_000L) // ringing, dialling
                    }
                    if (callUntil > now && state == State.LISTENING) cancel(c)
                    AmriService.resumeMic() // off the microphone for the call, on again after it
                }
            }, IntentFilter().apply {
                addAction("com.bt.ACTION_BT_INCOMING_CALL")
                addAction("com.bt.ACTION_BT_OUTGOING_NUMBER")
                addAction("com.bt.ACTION_BT_BEGIN_CALL_ONLINE")
                addAction("com.bt.ACTION_BT_END_CALL")
            })
        } catch (t: Throwable) {
            Log.w(TAG, "calls: $t")
        }
        if (BuildConfig.DEBUG) try {
            ctx.applicationContext.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    i.getStringExtra("say")?.let { heard(LocaleHelper.wrap(c.applicationContext), listOf(it), simulated = true); return }
                    if (i.getBooleanExtra("wake", false)) wake(c)
                    // the wake word from files (16 kHz mono WAV): --es enroll a.wav,b.wav,...  --es detect x.wav,y.wav
                    i.getStringExtra("enroll")?.let { files -> DebugWav.enroll(c.applicationContext, files.split(',')) }
                    i.getStringExtra("detect")?.let { files -> DebugWav.detect(c.applicationContext, files.split(',')) }
                    if (i.getBooleanExtra("miclevel", false)) DebugWav.micLevel(c.applicationContext)
                    // what the microphone gives the app: --es rec name --ei ms 10000 -> files/name.wav
                    i.getStringExtra("rec")?.let { name -> DebugWav.record(c.applicationContext, name, i.getIntExtra("ms", 10_000)) }
                }
            }, IntentFilter(DEBUG_ACTION))
        } catch (_: Throwable) { }
    }

    /** Starts or stops the wake word listener to match the settings. */
    fun refresh(ctx: Context) {
        val c = ctx.applicationContext
        if (shouldRun(c)) AmriService.start(c) else AmriService.stop(c)
    }

    /**
     * Should the wake word listener run: switched on, with the microphone and the owner's recordings, and AMRI not
     * switched off (after repeated crashes the stock launcher is home again; a restarted service must not listen).
     */
    fun shouldRun(ctx: Context): Boolean {
        val c = ctx.applicationContext
        return enabled && hasMicPermission(c) && WakeModel.file(c).isFile && SystemProps.get(Device.DISABLED_PROP) != "1"
    }

    // ------------------------------------------------------------------------------------------ when not
    /**
     * A phone call through the unit's own Bluetooth module (com.bt.*; the Android audio mode does not change for those
     * calls on this firmware, the music player knows them the same way): no assistant and no listening for its name
     * ("عمري" is a common endearment). Until the call ends, or at most two minutes of ringing or an hour of talking
     * should the end never be announced.
     */
    @Volatile private var callUntil = 0L
    val inCall: Boolean get() = SystemClock.elapsedRealtime() < callUntil

    /** Reversing: the camera is on the screen, and nothing may cover it. */
    val reversing: Boolean get() = Gear.current == Gear.Pos.R

    // ------------------------------------------------------------------------------------------ a request
    /** The wake word was heard, or the microphone tapped: listen for a request. */
    fun wake(ctx: Context) {
        val c = LocaleHelper.wrap(ctx.applicationContext) // the app's language, also for the answers
        if (state != State.IDLE || inCall || reversing) return
        if (!hasMicPermission(c)) {
            AmriOverlay.show(c, AmriOverlay.Mode.ERROR, c.getString(R.string.amri_no_permission), 3500)
            return
        }
        state = State.LISTENING
        val s = ++session
        AmriService.pauseMic() // the recogniser needs the microphone
        pausedMedia = false
        if (MediaMonitor.current?.playing == true) {
            MediaMonitor.send(c, MediaMonitor.Key.PLAY_PAUSE)
            pausedMedia = true
        }
        AmriOverlay.show(c, AmriOverlay.Mode.LISTEN, c.getString(R.string.amri_listening))
        Chime.play {
            if (session != s || state != State.LISTENING) return@play
            if (!canRecognize(c)) {
                finish(c, AmriOverlay.Mode.ERROR, c.getString(R.string.amri_no_recognizer), null)
                return@play
            }
            listen(c, s)
        }
    }

    /**
     * The recogniser to ask: the Google app's when it is there; else the one the system names (a head unit's setting
     * may be unset, or name an engine that is gone); else any there is (Speech Services by Google may bring one).
     */
    private fun recognizerService(c: Context): ComponentName? = try {
        val all = c.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
        val named = android.provider.Settings.Secure.getString(c.contentResolver, "voice_recognition_service")
            ?.let { ComponentName.unflattenFromString(it) }
        all.firstOrNull { it.packageName == VoiceSetup.GOOGLE_APP } ?: named?.takeIf { it in all } ?: all.firstOrNull()
    } catch (_: Throwable) {
        null
    }

    private fun listen(c: Context, s: Int) {
        val r = try { SpeechRecognizer.createSpeechRecognizer(c, recognizerService(c)) } catch (t: Throwable) {
            finish(c, AmriOverlay.Mode.ERROR, c.getString(R.string.amri_no_recognizer), null); return
        }
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { AmriOverlay.level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { AmriOverlay.level(0f) }
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(b: Bundle?) {
                val p = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!p.isNullOrBlank() && session == s) AmriOverlay.text(p)
            }
            override fun onResults(b: Bundle?) {
                if (session != s) return
                release()
                heard(c, b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty(), simulated = false)
            }
            override fun onError(error: Int) {
                if (session != s) return
                release()
                Log.i(TAG, "recogniser error $error")
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> R.string.amri_no_network
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> R.string.amri_not_heard
                    // the recogniser's own app without the microphone (AMRI has it: it got this far)
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.amri_recognizer_no_mic
                    else -> R.string.amri_not_heard
                }
                finish(c, AmriOverlay.Mode.ERROR, c.getString(msg), null)
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-SA")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ar-SA")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, c.packageName)
        }
        try { r.startListening(i) } catch (t: Throwable) {
            release()
            finish(c, AmriOverlay.Mode.ERROR, c.getString(R.string.amri_no_recognizer), null)
            return
        }
        // a recogniser that never answers must not keep the assistant (and the music) waiting
        main.postDelayed({
            if (session == s && state == State.LISTENING) {
                release()
                finish(c, AmriOverlay.Mode.ERROR, c.getString(R.string.amri_not_heard), null)
            }
        }, 15_000L)
    }

    /** The owner tapped the pill: stop listening (or just take the answer away). */
    fun cancel(ctx: Context) {
        val c = LocaleHelper.wrap(ctx.applicationContext)
        if (state == State.LISTENING) {
            session++
            release()
            finish(c, AmriOverlay.Mode.DONE, c.getString(R.string.amri_cancel), null, speak = false, cmd = Command.Cancel)
        } else {
            AmriOverlay.hide()
        }
    }

    private fun release() {
        val r = recognizer ?: return
        recognizer = null
        try { r.cancel() } catch (_: Throwable) { }
        try { r.destroy() } catch (_: Throwable) { }
    }

    /** The recogniser's guesses (or, in debug builds, a typed request). */
    private fun heard(c: Context, guesses: List<String>, simulated: Boolean) {
        Log.i(TAG, "heard: $guesses")
        if (simulated && state == State.IDLE) { state = State.LISTENING; session++ }
        val said = guesses.firstOrNull().orEmpty()
        val cmd = Command.parse(guesses)
        if (cmd == null) {
            finish(c, AmriOverlay.Mode.ERROR, c.getString(R.string.amri_not_understood), said.ifBlank { null })
            return
        }
        state = State.ANSWERING
        val answer = run(c, cmd)
        val what = if (cmd is Command.VolumeSet || cmd is Command.VolumeStep || cmd is Command.Navigate) cmd.toString() else cmd::class.java.simpleName
        Log.i(TAG, "request $what -> ${answer.text}")
        finish(c, AmriOverlay.Mode.DONE, answer.text, said, answer.speak, cmd)
    }

    private class Answer(val text: String, val speak: Boolean = true)

    /** Does [cmd]; the answer to show (and to say). */
    private fun run(c: Context, cmd: Command): Answer {
        fun s(id: Int, vararg a: Any) = c.getString(id, *a)
        return when (cmd) {
            Command.Next -> { media(c, MediaMonitor.Key.NEXT); Answer(s(R.string.amri_next)) }
            Command.Previous -> { media(c, MediaMonitor.Key.PREV); Answer(s(R.string.amri_previous)) }
            Command.Pause -> { pausedMedia = false; Answer(s(R.string.amri_paused)) } // paused when it started listening
            Command.Play -> {
                when {
                    pausedMedia -> { pausedMedia = false; MediaMonitor.send(c, MediaMonitor.Key.PLAY_PAUSE) }
                    MediaMonitor.current?.playing == true -> {}
                    MediaMonitor.current != null -> MediaMonitor.send(c, MediaMonitor.Key.PLAY_PAUSE)
                    else -> openMusic(c, false)
                }
                Answer(s(R.string.amri_playing))
            }
            is Command.VolumeStep -> {
                CarAudio.setMuted(false)
                CarAudio.step(if (cmd.up) cmd.by else -cmd.by)
                Answer(s(R.string.amri_volume_now, CarAudio.volume()))
            }
            is Command.VolumeSet -> {
                CarAudio.setMuted(false)
                CarAudio.setVolume(cmd.level.coerceIn(0, CarAudio.max()))
                Answer(s(R.string.amri_volume_now, CarAudio.volume()))
            }
            Command.VolumeMax -> { CarAudio.setMuted(false); CarAudio.setVolume(CarAudio.max()); Answer(s(R.string.amri_volume_max)) }
            Command.Mute -> { CarAudio.setMuted(true); Answer(s(R.string.amri_muted), speak = false) }
            Command.Unmute -> { CarAudio.setMuted(false); Answer(s(R.string.amri_unmuted)) }
            Command.OpenMusic -> { openMusic(c, false); Answer(s(R.string.amri_music)) }
            Command.BluetoothMusic -> { openMusic(c, true); Answer(s(R.string.amri_bt_music)) }
            Command.OpenMaps -> { com.abdllh.aura.home.Actions.nav(c); Answer(s(R.string.amri_maps)) }
            is Command.Navigate -> {
                com.abdllh.aura.nav.MapsActivity.open(c) { putExtra("go", if (cmd.home) "home" else "work") }
                Answer(s(if (cmd.home) R.string.amri_go_home else R.string.amri_go_work))
            }
            Command.HomeScreen -> {
                try { c.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(c.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) { }
                Answer(s(R.string.amri_home_screen), speak = false)
            }
            Command.Time -> {
                val now = Calendar.getInstance()
                Answer(s(R.string.amri_time, now.get(Calendar.HOUR).let { if (it == 0) 12 else it }, now.get(Calendar.MINUTE)))
            }
            Command.Cancel -> Answer(s(R.string.amri_cancel), speak = false)
        }
    }

    /** Next / previous: music the assistant paused plays on with the next song. */
    private fun media(c: Context, key: MediaMonitor.Key) {
        if (pausedMedia) {
            pausedMedia = false
            MediaMonitor.send(c, MediaMonitor.Key.PLAY_PAUSE)
            main.postDelayed({ MediaMonitor.send(c, key) }, 250)
        } else {
            MediaMonitor.send(c, key)
        }
    }

    /** The music screen on that source, playing (as the stock music key does). */
    private fun openMusic(c: Context, bt: Boolean) {
        pausedMedia = false // it plays itself
        com.abdllh.aura.music.MusicActivity.openInstead(c, bt)
    }

    /** Shows (and says) the outcome, then lets the music play on and listens for the wake word again. */
    private fun finish(c: Context, mode: AmriOverlay.Mode, text: String, said: String?, speak: Boolean = true, cmd: Command? = null) {
        state = State.ANSWERING
        val s = session
        AmriOverlay.show(c, mode, text, sub = said, hideAfterMs = if (mode == AmriOverlay.Mode.DONE) 2600 else 3200)
        var ended = false
        val done = {
            if (!ended && session == s) {
                ended = true
                // music the assistant paused plays on, unless the owner wanted it stopped or changed
                if (pausedMedia && cmd != Command.Pause && cmd != Command.Mute) MediaMonitor.send(c, MediaMonitor.Key.PLAY_PAUSE)
                pausedMedia = false
                state = State.IDLE
                AmriService.resumeMic()
            }
        }
        if (speak && spoken && mode == AmriOverlay.Mode.DONE) say(c, text, done) else main.postDelayed({ done() }, 900)
        main.postDelayed({ done() }, 8000) // a voice that never finishes must not leave it deaf
    }

    // ------------------------------------------------------------------------------------------ voice
    private val speechAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var ttsStarting = false

    /** Google's voice when it is there (it speaks Arabic; the unit's default engine may not); else the default. */
    private fun ttsEngine(c: Context): String? = try {
        c.packageManager.getPackageInfo(VoiceSetup.GOOGLE_TTS, 0)
        VoiceSetup.GOOGLE_TTS
    } catch (_: Throwable) {
        null
    }

    /**
     * Says [text] in the app's language (an Arabic voice for Arabic, when the device has one: Speech Services by Google),
     * then [then] on the main thread. Without a voice for it the answer is only shown.
     */
    private fun say(c: Context, text: String, then: () -> Unit) {
        val t = tts
        if (t == null) {
            if (ttsStarting) { main.postDelayed(then, 900); return } // still starting: this answer is only shown
            ttsStarting = true
            val made = arrayOfNulls<TextToSpeech>(1)
            made[0] = TextToSpeech(c, { status ->
                // posted: the engine can report from inside its constructor, before it is in [made]
                main.post {
                    ttsStarting = false
                    val e = made[0]
                    if (status == TextToSpeech.SUCCESS && e != null) {
                        e.setAudioAttributes(speechAttrs)
                        tts = e
                        say(c, text, then)
                    } else {
                        try { e?.shutdown() } catch (_: Throwable) { }
                        then() // without a voice this time (tried again with the next answer)
                    }
                }
            }, ttsEngine(c))
            return
        }
        val lang = if (Locale.getDefault().language == "ar") Locale("ar") else Locale.US
        ttsReady = try { t.isLanguageAvailable(lang) >= TextToSpeech.LANG_AVAILABLE } catch (_: Throwable) { false }
        if (ttsReady && t.language?.language != lang.language) t.language = lang
        if (!ttsReady) { main.postDelayed(then, 900); return }
        val id = "amri-${SystemClock.elapsedRealtime()}"
        var called = false
        val once = { if (!called) { called = true; main.post(then) } }
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (utteranceId == id) once() }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { if (utteranceId == id) once() }
        })
        if (t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) once()
        main.postDelayed({ once() }, 6000) // whatever happens, never stay silent and deaf
    }
}

/** The "I am listening" sound: two soft rising notes (generated, so nothing to ship). */
internal object Chime {
    private const val RATE = 22050
    private val pcm: ShortArray by lazy {
        val notes = listOf(880.0 to 0.09, 1318.5 to 0.13)
        val out = ArrayList<Short>()
        for ((f, len) in notes) {
            val n = (RATE * len).toInt()
            for (i in 0 until n) {
                val env = minOf(1.0, i / (RATE * 0.008)) * minOf(1.0, (n - i) / (RATE * 0.04)) // short attack, soft release
                out.add((sin(2 * PI * f * i / RATE) * env * 0.32 * Short.MAX_VALUE).toInt().toShort())
            }
        }
        out.toShortArray()
    }

    fun play(then: () -> Unit) {
        val main = Handler(Looper.getMainLooper())
        try {
            val data = pcm
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(data.size * 2)
                .build()
            track.write(data, 0, data.size)
            track.play()
            val ms = data.size * 1000L / RATE
            main.postDelayed({ try { track.release() } catch (_: Throwable) { } }, ms + 300)
            main.postDelayed(then, ms + 60) // listen once the chime is over (the recogniser must not hear it)
        } catch (_: Throwable) {
            main.post(then)
        }
    }
}
