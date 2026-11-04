package com.abdllh.aura.link

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.home.Actions
import com.abdllh.aura.home.Screens
import com.abdllh.aura.media.MediaMonitor
import com.abdllh.aura.media.NowPlaying
import com.abdllh.aura.music.PhoneLink
import com.abdllh.aura.nav.CarLocation
import com.abdllh.aura.nav.Geo
import com.abdllh.aura.nav.Instructions
import com.abdllh.aura.nav.NavSession
import com.abdllh.aura.nav.Place
import com.abdllh.aura.nav.Places
import com.abdllh.aura.nav.Trips
import com.abdllh.aura.nav.Units
import com.abdllh.aura.system.CarAudio
import com.abdllh.aura.system.CrashGuard
import com.abdllh.aura.system.Gear
import com.abdllh.aura.system.WifiKeeper
import com.abdllh.aura.util.Prefs
import com.abdllh.aura.voice.Amri
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The server's only door into the app: what it may ask of the map, the music and the car. [NavSession] and the screens
 * are main-thread only, so everything here runs posted to the main thread, caught, and a server thread that needs the
 * answer waits with a time limit ([ask]); the main thread never waits for anybody here. The redesign may rename the
 * Maps internals: this file changes then, the server does not. Also the live feed behind GET /v1/events: it listens
 * only while a phone does.
 */
internal object NavBridge {
    private const val TAG = "AuraNavBridge"
    /** At most one "progress" in this time (the session reports every fix). */
    private const val PROGRESS_MS = 10_000L
    /** How often the feed looks at what has no listener of its own (the call, the fix). */
    private const val LOOK_MS = 5_000L
    private const val LOOKUP_WAIT_S = 9L

    private val main = Handler(Looper.getMainLooper())

    fun post(what: String, block: () -> Unit) {
        main.post { try { block() } catch (t: Throwable) { Log.w(TAG, "$what: $t") } }
    }

    /** [block] on the main thread and its answer; null when it threw or did not come in time. Not from the main thread. */
    fun <T> ask(ms: Long, block: () -> T): T? {
        val latch = CountDownLatch(1)
        val box = AtomicReference<T?>()
        main.post {
            try { box.set(block()) } catch (t: Throwable) { Log.w(TAG, "ask: $t") }
            latch.countDown()
        }
        return if (latch.await(ms, TimeUnit.MILLISECONDS)) box.get() else null
    }

    private val arabic get() = Locale.getDefault().language == "ar"

    // ------------------------------------------------------------------------------------------ places
    /** A place on its way to the main thread: at most one. */
    private class Waiting(val id: String, val run: () -> Unit)

    private val waiting = AtomicReference<Waiting?>()
    private val drainPosted = AtomicBoolean(false)
    private val drain = Runnable {
        drainPosted.set(false) // (before the take: one that comes in between posts its own, which then finds nothing or the newest)
        val w = waiting.getAndSet(null)
        if (w != null) try { w.run() } catch (t: Throwable) { Log.w(TAG, "place: $t") }
    }

    /**
     * Never one Runnable per request: a loop of requests would outrun the main thread, and the backlog is a frozen home
     * screen to the driver and to the guard. A newer place takes the waiting one's seat, as it would on the screen (only one
     * place at a time, [PlaceInbox]); that one's phone hears so, since it will never be heard of again.
     */
    private fun offer(id: String, run: () -> Unit) {
        waiting.getAndSet(Waiting(id, run))?.let { old -> PhoneServer.navEvent(old.id, "replaced", null) }
        if (drainPosted.compareAndSet(false, true)) main.post(drain)
    }

    fun place(ctx: Context, x: PlaceInbox.Incoming) = offer(x.id) { PlaceInbox.receive(ctx, x) }

    fun hasSaved(which: String) = Trips.saved(which) != null || Trips.legacy(which).isNotBlank()

    /** Home or Work: as any place from a paired phone ([PlaceInbox]), found first if it was only typed in 1.0. */
    fun saved(ctx: Context, which: String, id: String, peer: String, pin: String) = offer(id) {
        val p = Trips.saved(which)
        if (p != null) {
            PlaceInbox.receive(ctx, PlaceInbox.Incoming(id, p.takeIf { it.name.isNotBlank() } ?: Place(pin, p.detail, p.pos), "saved.$which", null, true, peer, ask = false))
        } else {
            Trips.findLegacy(which, arabic) { found ->
                if (found != null) PlaceInbox.receive(ctx, PlaceInbox.Incoming(id, found, "saved.$which", null, true, peer, ask = false))
                else PhoneServer.navEvent(id, "failed", JSONObject().put("reason", "address_not_found"))
            }
        }
    }

    /** The screen's search on [q]; "" when it opened, else why not. */
    fun searchHere(ctx: Context?, q: String): String {
        val c = ctx ?: return "busy"
        if (Amri.reversing) return "reversing"
        if (NavSession.state == NavSession.State.NAVIGATING) return "navigating"
        Screens.mapsSearch(c, q)
        return ""
    }

    /** The unit's own search, for the phone: the results, nearest band first; null when nothing could be asked. Blocks. */
    fun lookup(q: String): JSONArray? {
        val latch = CountDownLatch(1)
        val box = AtomicReference<List<Place>?>()
        val nearBox = AtomicReference<com.abdllh.aura.nav.LatLon?>()
        post("lookup") {
            val near = CarLocation.lastKnown()
            nearBox.set(near)
            Places.lookup(q, near, arabic) { list, last ->
                box.set(list)
                if (last) latch.countDown()
            }
        }
        latch.await(LOOKUP_WAIT_S, TimeUnit.SECONDS)
        val list = box.get() ?: return null
        val near = nearBox.get()
        return JSONArray().also { a ->
            for (p in list.take(12)) {
                val o = p.toJson()
                if (near != null) o.put("distanceM", Math.round(Geo.distance(near, p.pos)))
                a.put(o)
            }
        }
    }

    fun places(): JSONObject = JSONObject()
        .put("home", Places.home?.toJson() ?: JSONObject.NULL)
        .put("work", Places.work?.toJson() ?: JSONObject.NULL)
        .put("favourites", JSONArray().also { a -> Places.favourites.forEach { a.put(it.toJson()) } })
        .put("recents", JSONArray().also { a -> Places.recents.forEach { a.put(it.toJson()) } })

    fun setPlaces(home: Pair<Boolean, Place?>, work: Pair<Boolean, Place?>, favourites: List<Place>?, recents: List<Place>?) {
        if (home.first) Places.home = home.second
        if (work.first) Places.work = work.second
        favourites?.let { Places.favourites = it }
        recents?.let { Places.setRecents(it) }
    }

    // ------------------------------------------------------------------------------------------ the remote
    private val COMMANDS = setOf("music.toggle", "music.next", "music.prev", "volume.up", "volume.down", "screen.off", "voice.mute", "open.maps", "open.music")

    fun isCommand(cmd: String) = cmd in COMMANDS

    /** Does [cmd]; what really happened comes back. */
    fun remote(ctx: Context?, cmd: String): JSONObject {
        val c = ctx ?: return JSONObject().put("state", "refused").put("reason", "busy")
        val o = JSONObject().put("cmd", cmd)
        // (nothing comes over the reversing camera, whoever asks)
        if (cmd.startsWith("open.") && Amri.reversing) return o.put("state", "refused").put("reason", "reversing")
        when (cmd) {
            "music.toggle" -> MediaMonitor.send(c, MediaMonitor.Key.PLAY_PAUSE)
            "music.next" -> MediaMonitor.send(c, MediaMonitor.Key.NEXT)
            "music.prev" -> MediaMonitor.send(c, MediaMonitor.Key.PREV)
            "volume.up" -> { CarAudio.setMuted(false); CarAudio.step(1) }
            "volume.down" -> CarAudio.step(-1)
            "screen.off" -> if (!Actions.screenOff(c)) return o.put("state", "refused").put("reason", "failed")
            "voice.mute" -> NavSession.voiceOn = !NavSession.voiceOn
            "open.maps" -> if (!Actions.nav(c)) return o.put("state", "refused").put("reason", "no_app")
            "open.music" -> Screens.music(c)
        }
        return o.put("state", "ok").put("volume", CarAudio.volume()).put("muted", CarAudio.muted()).put("navVoice", NavSession.voiceOn)
    }

    // ------------------------------------------------------------------------------------------ what is going on
    /** The trip as it is now, in the shape of GET /v1/state's "nav". */
    fun navJson(): JSONObject {
        val o = JSONObject()
        val a = Trips.attempt
        val st = NavSession.state
        o.put("state", when {
            st == NavSession.State.NAVIGATING -> "navigating"
            st == NavSession.State.ARRIVED -> "arrived"
            a?.phase == Trips.Phase.WAITING_FIX -> "waiting_fix"
            a != null -> "routing"
            else -> "idle"
        })
        NavSession.destination?.let { o.put("place", it.toJson()) } ?: a?.let { o.put("place", it.place.toJson()) }
        if (st != NavSession.State.IDLE) o.put("by", Trips.startedBy)
        val r = NavSession.route
        val p = NavSession.progress
        if (r != null && p != null) {
            o.put("remainingM", Math.round(p.remaining)).put("timeLeftS", Math.round(p.timeLeft))
                .put("etaLocal", Units.arrival(p.timeLeft, Prefs.clock24)).put("speedKmh", p.speedKmh)
                .put("rerouting", NavSession.rerouting)
            r.steps.getOrNull(p.next)?.let { o.put("nextText", Instructions.text(it, arabic)) }
        }
        PlaceInbox.pendingJson()?.let { o.put("pending", it) }
        return o
    }

    private fun musicJson(): JSONObject {
        val n: NowPlaying? = MediaMonitor.current
        val o = JSONObject().put("playing", n?.playing == true).put("volume", CarAudio.volume()).put("maxVolume", CarAudio.max()).put("muted", CarAudio.muted())
        if (n != null) o.put("title", n.title).put("artist", n.artist).put("source", n.sourcePkg ?: "")
        return o
    }

    private fun carJson(): JSONObject {
        val o = JSONObject().put("gear", Gear.current?.name ?: JSONObject.NULL).put("gearSure", Gear.sure)
            .put("reversing", Amri.reversing).put("inCall", Amri.inCall)
        CarLocation.last?.let { l ->
            o.put("fix", JSONObject().put("lat", l.latitude).put("lon", l.longitude)
                .put("ageS", (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000_000L)
                .put("kmh", if (l.hasSpeed()) Math.round(l.speed * 3.6f) else 0))
        }
        return o
    }

    private fun linkJson(): JSONObject = JSONObject()
        .put("naghm", PhoneLink.connected)
        .put("wifi", WifiKeeper.state.name.lowercase(Locale.ROOT))
        .put("address", PhoneServer.address() ?: JSONObject.NULL)
        .put("listening", PhoneServer.listening)

    fun snapshot(): JSONObject = JSONObject().put("nav", navJson()).put("music", musicJson()).put("car", carJson()).put("link", linkJson())

    fun snapshotEvents(): List<Pair<String, JSONObject>> = listOf("nav" to navJson(), "music" to musicJson(), "car" to carJson())

    /** GET /v1/diag: for a report from the unit. No token, no key, nothing secret. */
    fun diag(c: Context?): String {
        val b = StringBuilder()
        b.append("AMRI ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
        b.append("server: ").append(PhoneServer.status).append(" address ").append(PhoneServer.address() ?: "none").append(":").append(PhoneServer.PORT)
            .append(" listening ").append(PhoneServer.listening).append(" paired ").append(Pairing.devices().size).append('\n')
        b.append("last request: ").append(PhoneServer.lastRequest).append('\n')
        b.append("last place: ").append(PlaceInbox.lastRaw).append('\n')
        b.append("naghm: ").append(if (PhoneLink.connected) "connected" else "not connected").append("  ").append(PhoneLink.note).append('\n')
        val l = CarLocation.last
        b.append("gps: ").append(if (l == null) "no fix" else "${l.provider} ${(SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000_000L}s ago accuracy ${l.accuracy}").append('\n')
        b.append("trip: ").append(NavSession.state).append(" attempt ").append(Trips.attempt?.phase ?: "none").append('\n')
        b.append("wifi: ").append(WifiKeeper.state).append(' ').append(WifiKeeper.ssid).append('\n')
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT)
        for (e in WifiKeeper.log().takeLast(20)) {
            b.append("  ").append(fmt.format(java.util.Date(e.at))).append(' ').append(e.type).append(' ').append(e.a).append(' ').append(e.b).append('\n')
        }
        c?.let { CrashGuard.lastCrash(it) }?.let { b.append("last crash:\n").append(it.lineSequence().take(12).joinToString("\n")).append('\n') }
        return b.toString()
    }

    // ------------------------------------------------------------------------------------------ the live feed
    private var feeding = false
    private var navState = ""
    private var progressAt = 0L
    private var lastMusic = ""
    private var lastCar = ""

    private val onNav: () -> Unit = { navChanged() }
    private val onMusic: (NowPlaying?) -> Unit = { musicChanged() }
    private val onAudio: () -> Unit = { musicChanged() }
    private val onGear: () -> Unit = { carChanged() }
    private val look = object : Runnable {
        override fun run() {
            if (!feeding) return
            carChanged()
            main.postDelayed(this, LOOK_MS)
        }
    }

    /** On (somebody listens) or off (the last one went). Main thread. */
    fun feed(on: Boolean) {
        if (on == feeding) return
        feeding = on
        if (on) {
            navState = navJson().optString("state")
            progressAt = SystemClock.elapsedRealtime()
            // (what the snapshot just said is no news)
            lastMusic = musicJson().toString()
            lastCar = carKey(carJson())
            NavSession.addListener(onNav)
            Trips.addListener(onNav)
            MediaMonitor.observe(onMusic)
            CarAudio.addListener(onAudio)
            Gear.addListener(onGear)
            main.postDelayed(look, LOOK_MS)
        } else {
            NavSession.removeListener(onNav)
            Trips.removeListener(onNav)
            MediaMonitor.unobserve(onMusic)
            CarAudio.removeListener(onAudio)
            Gear.removeListener(onGear)
            main.removeCallbacks(look)
        }
    }

    private fun navChanged() {
        val now = SystemClock.elapsedRealtime()
        val o = navJson()
        val st = o.optString("state")
        if (st != navState) {
            navState = st
            progressAt = now
            PhoneServer.publish("nav", o)
        } else if (st == "navigating" && now - progressAt >= PROGRESS_MS) {
            progressAt = now
            o.put("state", "progress")
            PhoneServer.publish("nav", o)
        }
    }

    private fun musicChanged() {
        val s = musicJson().toString()
        if (s == lastMusic) return
        lastMusic = s
        PhoneServer.publish("music", JSONObject(s))
    }

    /** The fix's age and position change every moment: what is news is the gear, the call, whether there is a fix. */
    private fun carKey(o: JSONObject) = "${o.opt("gear")}|${o.optBoolean("gearSure")}|${o.optBoolean("reversing")}|${o.optBoolean("inCall")}|${o.has("fix")}"

    private fun carChanged() {
        val o = carJson()
        val key = carKey(o)
        if (key == lastCar) return
        lastCar = key
        PhoneServer.publish("car", o)
    }
}
