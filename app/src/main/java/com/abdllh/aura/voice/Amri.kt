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

    /** How close a word must come to the owner's recordings, at this sensitivity ([Limits], [WakeModel.judge]). */
    internal fun limits(): Limits = Limits.of(sensitivity)

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
                    // sounds to ignore: --es learn x.wav (what wakes in it, learned as if not meant)   --ez forget true
                    i.getStringExtra("learn")?.let { files -> DebugWav.learn(c.applicationContext, files.split(',')) }
                    if (i.getBooleanExtra("forget", false)) { forgetLearnedSounds(c.applicationContext); Log.i(TAG, "learned sounds forgotten") }
                    // requests without the internet: --es teach next:a.wav,b.wav,c.wav   --es offline x.wav,y.wav
                    i.getStringExtra("teach")?.let { spec -> DebugWav.teach(c.applicationContext, spec) }
                    i.getStringExtra("offline")?.let { files -> DebugWav.offline(c.applicationContext, files.split(',')) }
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
    /** This session's wake by voice (null: the microphone was tapped), until the owner shows whether he meant it. */
    private var heardBy: WakeCandidate? = null
    private var wokeAt = 0L
    /** What played when the assistant was called, before it paused the music ("what's playing" answers about it). */
    private var playingAtWake: com.abdllh.aura.media.NowPlaying? = null

    /** The microphone tapped (the home screen's button, Settings): listen for a request. */
    fun wake(ctx: Context) = wake(ctx, null)

    /** The wake word was heard ([heard]: what woke it), or the microphone tapped (null): listen for a request. */
    internal fun wake(ctx: Context, heard: WakeCandidate?) {
        val c = LocaleHelper.wrap(ctx.applicationContext) // the app's language, also for the answers
        if (state != State.IDLE || inCall || reversing) return
        if (!hasMicPermission(c)) {
            AmriOverlay.show(c, AmriOverlay.Mode.ERROR, c.getString(R.string.amri_no_permission), 3500)
            return
        }
        state = State.LISTENING
        val s = ++session
        heardBy = heard
        wokeAt = SystemClock.elapsedRealtime()
        playingAtWake = MediaMonitor.current
        AmriService.pauseMic() // the recogniser needs the microphone
        pausedMedia = false
        if (MediaMonitor.current?.playing == true) {
            MediaMonitor.send(c, MediaMonitor.Key.PLAY_PAUSE)
            pausedMedia = true
        }
        AmriOverlay.show(c, AmriOverlay.Mode.LISTEN, c.getString(R.string.amri_listening))
        Chime.play {
            if (session != s || state != State.LISTENING) return@play
            // Google with the internet (it understands anything); the requests the owner taught without it
            val google = canRecognize(c)
            when {
                google && online(c) -> listen(c, s)
                OfflineCommands.anyTaught(c) -> listenOffline(c, s)
                !google -> finish(c, AmriOverlay.Mode.ERROR, c.getString(R.string.amri_no_recognizer), null)
                else -> finish(c, AmriOverlay.Mode.ERROR, c.getString(R.string.amri_offline_none), null)
            }
        }
    }

    /** Is there internet now (a validated network that the car's Wi-Fi watchdog does not know to be down)? */
    private fun online(c: Context): Boolean = try {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true &&
            com.abdllh.aura.system.WifiKeeper.state != com.abdllh.aura.system.WifiKeeper.State.OFFLINE
    } catch (_: Throwable) {
        true
    }

    // ------------------------------------------------------------------------------------------ without the internet
    private var offline: MicLoop? = null

    /** A request said after the chime, matched against the ones the owner taught ([OfflineCommands]). */
    private fun listenOffline(c: Context, s: Int) {
        var heard = false
        val seg = Segmenter(maxMs = 2600, onLevel = { l -> main.post { if (session == s) AmriOverlay.level(l) } }, endHops = 40) { pcm, noise ->
            // a bump, a door, a cough before the request: not the request, keep listening (the 8 s still apply)
            if (heard || !VoiceCheck.isVoice(pcm, noise)) return@Segmenter
            heard = true
            val item = OfflineCommands.match(c, Features.spectrum(pcm, noise))
            main.post {
                stopOffline()
                if (session != s || state != State.LISTENING) return@post
                if (item == null) finish(c, AmriOverlay.Mode.ERROR, c.getString(R.string.amri_not_understood), null)
                else act(c, item.command, c.getString(item.label))
            }
        }
        offline = MicLoop(AmriService.device(c, micId), seg) { err ->
            Log.w(TAG, "offline mic: $err")
            main.post { if (session == s && state == State.LISTENING) { stopOffline(); finish(c, AmriOverlay.Mode.ERROR, c.getString(R.string.amri_not_heard), null) } }
        }.also { it.start() }
        main.postDelayed({
            if (session == s && state == State.LISTENING && offline != null) {
                stopOffline()
                if (!heard) unmeant(c, "nothing said")
                finish(c, AmriOverlay.Mode.ERROR, c.getString(R.string.amri_not_heard), null)
            }
        }, 8_000L)
    }

    /**
     * The owner did not mean the wake (nothing was asked, he tapped it away, he said "خلاص"): what woke it is learned
     * as a sound to ignore, if it matched weakly ([WakeNegatives]). Once a session, wakes by voice only.
     */
    private fun unmeant(c: Context, why: String) {
        val h = heardBy ?: return
        heardBy = null
        val f = WakeNegatives.file(c)
        val lim = limits()
        Thread {
            val learned = WakeNegatives.learn(f, h, lim)
            Log.i(TAG, "wake not meant ($why): " + if (learned) "learned (${WakeNegatives.count(f)})" else "a strong match, kept out")
        }.start()
    }

    /** The owner meant it (a request followed and was done): learned sounds too close to it are forgotten. */
    private fun meant(c: Context) {
        val h = heardBy ?: return
        heardBy = null
        val f = WakeNegatives.file(c)
        Thread { WakeNegatives.confirm(f, h) }.start()
    }

    /** The sounds learned so far (Settings). */
    fun learnedSounds(c: Context): Int = WakeNegatives.count(WakeNegatives.file(c))

    /** Settings: forget them. */
    fun forgetLearnedSounds(c: Context) = WakeNegatives.clear(WakeNegatives.file(c))

    private fun stopOffline() {
        offline?.quit()
        offline = null
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
                // no internet after all: the taught requests, said once more
                val net = error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
                    error == SpeechRecognizer.ERROR_SERVER
                if (net && OfflineCommands.anyTaught(c) && state == State.LISTENING) {
                    AmriOverlay.show(c, AmriOverlay.Mode.LISTEN, c.getString(R.string.amri_offline_again))
                    Chime.play { if (session == s && state == State.LISTENING) listenOffline(c, s) }
                    return
                }
                // nothing said after the chime: the wake was probably not meant
                if (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH) unmeant(c, "recogniser $error")
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
            // (the recogniser still listening: not when it gave way to the taught requests)
            if (session == s && state == State.LISTENING && recognizer === r) {
                release()
                unmeant(c, "no answer in 15 s")
                finish(c, AmriOverlay.Mode.ERROR, c.getString(R.string.amri_not_heard), null)
            }
        }, 15_000L)
    }

    /**
     * Stop listening (or just take the answer away): the owner tapped the pill ([byOwner]), or a call came. A tap within
     * a few seconds of a wake by voice says it was not meant.
     */
    fun cancel(ctx: Context, byOwner: Boolean = false) {
        val c = LocaleHelper.wrap(ctx.applicationContext)
        if (state == State.LISTENING) {
            if (byOwner && SystemClock.elapsedRealtime() - wokeAt < 6_000L) unmeant(c, "tapped away")
            session++
            release()
            finish(c, AmriOverlay.Mode.DONE, c.getString(R.string.amri_cancel), null, speak = false, cmd = Command.Cancel)
        } else {
            AmriOverlay.hide()
        }
    }

    private fun release() {
        stopOffline()
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
        act(c, cmd, said)
    }

    /** Does a request (heard by Google, or matched without the internet) and answers it; [said]: shown under it. */
    private fun act(c: Context, cmd: Command, said: String?) {
        state = State.ANSWERING
        // "خلاص" right after the chime: called by mistake; any other request: meant
        if (cmd == Command.Cancel) unmeant(c, "cancelled") else meant(c)
        val answer = run(c, cmd)
        val what = cmd.toString().let { if ('@' in it) cmd::class.java.simpleName else it } // (a data class says its values)
        Log.i(TAG, "request $what -> ${answer.text}")
        val later = answer.later
        if (later == null) {
            finish(c, if (answer.error) AmriOverlay.Mode.ERROR else AmriOverlay.Mode.DONE, answer.text, said, answer.speak, cmd)
            return
        }
        // an answer that takes a moment (a search on the internet): its first words meanwhile, the answer when it comes
        val s = session
        AmriOverlay.show(c, AmriOverlay.Mode.DONE, answer.text, sub = said)
        var given = false
        val give = { a: Answer ->
            if (!given && session == s) {
                given = true
                Log.i(TAG, "request $what -> ${a.text}")
                finish(c, if (a.error) AmriOverlay.Mode.ERROR else AmriOverlay.Mode.DONE, a.text, said, a.speak, cmd)
            }
        }
        main.postDelayed({ give(Answer(c.getString(R.string.amri_slow), error = true)) }, 12_000L)
        later { a -> main.post { give(a) } }
    }

    /**
     * What to show and say. [error]: shown as a failure (and not said). [later]: the answer comes later (searches
     * on the internet); [text] is shown meanwhile and [later] is given what to answer when it is known.
     */
    private class Answer(val text: String, val speak: Boolean = true, val error: Boolean = false,
                         val later: (((Answer) -> Unit) -> Unit)? = null)

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
                val which = if (cmd.home) "home" else "work"
                // (the navigation app chosen in Settings, when it is not AMRI Maps and the place is known to hand over)
                if (com.abdllh.aura.util.Prefs.builtInMaps || !com.abdllh.aura.home.Actions.driveWithApp(c, which)) {
                    com.abdllh.aura.nav.MapsActivity.open(c) { putExtra("go", which) }
                }
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
            Command.NowPlaying -> {
                // what played when it was called (the pause since may have handed the screen to an older source)
                val np = (playingAtWake ?: MediaMonitor.current)?.takeIf { it.title.isNotBlank() }
                when {
                    np == null -> Answer(s(R.string.amri_nothing_playing), error = true)
                    np.artist.isBlank() -> Answer(s(R.string.amri_now_playing_title, np.title))
                    else -> Answer(s(R.string.amri_now_playing, np.title, np.artist))
                }
            }
            Command.Eta -> eta(c)
            Command.Date -> Answer(s(R.string.amri_date, today()))
            is Command.Brightness -> brightness(c, cmd)
            Command.ScreenOff -> { com.abdllh.aura.home.Actions.screenOff(c); Answer(s(R.string.amri_cancel), speak = false) }
            is Command.Nearby -> nearby(c, cmd.place)
            is Command.SearchPlace -> searchPlace(c, cmd.query)
            is Command.OpenApp -> openApp(c, cmd.name)
            is Command.PlaySong -> playSong(c, cmd.query)
            Command.Thanks -> Answer(s(R.string.amri_thanks))
            Command.Who -> Answer(s(R.string.amri_who))
        }
    }

    private val arabic get() = Locale.getDefault().language == "ar"

    /** What is left of the route on AMRI Maps (another navigation app does not say). */
    private fun eta(c: Context): Answer {
        val nav = com.abdllh.aura.nav.NavSession
        val none = c.getString(if (com.abdllh.aura.util.Prefs.builtInMaps) R.string.amri_eta_none else R.string.amri_eta_other_app)
        if (nav.state != com.abdllh.aura.nav.NavSession.State.NAVIGATING) return Answer(none, error = true)
        // (no progress yet just after the start, before a fix: the whole route)
        val p = nav.progress
        val m = p?.remaining ?: nav.route?.distance ?: return Answer(none, error = true)
        val sec = p?.timeLeft ?: nav.route?.duration ?: 0.0
        val units = com.abdllh.aura.nav.Units
        return Answer(c.getString(R.string.amri_eta, units.spokenDistance(m, arabic),
            units.arrival(sec, com.abdllh.aura.util.Prefs.clock24, arabic)))
    }

    /** The day and date ("السبت 18 أكتوبر"), and the Hijri date in Arabic (Umm al-Qura, as the Kingdom counts it). */
    private fun today(): String {
        val loc = Locale.getDefault()
        val day = java.text.SimpleDateFormat("EEEE d MMMM", loc).format(java.util.Date())
        if (!arabic) return day
        return try {
            val f = android.icu.text.SimpleDateFormat("d MMMM", android.icu.util.ULocale("ar_SA@calendar=islamic-umalqura"))
            "$day، ${f.format(java.util.Date())}"
        } catch (_: Throwable) {
            day
        }
    }

    /** The screen brighter or dimmer (Android's brightness: AMRI needs "modify system settings" for it). */
    private fun brightness(c: Context, cmd: Command.Brightness): Answer {
        val ctl = com.abdllh.aura.system.Controls
        val now = ctl.brightnessPercent(c)
        val to = (cmd.level ?: (now + if (cmd.up) cmd.by else -cmd.by)).coerceIn(5, 100)
        if (!ctl.setBrightnessPercent(c, to)) {
            ctl.requestWriteSettings(c)
            return Answer(c.getString(R.string.amri_brightness_permission), error = true)
        }
        val id = when {
            cmd.level != null -> R.string.amri_brightness_now
            cmd.up -> R.string.amri_brighter
            else -> R.string.amri_dimmer
        }
        return Answer(c.getString(id, to))
    }

    /**
     * The nearest place of a kind: with AMRI Maps, searched near the car and the nearest one said, then the map's
     * search opened on it; with another navigation app (Settings), that app's own search.
     */
    private fun nearby(c: Context, place: Command.Place): Answer {
        val kind = c.getString(place.label)
        val query = if (arabic) place.ar else place.en
        if (!com.abdllh.aura.util.Prefs.builtInMaps) {
            return if (searchWithApp(c, query)) Answer(c.getString(R.string.amri_nearby_map, kind))
            else Answer(c.getString(R.string.amri_nearby_none, kind), error = true)
        }
        val car = com.abdllh.aura.nav.CarLocation.lastKnown()
        if (car == null) { // no position yet: the map's search, around where the map is
            openMapSearch(c, query)
            return Answer(c.getString(R.string.amri_nearby_map, kind))
        }
        return Answer(c.getString(R.string.amri_nearby_looking, kind)) { give ->
            var best: com.abdllh.aura.nav.Place? = null
            var told = false
            fun tell() {
                if (told) return
                told = true
                val b = best
                if (b == null) give(Answer(c.getString(R.string.amri_nearby_none, kind), error = true))
                else {
                    openMapSearch(c, query)
                    val far = com.abdllh.aura.nav.Units.spokenDistance(com.abdllh.aura.nav.Geo.distance(car, b.pos), arabic)
                    give(Answer(c.getString(R.string.amri_nearby_found, kind, b.name, far)))
                }
            }
            // the offline map's answer may come first and the internet's later (the list grows): the nearest of all
            // that came within a moment of the first
            com.abdllh.aura.nav.Places.search(query, car, arabic) { list ->
                if (told) return@search
                if (list == null) { tell(); return@search }
                list.minByOrNull { com.abdllh.aura.nav.Geo.distance(car, it.pos) }?.let { p ->
                    val b = best
                    if (b == null || com.abdllh.aura.nav.Geo.distance(car, p.pos) < com.abdllh.aura.nav.Geo.distance(car, b.pos)) best = p
                }
                main.postDelayed({ tell() }, 1500)
            }
        }
    }

    /** "ودني المطار": the map's search on it (or the navigation app's). */
    private fun searchPlace(c: Context, query: String): Answer {
        if (com.abdllh.aura.util.Prefs.builtInMaps) openMapSearch(c, query) else searchWithApp(c, query)
        return Answer(c.getString(R.string.amri_search_place, query))
    }

    /** AMRI Maps with its search open on [query] and its results showing (MapsActivity's "q"). */
    private fun openMapSearch(c: Context, query: String) {
        com.abdllh.aura.nav.MapsActivity.open(c) { putExtra("search", true); putExtra("q", query) }
    }

    /** The navigation app chosen in Settings, searching [query] around the car (Google Maps: geo:, Waze: its link). */
    private fun searchWithApp(c: Context, query: String): Boolean {
        val pkg = com.abdllh.aura.home.AppRepo.navPackage(c) ?: return false
        val car = com.abdllh.aura.nav.CarLocation.lastKnown()
        val at = car?.let { String.format(Locale.ROOT, "%.6f,%.6f", it.lat, it.lon) } ?: "0,0"
        val uri = if (pkg == "com.waze") "waze://?q=${android.net.Uri.encode(query)}" else "geo:$at?q=${android.net.Uri.encode(query)}"
        com.abdllh.aura.system.HomeGuard.launching(pkg)
        return try {
            c.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** An app by its name: the installed app whose label is closest (Arabic or Latin: "يوتيوب" finds YouTube). */
    private fun openApp(c: Context, name: String): Answer {
        val repo = com.abdllh.aura.home.AppRepo
        val apps = repo.cached ?: try { repo.load(c) } catch (_: Throwable) { emptyList() }
        val best = apps.maxByOrNull { com.abdllh.aura.util.TextMatch.score(name, it.label) }
        if (best == null || com.abdllh.aura.util.TextMatch.score(name, best.label) < 0.8 || !repo.launch(c, best.pkg)) {
            return Answer(c.getString(R.string.amri_app_missing, name), error = true)
        }
        pausedMedia = false // another app in front: the music stays as the owner left it
        return Answer(c.getString(R.string.amri_open_app, best.label))
    }

    /** A song by name: YouTube Music's search, its first song handed to the YouTube Music app (AMRI Music's source). */
    private fun playSong(c: Context, query: String): Answer {
        val yt = com.abdllh.aura.music.YtMusic
        if (yt.player(c) == null) { yt.openStore(c); return Answer(c.getString(R.string.amri_song_no_app), error = true) }
        if (!online(c)) return Answer(c.getString(R.string.amri_song_offline), error = true)
        return Answer(c.getString(R.string.amri_song_looking, query)) { give ->
            yt.search(query) { songs ->
                val song = songs?.firstOrNull()
                when {
                    songs == null -> give(Answer(c.getString(R.string.amri_song_offline), error = true))
                    song == null -> give(Answer(c.getString(R.string.amri_song_none, query), error = true))
                    !yt.play(c, song) { com.abdllh.aura.music.MusicActivity.open(c) } ->
                        give(Answer(c.getString(R.string.amri_song_no_app), error = true))
                    else -> {
                        pausedMedia = false // it plays on its own; the music paused for the request stays paused
                        give(Answer(if (song.artist.isBlank()) c.getString(R.string.amri_now_playing_title, song.title)
                            else c.getString(R.string.amri_song_playing, song.title, song.artist)))
                    }
                }
            }
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
                heardBy = null
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
