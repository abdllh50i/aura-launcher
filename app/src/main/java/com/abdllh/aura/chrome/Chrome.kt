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
import com.abdllh.aura.ui.Asked
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.Look
import com.abdllh.aura.ui.Modal
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Recolours
import com.abdllh.aura.ui.Snack
import com.abdllh.aura.ui.Surfaces
import com.abdllh.aura.ui.Theme
import com.abdllh.aura.ui.Tokens2
import com.abdllh.aura.ui.at
import com.abdllh.aura.util.Fmt
import com.abdllh.aura.util.Prefs
import com.abdllh.aura.voice.Amri
import java.util.Date
import kotlin.math.roundToInt

/**
 * What stays over every panel (SPEC.md section 6.0): the [Strip], Home's [RowHome] and the [RowDock], which cross-fade, and above both the [VolumeCluster] and the
 * [VoiceButton], which never fade. Made once when the window starts, never rebuilt: the theme goes through in place ([Look]), a language change is a new window.
 *
 * The two rows are one row: the volume and the mic's place do not change, and the rest cross-fades in 160 ms (section 9). In the order where Home's tiles each stand one cell to the left of their dock
 * cell ([ChromeRules.shifts]), that part of the row moves by the cell in the same 160 ms on the same curve, tiles and glyphs at the same x at every frame, so the eye follows one row that makes room for
 * the home cell, which fades in where it is. Only alpha and translation, and no layer: a group's alpha goes into each thing it draws. A change while one runs turns back from where it is.
 *
 * Which panel is on top comes from [Front]; [show] is the whole of it, so a debug build can go through the same steps with no panel at all.
 */
class Chrome private constructor(private val shell: HomeActivity, order: List<Asked.Cell>) : FrameLayout(shell), Recolours {
    internal val strip = Strip(shell, { openControls() }, { shell.root.night.show() })
    internal val rowHome = RowHome(shell, order) { open(it) }
    internal val rowDock = RowDock(shell, order) { open(it) }
    internal val volume = VolumeCluster(shell)
    internal val voice = VoiceButton(shell) { voiceTap() }

    /** Maps tells when a search or a place card lies over its speed card: the strip takes the speed then. */
    var speedCardHidden = false
        set(v) {
            if (v == field) return
            field = v
            refreshFaces(canMove())
        }

    private var panel = Screens.HOME
    private var onHome = true
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

    private val tweens = Tweens(onRest = { atRest() })
    private val homeA = tweens.part(1f) { v -> rowHome.alpha = v }
    private val dockA = tweens.part(0f) { v -> rowDock.alpha = v }
    private val shift = tweens.part(0f) { k -> rowHome.shift(k); rowDock.shift(k) }
    private val face = tweens.part(0f) { v -> voice.face = v }
    private val stripHome = tweens.part(1f) { v -> strip.homeFace = v }
    private val stripOther = tweens.part(0f) { v -> strip.otherFace = v }
    private val speedA = tweens.part(0f) { v -> strip.speed.alpha = v }

    init {
        // (from the left in every language, the seat and not the language: section 7; text inside goes its own way)
        layoutDirection = LAYOUT_DIRECTION_LTR
        addView(strip, at(0, 0, 1024, 48))
        addView(rowHome, at(0, RowHome.TOP, 1024, 600 - RowHome.TOP))
        addView(rowDock, at(0, RowDock.TOP, 1024, 600 - RowDock.TOP))
        addView(volume, at(0, 516, 204, 84))
        addView(voice, at(186, 502, VoiceButton.SIZE, VoiceButton.SIZE))
        strip.speed.visibility = INVISIBLE
        rowDock.glance.onTitle = { openPlayer() }
        panel = shell.topPanel.ifEmpty { Screens.HOME }
        onHome = panel == Screens.HOME
        snapRows()
        // (what it shows comes when it is attached: refreshAll, before its first frame)
    }

    // ------------------------------------------------------------------------------------------ the panel on top
    /** Panel [name] came to the top: the row, the plate, the glance and the strip's faces follow it. */
    internal fun show(name: String, animate: Boolean) {
        panel = name
        val home = name == Screens.HOME
        val rowChanges = home != onHome
        if (rowChanges) {
            onHome = home
            rowChange(animate)
        } else if (!home) {
            if (animate) rowDock.slideTo(name) else rowDock.placeOn(name)
        }
        // (the glance's own crossfade only inside a dock that is there: coming with the row, it is already the right face)
        refreshFaces(animate, glance = animate && !rowChanges && !home)
    }

    private fun canMove() = shell.resumedNow && Prefs.screenAnimations && isShown

    private fun rowChange(animate: Boolean) {
        rowHome.live = onHome
        rowDock.live = !onHome
        rowHome.visibility = VISIBLE
        rowDock.visibility = VISIBLE
        if (!onHome) rowDock.placeOn(panel)
        if (!animate) {
            snapRows()
            return
        }
        val row = Tokens2.MS_dockRow
        val out = Tokens2.MS_homeOut
        if (onHome) {
            homeA.to(1f, 0L, row, Motion.MOVE)
            dockA.to(0f, 0L, out, Motion.EXIT)
            shift.to(0f, 0L, row, Motion.MOVE)
            face.to(0f, 0L, row, Motion.MOVE)
            stripHome.to(1f, Tokens2.MS_columnDelay, Tokens2.MS_cardIn, Motion.ENTER)
            stripOther.to(0f, 0L, out, Motion.EXIT)
        } else {
            homeA.to(0f, 0L, out, Motion.EXIT)
            dockA.to(1f, 0L, row, Motion.MOVE)
            shift.to(1f, 0L, row, Motion.MOVE)
            face.to(1f, 0L, row, Motion.MOVE)
            stripHome.to(0f, 0L, out, Motion.EXIT)
            stripOther.to(1f, 0L, out, Motion.EXIT)
        }
    }

    private fun snapRows() {
        tweens.stop()
        rowHome.live = onHome
        rowDock.live = !onHome
        val k = if (onHome) 0f else 1f
        homeA.snap(1f - k)
        dockA.snap(k)
        shift.snap(k)
        face.snap(k)
        stripHome.snap(1f - k)
        stripOther.snap(k)
        speedA.snap(if (speedShown) 1f else 0f) // (its own fade too, if it was on its way: all of it stopped above)
        if (!onHome) rowDock.placeOn(panel)
        atRest()
    }

    /** What has faded out is not drawn and takes no touch. */
    private fun atRest() {
        if (tweens.isRunning) return
        rowHome.visibility = if (onHome) VISIBLE else INVISIBLE
        rowDock.visibility = if (onHome) INVISIBLE else VISIBLE
        if (speedA.value <= 0f && strip.speed.visibility != INVISIBLE) strip.speed.visibility = INVISIBLE
    }

    private fun refreshFaces(animate: Boolean, glance: Boolean = animate && !onHome) {
        val moving = DriveState.moving
        rowDock.glance.face(ChromeRules.glance(panel, trip, song != null) == ChromeRules.Glance.MINI, glance)
        strip.setGear(Gear.current, Gear.sure, moving)
        val want = ChromeRules.stripSpeed(panel, moving, trip, speedCardHidden)
        if (want != speedShown && !debugSpeedHeld) {
            speedShown = want
            if (want) {
                strip.speed.visibility = VISIBLE
                syncSpeed()
            }
            if (animate) speedA.to(if (want) 1f else 0f, 0L, Tokens2.MS_swap, Motion.MOVE)
            else {
                speedA.snap(if (want) 1f else 0f)
                atRest()
            }
        }
    }

    private fun refreshAll(animate: Boolean) {
        trip = NavSession.state != NavSession.State.IDLE
        volume.sync(false)
        strip.setTime(Fmt.clock(Date(), Prefs.clock24))
        wifi = Actions.wifiConnected(context)
        syncStatus()
        bindSong(MediaMonitor.current, false)
        refreshFaces(animate)
        refreshPrayer()
    }

    // ------------------------------------------------------------------------------------------ the next prayer
    private fun refreshPrayer() {
        val n = Prayer.next()
        val word = "${context.getString(ChromeWords.prayer(n.prayer))} ${Fmt.time(Date(n.atUtc), Prefs.clock24)}"
        val left = if (ChromeRules.leftInMinutes(n.minutes)) context.getString(R.string.chrome_left_minutes, n.minutes.toString())
        else context.getString(R.string.chrome_left_hours, ChromeRules.leftHours(n.minutes).toString())
        val icon = ChromeWords.prayerIcon(n.prayer)
        rowHome.setPrayer(word, left, icon)
        rowDock.glance.setPrayer(word, icon, ChromeRules.prayerQuiet(DriveState.moving, n.minutes))
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
        if (n != null) rowDock.glance.setSong(n.title, n.art, n.playing, animate)
    }

    private fun openPlayer() {
        // (as today's card: the app that plays, or the player of its own)
        val pkg = song?.sourcePkg
        if (pkg == null || pkg == MediaMonitor.SOURCE_BT || pkg == context.packageName || !AppRepo.isInstalled(context, pkg)) Screens.music(context)
        else if (!AppRepo.launch(context, pkg)) Screens.music(context)
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

    private val onDrive: () -> Unit = {
        // (the car starts moving: a dialog goes as Cancel, section 6.0's event table)
        if (DriveState.moving) Modal.dismissAll()
        refreshFaces(canMove())
        refreshPrayer()
    }

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
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                // muting (the wheel's key) sends only the second
                VOLUME_CHANGED, STREAM_MUTE_CHANGED -> volume.sync()
                else -> minute()
            }
        }
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

    private val minuteNow = Runnable {
        strip.setTime(Fmt.clock(Date(), Prefs.clock24))
        refreshPrayer()
    }

    /** The clock's form changed in Settings: now, not at the next minute. */
    fun clockChanged() = minute()

    // ------------------------------------------------------------------------------------------ opening what the row names
    private fun open(name: String) {
        if (name == panel) {
            // (the selected Map cell is inert; the selected Music cell returns to the panel he came from: section 1)
            if (name == Screens.MUSIC) shell.panels.back()
            return
        }
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
        sheet<ControlsSheet>()?.open()
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
        Theme.changed = { post { if (appliedDark != Palette.dark) Look.apply(this) } }
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
        tweens.stop()
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
                    addAction(VOLUME_CHANGED)
                    addAction(STREAM_MUTE_CHANGED)
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

    /** A still picture of a row's change, [ms] after it started towards [name]. */
    internal fun debugHold(name: String, ms: Float) {
        show(if (name == Screens.HOME) DEBUG_OTHER else Screens.HOME, false)
        show(name, true)
        tweens.holdAt(ms)
    }

    internal val debugAnimating: Boolean get() = tweens.isRunning

    /** Where everything has come to rest, for the log. */
    internal fun debugState() = "panel=$panel top=${shell.topPanel} home=$onHome running=${tweens.isRunning} rowHome(a=${rowHome.alpha} vis=${rowHome.visibility} live=${rowHome.live}) " +
        "rowDock(a=${rowDock.alpha} vis=${rowDock.visibility} live=${rowDock.live}) face=${voice.face} strip(home=${strip.homeFace} other=${strip.otherFace}) moving=${DriveState.moving} speed=$speedShown"

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
        strip.speed.visibility = VISIBLE
        speedA.snap(1f)
        strip.speed.set(v, limit, over, saher)
    }

    companion object {
        private const val TAG = "AuraChrome"
        private const val HOLDER = "chrome"
        private const val FIX_MAX_AGE_MS = 5000L
        private const val FIX_MS = 1000L
        private const val AFTER_FIX_MS = 200L
        private const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        private const val STREAM_MUTE_CHANGED = "android.media.STREAM_MUTE_CHANGED_ACTION"
        private const val DEBUG_OTHER = Screens.MAPS

        /** Debug builds, until the window's next start: off for the gallery's pages, which draw their own (debug/GalleryPanel.kt, debug/ChromeProbe.kt). */
        internal fun debugOn(shell: HomeActivity, on: Boolean) {
            if (on) attach(shell) else shell.root.chrome.removeAllViews()
        }

        /**
         * At the process's start, off the main thread: what the row's first frame needs. The first hardware bitmap of a process brings up a GL context of the uploader's on the calling thread
         * (about 600 ms on a loaded emulator, against 12 ms for each one after it), the logo is cut down from a big file, and the prayer times are a hundred looks at the sun in code not yet warm.
         */
        fun warm(c: Context) {
            val app = c.applicationContext
            Thread({
                try {
                    for (stem in arrayOf("cap-left", "cap-dock", "cap-chip", "dock")) Surfaces.themed(app, stem)
                    Logo.prepare(app)
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
            shell.root.chrome.addView(Chrome(shell, Asked.DOCK), FrameLayout.LayoutParams(1024, 600))
            Log.i(TAG, "built in ${SystemClock.uptimeMillis() - t0} ms")
        }

        fun of(shell: HomeActivity): Chrome? = shell.root.chrome.getChildAt(0) as? Chrome

        /** Debug builds: the row in another order, built anew, for what the mockups draw and both orders' pictures. */
        internal fun debugOrder(shell: HomeActivity, order: List<Asked.Cell>) {
            val was = of(shell)
            shell.root.chrome.removeAllViews()
            shell.root.chrome.addView(Chrome(shell, order), FrameLayout.LayoutParams(1024, 600))
            was?.let { of(shell)?.debugShow(it.panel, false) }
        }

        /** The row is on screen, so a volume change shows there and needs no overlay. */
        fun rowOnScreen(): Boolean = HomeActivity.current?.let { it.resumedNow && of(it) != null } == true
    }
}

/** The row's words: what a panel is called, what a prayer is called and its icon. */
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

    /** `sunrise` for Fajr, `sun` for Dhuhr and Asr, `sunset` for Maghrib, `moon` for Isha. */
    fun prayerIcon(p: PrayerTimes.Prayer): Int = when (p) {
        PrayerTimes.Prayer.FAJR -> R.drawable.ic_sunrise
        PrayerTimes.Prayer.DHUHR, PrayerTimes.Prayer.ASR -> R.drawable.ic_sun
        PrayerTimes.Prayer.MAGHRIB -> R.drawable.ic_sunset
        PrayerTimes.Prayer.ISHA -> R.drawable.ic_moon
    }
}
