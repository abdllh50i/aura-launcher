package com.abdllh.aura.ui

import android.content.Context
import android.graphics.Outline
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.abdllh.aura.R
import com.abdllh.aura.util.dp
import kotlin.math.abs
import kotlin.math.min

/**
 * A sheet over the screen; subclasses fill [panel]. Closing hands the screen back at once: from the moment [close] is called
 * touches go through to what is under the sheet (until 1.11 the first tap after closing was often lost to a sheet that was out of
 * sight but not gone), and a touch that began on it hears it end.
 *
 * [side] false: the old bottom sheet, dimmed scrim, rounded panel sliding up on a spring, a fling carries its speed into the motion.
 *
 * [side] true (SPEC.md section 5, "Side sheet"): the `overlay` 9-patch 440 wide, height to fit and at most 462, at y 44; at x 12 with
 * no trip and at x 384, beside the column, while a trip runs ([placeOnTrip]), so the turn card is never covered. No scrim over a
 * moving map: a tap outside only closes it. A drag toward the screen's left edge closes it, in both languages, since cards do not
 * swap sides. In: alpha and x -32 to 0, 240 `enter`; out: alpha and x to -24, 160 `exit`; turned back from where it is. [sideHead]
 * adds the title and the close button.
 */
open class Sheet(ctx: Context, fullHeight: Boolean = false, private val side: Boolean = false) : FrameLayout(ctx), Recolours {
    // (a single fill: its alpha goes into the colour, where a plain View fading was drawn through a full-screen
    // offscreen buffer on every frame of every open, close and drag)
    private val scrim = object : View(ctx) { override fun hasOverlappingRendering() = false }
        .apply { if (!side) setBackgroundColor(Palette.scrim); alpha = 0f }
    protected val panel: LinearLayout = if (side) SurfaceColumn(ctx, Surface.OVERLAY, SIDE_MAX_H) else LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    var isOpen = false
        private set
    var onClosed: (() -> Unit)? = null

    /** When a touch outside a side sheet last closed it (uptimeMillis): that same touch goes on to what it landed on, and whatever opens this must not take it for a new request. */
    var closedByTouchAt = -1_000_000L
        private set

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var touching = false // a finger came down while the sheet was open and is not up yet
    private var vt: VelocityTracker? = null
    private var finished = true // closed and reported (onClosed) since the last open
    private var closeFrom = 1f // how far open the panel was when the close began
    private val spring = Spring(0f, Tokens2.SPRING_sheet[0], Tokens2.SPRING_sheet[1], restDelta = 1.5f) { y -> moved(y) }
        .also { s -> s.onRest = { if (isOpen) onOpened() else finish() } }
    /** The side panel's way back after a drag let go of too early. */
    private val back = Spring(0f, 300f, 0.8f, restDelta = 0.5f) { x -> panel.translationX = x }

    init {
        visibility = GONE
        isClickable = true
        addView(scrim, flp(MATCH, MATCH))
        scrim.setOnClickListener { close() }
        panel.isClickable = true
        if (side) {
            panel.visibility = INVISIBLE
            addView(panel, flp(SIDE_W + 2 * Surface.OVERLAY.outset, WRAP, Gravity.LEFT or Gravity.TOP).apply {
                leftMargin = X_NO_TRIP - Surface.OVERLAY.outset
                topMargin = Tokens2.CONTENT_TOP - Surface.OVERLAY.outset
            })
        } else {
            panel.background = Shapes.topRounded(Palette.sheet, Radius.XL)
            // a light-theme shadow only (on the dark one the scrim hides it), from a round rectangle reaching past the
            // bottom edge: a top-rounded outline is a path, whose shadow is the expensive kind, re-done as the panel moves
            panel.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) {
                    val r = Radius.XL.dp
                    o.setRoundRect(0, 0, v.width, v.height + r.toInt(), r)
                }
            }
            panel.lift(Elev.FLOAT)
            panel.addView(View(ctx).apply { background = Shapes.pill(Palette.withAlpha(Palette.ink3, 0.5f)) },
                lp(44.dp, 5.dp).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = 10.dp })
            addView(panel, flp(MATCH, if (fullHeight) MATCH else WRAP, Gravity.BOTTOM).apply {
                if (fullHeight) topMargin = 14.dp
                marginStart = 10.dp
                marginEnd = 10.dp
            })
        }
    }

    override fun onLook() {
        if (side) (panel as SurfaceColumn).onLook()
        else {
            scrim.setBackgroundColor(Palette.scrim)
            panel.background = Shapes.topRounded(Palette.sheet, Radius.XL)
        }
    }

    /** A side sheet at x 384, beside the column, while a trip runs; at x 12 with none. */
    fun placeOnTrip(onTrip: Boolean) {
        if (!side) return
        val lp = panel.layoutParams as LayoutParams
        val want = (if (onTrip) X_ON_TRIP else X_NO_TRIP) - Surface.OVERLAY.outset
        if (lp.leftMargin != want) { lp.leftMargin = want; panel.layoutParams = lp }
    }

    /**
     * A side sheet's head: the title in `pageTitle` at the reading edge, the close button 56 x 56 at the far end, [tools] (shuffle,
     * repeat) beside it. In 64 px; what follows starts under it. The close button is given back for its description.
     */
    protected fun sideHead(title: CharSequence, vararg tools: View): View {
        val head = FrameLayout(context)
        val rtl = Fonts.arabicUi
        head.addView(context.label(Type.pageTitle, { Palette.ink }).apply { text = title },
            FrameLayout.LayoutParams(SIDE_W - 2 * HEAD_INSET - 56 * (1 + tools.size), Type.pageTitle.viewH, Gravity.TOP or if (rtl) Gravity.RIGHT else Gravity.LEFT).apply {
                topMargin = 10 + Type.pageTitle.viewTop
                if (rtl) rightMargin = HEAD_INSET else leftMargin = HEAD_INSET
            })
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = LAYOUT_DIRECTION_LTR
        }
        val close = IconButton(context, R.drawable.ic_close, 28, 56, 56, token = { Palette.ink2 }).apply {
            setOnClickListener { close() }
            contentDescription = context.getString(R.string.btn_cancel)
        }
        // (the close button always outermost, in both languages: left in Arabic, right in English)
        if (rtl) {
            row.addView(close, lp(56, 56))
            for (t in tools) row.addView(t, lp(56, 56))
        } else {
            for (t in tools) row.addView(t, lp(56, 56))
            row.addView(close, lp(56, 56))
        }
        head.addView(row, FrameLayout.LayoutParams(WRAP, 56, Gravity.TOP or if (rtl) Gravity.LEFT else Gravity.RIGHT).apply {
            topMargin = 8
            if (rtl) leftMargin = 8 else rightMargin = 8
        })
        panel.addView(head, lp(MATCH, HEAD_H))
        return close
    }

    /** How far down the panel goes to be out of sight. */
    private fun travel(): Float = (if (panel.height > 0) panel.height else resources.displayMetrics.heightPixels).toFloat()

    private fun moved(y: Float) {
        panel.translationY = y
        val k = (1f - y / travel()).coerceIn(0f, 1f)
        // closing, the dim clears ahead of the panel (squared, from where the close began, so it does not jump), so the
        // screen under it is back before the panel is gone
        scrim.alpha = if (isOpen || closeFrom <= 0f) k else (k * k / closeFrom).coerceAtMost(k)
        // the last pixels of a critically damped spring take as long as the rest of the way: out of sight is closed
        if (!isOpen && !dragging && y >= travel() - 2.dp) finish()
    }

    private fun finish() {
        if (finished || isOpen) return
        finished = true
        if (side) {
            panel.visibility = INVISIBLE
            panel.translationX = 0f
        } else {
            spring.cancel()
            panel.translationY = travel()
            scrim.alpha = 0f
        }
        visibility = GONE
        onClosed?.invoke()
    }

    fun open(animate: Boolean = true) {
        if (isOpen) return
        isOpen = true
        finished = false
        visibility = VISIBLE
        onOpen()
        if (side) { openSide(animate); return }
        if (!animate) { spring.snapTo(0f); return }
        spring.stiffness = Tokens2.SPRING_sheet[0]
        spring.dampingRatio = Tokens2.SPRING_sheet[1]
        if (spring.isRunning) { spring.animateTo(0f); return } // closing half way: back up from there
        spring.snapTo(travel())
        if (panel.height == 0) post { if (isOpen) { spring.snapTo(travel()); spring.animateTo(0f) } } // (laid out first)
        else spring.animateTo(0f)
    }

    private fun openSide(animate: Boolean) {
        back.cancel()
        if (!animate) {
            panel.animate().cancel()
            panel.setTag(R.id.aura_exiting, null)
            panel.visibility = VISIBLE
            panel.alpha = 1f
            panel.translationX = 0f
            onOpened()
            return
        }
        panel.enter(dx = SIDE_IN, dur = SIDE_IN_MS) { if (isOpen) onOpened() }
    }

    fun close() = close(0f)

    private fun close(velocity: Float) {
        if (!isOpen) return
        isOpen = false
        if (touching && !dragging) {
            // closed under a finger (Back, Home, the host): what it was on is told the touch is over, since the rest
            // of the gesture no longer reaches it (a drag of the sheet itself finishes in onTouchEvent instead)
            touching = false
            val now = SystemClock.uptimeMillis()
            val c = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            super.dispatchTouchEvent(c)
            c.recycle()
        }
        onClose()
        if (side) {
            back.cancel()
            panel.exit(dx = min(panel.translationX, SIDE_OUT), dur = SIDE_OUT_MS) { finish() }
            return
        }
        closeFrom = (1f - panel.translationY / travel()).coerceIn(0f, 1f)
        // quicker than the open, and moving from the first frame: from rest a spring starts slowly, so it is given
        // a push (or a fling's own speed, when that is more)
        spring.stiffness = Tokens2.SPRING_sheetClose[0]
        spring.dampingRatio = Tokens2.SPRING_sheetClose[1]
        spring.animateTo(travel(), maxOf(velocity, travel() * 3f))
    }

    protected open fun onOpen() {}
    /** The open movement has come to rest (e.g. pause what the sheet covers entirely). */
    protected open fun onOpened() {}
    protected open fun onClose() {}

    /**
     * Area at the top of the panel where a downward drag closes the sheet: the whole panel by default. A drag that
     * starts on something that can still scroll up (a list not at its top) scrolls it instead, whatever the zone.
     */
    protected open fun dragZone(): Int = panel.height

    // (the panel moves under the finger now: its children show their press only once a tap is clear, as in a list)
    override fun shouldDelayChildPressedState() = true

    // Closed, a sheet takes no new touch; one that began while it was open is cancelled once when it closes (see
    // [close]), so the control under the finger hears it end: a slider let go, a tile's press undone, no long-press.
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!isOpen) return false
                // (no scrim on a side sheet: a touch outside closes it and goes on to what it landed on, so volume, End, next and the voice stay one touch away: SPEC.md section 1)
                if (side && outsidePanel(e)) {
                    closedByTouchAt = SystemClock.uptimeMillis()
                    close()
                    return false
                }
                touching = true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touching = false
        }
        return if (!isOpen && !dragging) false else super.dispatchTouchEvent(e)
    }

    private fun outsidePanel(e: MotionEvent): Boolean {
        val o = Surface.OVERLAY.outset
        return e.x < panel.left + o || e.x >= panel.right - o || e.y < panel.top + o || e.y >= panel.bottom - o
    }

    /** Whether a view under ([x], [y]) (in this sheet's coordinates) can still scroll up. */
    private fun scrollsUpAt(v: View, x: Float, y: Float): Boolean {
        if (!v.isShown) return false
        if (v !== this && v.canScrollVertically(-1)) return true
        if (v is ViewGroup) for (i in v.childCount - 1 downTo 0) {
            val c = v.getChildAt(i)
            val cx = x + v.scrollX - c.left - c.translationX
            val cy = y + v.scrollY - c.top - c.translationY
            if (cx >= 0 && cy >= 0 && cx < c.width && cy < c.height && scrollsUpAt(c, cx, cy)) return true
        }
        return false
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (side) {
                    val dx = e.x - downX
                    val outset = Surface.OVERLAY.outset
                    val onPanel = downX >= panel.left + outset && downX < panel.right - outset && downY >= panel.top + outset && downY < panel.bottom - outset
                    if (isOpen && onPanel && dx < -slop && -dx > abs(e.y - downY)) {
                        dragging = true
                        back.cancel()
                        panel.animate().cancel()
                        downX = e.x - panel.translationX
                        vt?.recycle()
                        vt = VelocityTracker.obtain()
                        return true
                    }
                    return false
                }
                val top = panel.top + panel.translationY
                val inZone = downY >= top && downY <= top + dragZone()
                val dy = e.y - downY
                if (isOpen && inZone && dy > slop && dy > abs(e.x - downX) && !scrollsUpAt(this, downX, downY)) {
                    dragging = true
                    spring.cancel()
                    downY = e.y - panel.translationY
                    vt?.recycle()
                    vt = VelocityTracker.obtain()
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!dragging) return super.onTouchEvent(e)
        if (!isOpen) {
            // closed meanwhile from outside (Home or Back while the finger was on it): let go, and finish closing
            dragging = false
            vt?.recycle(); vt = null
            if (!side && (!spring.isRunning || spring.target != travel())) spring.animateTo(travel())
            return true
        }
        vt?.addMovement(e)
        if (side) { dragSide(e); return true }
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dy = e.y - downY
                // pulled up past the top: it resists (a quarter of the movement)
                spring.snapTo(if (dy >= 0f) dy else dy / 4f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                vt?.computeCurrentVelocity(1000)
                val v = vt?.yVelocity ?: 0f
                vt?.recycle(); vt = null
                if (panel.translationY > panel.height * 0.28f || v > 900) close(v)
                else {
                    spring.stiffness = 300f
                    spring.dampingRatio = 0.8f
                    spring.animateTo(0f, v)
                }
            }
        }
        return true
    }

    private fun dragSide(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX
                // pulled away from the edge: it resists (a quarter of the movement)
                panel.translationX = if (dx <= 0f) dx else dx / 4f
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                vt?.computeCurrentVelocity(1000)
                val v = vt?.xVelocity ?: 0f
                vt?.recycle(); vt = null
                if (panel.translationX < -SIDE_W * 0.28f || v < -900) close(v)
                else {
                    back.snapTo(panel.translationX)
                    back.animateTo(0f, v)
                }
            }
        }
    }

    private companion object {
        const val SIDE_W = 440
        const val SIDE_MAX_H = 462
        const val X_NO_TRIP = 12
        const val X_ON_TRIP = 384
        const val SIDE_IN = -32f
        const val SIDE_OUT = -24f
        const val SIDE_IN_MS = 240L
        const val SIDE_OUT_MS = 160L
        const val HEAD_H = 64
        const val HEAD_INSET = 22
    }
}
