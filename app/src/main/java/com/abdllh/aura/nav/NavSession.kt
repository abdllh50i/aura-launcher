package com.abdllh.aura.nav

import android.content.Context
import android.location.Location
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.abdllh.aura.R
import com.abdllh.aura.util.Prefs
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToInt

/**
 * The active navigation: destination, route, live progress along it, spoken prompts, re-routing when the car leaves
 * the route, and arrival. Lives in the app process (Aura is the home app, so it is always there); [NavService] keeps it
 * allowed to use GPS while another app is in front. Main thread only.
 */
object NavSession {
    enum class State { IDLE, NAVIGATING, ARRIVED }

    class Progress(
        val position: LatLon,     // snapped to the route
        val bearing: Double,
        val along: Double,
        val next: Int,            // index of the upcoming manoeuvre in route.steps
        val toNext: Double,       // metres to it
        val remaining: Double,    // metres to the destination
        val timeLeft: Double,     // seconds
        val speedKmh: Int,
        val offRoute: Boolean
    )

    var state = State.IDLE
        private set
    var destination: Place? = null
        private set
    var route: Route? = null
        private set
    var progress: Progress? = null
        private set
    var rerouting = false
        private set
    /** When the car arrived (elapsedRealtime): the trip ends by itself [ARRIVED_HOLD_MS] later. */
    var arrivedAt = 0L
        private set
    const val ARRIVED_HOLD_MS = 30_000L

    private var app: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var lastSeg = 0
    private var offCount = 0
    private var rerouteStreak = 0  // reroutes while the car stays off the route (no network, a road OSM lacks...)
    private var nextRerouteAt = 0L
    private var trip = 0           // bumped by start/stop: answers for an older trip are dropped
    private val endAfterArrival = Runnable { if (state == State.ARRIVED) stop() }
    private val saidEarly = HashSet<Int>()
    private val saidFar = HashSet<Int>()
    private val saidNear = HashSet<Int>()
    private val saidLong = HashSet<Int>()
    /** The first words of a route (a new trip, or a new route after leaving the old one) are still to be said. */
    private var pendingIntro = false
    private var tts: TextToSpeech? = null
    private var ttsArabic = false
    private var ttsReady = false
    var voiceOn: Boolean
        get() = Prefs.raw.getBoolean("navVoice", true)
        set(v) { Prefs.raw.edit().putBoolean("navVoice", v).apply(); if (!v) tts?.stop() }
    /** No text-to-speech engine answered (the stock firmware has none): prompts cannot be spoken. */
    var voiceMissing = false
        private set

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun changed() { for (l in listeners) l() }

    private val arabic: Boolean get() = Locale.getDefault().language == "ar"

    fun start(ctx: Context, dest: Place, r: Route) {
        val c = ctx.applicationContext
        app = c
        destination = dest
        route = r
        state = State.NAVIGATING
        trip++
        main.removeCallbacks(endAfterArrival)
        rerouting = false
        lastSeg = 0
        offCount = 0
        rerouteStreak = 0
        nextRerouteAt = 0L
        forgetPrompts()
        Places.addRecent(dest)
        CarLocation.acquire(c, "nav")
        CarLocation.removeListener(onFix)
        CarLocation.addListener(onFix)
        NavService.start(c)
        initVoice(c)
        CarLocation.last?.let { update(it) } ?: changed()
    }

    fun stop() {
        val c = app
        state = State.IDLE
        trip++
        main.removeCallbacks(endAfterArrival)
        destination = null
        route = null
        progress = null
        rerouting = false
        CarLocation.removeListener(onFix)
        CarLocation.release("nav")
        NavSim.stop()
        if (c != null) NavService.stop(c)
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        if (c != null) duck?.let { try { (c.getSystemService(Context.AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(it) } catch (_: Throwable) { } }
        changed()
    }

    private val onFix: (Location) -> Unit = { update(it) }

    private fun update(l: Location) {
        val r = route ?: return
        if (state == State.ARRIVED) return
        val here = LatLon(l.latitude, l.longitude)
        val accuracy = if (l.hasAccuracy()) l.accuracy.toDouble() else 20.0
        val limit = maxOf(40.0, accuracy * 1.5)
        var snap = r.line.snap(here, lastSeg) ?: return
        if (snap.offset > limit) {
            // beyond the usual search window (a GPS gap in a tunnel...): look along the rest of the route once
            r.line.snap(here, lastSeg, ahead = r.line.points.size)?.let { if (it.offset <= limit) snap = it }
        }
        val off = snap.offset > limit
        if (!off) lastSeg = snap.segment
        val speed = if (l.hasSpeed()) l.speed * 3.6 else 0.0
        val along = snap.along
        // the next thing the driver is told (a street that only changes its name is not one); a roundabout stays the
        // next one until the car has left it, with the distance to its exit
        var next = r.steps.indexOfFirst { !it.silent && it.at + it.span > along + 3 }
        if (next < 0) next = r.steps.size - 1
        val step = r.steps[next]
        val toNext = ((if (along > step.at) step.at + step.span else step.at) - along).coerceAtLeast(0.0)
        val remaining = (r.line.length - along).coerceAtLeast(0.0)
        // on the route the arrow follows the road (steady, and right even when a GPS module reports no heading);
        // off it, the GPS heading while moving
        val bearing = if (!off || !l.hasBearing() || speed <= 7) snap.bearing else l.bearing.toDouble()
        // (GPS reports 1-3 km/h for a parked car: that is standing still, and a reading is rounded, not cut)
        progress = Progress(if (off) here else snap.point, bearing, along, next, toNext, remaining, r.timeLeft(along),
            if (speed < 3) 0 else speed.roundToInt(), off)

        val dest = destination
        if (remaining < 25 || (dest != null && Geo.distance(here, dest.pos) < 30)) {
            arrive()
            return
        }
        // leaving the route: three bad fixes in a row while moving; retries back off while the car stays off it
        if (off) offCount++ else { offCount = 0; rerouteStreak = 0 }
        if (offCount >= 3 && speed > 5 && !rerouting && SystemClock.elapsedRealtime() >= nextRerouteAt) reroute(here, l)
        if (!off) prompt(r, next, toNext, along, speed)
        changed()
    }

    private fun forgetPrompts() {
        saidEarly.clear()
        saidFar.clear()
        saidNear.clear()
        saidLong.clear()
        pendingIntro = true
    }

    /**
     * The spoken prompts for the step ahead: early for a highway exit or at highway speed (crossing three lanes needs
     * more than the 20 s the far prompt gives at 120 km/h), far, and near (8 s ahead: an Arabic sentence takes about
     * 3 s); with the manoeuvre right after it when that one follows within 150 m. A route's first words say what comes
     * first, and a long stretch is announced as one ("استمر لمسافة 12 كيلومترًا").
     */
    private fun prompt(r: Route, next: Int, toNext: Double, along: Double, speedKmh: Double) {
        if (next <= 0) return
        val step = r.steps[next]
        val v = speedKmh / 3.6
        val far = (v * 22).coerceIn(400.0, 1500.0)
        val near = (v * 8).coerceIn(80.0, 300.0)
        val highway = step.type in setOf("off ramp", "on ramp", "fork", "merge") || speedKmh > 80
        val early = if (highway) (v * 60).coerceIn(1000.0, 2000.0) else 0.0
        // the stretch before it, from the last manoeuvre the driver was told about
        val prev = (next - 1 downTo 0).firstOrNull { !r.steps[it].silent } ?: 0
        val legLength = step.at - r.steps[prev].at
        val after = r.following(next)?.takeIf { it.at - (step.at + step.span) < 150 && it.type != "arrive" }
        val current = r.steps.indexOfLast { it.at <= along + 3 }.coerceAtLeast(0)
        if (pendingIntro && ttsReady && voiceOn) {
            pendingIntro = false
            if (toNext > near) {
                if (toNext > 3000) { saidLong.add(next); say(Instructions.continueFor(Instructions.road(r.steps[current]), toNext, ttsArabic)) }
                else say(Instructions.spoken(step, toNext, ttsArabic))
                if (toNext <= far) saidFar.add(next)
                if (toNext <= early) saidEarly.add(next)
                return
            }
        }
        when {
            toNext <= near && next !in saidNear -> {
                saidNear.add(next); saidFar.add(next); saidEarly.add(next)
                say(Instructions.spoken(step, if (toNext < 60) 0.0 else toNext, ttsArabic, after))
            }
            toNext <= far && toNext > near + 80 && legLength > near + 150 && next !in saidFar &&
                !(step.type == "arrive" && legLength < 600) -> {
                saidFar.add(next); saidEarly.add(next)
                say(Instructions.spoken(step, toNext, ttsArabic))
            }
            highway && toNext <= early && toNext > far + 300 && next !in saidEarly -> {
                saidEarly.add(next)
                say(Instructions.spoken(step, toNext, ttsArabic))
            }
            // just past a manoeuvre, a long way to the next one: how long this road goes on
            toNext > 3000 && next !in saidLong && along - r.steps[prev].at < 300 -> {
                saidLong.add(next)
                say(Instructions.continueFor(Instructions.road(r.steps[current]), toNext, ttsArabic))
            }
        }
    }

    private fun reroute(here: LatLon, l: Location) {
        val dest = destination ?: return
        rerouting = true
        // 8 s, 16 s, 32 s, then every 64 s while the car stays off the route
        nextRerouteAt = SystemClock.elapsedRealtime() + (8_000L shl rerouteStreak.coerceAtMost(3))
        if (rerouteStreak == 0) say(if (ttsArabic) "جارٍ تحديث المسار" else "Rerouting") // once per detour
        rerouteStreak++
        changed()
        val t = trip
        val moving = l.hasSpeed() && l.speed > 2f // a heading at walking pace is noise: it could pick the wrong road
        Router.route(here, dest.pos, if (l.hasBearing() && moving) l.bearing.toDouble() else null) { nr, _ ->
            if (t != trip) return@route // that trip has ended (or another one started)
            rerouting = false
            if (state != State.NAVIGATING) return@route
            if (nr != null) {
                route = nr
                lastSeg = 0
                offCount = 0
                forgetPrompts() // (the new route's first manoeuvre is said at once: the driver is waiting to hear it)
                CarLocation.last?.let { update(it) }
            }
            changed()
        }
    }

    private fun arrive() {
        state = State.ARRIVED
        arrivedAt = SystemClock.elapsedRealtime()
        progress = progress?.let { Progress(it.position, it.bearing, it.along, it.next, 0.0, 0.0, 0.0, it.speedKmh, false) }
        // by the place's name, unless it is only a pin's
        val name = destination?.name?.takeIf { it != app?.getString(R.string.maps_dropped_pin) }
        say(Instructions.arrived(name, ttsArabic))
        changed()
        NavSim.stop()
        // the trip ends by itself (GPS, service and the "arrived" card are not kept for a driver who drives on)
        main.removeCallbacks(endAfterArrival)
        main.postDelayed(endAfterArrival, ARRIVED_HOLD_MS)
    }

    // ------------------------------------------------------------------------------------------ voice
    // Prompts play as navigation guidance (the firmware's audio service and the mixer treat them as such) and take
    // transient focus that lets music duck under them instead of stopping.
    private val voiceAttrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private var duck: AudioFocusRequest? = null
    @Volatile private var lastUtterance = ""
    private var utterances = 0

    private fun initVoice(c: Context) {
        if (tts != null) return
        ttsArabic = arabic
        voiceMissing = false
        val created = try {
            TextToSpeech(c) { status ->
                // With no engine at all this runs inside the constructor, before `tts` is set.
                if (status != TextToSpeech.SUCCESS) {
                    voiceMissing = true
                    // forget the dead engine, so the next navigation looks again (e.g. after installing one)
                    tts?.let { try { it.shutdown() } catch (_: Throwable) { } }
                    tts = null
                    changed()
                    return@TextToSpeech
                }
                val t = tts ?: return@TextToSpeech
                if (ttsArabic && t.isLanguageAvailable(Locale("ar")) < TextToSpeech.LANG_AVAILABLE) ttsArabic = false // speak English then
                t.language = if (ttsArabic) Locale("ar") else Locale.US
                t.setAudioAttributes(voiceAttrs)
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) = finished(id)
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) = finished(id)
                    override fun onError(id: String?, errorCode: Int) = finished(id)
                    override fun onStop(id: String?, interrupted: Boolean) = finished(id)
                })
                ttsReady = true
            }
        } catch (_: Throwable) {
            voiceMissing = true
            null
        }
        if (voiceMissing) {
            try { created?.shutdown() } catch (_: Throwable) { }
            changed()
        } else {
            tts = created
        }
    }

    private fun say(text: String) {
        if (!voiceOn || !ttsReady) return
        val c = app ?: return
        val id = "aura-nav-${++utterances}"
        lastUtterance = id
        try {
            val req = duck ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(voiceAttrs).build().also { duck = it }
            (c.getSystemService(Context.AUDIO_SERVICE) as AudioManager).requestAudioFocus(req)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        } catch (_: Throwable) {
            finished(id)
        }
    }

    /** A prompt ended (TTS thread): give the focus back once the latest one is over (a flushed one ends first). */
    private fun finished(id: String?) {
        if (id != lastUtterance) return
        val c = app ?: return
        val req = duck ?: return
        try { (c.getSystemService(Context.AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(req) } catch (_: Throwable) { }
    }
}
