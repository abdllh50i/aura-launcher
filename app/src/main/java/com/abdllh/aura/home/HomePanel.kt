package com.abdllh.aura.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.abdllh.aura.R
import com.abdllh.aura.chrome.Chrome
import com.abdllh.aura.chrome.ChromeWords
import com.abdllh.aura.chrome.Tweens
import com.abdllh.aura.nav.CarLocation
import com.abdllh.aura.nav.NavSession
import com.abdllh.aura.nav.TripMemory
import com.abdllh.aura.phone.Calls
import com.abdllh.aura.system.CarAudio
import com.abdllh.aura.system.DriveState
import com.abdllh.aura.system.NwdBridge
import com.abdllh.aura.system.Prayer
import com.abdllh.aura.ui.CarGlView
import com.abdllh.aura.ui.CarStage
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.MapBackdropView
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Snack
import com.abdllh.aura.ui.Spring
import com.abdllh.aura.ui.Surfaces
import com.abdllh.aura.ui.ThemeSwap
import com.abdllh.aura.ui.Tokens2
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.afterNextDraw
import com.abdllh.aura.ui.at
import com.abdllh.aura.ui.box
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.look
import com.abdllh.aura.ui.surfaceImage
import com.abdllh.aura.update.UpdateManager
import com.abdllh.aura.util.Fmt
import com.abdllh.aura.util.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The home screen, direction 4 of the design, at the bottom of the stack (SPEC.md sections 6.1, 6.7, 10, CHANGES.md section 3): one picture of the wall with two holes in it, one over the one map (the map card's corners and
 * its shadow are the wall's) and one the car's GL view stands in; the clock, the map card and the music widget over it, and the row in the chrome. Parked, moving and on a trip it is the same views:
 * [HomeCard] and [HomeWidget] have faces in alpha, built and laid out once, and nothing moves by layout. A panel that recolours in place: nothing here is rebuilt for a theme.
 *
 * What goes between it and the other panels is here too, as [PanelMotion] asks ([leaving], [returning]): it knows its parts, and section 9 says what each does.
 */
class HomePanel(shell: HomeActivity) : Panel(shell, Screens.HOME), HomeHost {
    private val room = object : FrameLayout(shell) {
        override fun hasOverlappingRendering() = false

        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) skipEntrance()
            return super.dispatchTouchEvent(e)
        }
    }

    /** What stands in the card's hole until the map has drawn: its kept picture, or the grid in place of a map that cannot run. */
    private val backdrop = MapBackdropView(shell, if (shell.map.allowed) MapBackdropView.pictureFile(shell) else null, grid = !shell.map.allowed)
    private val wall = shell.surfaceImage("wall-hole")
    private val stage = CarStage(shell)

    /** Clock, date, map card's pills, widget: the parts that go first. */
    private val parts = object : FrameLayout(shell) {
        override fun hasOverlappingRendering() = false
    }
    private val card = HomeCard(shell, this)
    private val widget = HomeWidget(shell, this)
    private val clock = HomeClock(shell)

    private lateinit var controls: ControlsSheet
    private var startLogo: ImageView? = null
    private var startVeil: ImageView? = null
    private var introPlayed = false
    private var resumedNow = false
    private var shownNow = false
    private var cardOpen = false
    private var movingLook = false

    // ---- section 9's laws, on one frame callback: a change turns back from where it is
    private var moveDone: (() -> Unit)? = null
    private val moves = Tweens(onRest = { val d = moveDone; moveDone = null; d?.invoke() })
    private val partsA = moves.part(1f) { v -> parts.alpha = v; show(parts, v) }
    private val wallA = moves.part(1f) { v -> wall.alpha = v; stage.fade(v) }
    private val carK = moves.part(1f) { v -> stage.presence = v }
    private var atRestUnder = false

    private val dimFade = Tweens()
    private val dimA = dimFade.part(1f) { v -> stage.dim = v }

    private val entrance = Tweens(onRest = { entranceOver() })
    private var entranceOn = false
    private var chromeUp: View? = null

    private val volume = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (::controls.isInitialized && controls.isOpen) controls.syncVolume()
        }
    }
    private val carAudio: () -> Unit = {
        if (::controls.isInitialized && controls.isOpen) controls.syncVolume()
    }
    private val navListener: () -> Unit = { navChanged() }
    private val driveListener: () -> Unit = { syncFace(canMove()) }
    private var tripNow = false

    override val root: View get() = room
    override val opaque: Boolean get() = false // (the map shows in its card, so it stays awake)
    override val solid: Boolean get() = true // (but no touch reaches it)
    override val keep: Boolean get() = true
    override val recolours: Boolean get() = true
    override val navBar: Int get() = Palette.dock

    init {
        current = this
        buildUi()
        if (com.abdllh.aura.util.Prefs.safeModeNotice) {
            Prefs.safeModeNotice = false
            toast(getString(R.string.safe_mode_notice))
        }
        // the theme's one path: ready first on a worker (the song's wash), the car's picture in the ghost
        if (!stepAdded) {
            stepAdded = true
            ThemeSwap.readySteps.add { night, done ->
                try { current?.widget?.prepareTheme(night) } catch (_: Throwable) { }
                done()
            }
        }
        ThemeSwap.ghostExtra = { c ->
            if (isTop) stage.picture()?.let { b -> c.drawBitmap(b, CarGlView.RECT_X, CarGlView.RECT_Y, null) }
        }
        ThemeSwap.holdOff = {
            val p = NavSession.progress
            (NavSession.state == NavSession.State.NAVIGATING && p != null && p.toNext < ThemeSwap.NEAR_TURN_M) || Calls.state == Calls.State.RINGING
        }
    }

    private fun buildUi() {
        card.onUndo = { tripNow = true }
        room.addView(backdrop, at(CARD_X, CARD_Y, CARD_W, CARD_H))
        room.addView(wall, at(0, 0, 1024, 600))
        room.addView(stage, at(CarGlView.RECT_X.toInt(), CarGlView.RECT_Y.toInt(), CarGlView.RECT_W.toInt(), CarGlView.RECT_H.toInt()))
        room.addView(parts, MATCH_WINDOW())
        parts.addView(clock, MATCH_WINDOW())
        parts.addView(card, MATCH_WINDOW())
        parts.addView(widget, MATCH_WINDOW())
        shell.map.homeCard(false)
        shell.map.setHomeRoom(Rect(CARD_X, CARD_Y, CARD_X + CARD_W, CARD_Y + CARD_H), 1024, 600)
        // the map has drawn: the card opens on it, and the picture that stood in for it fades. With no map ever it simply stays
        shell.map.whenDrawn {
            cardOpen = true
            shell.map.homeCard(true)
            handOff()
        }
        room.look {
            BootFlag.keep(this@HomePanel, Palette.dark)
            stage.dim = dimFor(movingLook)
            dimA.snap(stage.dim)
        }
        controls = ControlsSheet(shell, this)
        shell.root.sheets.addView(controls, MATCH, MATCH)
    }

    /**
     * The picture fades off the live map under it. On a spring, which steps at most a few frames' worth of time per frame: the map's first frames are the heaviest of the start, and a timed fade jumped to
     * its end across them.
     */
    private fun handOff() {
        if (backdrop.visibility != View.VISIBLE) return
        Spring(1f, HANDOFF_STIFFNESS, 1f, 0.01f) { backdrop.alpha = it }.apply {
            onRest = { backdrop.visibility = View.GONE }
            animateTo(0f)
        }
    }

    private fun show(v: View, a: Float) {
        val want = if (a > 0f) View.VISIBLE else View.INVISIBLE
        if (v.visibility != want) v.visibility = want
    }

    private fun canMove() = shell.resumedNow && Prefs.screenAnimations && isTop

    private fun dimFor(m: Boolean) = if (m && Palette.dark) DIM_NIGHT else 1f

    // ------------------------------------------------------------------------------------------ the panel's life
    override fun onShow(args: Bundle?) {
        shownNow = true
        NwdBridge.notifyHomeForeground(this)
        shell.map.toHome()
        NavSession.addListener(navListener)
        DriveState.addListener(driveListener)
        card.listen(true)
        widget.listen(true)
        clock.refresh()
        syncFace(false)
        tripNow = NavSession.state == NavSession.State.NAVIGATING
    }

    override fun onHide() {
        shownNow = false
        NavSession.removeListener(navListener)
        DriveState.removeListener(driveListener)
        card.listen(false)
        widget.listen(false)
    }

    /** The stack has come to rest: everything of this panel as it is at rest, wherever a move stopped. [top]: and it is what shows. */
    override fun onSettle(top: Boolean) {
        moves.stop()
        moveDone = null
        partsA.snap(1f)
        wallA.snap(1f)
        carK.snap(1f)
        atRestUnder = !top
        // (back on Home the map's words go, now that nothing but the card shows it)
        if (top) shell.map.homeLabels()
    }

    /** Asks once for the location (the home map shows the car; Maps needs it anyway). */
    private fun askLocationOnce() {
        if (CarLocation.hasPermission(this) || Prefs.raw.getBoolean("askedLocation", false)) return
        Prefs.raw.edit().putBoolean("askedLocation", true).apply()
        requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION), 7)
    }

    override fun onPermission(code: Int, grants: IntArray) {
        if (CarLocation.hasPermission(this)) shell.map.locationAllowed()
    }

    override fun onResume() {
        if (shell.languageChanged()) return // the language changed in Settings
        com.abdllh.aura.system.ZLinkGuard.onHomeShown() // back from ZLink: it is switched off again after a while
        try {
            // muting (Mute tile, steering-wheel key) only sends STREAM_MUTE_CHANGED, not VOLUME_CHANGED
            registerReceiver(volume, IntentFilter("android.media.VOLUME_CHANGED_ACTION").apply { addAction("android.media.STREAM_MUTE_CHANGED_ACTION") })
        } catch (_: Throwable) { }
        CarAudio.addListener(carAudio)
        resumedNow = true
        clock.refresh()
        if (controls.isOpen) controls.refresh()
        UpdateManager.autoCheckIfDue()
        if (!introPlayed) {
            introPlayed = true
            playEntrance()
            room.postDelayed({ if (!isGone) askLocationOnce() }, 2600)
        } else if (!entranceOn) prepareAhead()
    }

    /** The kept panels ahead of time, so nothing is built in a tap (once they are there these are two looks in a map). */
    private fun prepareAhead() {
        if (Prefs.builtInMaps) shell.panels.prepareWhenQuiet(Screens.MAPS, MAPS_PANEL_AFTER_MS)
        shell.panels.prepareWhenQuiet(Screens.MUSIC, MUSIC_PANEL_AFTER_MS)
    }

    override fun onPause() {
        try { unregisterReceiver(volume) } catch (_: Throwable) { }
        CarAudio.removeListener(carAudio)
        resumedNow = false
        com.abdllh.aura.system.ZLinkGuard.onHomeHidden()
    }

    override fun onTick() {
        clock.refresh()
    }

    override fun onDestroy() {
        if (current === this) current = null
    }

    // ------------------------------------------------------------------------------------------ parked, moving, on a trip
    private fun navChanged() {
        // (a trip starting anywhere opens Maps, section 6.0: a place from the phone, the voice. Nothing else ever takes him there by itself; one brought back after a restart has its pills here)
        val trip = NavSession.state == NavSession.State.NAVIGATING
        if (trip && !tripNow && isTop && !TripMemory.handingOver) {
            tripNow = true
            Screens.maps(this)
            return
        }
        tripNow = trip
        syncFace(canMove())
        card.syncTrip()
    }

    /** One source of Parked and Moving; a trip looks the same at a red light, with no strip speed, which the chrome knows. */
    private fun syncFace(animate: Boolean) {
        if (debugFaceHeld) return
        val trip = NavSession.state == NavSession.State.NAVIGATING
        val moving = DriveState.moving
        val look = trip || moving
        card.setFace(if (trip) HomeCard.Face.TRIP else if (moving) HomeCard.Face.MOVING else HomeCard.Face.PARKED, animate)
        widget.movingNow = look
        if (look != movingLook) {
            movingLook = look
            val d = dimFor(look)
            if (animate) dimA.to(d, 0L, DIM_MS, Motion.MOVE) else dimA.snap(d)
        }
    }

    // ------------------------------------------------------------------------------------------ the first start: section 6.7
    /**
     * The car's first frames stand on BootHome's wall: the same pixels with the logo over them, which fades. Then the car, when its GL view has come; then the map card, the widget, the clock and the row, 40
     * ms apart. Everything is put where it starts from at once, so the first frame shows that, and it starts moving after that frame. Any tap skips to the end. Only at the process's first show.
     */
    private fun playEntrance() {
        val first = !processStarted && Prefs.screenAnimations
        processStarted = true
        val r = room
        if (!first) {
            r.postDelayed({ if (!isGone) shell.map.startForHome() }, LIVE_MAP_AFTER_MS)
            prepareAhead() // (counted from here: with no entrance going on, the map coming up, and never in a start's first seconds)
            return
        }
        entranceOn = true
        val logo = bootLogo(this, Palette.dark, Fonts.arabicUi)
        if (logo != null) {
            val h = logo.height
            startLogo = ImageView(shell).apply { setImageBitmap(logo); scaleType = ImageView.ScaleType.FIT_XY }
            r.addView(startLogo, at(LOGO_X, LOGO_CENTRE_Y - h / 2, LOGO_W, h))
        }
        carK.snap(0f)
        fun rise(v: View, delay: Long): Tweens.Part {
            val p = entrance.part(0f) { k -> v.alpha = k; v.translationY = RISE * (1f - k) }
            pending.add(Runnable { p.to(1f, delay, ENTER_MS, Motion.ENTER) })
            return p
        }
        // (BootHome's wall has no card in it: the card's hole, its shadow and the map's picture come with the card)
        val veil = ImageView(shell).apply {
            setImageBitmap(Surfaces.themed(shell, "wall"))
            scaleType = ImageView.ScaleType.MATRIX
        }
        startVeil = veil
        room.addView(veil, room.indexOfChild(parts), at(0, 0, CarGlView.RECT_X.toInt(), 600))
        val veilPart = entrance.part(1f) { a -> veil.alpha = a }
        pending.add(Runnable { veilPart.to(0f, 160L, ENTER_MS, Motion.ENTER) })
        rise(card, 160L)
        rise(widget, 200L)
        rise(clock, 240L)
        Chrome.of(shell)?.let { c -> chromeUp = c; rise(c, 280L) }
        val logoPart = startLogo?.let { l -> entrance.part(1f) { a -> l.alpha = a } }
        var drawnAt = 0L
        var glUp = false
        fun carIn() {
            val since = android.os.SystemClock.uptimeMillis() - drawnAt
            carK.to(1f, (CAR_AT_MS - since).coerceAtLeast(0L), CAR_MS, Motion.ENTER)
            if (entranceOn) stage.spinIn()
        }
        if (stage.gl?.drawing == true) glUp = true // (it was up already, under a panel the window started with)
        else stage.onFirstFrame = { glUp = true; if (drawnAt != 0L) carIn() }
        r.afterNextDraw {
            if (isGone) return@afterNextDraw
            drawnAt = android.os.SystemClock.uptimeMillis()
            for (p in pending) p.run()
            pending.clear()
            logoPart?.to(0f, 0L, LOGO_MS, Motion.MOVE)
            if (glUp) carIn()
            com.abdllh.aura.nav.MapLib.preload(this) // (the map below will want it: off the main thread, and not before this frame)
            r.postDelayed({ if (!isGone) shell.map.startForHome() }, LIVE_MAP_AFTER_MS)
            prepareAhead() // (counted from here: with no entrance going on, the map coming up, and never in a start's first seconds)
        }
    }

    private val pending = ArrayList<Runnable>()

    private fun skipEntrance() {
        if (!entranceOn) return
        entrance.stop()
        pending.clear()
        for (v in listOf(card, widget, clock, chromeUp)) { v?.alpha = 1f; v?.translationY = 0f }
        stage.onFirstFrame = null
        stage.skipSpin()
        carK.snap(1f)
        entranceOver()
    }

    private fun entranceOver() {
        if (!entranceOn) return
        entranceOn = false
        startLogo?.let { room.removeView(it) }
        startLogo = null
        startVeil?.let { room.removeView(it) }
        startVeil = null
        chromeUp = null
    }

    // ------------------------------------------------------------------------------------------ between the panels: SPEC.md section 9
    /**
     * Home goes, [PanelMotion] has the other panel. Its texts and cards 120 `exit`. [overMap]: Maps comes with what lies on the map, so the wall goes off it too: the car inside the GL view at 40 for
     * 100 `exit`, so no grey ghost hangs over the map, and the wall and the GL view together at 40 for 240 `move`. Otherwise the room coming over it hides them, and they stay as they are.
     */
    internal fun leaving(overMap: Boolean, done: () -> Unit) {
        skipEntrance()
        atRestUnder = false
        moveDone = done
        partsA.to(0f, 0L, Tokens2.MS_homeOut, Motion.EXIT)
        if (overMap) {
            carK.to(0f, WALL_DELAY_MS, CAR_OUT_MS, Motion.EXIT)
            wallA.to(0f, WALL_DELAY_MS, WALL_MS, Motion.MOVE)
        }
    }

    /**
     * Home comes back, wherever its parts were (a change of mind turns back from there). The wall and the car 240 `enter` from t 0, the car's rest frame first, within one frame; its parts at 60 for 180 `enter`.
     */
    internal fun returning(overMap: Boolean, done: () -> Unit) {
        moveDone = done
        val fresh = atRestUnder
        atRestUnder = false
        if (fresh) partsA.snap(0f)
        partsA.to(1f, Tokens2.MS_columnDelay, Tokens2.MS_cardIn, Motion.ENTER)
        carK.snap(1f)
        if (overMap) {
            if (fresh) wallA.snap(0f)
            wallA.to(1f, 0L, WALL_MS, Motion.ENTER)
        } else {
            wallA.snap(1f)
        }
    }

    /**
     * The one thing that travels far (section 9): the cover from the widget to [target], a square copy of it on one layer ([com.abdllh.aura.chrome.Flyer]), [target] hidden until the last 60 ms, in which it comes up under
     * the flyer. Parked, with a cover and a panel that has a place for it; otherwise the panels simply fade.
     */
    internal fun flyOut(target: View?, radius: Int, ms: Long) {
        val b = widget.flightBitmap ?: return
        val from = widget.flightView ?: return
        if (target == null || !target.isShown || target.width == 0) return
        val at = IntArray(2)
        target.getLocationInWindow(at)
        val fromAt = IntArray(2)
        from.getLocationInWindow(fromAt)
        from.alpha = 0f
        target.alpha = 0f
        target.postDelayed({ target.animate().alpha(1f).setDuration(FLIGHT_LAND_MS).setStartDelay(0L).start() }, ms - FLIGHT_LAND_MS)
        shell.root.flyer.fly(b, fromAt[0].toFloat(), fromAt[1].toFloat(), from.width.toFloat(), HomeWidget.COVER_R.toFloat(), at[0].toFloat(), at[1].toFloat(), target.width.toFloat(), radius.toFloat(), ms, Motion.MOVE) {
            from.alpha = 1f
            target.alpha = 1f
        }
    }

    /** The same way back: from where [source] stands to the widget, whose cover comes up when it lands. */
    internal fun flyBack(source: View?, radius: Int, ms: Long) {
        val b = widget.flightBitmap ?: return
        val to = widget.flightView ?: return
        if (source == null || source.width == 0) return
        val at = IntArray(2)
        source.getLocationInWindow(at)
        val toAt = IntArray(2)
        to.getLocationInWindow(toAt)
        source.alpha = 0f
        to.alpha = 0f
        shell.root.flyer.fly(b, at[0].toFloat(), at[1].toFloat(), source.width.toFloat(), radius.toFloat(), toAt[0].toFloat(), toAt[1].toFloat(), to.width.toFloat(), HomeWidget.COVER_R.toFloat(), ms, Motion.MOVE) {
            source.alpha = 1f
            to.alpha = 1f
        }
    }

    // ------------------------------------------------------------------------------------------ HomeHost
    override fun openControls() {
        controls.open()
    }

    override fun openSettings(page: Int) {
        Screens.settings(this, page)
    }

    override fun toast(msg: String, outlives: Boolean) {
        Snack.show(this, msg, outlives = outlives)
    }

    /** A theme chosen in Controls: the one path of section 8. */
    override fun restyle() {
        ThemeSwap.run(shell)
    }

    // ------------------------------------------------------------------------------------------ debug builds: states as the mockups draw them (debug/HomeProbe.kt)
    internal val debugCard: HomeCard get() = card
    internal val debugWidget: HomeWidget get() = widget
    internal val debugClock: HomeClock get() = clock
    internal val debugStage: CarStage get() = stage

    private var debugFaceHeld = false

    internal fun debugFace(moving: Boolean, trip: Boolean) {
        debugFaceHeld = true
        card.setFace(if (trip) HomeCard.Face.TRIP else if (moving) HomeCard.Face.MOVING else HomeCard.Face.PARKED, false)
        val look = trip || moving
        widget.movingNow = look
        movingLook = look
        dimA.snap(dimFor(look))
    }

    companion object {
        /** The home screen has been built once in this process: later builds have no entrance. */
        private var processStarted = false
        private var stepAdded = false
        /** What the debug probe and the theme's one ready step talk to. */
        internal var current: HomePanel? = null
            private set

        private const val CARD_X = 16
        private const val CARD_Y = 48
        private const val CARD_W = 364
        private const val CARD_H = 264
        private const val HANDOFF_STIFFNESS = 80f
        private const val DIM_NIGHT = .55f
        private const val DIM_MS = 300L
        private const val LOGO_X = 412
        private const val LOGO_W = 200
        private const val LOGO_CENTRE_Y = 296
        private const val LOGO_MS = 200L
        private const val CAR_AT_MS = 120L
        private const val CAR_MS = 420L
        private const val ENTER_MS = 240L
        private const val RISE = 8f
        private const val WALL_DELAY_MS = 40L
        private const val WALL_MS = 240L
        private const val CAR_OUT_MS = 100L
        private const val FLIGHT_LAND_MS = 60L
        /** The live map starts once the entrance has settled. */
        private const val LIVE_MAP_AFTER_MS = 700L
        /** Maps' panel is built ahead of time once that has come ([PanelStack.prepareWhenQuiet]), */
        private const val MAPS_PANEL_AFTER_MS = 3000L
        /** and then the music screen. */
        private const val MUSIC_PANEL_AFTER_MS = 6000L

        /** BootHome's logo, 200 wide, for the frame that follows it. */
        internal fun bootLogo(c: Context, night: Boolean, arabic: Boolean): Bitmap? = try {
            val res = if (arabic) { if (night) R.drawable.logo_amri_ar else R.drawable.logo_amri_ar_ink } else { if (night) R.drawable.logo_amri_en else R.drawable.logo_amri_en_ink }
            var b = BitmapFactory.decodeResource(c.resources, res, BitmapFactory.Options().apply { inScaled = false })
            val h = b.height * LOGO_W / b.width
            // (by halves: one bilinear step from 4 to 6 times the size skips most of its pixels)
            while (b.width >= LOGO_W * 2) b = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true)
            Bitmap.createScaledBitmap(b, LOGO_W, h, true)
        } catch (_: Throwable) {
            null
        }
    }
}

/**
 * The clock, the date and the next prayer, right edge at x 1008 in both languages (CHANGES.md section 3): the time with no suffix, in Western digits, one date line, `الخميس، 5 نوفمبر`, and a quiet line under it,
 * `المغرب 4:56`. The same parked and moving. A new minute is a new text in a fixed box.
 */
internal class HomeClock(ctx: Context) : FrameLayout(ctx) {
    private val date = ctx.label(Type.dateHome, { Palette.ink }).apply { rightEdge() }
    private val prayer = ctx.label(Type.prayerHome, { Palette.ink2 }).apply { rightEdge() }
    private val time = ctx.label(Type.clockHome, { Palette.ink }).apply {
        rightEdge()
        textDirection = TEXT_DIRECTION_LTR
        digitSpacing(-1f)
    }
    private var debugHeld = false

    init {
        addView(time, Type.clockHome.box(TIME_RIGHT - W, 8, W))
        addView(date, Type.dateHome.box(RIGHT - W, 88, W))
        addView(prayer, Type.prayerHome.box(RIGHT - W, 122, W))
        refresh()
    }

    private fun com.abdllh.aura.ui.AText.rightEdge() {
        gravity = Gravity.RIGHT or Gravity.TOP
        textAlignment = TEXT_ALIGNMENT_GRAVITY
        textDirection = TEXT_DIRECTION_LOCALE
    }

    override fun hasOverlappingRendering() = false

    fun refresh() {
        if (debugHeld) return
        val now = Date()
        val next = Prayer.next()
        set(Fmt.time(now, Prefs.clock24), line(now), "${context.getString(ChromeWords.prayer(next.prayer))} ${Fmt.time(Date(next.atUtc), Prefs.clock24)}")
    }

    private fun set(t: String, d: String, p: String) {
        if (t != time.text.toString()) time.text = t
        if (d != date.text.toString()) date.text = d
        if (p != prayer.text.toString()) prayer.text = p
    }

    private fun line(now: Date): String =
        if (Fonts.arabicUi) SimpleDateFormat("EEEE، d MMMM", Fmt.locale()).format(now) else SimpleDateFormat("EEE, d MMM", Locale.ENGLISH).format(now)

    internal fun debugHold(t: String, d: String, p: String) {
        debugHeld = true
        set(t, d, p)
    }

    private companion object {
        const val RIGHT = 1008
        /** The digits' own side bearing: their ink ends at [RIGHT]. */
        const val TIME_RIGHT = 1011
        const val W = 400
    }
}

/**
 * The look at the next start, for [BootHome], which runs before the user's storage is open and touches nothing stored but this: the night flag and the language, mirrored to device-protected storage at
 * every theme change. With none: night, Arabic.
 */
internal object BootFlag {
    private const val FILE = "boot_look"
    private const val KEY_NIGHT = "night"
    private const val KEY_ARABIC = "arabic"

    fun keep(c: Context, night: Boolean) {
        try {
            val p = c.createDeviceProtectedStorageContext().getSharedPreferences(FILE, Context.MODE_PRIVATE)
            val arabic = Fonts.arabicUi
            if (p.getBoolean(KEY_NIGHT, true) != night || p.getBoolean(KEY_ARABIC, true) != arabic || !p.contains(KEY_NIGHT)) {
                p.edit().putBoolean(KEY_NIGHT, night).putBoolean(KEY_ARABIC, arabic).apply()
            }
        } catch (_: Throwable) { }
    }

    fun night(c: Context): Boolean = try { c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_NIGHT, true) } catch (_: Throwable) { true }

    fun arabic(c: Context): Boolean = try { c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_ARABIC, true) } catch (_: Throwable) { true }
}
