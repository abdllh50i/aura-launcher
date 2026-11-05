package com.abdllh.aura.chrome

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import com.abdllh.aura.R
import com.abdllh.aura.home.Actions
import com.abdllh.aura.home.AppRepo
import com.abdllh.aura.home.ControlsSheet
import com.abdllh.aura.home.HomeActivity
import com.abdllh.aura.home.Known
import com.abdllh.aura.home.Screens
import com.abdllh.aura.media.MediaMonitor
import com.abdllh.aura.media.NowPlaying
import com.abdllh.aura.music.BtMusic
import com.abdllh.aura.music.PhoneLink
import com.abdllh.aura.nav.CarLocation
import com.abdllh.aura.nav.MapExtras
import com.abdllh.aura.nav.NavSession
import com.abdllh.aura.nav.RoadWatch
import com.abdllh.aura.nav.Units
import com.abdllh.aura.settings.SettingsActivity
import com.abdllh.aura.system.CarAudio
import com.abdllh.aura.system.DriveState
import com.abdllh.aura.system.Front
import com.abdllh.aura.system.Gear
import com.abdllh.aura.system.Prayer
import com.abdllh.aura.system.PrayerTimes
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.Look
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Recolours
import com.abdllh.aura.ui.Snack
import com.abdllh.aura.ui.Surface
import com.abdllh.aura.ui.Surfaces
import com.abdllh.aura.ui.Theme
import com.abdllh.aura.ui.Tokens2
import com.abdllh.aura.ui.at
import com.abdllh.aura.ui.fadeTo
import com.abdllh.aura.util.Fmt
import com.abdllh.aura.util.Prefs
import com.abdllh.aura.voice.Amri
import java.util.Date
import kotlin.math.roundToInt

/**
 * What stays over every panel: the [Strip], the one [Row] with the [VolumeCluster] and the [VoiceButton] on it, and on Maps the [SongCard]. Made once when the window starts, never rebuilt: the theme goes
 * through in place ([Look]), a language change is a new window. The row is the same pixels on every screen and nothing of it moves when the screen changes; only the mark on the current tile goes over.
 *
 * Which panel is on top comes from [Front]; [show] is the whole of it, so a debug build can go through the same steps with no panel at all.
 */
class Chrome private constructor(private val shell: HomeActivity) : FrameLayout(shell), Recolours {
    internal val strip = Strip(shell, { openControls() }, { shell.root.night.show() })
    internal val row = Row(shell) { open(it) }
    internal val volume = VolumeCluster(shell)
    internal val voice = VoiceButton(shell) { voiceTap() }
    internal val songCard = SongCard(shell)

    /** Maps tells when a search or a place card lies over its speed card: the strip takes the speed then. */
    var speedCardHidden = false
        set(v) {
            if (v == field) return
            field = v
            refreshFaces(canMove())
        }

    private var panel = Screens.HOME
    private var appliedDark = Palette.dark
    private var trip = false
    private var song: NowPlaying? = null
    private var speedShown = false
    private var kmh: Int? = null
    private var gps = false
    private var wifi = false
    private var lastFixAt = 0L
    private var receiving = false
    private var networkWatched = false

    init {
        // (from the left in every language, the seat and not the language: section 7; text inside goes its own way)
        layoutDirection = LAYOUT_DIRECTION_LTR
        addView(strip, at(0, 0, 1024, 48))
        addView(row, at(0, Row.TOP, 1024, 600 - Row.TOP))
        addView(volume, at(0, 516, 204, 84))
        addView(voice, at(186, 502, VoiceButton.SIZE, VoiceButton.SIZE))
        addView(songCard, Surface.PILL.at(SongCard.X, SongCard.Y, SongCard.W, SongCard.H))
        songCard.onOpen = { Screens.music(context) }
        strip.speed.visibility = INVISIBLE
        strip.speed.alpha = 0f
        show(shell.topPanel.ifEmpty { Screens.HOME }, false)
        // (what it shows comes when it is attached: refreshAll, before its first frame)
    }

    // ------------------------------------------------------------------------------------------ the panel on top
    /** Panel [name] came to the top: the mark, the song's card and the strip's faces follow it. */
    internal fun show(name: String, animate: Boolean) {
        panel = name
        row.mark(name)
        strip.time.fade(name != Screens.HOME, animate)
        refreshFaces(animate)
    }

    private fun canMove() = shell.resumedNow && isShown

    /** In or out in place; what is out is not drawn and takes no touch. */
    private fun View.fade(on: Boolean, animate: Boolean) {
        val target = if (on) 1f else 0f
        if (alpha == target && (visibility == VISIBLE) == on) return
        animate().cancel()
        if (!animate) {
            alpha = target
            visibility = if (on) VISIBLE else INVISIBLE
            return
        }
        visibility = VISIBLE
        fadeTo(target, Tokens2.MS_swap, Motion.MOVE) { if (!on) visibility = INVISIBLE }
    }

    private fun refreshFaces(animate: Boolean) {
        songCard.fade(ChromeRules.songCard(panel, song != null), animate)
        strip.setGear(if (Gear.canReports) Gear.current else null, Gear.sure)
        val want = ChromeRules.stripSpeed(panel, DriveState.moving, trip, speedCardHidden)
        if (want != speedShown && !debugSpeedHeld) {
            speedShown = want
            if (want) syncSpeed()
            strip.speed.fade(want, animate)
        }
    }

    private fun refreshAll(animate: Boolean) {
        trip = NavSession.state != NavSession.State.IDLE
        volume.sync()
        strip.setTime(Fmt.clock(Date(), Prefs.clock24))
        wifi = Actions.wifiConnected(context)
        syncStatus()
        bindSong(MediaMonitor.current, false)
        refreshFaces(animate)
    }

    // ------------------------------------------------------------------------------------------ the song
    private val onSong: (NowPlaying?) -> Unit = { n ->
        // (the same song published again is most of what comes)
        if (n != song) {
            val faceMayChange = (n == null) != (song == null)
            bindSong(n, true)
            if (faceMayChange) refreshFaces(canMove())
        }
    }

    private fun bindSong(n: NowPlaying?, animate: Boolean) {
        song = n
        if (n != null) songCard.setSong(n.title, n.art, n.playing, animate)
    }

    // ------------------------------------------------------------------------------------------ the strip's data
    private val onFix: (Location) -> Unit = { l -> post { takeFix(l) } }

    private fun takeFix(l: Location) {
        val age = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000L
        if (age >= FIX_MAX_AGE_MS) return // (the last known one at a start)
        lastFixAt = SystemClock.uptimeMillis()
        gps = true
        kmh = if (l.hasSpeed()) (l.speed * 3.6f).roundToInt() else null
        removeCallbacks(fixGone)
        postDelayed(fixGone, FIX_MAX_AGE_MS - age)
        syncStatus()
        syncSpeed()
    }

    private val fixGone = Runnable {
        gps = false
        kmh = null
        syncStatus()
        syncSpeed()
    }

    private val onRoad: () -> Unit = { post { syncSpeed() } }

    private fun syncSpeed() {
        if (!speedShown || debugSpeedHeld) return
        val w = RoadWatch.state
        val spot = w.spot
        val saher = if (spot != null && spot.kind == MapExtras.Kind.CAMERA) Units.distance(w.metres.toDouble(), Fonts.arabicUi) else null
        strip.speed.set(kmh, w.limit, w.over, saher)
    }

    private fun syncStatus() {
        if (debugStatus) return
        strip.setStatus(gps, wifi, (BtMusic.available && BtMusic.connected) || PhoneLink.connected)
    }

    private val onLink: () -> Unit = { post { syncStatus() } }

    // (the two calls that ask for it go to another process: asked when the network says something changed, and not at every fix)
    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) = wifiChanged()
        override fun onLost(n: Network) = wifiChanged()
    }

    private fun wifiChanged() {
        post {
            wifi = Actions.wifiConnected(context)
            syncStatus()
        }
    }

    private val onGear: () -> Unit = { refreshFaces(canMove()) }

    private val onDrive: () -> Unit = { refreshFaces(canMove()) }

    private val onNav: () -> Unit = {
        val t = NavSession.state != NavSession.State.IDLE
        if (t != trip) {
            trip = t
            refreshFaces(canMove())
        }
    }

    // ------------------------------------------------------------------------------------------ the voice and the volume
    private fun voiceTap() {
        // (Amri, or its settings until the microphone is allowed: as the old quick button)
        if (Amri.hasMicPermission(context)) Amri.wake(context) else Screens.settings(context, SettingsActivity.PAGE_VOICE)
    }

    private val onVoice: () -> Unit = { voice.listening(Amri.state == Amri.State.LISTENING) }

    private val onCarAudio: () -> Unit = { volume.sync() }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = minute()
    }

    /**
     * Once a minute, in place. A speed set on a fix at the other end of the window would make one damage rect of the whole width, so while fixes come this waits until 200 ms after the
     * next one (SPEC.md section 4).
     */
    private fun minute() {
        removeCallbacks(minuteNow)
        val since = SystemClock.uptimeMillis() - lastFixAt
        postDelayed(minuteNow, if (gps) ((FIX_MS + AFTER_FIX_MS - since % FIX_MS) % FIX_MS) else 0L)
    }

    private val minuteNow = Runnable { strip.setTime(Fmt.clock(Date(), Prefs.clock24)) }

    /** The clock's form changed in Settings: now, not at the next minute. */
    fun clockChanged() = minute()

    // ------------------------------------------------------------------------------------------ opening what the row names
    internal fun open(name: String) {
        if (name == panel) return // (the screen he is on)
        when (name) {
            Screens.HOME -> shell.panels.show(Screens.HOME)
            Screens.MAPS -> if (!Actions.nav(context)) Snack.show(context, R.string.err_no_nav)
            Screens.MUSIC -> Screens.music(context)
            Screens.SETTINGS -> Screens.settings(context)
            Screens.APPS -> Screens.apps(context)
            // (no panel in this version: the firmware's own dial pad, the design's fallback, section 6.0's table)
            Screens.PHONE -> if (!AppRepo.launch(context, Known.PHONE)) Snack.show(context, R.string.err_app_missing)
        }
    }

    private fun openControls() {
        val s = sheet<ControlsSheet>() ?: return
        // (a tap on the strip while it is open: that very touch has just closed it)
        if (SystemClock.uptimeMillis() - s.closedByTouchAt < SAME_TOUCH_MS) return
        s.open()
    }

    private inline fun <reified T : com.abdllh.aura.ui.Sheet> sheet(): T? {
        val s = shell.root.sheets
        var found: T? = null
        for (i in 0 until s.childCount) {
            val c = s.getChildAt(i) as? com.abdllh.aura.ui.Sheet ?: continue
            if (c is T) found = c else if (c.isOpen) c.close()
        }
        return found
    }

    // ------------------------------------------------------------------------------------------ the window
    private val onPanel: (String) -> Unit = { name -> if (name.isNotEmpty() && name != panel) show(name, canMove()) }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // (a recreate puts back what was on top after this was built, and Front, which goes through it, hears no change: same name)
        val top = shell.topPanel
        if (top.isNotEmpty() && top != panel && !debugHeld) show(top, false)
        Front.addPanelListener(onPanel)
        DriveState.addListener(onDrive)
        Gear.addListener(onGear)
        NavSession.addListener(onNav)
        RoadWatch.addListener(onRoad)
        CarLocation.addListener(onFix)
        CarAudio.addListener(onCarAudio)
        BtMusic.addListener(onLink)
        PhoneLink.addListener(onLink)
        Amri.addStateListener(onVoice)
        Amri.onLevel = { l -> voice.level(l) }
        MediaMonitor.observe(onSong)
        // (the overlay windows too: a theme changed by an old panel's own path does not go through ThemeSwap, which tells them)
        Theme.changed = { post { if (appliedDark != Palette.dark) { Look.apply(this); Look.applyToWindows() } } }
    }

    override fun onDetachedFromWindow() {
        Front.removePanelListener(onPanel)
        DriveState.removeListener(onDrive)
        Gear.removeListener(onGear)
        NavSession.removeListener(onNav)
        RoadWatch.removeListener(onRoad)
        CarLocation.removeListener(onFix)
        CarAudio.removeListener(onCarAudio)
        BtMusic.removeListener(onLink)
        PhoneLink.removeListener(onLink)
        Amri.removeStateListener(onVoice)
        Amri.onLevel = null
        MediaMonitor.unobserve(onSong)
        Theme.changed = null
        removeCallbacks(fixGone)
        removeCallbacks(minuteNow)
        watchWindow(false)
        super.onDetachedFromWindow()
    }

    /** With the window on screen: the receivers, the place, and a look at what changed while it was not. */
    override fun onWindowVisibilityChanged(v: Int) {
        super.onWindowVisibilityChanged(v)
        watchWindow(v == VISIBLE)
    }

    private fun watchWindow(on: Boolean) {
        if (on == receiving) return
        receiving = on
        if (on) {
            CarLocation.acquire(context, HOLDER)
            try {
                context.registerReceiver(receiver, IntentFilter().apply {
                    addAction(Intent.ACTION_TIME_TICK)
                    addAction(Intent.ACTION_TIME_CHANGED)
                    addAction(Intent.ACTION_TIMEZONE_CHANGED)
                })
            } catch (_: Throwable) { }
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), network)
                networkWatched = true
            } catch (_: Throwable) { }
            refreshAll(false)
        } else {
            CarLocation.release(HOLDER)
            try { context.unregisterReceiver(receiver) } catch (_: Throwable) { }
            if (networkWatched) {
                networkWatched = false
                try { (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(network) } catch (_: Throwable) { }
            }
        }
    }

    override fun onLook() {
        appliedDark = Palette.dark
    }

    override fun hasOverlappingRendering() = false

    // ------------------------------------------------------------------------------------------ debug builds
    private var debugHeld = false

    /** The same steps as a panel coming to the top, with no panel: for the gallery and the probe. */
    internal fun debugShow(name: String, animate: Boolean = true) {
        debugHeld = true
        show(name, animate)
    }

    /** Where everything has come to rest, for the log. */
    internal fun debugState() = "panel=$panel top=${shell.topPanel} card=${songCard.visibility} moving=${DriveState.moving} speed=$speedShown"

    /** All on and staying so, as the mockups draw them. */
    internal var debugStatus = false
        set(v) {
            field = v
            if (v) strip.setStatus(gps = true, wifi = true, phone = true) else syncStatus()
        }

    private var debugSpeedHeld = false

    internal fun debugSpeed(v: Int?, limit: Int, over: Boolean, saher: String?) {
        debugSpeedHeld = true
        speedShown = true
        strip.speed.animate().cancel()
        strip.speed.visibility = VISIBLE
        strip.speed.alpha = 1f
        strip.speed.set(v, limit, over, saher)
    }

    companion object {
        private const val TAG = "AuraChrome"
        private const val HOLDER = "chrome"
        private const val FIX_MAX_AGE_MS = 5000L
        private const val FIX_MS = 1000L
        private const val AFTER_FIX_MS = 200L
        private const val SAME_TOUCH_MS = 700L

        /** Debug builds, until the window's next start: off for the gallery's pages, which draw their own (debug/GalleryPanel.kt, debug/ChromeProbe.kt). */
        internal fun debugOn(shell: HomeActivity, on: Boolean) {
            if (on) attach(shell) else shell.root.chrome.removeAllViews()
        }

        /**
         * At the process's start, off the main thread: what the row's first frame needs. The first hardware bitmap of a process brings up a GL context of the uploader's on the calling thread
         * (about 600 ms on a loaded emulator, against 12 ms for each one after it), and the prayer times are a hundred looks at the sun in code not yet warm.
         */
        fun warm(c: Context) {
            val app = c.applicationContext
            Thread({
                try {
                    for (stem in arrayOf("cap-left", "cap-dock", "wall-hole", "wall-gl", "wall")) Surfaces.themed(app, stem)
                    Prayer.next()
                } catch (t: Throwable) {
                    Log.w(TAG, "warm: $t")
                }
            }, "chrome-warm").start()
        }

        /** From the window's start, before its first frame. */
        fun attach(shell: HomeActivity) {
            if (of(shell) != null) return
            val t0 = SystemClock.uptimeMillis()
            shell.root.chrome.addView(Chrome(shell), FrameLayout.LayoutParams(1024, 600))
            Log.i(TAG, "built in ${SystemClock.uptimeMillis() - t0} ms")
        }

        fun of(shell: HomeActivity): Chrome? = shell.root.chrome.getChildAt(0) as? Chrome
    }
}

/** The row's words: what a panel is called; Home's clock asks what a prayer is called. */
internal object ChromeWords {
    fun nameOf(panel: String): Int = when (panel) {
        Screens.HOME -> R.string.chrome_home
        Screens.MAPS -> R.string.dock_nav
        Screens.MUSIC -> R.string.dock_music
        Screens.PHONE -> R.string.dock_phone
        Screens.APPS -> R.string.dock_apps
        else -> R.string.dock_settings
    }

    fun prayer(p: PrayerTimes.Prayer): Int = when (p) {
        PrayerTimes.Prayer.FAJR -> R.string.chrome_fajr
        PrayerTimes.Prayer.DHUHR -> R.string.chrome_dhuhr
        PrayerTimes.Prayer.ASR -> R.string.chrome_asr
        PrayerTimes.Prayer.MAGHRIB -> R.string.chrome_maghrib
        PrayerTimes.Prayer.ISHA -> R.string.chrome_isha
    }
}
