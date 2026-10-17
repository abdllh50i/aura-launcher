package com.abdllh.aura.nav

import android.content.Context
import android.graphics.drawable.TransitionDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.text.BidiFormatter
import com.abdllh.aura.R
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Elev
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Radius
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.Space
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.enter
import com.abdllh.aura.ui.exit
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lift
import com.abdllh.aura.ui.lp
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.roundedClip
import com.abdllh.aura.util.Prefs
import com.abdllh.aura.util.dp
import java.util.Locale

/**
 * The next manoeuvre, like a road sign, for every map that guides: a panel in the guidance colour ([Guide.sign]) with
 * the arrow, and beside it the distance (the number large, its unit small), what to do, and the road (its own line,
 * so a long or Latin name never breaks the sentence). Under them, when they apply, the lanes to keep to and the
 * manoeuvre that follows closely. While a new route is found it says so with a circling arrow instead of the old
 * step. A new step comes in (the arrow grows in, the words rise in); the same one only changes its numbers.
 *
 * [compact]: the home screen's map (a smaller sign, no lane or "then" strip; the arrival time and what is left in a
 * column at its end, since the trip bar is not there). Its width is fixed (440 dp, at most [maxWidthPx]), so the
 * changing numbers never resize it.
 *
 * Use: add it, call [update] whenever [NavSession] changes (it reads the session; nothing happens when nothing
 * changed), and [reveal] it in and out as navigation starts and ends.
 */
class GuidanceBanner(ctx: Context, val compact: Boolean = false) : LinearLayout(ctx) {
    /** Compact: the widest it may be (the home map gives what the speed sign and the camera chip leave); 0 = no cap. */
    var maxWidthPx = 0
        set(v) { if (v != field) { field = v; requestLayout() } }

    private val sign = Guide.sign()
    private val on = 0xFFFFFFFF.toInt() // (the sign is deep enough for white: Guide.sign)
    private val back = TransitionDrawable(arrayOf(Shapes.rect(sign, Radius.XL), Shapes.rect(Palette.mix(sign, 0xFF5A6270.toInt(), 0.5f), Radius.XL)))
    private val icon = ManeuverView(ctx).apply { color = on }
    private val texts = LinearLayout(ctx).apply { orientation = VERTICAL }
    private val dist: AText
    private val action: AText
    private val road: AText
    private val lanes: LaneStrip?
    private val thenRow: LinearLayout?
    private val thenIcon: ManeuverView?
    private val thenText: AText?
    private val arriveAt: AText?
    private val left: AText?

    /** The step shown (the route and its index), so a new one is told apart from new numbers for the same one. */
    private var shown: Pair<Route, Int>? = null
    private var rerouteShown = false
    /** A route was being found: the step shown when it is there comes in like a new one, whichever it is. */
    private var afterReroute = false
    private val arabic get() = Locale.getDefault().language == "ar"
    private val bidi get() = BidiFormatter.getInstance(arabic)

    init {
        orientation = VERTICAL
        background = back
        roundedClip(Radius.XL)
        lift(Elev.FLOAT, overMap = true)
        visibility = View.GONE
        val top = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (compact) setPaddingRelative(Space.M.dp, Space.M.dp, Space.L.dp, Space.M.dp)
            else setPaddingRelative(Space.L.dp, Space.M.dp, Space.XL.dp, Space.M.dp)
        }
        val iconSize = if (compact) 52 else 80
        top.addView(icon, lp(iconSize.dp, iconSize.dp))
        // the distance in the display role (tabular figures: a changing number does not jiggle); MATCH widths, so a
        // new number on every fix only redraws the line instead of measuring the screen again
        dist = if (compact) ctx.label(Type.HEADLINE, on).apply { fontFeatureSettings = "tnum" } else ctx.label(Type.DISPLAY_M, on)
        action = ctx.label(if (compact) Type.BODY else Type.TITLE, on)
        // every word on the sign in full white (a faded one fell under 4.5:1 on it): the lines differ by size alone
        road = ctx.label(if (compact) Type.FOOTNOTE else Type.BODY, on).apply { ellipsize = TextUtils.TruncateAt.END }
        texts.addView(dist, lp(MATCH, WRAP))
        texts.addView(action, lp(MATCH, WRAP).apply { topMargin = Space.XXS.dp })
        texts.addView(road, lp(MATCH, WRAP).apply { topMargin = Space.XXS.dp })
        top.addView(texts, lp(0, WRAP, 1f).apply { marginStart = (if (compact) Space.M else Space.L).dp })
        if (compact) {
            // what the trip bar says on the Maps screen: when, and how much is left
            top.addView(View(ctx).apply { setBackgroundColor(Palette.withAlpha(on, 0.25f)) },
                lp(1.dp, 40.dp).apply { marginStart = Space.M.dp; marginEnd = Space.M.dp })
            val eta = LinearLayout(ctx).apply { orientation = VERTICAL }
            arriveAt = ctx.label(Type.LABEL, on).apply { fontFeatureSettings = "tnum" }
            left = ctx.label(Type.CAPTION, on).apply { fontFeatureSettings = "tnum" }
            eta.addView(arriveAt, lp(MATCH, WRAP))
            eta.addView(left, lp(MATCH, WRAP))
            top.addView(eta, lp(104.dp, WRAP))
        } else {
            arriveAt = null
            left = null
        }
        addView(top, lp(MATCH, WRAP))
        if (compact) {
            lanes = null
            thenRow = null
            thenIcon = null
            thenText = null
        } else {
            val strip = Palette.mix(sign, 0xFF000000.toInt(), 0.24f)
            lanes = LaneStrip(ctx, on).apply {
                setBackgroundColor(strip)
                visibility = View.GONE
            }
            addView(lanes, lp(MATCH, 48.dp))
            thenRow = LinearLayout(ctx).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(strip)
                setPaddingRelative(Space.XL.dp, Space.S.dp, Space.XL.dp, Space.S.dp)
                visibility = View.GONE
            }
            thenIcon = ManeuverView(ctx).apply { color = on }
            thenRow.addView(thenIcon, lp(28.dp, 28.dp))
            thenText = ctx.label(Type.BODY, on)
            thenRow.addView(thenText, lp(0, WRAP, 1f).apply { marginStart = Space.M.dp })
            addView(thenRow, lp(MATCH, WRAP))
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!compact) return super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        var w = COMPACT_WIDTH_DP.dp
        if (maxWidthPx > 0) w = minOf(w, maxWidthPx)
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) w = minOf(w, MeasureSpec.getSize(widthMeasureSpec))
        super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), heightMeasureSpec)
    }

    /** Shows what [NavSession] says now. */
    fun update() {
        val r = NavSession.route ?: return
        val p = NavSession.progress ?: return
        bind(r, p, NavSession.rerouting, NavSession.destination)
    }

    /** Forgets the step shown: the next [update] shows its step at once, without the change animation. */
    fun reset() { shown = null }

    private fun bind(r: Route, p: NavSession.Progress, rerouting: Boolean, destination: Place?) {
        val ar = arabic
        if (rerouting != rerouteShown) {
            rerouteShown = rerouting
            // (a muted sign: the old step no longer holds, the new one is not there yet)
            if (rerouting) back.startTransition(Motion.SHORT.toInt()) else back.reverseTransition(Motion.SHORT.toInt())
            if (!rerouting) afterReroute = true
        }
        if (rerouting) {
            icon.turn = Turn.REROUTE
            dist.visibility = View.GONE
            road.visibility = View.GONE
            action.setIfChanged(context.getString(R.string.maps_rerouting))
            lanes?.reveal(false, -6f)
            thenRow?.reveal(false, -6f)
            return
        }
        val step = r.steps[p.next]
        val key = r to p.next
        if (key != shown || afterReroute) {
            val first = shown == null && !afterReroute
            shown = key
            afterReroute = false
            icon.show(step)
            // a new manoeuvre: it arrives (the arrow grows in, the words rise), so a turn just made is seen to be over
            if (!first && isShown) {
                icon.enter(scaleFrom = 0.82f)
                texts.enter(dy = 8f)
            }
        }
        dist.visibility = View.VISIBLE
        dist.setIfChanged(styled(Units.distance(p.toNext, ar), 0.55f, on))
        action.setIfChanged(Instructions.action(step, ar))
        val name = Instructions.roadLine(step, ar, destination?.name?.takeIf { it != context.getString(R.string.maps_dropped_pin) })
        // (a Latin name in the Arabic line, or one with brackets, keeps its own order: "Jeddah Street (Road 274)")
        road.setIfChanged(if (name.isBlank()) "" else bidi.unicodeWrap(name))
        road.visibility = if (name.isBlank()) View.GONE else View.VISIBLE
        if (compact) {
            arriveAt?.setIfChanged(Units.arrival(p.timeLeft, Prefs.clock24))
            left?.setIfChanged("${Units.duration(p.timeLeft, ar)} · ${Units.distance(p.remaining, ar)}")
            return
        }
        // the lanes, while it matters which one the car is in: some lead elsewhere, and the junction is near
        val laneList = step.lanes
        val showLanes = laneList.size >= 2 && laneList.any { !it.valid } && p.toNext < 800
        if (showLanes) lanes?.set(laneList, step.modifier)
        lanes?.reveal(showLanes, -6f)
        // what follows closely (within 12 s at this speed, at least 250 m): seen before this one is done
        val then = r.following(p.next)
        val soon = then != null && step.type != "arrive" &&
            then.at - (step.at + step.span) < maxOf(250.0, p.speedKmh / 3.6 * 12)
        if (soon && then != null) {
            thenIcon?.show(then)
            val next = Instructions.action(then, ar)
            thenText?.setIfChanged("${context.getString(R.string.maps_then)} ${if (ar) next else next.replaceFirstChar { it.lowercase() }}")
        }
        thenRow?.reveal(soon, -6f)
    }

    companion object {
        /** The compact banner's width, unless [maxWidthPx] is less. */
        const val COMPACT_WIDTH_DP = 440

        /**
         * [value] with each run of letters (the units: "م", "كم", "min", "دقيقة") at [unitScale] of the number's size
         * and in [unitColor]: the number is what is read at a glance.
         */
        fun styled(value: String, unitScale: Float, unitColor: Int): CharSequence {
            val s = SpannableString(value)
            var i = 0
            while (i < value.length) {
                if (Character.isLetter(value[i])) {
                    var j = i
                    while (j < value.length && Character.isLetter(value[j])) j++
                    s.setSpan(RelativeSizeSpan(unitScale), i, j, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    s.setSpan(ForegroundColorSpan(unitColor), i, j, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = j
                } else i++
            }
            return s
        }
    }
}

/**
 * The colours of guidance, one for every map and sign: the route line, the turn arrow, the car and the destination
 * on the map ([MapStyle.routeBase]) and the sign over it, so what the sign says and what the map shows are one thing.
 * They follow the owner's accent where it can mean "the way": blue, cyan and violet. The others would change their
 * meaning or sink into the map: green is the parks' colour, amber the motorways' and a warning, red the End button's
 * and the cameras', graphite the roads' and reads as switched off; those guide in the default blue.
 */
object Guide {
    // (the swatches themselves, as Settings offers them: blue, cyan, violet)
    private val FOR_GUIDING = setOf(Palette.accents[0], Palette.accents[1], Palette.accents[5])

    /** The route's colour (a fill: the line, the turn arrow, the car, the sign's base). */
    fun color(): Int = Palette.accent.takeIf { it in FOR_GUIDING } ?: Palette.DEFAULT_ACCENT

    /**
     * The sign's fill: [color], deepened just enough for white words on it to read at 4.5:1 (on the cyan accent
     * white stood at about 2:1).
     */
    fun sign(): Int {
        val c = color()
        var t = 0f
        var out = c
        while (t < 0.7f && Palette.contrast(0xFFFFFFFF.toInt(), out) < 4.5) {
            t += 0.04f
            out = Palette.mix(c, 0xFF000000.toInt(), t)
        }
        return out
    }
}

/**
 * The lanes at the junction ahead, as on the road's own signs: left to right in every language (OSRM lists them so),
 * each with its arrow; the ones that lead the route's way bright, the others faint.
 */
private class LaneStrip(ctx: Context, private val on: Int) : LinearLayout(ctx) {
    private var shown: List<Lane>? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        layoutDirection = LAYOUT_DIRECTION_LTR
    }

    fun set(lanes: List<Lane>, modifier: String) {
        if (lanes === shown) return
        shown = lanes
        removeAllViews()
        for ((i, l) in lanes.withIndex()) {
            if (i > 0) addView(View(context).apply { setBackgroundColor(Palette.withAlpha(on, 0.25f)) }, lp(1.dp, 26.dp))
            // a lane the route takes shows the way it takes; another, its first arrow
            val way = l.validIndication.ifBlank { l.indications.firstOrNull { it == modifier } ?: l.indications.firstOrNull().orEmpty() }
            addView(ManeuverView(context).apply {
                color = on
                turn = laneTurn(if (l.valid) way else l.indications.firstOrNull().orEmpty())
                alpha = if (l.valid) 1f else 0.32f
            }, lp(34.dp, 30.dp).apply { marginStart = Space.XXS.dp; marginEnd = Space.XXS.dp })
        }
    }

    private fun laneTurn(ind: String) = when (ind) {
        "slight left" -> Turn.SLIGHT_LEFT
        "left" -> Turn.LEFT
        "sharp left" -> Turn.SHARP_LEFT
        "slight right" -> Turn.SLIGHT_RIGHT
        "right" -> Turn.RIGHT
        "sharp right" -> Turn.SHARP_RIGHT
        "uturn" -> Turn.UTURN
        else -> Turn.STRAIGHT
    }
}

/** Sets the text only when it differs: a label given the same words on every fix would still lay itself out again. */
fun TextView.setIfChanged(s: CharSequence) {
    if (!TextUtils.equals(text, s)) text = s
}

/**
 * Shows or hides a panel the way the design system brings things in and out ([enter] from [dy] dp away, [exit]
 * halfway back, then GONE), only when that changes: the maps sync their panels on every fix. [animate] false, or a
 * view not on the screen yet: at once (the first sync after a screen is built must not play its entrances).
 */
fun View.reveal(show: Boolean, dy: Float, delay: Long = 0L, animate: Boolean = true, scaleFrom: Float = 1f) {
    val showing = visibility == View.VISIBLE && getTag(R.id.aura_exiting) != true
    if (show == showing) return
    if (!animate || !isAttachedToWindow || (parent as? View)?.isShown != true) {
        animate().cancel()
        setTag(R.id.aura_exiting, null)
        alpha = 1f
        translationY = 0f
        scaleX = 1f
        scaleY = 1f
        visibility = if (show) View.VISIBLE else View.GONE
        return
    }
    if (show) enter(dy = dy, scaleFrom = scaleFrom, delay = delay)
    else exit(dy = dy * 0.5f, scaleTo = if (scaleFrom != 1f) (1f + scaleFrom) / 2f else 1f) { visibility = View.GONE }
}
