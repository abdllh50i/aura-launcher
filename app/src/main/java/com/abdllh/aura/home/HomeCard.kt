package com.abdllh.aura.home

import android.location.Location
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import com.abdllh.aura.R
import com.abdllh.aura.nav.CarLocation
import com.abdllh.aura.nav.Instructions
import com.abdllh.aura.nav.NavSession
import com.abdllh.aura.nav.RoadWatch
import com.abdllh.aura.nav.Route
import com.abdllh.aura.nav.Turn
import com.abdllh.aura.nav.Units
import com.abdllh.aura.ui.ABtn
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.LimitSign
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.PressGesture
import com.abdllh.aura.ui.Surface
import com.abdllh.aura.ui.SurfaceCard
import com.abdllh.aura.ui.Tint
import com.abdllh.aura.ui.Tokens2
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.at
import com.abdllh.aura.ui.box
import com.abdllh.aura.ui.iconView
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.look
import com.abdllh.aura.ui.plateView
import com.abdllh.aura.ui.withUnit
import com.abdllh.aura.util.Prefs
import kotlin.math.roundToInt

/**
 * The map card on Home, 364 x 268 at (12, 44) (SPEC.md sections 5 and 6.1): a hole in the wall over the one map, its edge, and what lies on it. Parked: the search pill and three chips. Moving with no
 * trip: the speed and limit in the search's slot, the chips with no discs at `chip`. On a trip: the turn pill and the ETA pill with End. Tap anywhere else: Maps. All the faces are built and laid
 * out and cross-fade in alpha; a new speed or distance is a new text in a fixed box, one small damage rect, nothing laid out. Coordinates are the window's: the numbers of the layout tables.
 */
internal class HomeCard(private val shell: HomeActivity, private val host: HomeHost) : FrameLayout(shell) {
    enum class Face { PARKED, MOVING, TRIP }

    private val ar = Fonts.arabicUi
    private val tap = View(shell)
    private val edge = View(shell)
    private val searchFace = SurfaceCard(shell, Surface.PILL)
    private val speedFace = SurfaceCard(shell, Surface.PILL)
    private var shownOver = false
    private val speedNumber: AText = shell.label(SPEED_ROLE, { if (shownOver) Palette.overInk else Palette.ink }).apply {
        gravity = Gravity.RIGHT or Gravity.TOP
        textAlignment = TEXT_ALIGNMENT_GRAVITY
    }
    private val speedSign = LimitSign(shell, HOME_LIMIT).apply { visibility = INVISIBLE }
    private val overPlate = shell.plateView(Tokens2.R_card) { Palette.overFill }.apply { visibility = INVISIBLE }
    private val tripFace = group()
    private val turnPill = SurfaceCard(shell, Surface.PILL)
    private val etaPill = SurfaceCard(shell, Surface.PILL)
    private val turnTile = shell.plateView(Tokens2.R_mid) { Palette.accent }
    private val turnGlyph = android.widget.ImageView(shell).apply { scaleType = android.widget.ImageView.ScaleType.FIT_CENTER; setColorFilter(Palette.onAccent) }
    private val turnText: AText = shell.label(Type.chipPrayer, { Palette.ink }, moving = true).apply { textDirection = TEXT_DIRECTION_LOCALE }
    private val etaText: AText = shell.label(Type.chipPrayer, { Palette.ink }, moving = true).apply { textDirection = TEXT_DIRECTION_LOCALE }
    private val end = ABtn(shell).apply {
        kind = ABtn.Kind.DANGER
        setHeightOverride(58)
        setPadding(0, 0, 0, 4)
        setText(R.string.home_end)
    }
    /** After End, in the ETA pill's own rect: the trip has ended, and `رجّعها` where End was, for 5 s. */
    private val endedFace = group()
    private val undo = ABtn(shell).apply {
        kind = ABtn.Kind.PRIMARY
        setHeightOverride(58)
        setPadding(0, 0, 0, 2)
        setText(R.string.home_undo)
    }
    private val faces = HomeFaces(listOf(searchFace, speedFace, tripFace), searchFace)
    private val endedFaces = HomeFaces(listOf(endedFace), null)
    private var face = Face.PARKED
    private class Kept(val place: com.abdllh.aura.nav.Place, val route: Route)
    private var kept: Kept? = null
    private val undoGone = Runnable { clearUndo(true) }

    /** The trip End took off comes back: it is not a trip starting, so Home stays. */
    var onUndo: (() -> Unit)? = null

    private var kmh: Int? = null
    private var listening = false
    private var shownSpeed = ""
    private var shownTurnKey: Any? = null
    private var shownTurn = ""
    private var shownEta = ""
    private var debugHeld = false
    private var lastSpeed: (() -> Unit)? = null
    private var lastTrip: (() -> Unit)? = null

    init {
        // the map under it takes no touches: a tap anywhere opens Maps, and the press shows as a plate at once
        val press = Tint(shell, R.drawable.plate_26) { Palette.fillPress }
        tap.background = PressGesture.plate(press.drawable)
        tap.isClickable = true
        tap.setOnClickListener { openMaps(false) }
        addView(tap, at(CARD_X, CARD_Y, CARD_W, CARD_H))
        // (a ring only, its middle empty: top light and ring over the hole)
        edge.look {
            background = context.getDrawable(if (Palette.dark) R.drawable.map_edge_night else R.drawable.map_edge_day)
            press.recolour()
        }
        addView(edge, at(CARD_X, CARD_Y, CARD_W, CARD_H))

        // the search pill
        searchFace.addView(shell.iconView(R.drawable.ic_search, 26) { Palette.ink2 }, at(if (ar) 306 else 16, 13, 26, 26))
        searchFace.addView(shell.label(Type.search, { Palette.ink2 }).apply { setText(R.string.home_where_to) }, Type.search.box(if (ar) 16 else 52, 10, 280))
        searchFace.setOnClickListener { openMaps(true) }
        pressable(searchFace, PressGesture.SURFACE)
        addView(searchFace, Surface.PILL.at(PILL_X, PILL_Y, PILL_W, 52))

        // the speed in the search's slot
        speedFace.addView(overPlate, at(0, 0, PILL_W, 52))
        speedFace.addView(speedSign, at(4, 4, HOME_LIMIT.px, HOME_LIMIT.px))
        speedFace.addView(speedNumber, SPEED_ROLE.box(56, 2, 280))
        addView(speedFace, Surface.PILL.at(PILL_X, PILL_Y, PILL_W, 52))

        // the trip's two pills, the tile at the reading edge and End on the driver's side as the mockup has them
        addView(tripFace, MATCH_WINDOW())
        tripFace.addView(turnPill, Surface.PILL.at(PILL_X, PILL_Y, PILL_W, 64))
        tripFace.addView(etaPill, Surface.PILL.at(PILL_X, ETA_Y, PILL_W, 64))
        val tileX = if (ar) 292 else 8
        turnPill.addView(turnTile, at(tileX, 8, 48, 48))
        turnPill.addView(turnGlyph, at(tileX + 6, 14, 36, 36))
        turnPill.addView(turnText, Type.chipPrayer.box(if (ar) 8 else 66, 16, 274))
        turnPill.setOnClickListener { openMaps(false) }
        pressable(turnPill, PressGesture.SURFACE)
        etaPill.addView(end, at(3, 3, 100, 58))
        etaPill.addView(etaText, Type.chipPrayer.box(111, 16, 226))
        // End: the 100 x 58 plate has a hit of 122 x 80, which runs past the pill's edges
        val endGesture = PressGesture(end, PressGesture.CONTROL)
        tripFace.addView(View(shell).apply {
            isClickable = true
            contentDescription = shell.getString(R.string.home_end)
            setOnTouchListener { _, e -> endGesture.onTouch(e); false }
            setOnClickListener { endTrip() }
        }, at(END_HIT_X, END_HIT_Y, 122, 80))
        // what End leaves: the same rect, the same hit
        addView(endedFace, MATCH_WINDOW())
        val endedPill = SurfaceCard(shell, Surface.PILL)
        endedPill.addView(undo, at(3, 3, 100, 58))
        endedPill.addView(shell.label(Type.chipPrayer, { Palette.ink }, moving = true).apply {
            textDirection = TEXT_DIRECTION_LOCALE
            setText(R.string.home_trip_ended)
        }, Type.chipPrayer.box(111, 16, 226))
        endedFace.addView(endedPill, Surface.PILL.at(PILL_X, ETA_Y, PILL_W, 64))
        val undoGesture = PressGesture(undo, PressGesture.CONTROL)
        endedFace.addView(View(shell).apply {
            isClickable = true
            contentDescription = shell.getString(R.string.home_undo)
            setOnTouchListener { _, e -> undoGesture.onTouch(e); false }
            setOnClickListener { undoEnd() }
        }, at(END_HIT_X, END_HIT_Y, 122, 80))
        look {
            turnGlyph.setColorFilter(Palette.onAccent)
            // (what is in spans has its colour in the text)
            shownSpeed = ""
            lastSpeed?.invoke()
            shownEta = ""
            lastTrip?.invoke()
        }
    }

    private fun group() = object : FrameLayout(shell) {
        override fun hasOverlappingRendering() = false
    }

    private fun pressable(v: View, scale: Float) {
        val g = PressGesture(v, scale)
        v.setOnTouchListener { _, e -> g.onTouch(e); false }
    }

    override fun hasOverlappingRendering() = false

    // ------------------------------------------------------------------------------------------ the face
    fun setFace(f: Face, animate: Boolean) {
        val was = face
        face = f
        faces.show(when (f) { Face.PARKED -> searchFace; Face.MOVING -> speedFace; Face.TRIP -> tripFace }, animate)
        syncEnded(animate)
        if (was != f) {
            syncSpeed()
            syncTrip()
        }
    }

    private fun syncEnded(animate: Boolean) = endedFaces.show(if (kept != null && face != Face.TRIP) endedFace else null, animate)

    /** Home is on top: fixes, the road and the trip. Off when it is not: a hidden view asks for no frame. */
    fun listen(on: Boolean) {
        if (on == listening) return
        listening = on
        if (on) {
            CarLocation.addListener(onFix)
            RoadWatch.addListener(onRoad)
            CarLocation.last?.let { takeFix(it) }
            syncSpeed()
            syncTrip()
        } else {
            CarLocation.removeListener(onFix)
            RoadWatch.removeListener(onRoad)
            removeCallbacks(fixGone)
            if (kept != null) clearUndo(false)
        }
    }

    // ------------------------------------------------------------------------------------------ the speed: it only shows it, DriveState says when
    private val onFix: (Location) -> Unit = { l -> post { takeFix(l) } }
    private val onRoad: () -> Unit = { post { syncSpeed() } }

    private fun takeFix(l: Location) {
        val age = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000L
        if (age >= FIX_MAX_AGE_MS) return
        kmh = if (l.hasSpeed()) (l.speed * 3.6f).roundToInt() else null
        removeCallbacks(fixGone)
        postDelayed(fixGone, FIX_MAX_AGE_MS - age)
        syncSpeed()
    }

    private val fixGone = Runnable {
        kmh = null
        syncSpeed()
    }

    private fun syncSpeed() {
        if (face != Face.MOVING || debugHeld) return
        val w = RoadWatch.state
        showSpeed(kmh, w.limit, w.over)
    }

    private fun showSpeed(v: Int?, limit: Int, over: Boolean) {
        lastSpeed = { showSpeed(v, limit, over) }
        val text = v?.toString() ?: "—"
        val isOver = over && v != null
        val hasSign = v != null && limit > 0
        val want = if (hasSign) VISIBLE else INVISIBLE
        if (speedSign.visibility != want) speedSign.visibility = want
        if (hasSign) speedSign.kmh = limit
        if (text == shownSpeed && isOver == shownOver) return
        shownSpeed = text
        if (isOver != shownOver) {
            shownOver = isOver
            overPlate.visibility = if (isOver) VISIBLE else INVISIBLE
            speedNumber.setTextColor(if (isOver) Palette.overInk else Palette.ink)
        }
        speedNumber.text = withUnit(text, shell.getString(R.string.maps_kmh), Type.speedUnit, if (isOver) Palette.overUnit else Palette.ink2Move, moving = true)
    }

    // ------------------------------------------------------------------------------------------ the trip
    /** What [NavSession] says now, in the two pills. Text in fixed boxes, set only when it changed. */
    fun syncTrip() {
        if (face != Face.TRIP || debugHeld) return
        val r = NavSession.route ?: return
        val p = NavSession.progress ?: return
        val step = r.steps.getOrNull(p.next) ?: return
        val key = r to p.next
        if (key != shownTurnKey) {
            shownTurnKey = key
            turnGlyph.setImageResource(turnIcon(Instructions.turn(step)))
        }
        val dest = NavSession.destination?.name?.takeIf { it != shell.getString(R.string.maps_dropped_pin) }
        val guide = r.guide?.takeIf { it.arabic == ar }
        val told = guide?.toldOfStep(p.next) ?: -1
        val sign = if (guide != null && told >= 0) guide.banner(told, p.along, dest) else null
        val road = sign?.road?.ifBlank { null } ?: Instructions.roadLine(step, ar, dest)
        showTrip(Units.distance(p.toNext, ar), road, Units.arrival(p.timeLeft, Prefs.clock24), Units.duration(p.timeLeft, ar))
    }

    private fun showTrip(dist: String, road: String, at: String, left: String) {
        lastTrip = { showTrip(dist, road, at, left) }
        val turn = "$dist|$road"
        if (turn != shownTurn) {
            shownTurn = turn
            val s = SpannableStringBuilder(dist)
            s.append("  ")
            if (road.isNotBlank()) {
                val from = s.length
                s.append(android.text.BidiFormatter.getInstance().unicodeWrap(road))
                small(s, from, null)
            }
            turnText.text = s
        }
        val eta = "$at|$left"
        if (eta != shownEta) {
            shownEta = eta
            val s = SpannableStringBuilder(at)
            val from = s.length
            s.append(" · ").append(left)
            small(s, from, Palette.ink2Move)
            etaText.text = s
        }
    }

    /** The second part of a line in `miniSub`: one fixed-width label with spans, as a number and its unit are everywhere. */
    private fun small(s: SpannableStringBuilder, from: Int, color: Int?) {
        s.setSpan(AbsoluteSizeSpan(Type.miniSub.px, false), from, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        s.setSpan(TypefaceSpan(Fonts.of(Type.miniSub, true)), from, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (color != null) s.setSpan(ForegroundColorSpan(color), from, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** One touch, no confirm (section 1): the trip ends, and the same rect offers it back for 5 s. */
    private fun endTrip() {
        val place = NavSession.destination
        val route = NavSession.route
        kept = if (place != null && route != null) Kept(place, route) else null
        NavSession.stop()
        shell.map.setRoute(null)
        shell.map.setDestination(null)
        if (kept != null) {
            removeCallbacks(undoGone)
            postDelayed(undoGone, Tokens2.MS_undoEnd)
        }
    }

    private fun clearUndo(animate: Boolean) {
        removeCallbacks(undoGone)
        kept = null
        syncEnded(animate)
    }

    private fun undoEnd() {
        val k = kept ?: return
        removeCallbacks(undoGone)
        kept = null // (this rect goes straight back to the trip's)
        onUndo?.invoke()
        NavSession.start(shell, k.place, k.route, remember = false)
    }

    private fun openMaps(search: Boolean) {
        if (search) Screens.mapsSearch(context) else Screens.maps(context)
    }

    // ------------------------------------------------------------------------------------------ debug builds: the mockups' numbers
    internal fun debugSpeed(v: Int, limit: Int, over: Boolean) {
        debugHeld = true
        showSpeed(v, limit, over)
    }

    internal fun debugTrip(dist: String, road: String, at: String, left: String, turn: Turn) {
        debugHeld = true
        turnGlyph.setImageResource(turnIcon(turn))
        showTrip(dist, road, at, left)
    }

    /** The tile's glyph, of the one icon family, as the mockup has it: this pill's 36 px says which way, and Maps has the roundabout at its real angle. */
    private fun turnIcon(t: Turn): Int = when (t) {
        Turn.STRAIGHT -> R.drawable.ic_go_straight
        Turn.SLIGHT_LEFT -> R.drawable.ic_slight_left
        Turn.LEFT -> R.drawable.ic_turn_left
        Turn.SHARP_LEFT -> R.drawable.ic_sharp_left
        Turn.UTURN -> R.drawable.ic_uturn_left
        Turn.SLIGHT_RIGHT -> R.drawable.ic_slight_right
        Turn.RIGHT -> R.drawable.ic_turn_right
        Turn.SHARP_RIGHT -> R.drawable.ic_sharp_right
        Turn.ROUNDABOUT -> R.drawable.ic_round_right
        Turn.ARRIVE -> R.drawable.ic_arrive
        Turn.REROUTE -> R.drawable.ic_reroute
    }

    private companion object {
        const val CARD_X = 16
        const val CARD_Y = 48
        const val CARD_W = 364
        const val CARD_H = 264
        const val PILL_X = 24
        const val PILL_Y = 56
        const val PILL_W = 348
        const val ETA_Y = 240
        /** End's hit, 122 x 80 round its 100 x 58 at (27, 243). */
        const val END_HIT_X = 16
        const val END_HIT_Y = 232
        /** The number in the search's slot: `chipPrayer`'s weight at 34, as the mockup. */
        val SPEED_ROLE = Type.Role("homeSpeed", 34, 600, 48, 34)
        val HOME_LIMIT = LimitSign.Spec(44, Type.Role("homeLimit", 19, 600, 44, 19), 3f, 3f, -0.4f, 19f)
        const val FIX_MAX_AGE_MS = 5000L
    }
}
