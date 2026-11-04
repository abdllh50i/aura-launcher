package com.abdllh.aura.home

import android.content.Context
import android.graphics.Region
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Sheet

/**
 * The content of the one window, in layers, bottom to top: [mapHost] (the map's slot), [panelLayer] (Home, Maps, Settings...:
 * [PanelStack]), [chrome] (what stays over every panel in the new design: empty until then), [sheets] (Controls, the
 * app drawer). Snack adds itself to android.R.id.content, above all of this. Set once and never replaced: a new theme
 * rebuilds what is in the layers, so the surface in [mapHost] is never detached.
 */
class ShellRoot(ctx: Context) : FrameLayout(ctx) {
    val mapHost = MapHost(ctx, this)
    val panelLayer = FrameLayout(ctx)
    val chrome = FrameLayout(ctx)
    val sheets = FrameLayout(ctx)

    /** The content's size with no keyboard in it: what the map and every panel but one that asked to follow it are given. */
    internal var restWidth = 0
    internal var restHeight = 0

    /** The window's soft input mode is ADJUST_RESIZE now: a smaller size says nothing about the screen ([PanelStack]). */
    internal var followsKeyboard = false

    init {
        addView(mapHost, MATCH, MATCH)
        addView(panelLayer, MATCH, MATCH)
        addView(chrome, MATCH, MATCH)
        addView(sheets, MATCH, MATCH)
    }

    override fun onMeasure(w: Int, h: Int) {
        if (!followsKeyboard || restHeight == 0) {
            restWidth = MeasureSpec.getSize(w)
            restHeight = MeasureSpec.getSize(h)
        }
        super.onMeasure(w, h)
    }

    /**
     * The window is one rectangle, and its alpha alone reveals the map. A SurfaceView makes the window tell the compositor which part
     * of it is transparent, worked out at a layout pass from where the views are then: nothing here moves or shows by layout (a panel
     * comes from INVISIBLE, a card rises by translation), and what is drawn outside that hint can be cut off (the design's section 14,
     * question 2; the views that vanished over a panned surface, DEV.md "One window"). An empty hint cannot cut anything.
     */
    override fun gatherTransparentRegion(region: Region?): Boolean {
        region?.setEmpty()
        return false
    }

    /** True when one was open. */
    fun closeSheets(): Boolean {
        var closed = false
        for (i in 0 until sheets.childCount) {
            val s = sheets.getChildAt(i) as? Sheet ?: continue
            if (s.isOpen) { s.close(); closed = true }
        }
        return closed
    }
}

/**
 * The map's slot: always attached, always visible, always the screen's size whatever the keyboard does. The one map is in here
 * ([com.abdllh.aura.nav.MapController]); it must never be detached, resized or hidden (home-shell.md 5.2: detaching loses its GL
 * state, hiding its surface). Nothing else touches it.
 */
class MapHost(ctx: Context, private val shell: ShellRoot) : FrameLayout(ctx) {
    /** An opaque panel is at rest on top: the map sleeps. False before anything uncovers it ("wake first"). */
    var covered = false
        set(v) {
            if (v == field) return
            field = v
            onCovered?.invoke(v)
        }
    var onCovered: ((Boolean) -> Unit)? = null

    /** Over the map from a start until it has drawn: its land's colour, one flat fill, so a surface not there yet never shows as black. */
    val cover: View = object : View(ctx) {
        override fun hasOverlappingRendering() = false
    }.apply {
        setBackgroundColor(Palette.mapLand)
        visibility = GONE
    }

    init {
        addView(cover, MATCH, MATCH)
    }

    override fun onMeasure(w: Int, h: Int) {
        if (shell.restHeight == 0) super.onMeasure(w, h)
        else super.onMeasure(MeasureSpec.makeMeasureSpec(shell.restWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(shell.restHeight, MeasureSpec.EXACTLY))
    }
}

/**
 * What holds a panel's [Panel.root] in the panel layer: the stack moves, hides and shows this, so the panel's own views
 * keep their transforms to themselves. Only the top panel takes touches: one that is leaving hands the screen back at
 * once, as [Sheet] does, and a touch that began on it hears it end. A solid panel lets nothing through to what is under it.
 */
internal class PanelFrame(ctx: Context, private val shell: ShellRoot, private val panel: Panel) : FrameLayout(ctx) {
    private var touching = false

    var live = true
        set(v) {
            if (field && !v && touching) {
                touching = false
                val now = SystemClock.uptimeMillis()
                val c = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
                super.dispatchTouchEvent(c)
                c.recycle()
            }
            field = v
        }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { if (!live) return false; touching = true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touching = false
        }
        return live && super.dispatchTouchEvent(e)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean = panel.solid

    override fun onMeasure(w: Int, h: Int) {
        val follows = live && panel.softInput and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST == WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        if (follows || shell.restHeight == 0) super.onMeasure(w, h)
        else super.onMeasure(MeasureSpec.makeMeasureSpec(shell.restWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(shell.restHeight, MeasureSpec.EXACTLY))
    }
}
