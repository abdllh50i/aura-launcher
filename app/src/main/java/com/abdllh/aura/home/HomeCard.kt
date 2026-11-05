package com.abdllh.aura.home

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
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
import com.abdllh.aura.nav.ManeuverView
import com.abdllh.aura.nav.NavSession
import com.abdllh.aura.nav.Place
import com.abdllh.aura.nav.Places
import com.abdllh.aura.nav.RoadWatch
import com.abdllh.aura.nav.Units
import com.abdllh.aura.settings.SettingsActivity
import com.abdllh.aura.ui.ABtn
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.LimitSign
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.PressGesture
import com.abdllh.aura.ui.Recolours
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
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
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
    private val chipsParked = group()
    private val chipsMoving = group()
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
    private val turnGlyph = ManeuverView(shell).apply { color = Palette.onAccent }
    private val turnText: AText = shell.label(Type.chipPrayer, { Palette.ink }, moving = true).apply { textDirection = TEXT_DIRECTION_LOCALE }
    private val etaText: AText = shell.label(Type.chipPrayer, { Palette.ink }, moving = true).apply { textDirection = TEXT_DIRECTION_LOCALE }
    private val end = ABtn(shell).apply {
        kind = ABtn.Kind.DANGER
        setHeightOverride(58)
        setPadding(0, 0, 0, 4)
        setText(R.string.home_end)
    }
    private val faces = HomeFaces(listOf(searchFace, speedFace, tripFace), searchFace)
    private val chipFaces = HomeFaces(listOf(chipsParked, chipsMoving), chipsParked)
    private var chipsShown = ""
    private var face = Face.PARKED

    private var kmh: Int? = null
    private var listening = false
    private var shownSpeed = ""
    private var shownTurnKey: Any? = null
    private var shownTurn = ""
    private var shownEta = ""
    private var debugHeld = false

    init {
        // the map under it takes no touches: a tap anywhere opens Maps, and the press shows as a plate at once
        val press = Tint(shell, R.drawable.plate_26) { Palette.fillPress }
        tap.background = PressGesture.plate(press.drawable)
        tap.isClickable = true
        tap.setOnClickListener { openMaps(false) }
        addView(tap, at(12, 44, 364, 268))
        // (a ring only, its middle empty: top light and ring over the hole)
        edge.look {
            background = context.getDrawable(if (Palette.dark) R.drawable.map_edge_night else R.drawable.map_edge_day)
            press.recolour()
        }
        addView(edge, at(12, 44, 364, 268))

        // the search pill
        searchFace.addView(shell.iconView(R.drawable.ic_search, 24) { Palette.ink2 }, at(if (ar) 310 else 14, 12, 24, 24))
        searchFace.addView(shell.label(Type.homeSearch, { Palette.ink2 }).apply { setText(R.string.home_where_to) }, Type.homeSearch.box(if (ar) 14 else 46, 10, 288))
        searchFace.setOnClickListener { openMaps(true) }
        pressable(searchFace, PressGesture.SURFACE)
        addView(searchFace, Surface.PILL.at(20, 52, 348, 48))

        // the speed in the search's slot
        speedFace.addView(overPlate, at(0, 0, 348, 48))
        speedFace.addView(speedSign, at(4, 2, HOME_LIMIT.px, HOME_LIMIT.px))
        speedFace.addView(speedNumber, SPEED_ROLE.box(56, 0, 280))
        addView(speedFace, Surface.PILL.at(20, 52, 348, 48))

        addView(chipsParked, MATCH_WINDOW())
        addView(chipsMoving, MATCH_WINDOW())

        // the trip's two pills, the tile at the reading edge and End on the driver's side as the mockup has them
        addView(tripFace, MATCH_WINDOW())
        tripFace.addView(turnPill, Surface.PILL.at(20, 52, 348, 64))
        tripFace.addView(etaPill, Surface.PILL.at(20, 240, 348, 64))
        val tileX = if (ar) 292 else 8
        turnPill.addView(turnTile, at(tileX, 8, 48, 48))
        turnPill.addView(turnGlyph, at(tileX + 6, 14, 36, 36))
        turnPill.addView(turnText, Type.chipPrayer.box(if (ar) 8 else 66, 16, 274))
        turnPill.setOnClickListener { openMaps(false) }
        pressable(turnPill, PressGesture.SURFACE)
        etaPill.addView(end, at(3, 3, 100, 58))
        etaPill.addView(etaText, Type.chipPrayer.box(111, 16, 229))
        // End: the 100 x 58 plate has a hit of 122 x 80, which runs past the pill's edges
        val endGesture = PressGesture(end, PressGesture.CONTROL)
        tripFace.addView(View(shell).apply {
            isClickable = true
            contentDescription = shell.getString(R.string.home_end)
            setOnTouchListener { _, e -> endGesture.onTouch(e); false }
            setOnClickListener { endTrip() }
        }, at(12, 232, 122, 80))
        look { turnGlyph.color = Palette.onAccent }
        refreshPlaces()
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
        chipFaces.show(when (f) { Face.PARKED -> chipsParked; Face.MOVING -> chipsMoving; Face.TRIP -> null }, animate)
        if (was != f) {
            syncSpeed()
            syncTrip()
        }
    }

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
            refreshPlaces()
        } else {
            CarLocation.removeListener(onFix)
            RoadWatch.removeListener(onRoad)
            removeCallbacks(fixGone)
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
            turnGlyph.show(step)
        }
        val dest = NavSession.destination?.name?.takeIf { it != shell.getString(R.string.maps_dropped_pin) }
        val guide = r.guide?.takeIf { it.arabic == ar }
        val told = guide?.toldOfStep(p.next) ?: -1
        val sign = if (guide != null && told >= 0) guide.banner(told, p.along, dest) else null
        val road = sign?.road?.ifBlank { null } ?: Instructions.roadLine(step, ar, dest)
        showTrip(Units.distance(p.toNext, ar), road, Units.arrival(p.timeLeft, Prefs.clock24), Units.duration(p.timeLeft, ar))
    }

    private fun showTrip(dist: String, road: String, at: String, left: String) {
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

    private fun endTrip() {
        NavSession.stop()
        shell.map.setRoute(null)
        shell.map.setDestination(null)
    }

    // ------------------------------------------------------------------------------------------ the chips
    /** Home, Work and the last destination, as they are saved now; laid out again only when a name changed. */
    fun refreshPlaces() {
        if (debugHeld) return
        val l = Places.recents.firstOrNull()
        val key = l?.name.orEmpty()
        if (key == chipsShown && chipsParked.childCount > 0) return
        chipsShown = key
        val names = ArrayList<Chip>()
        names.add(Chip(R.drawable.ic_home, shell.getString(R.string.home_chip_home)) { go("home") })
        names.add(Chip(R.drawable.ic_work, shell.getString(R.string.home_chip_work)) { go("work") })
        if (l != null) names.add(Chip(R.drawable.ic_bag, l.name) { goPlace(l) })
        buildChips(chipsParked, names, true)
        buildChips(chipsMoving, names, false)
    }

    private class Chip(val icon: Int, val name: String, val act: () -> Unit)

    /**
     * Flex by the mockup's CSS: each chip its content and padding, and the rest of the 348 shared out equally, with a gap of 6; a long name is cut at 120 (150 at `chip`) and only the third gives way. From the
     * reading edge in each language.
     */
    private fun buildChips(row: FrameLayout, names: List<Chip>, disc: Boolean) {
        row.removeAllViews()
        val role = if (disc) Type.homeChip else Type.chip
        val p = Paint().apply { typeface = Fonts.of(role, !disc); textSize = role.sp; Fonts.tune(this) }
        val pad = if (ar) 8 else 4
        val gap = if (ar) 6 else 4
        val most = if (disc) 120 else 150
        val textW = names.map { min(ceil(p.measureText(it.name)).toInt() + 2, most) }
        val base = textW.map { it + 2 * pad + if (disc) 26 + gap else 0 }
        val total = 348 - 6 * (names.size - 1)
        val free = total - base.sum()
        val widths = if (free >= 0) {
            val share = free / names.size
            base.mapIndexed { i, b -> b + share + if (i == names.lastIndex) free - share * names.size else 0 }
        } else {
            base.mapIndexed { i, b -> if (i == names.lastIndex) max(60, b + free) else b }
        }
        var left = 20
        val order = if (ar) names.indices.reversed() else names.indices
        for (i in order) {
            val w = widths[i]
            val c = names[i]
            val chip = SurfaceCard(shell, Surface.PILL)
            val content = textW[i] + if (disc) 26 + gap else 0
            val start = (w - content) / 2
            if (disc) {
                chip.addView(DiscGlyph(shell, c.icon), at(if (ar) start + content - 26 else start, 11, 26, 26))
                chip.addView(shell.label(role, { Palette.ink }).apply { text = c.name; gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP }, role.box(if (ar) start else start + 26 + gap, 10, textW[i]))
            } else {
                chip.addView(shell.label(role, { Palette.ink }, moving = true).apply { text = c.name; gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP }, role.box(start, 8, textW[i]))
            }
            chip.setOnClickListener { c.act() }
            pressable(chip, PressGesture.CONTROL)
            row.addView(chip, Surface.PILL.at(left, 256, w, 48))
            left += w + 6
        }
    }

    private fun openMaps(search: Boolean) {
        if (!Prefs.builtInMaps) { if (!Actions.nav(context)) host.toast(context.getString(R.string.err_no_nav)); return }
        if (search) Screens.mapsSearch(context) else Screens.maps(context)
    }

    /** Home or Work: the place card for it; unsaved, the pick flow on Maps. With an external navigation app the saved address is handed over. */
    private fun go(which: String) {
        val label = if (which == "home") R.string.home_home else R.string.home_work
        if (Prefs.builtInMaps) {
            val saved = if (which == "home") Places.home else Places.work
            val legacy = if (which == "home") Prefs.homeAddress else Prefs.workAddress
            when {
                saved != null -> goPlace(saved)
                legacy.isBlank() -> Screens.mapsPick(context, which)
                else -> Screens.mapsGo(context, which)
            }
            return
        }
        if (AppRepo.navPackage(context) == null) { host.toast(context.getString(R.string.err_no_nav)); return }
        if (!Actions.driveWithApp(context, which)) {
            // no such place yet: it is chosen with a pin on AMRI's map (Settings → Navigation)
            host.toast(context.getString(R.string.home_set_address_hint, context.getString(label)), outlives = true)
            host.openSettings(SettingsActivity.PAGE_NAVIGATION)
        }
    }

    private fun goPlace(p: Place) {
        Screens.mapsPlace(context, p.pos.lat, p.pos.lon, p.name, p.detail, true)
    }

    // ------------------------------------------------------------------------------------------ debug builds: the mockups' numbers
    internal fun debugSpeed(v: Int, limit: Int, over: Boolean) {
        debugHeld = true
        showSpeed(v, limit, over)
    }

    internal fun debugTrip(dist: String, road: String, at: String, left: String, turn: com.abdllh.aura.nav.Turn) {
        debugHeld = true
        turnGlyph.turn = turn
        showTrip(dist, road, at, left)
    }

    internal fun debugChips(third: String) {
        debugHeld = true
        val names = ArrayList<Chip>()
        names.add(Chip(R.drawable.ic_home, shell.getString(R.string.home_chip_home)) { })
        names.add(Chip(R.drawable.ic_work, shell.getString(R.string.home_chip_work)) { })
        names.add(Chip(R.drawable.ic_bag, third) { })
        buildChips(chipsParked, names, true)
        buildChips(chipsMoving, names, false)
    }

    /** A blue disc 26 with a white glyph 18: one view, no children. */
    private class DiscGlyph(ctx: android.content.Context, icon: Int) : View(ctx), Recolours {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glyph: Drawable = ctx.getDrawable(icon)!!.mutate().also { it.setBounds(4, 4, 22, 22) }

        init { onLook() }

        override fun onLook() {
            p.color = Palette.accent
            glyph.setTint(Palette.onAccent)
            invalidate()
        }

        override fun hasOverlappingRendering() = false

        override fun onDraw(c: Canvas) {
            c.drawCircle(13f, 13f, 13f, p)
            glyph.draw(c)
        }
    }

    private companion object {
        /** The number in the search's slot: `chipPrayer`'s weight at 34, as the mockup. */
        val SPEED_ROLE = Type.Role("homeSpeed", 34, 600, 48, 34)
        val HOME_LIMIT = LimitSign.Spec(44, Type.Role("homeLimit", 19, 600, 44, 19), 3f, 3f, -0.4f, 19f)
        const val FIX_MAX_AGE_MS = 5000L
    }
}
