package com.abdllh.aura.ui

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Region
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import com.abdllh.aura.system.DriveState
import java.lang.reflect.Proxy

/**
 * One window of SPEC.md section 6.5, over everything: a fixed rect, its own `TYPE_APPLICATION_OVERLAY`, an opaque card on the `overlay` 9-patch with room in the window for
 * the shadow. Never off screen (no `FLAG_LAYOUT_NO_LIMITS`), so what of the outset lies past the screen is cut by the window's bounds. Its content is placed in [root] in the
 * window's own coordinates, from the left in every language.
 *
 * The window is larger than what shows, and a touch on its transparent part must reach what is under it: [visible] says which part of it takes touches, through the view tree's
 * internal insets (hidden, by reflection, greylisted on Android 10). Where those do not work the window itself ends at the visible rect and its content is scrolled inside it,
 * which cuts the shadow and changes nothing else.
 *
 * Nothing here rebuilds for a theme: an open window hears it ([Look.opened]), and [syncLook] catches up on a change made by an old path.
 */
internal class OverlayWindow(
    private val ctx: Context,
    private val name: String,
    private val x: Int,
    private val y: Int,
    private val w: Int,
    private val h: Int,
    touchable: Boolean = true,
    keepScreenOn: Boolean = false
) {
    private var lookDark = Palette.dark

    val root: FrameLayout = object : FrameLayout(ctx), Recolours {
        override fun onLook() { lookDark = Palette.dark }
    }.apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }

    private val params = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) or (if (keepScreenOn) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0),
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.LEFT or Gravity.TOP
        this.x = this@OverlayWindow.x
        this.y = this@OverlayWindow.y
        title = name
        windowAnimations = 0
    }

    private val wm: WindowManager get() = ctx.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val touchRegion = Rect(0, 0, w, h)
    private val insets = if (touchable) TouchRegion.listen(root, touchRegion) else true
    private var width = w

    var attached = false
        private set

    fun add(): Boolean {
        if (attached) return true
        return try {
            syncLook()
            wm.addView(root, params)
            attached = true
            Look.opened(root)
            true
        } catch (t: Throwable) {
            Log.w("AuraOverlay", "$name: $t")
            false
        }
    }

    fun remove() {
        if (!attached) return
        attached = false
        Look.closed(root)
        try { wm.removeView(root) } catch (_: Throwable) { }
    }

    /** The part of the window, in its coordinates, that shows and takes touches. */
    fun visible(l: Int, t: Int, r: Int, b: Int) {
        touchRegion.set(l, t, r, b)
        if (insets) {
            if (attached) root.invalidate() // (asked for again at the next traversal)
            return
        }
        params.x = x + l
        params.y = y + t
        params.width = r - l
        params.height = b - t
        root.scrollTo(l, t)
        if (attached) update()
    }

    /** Narrower from now on, once, at the end of what hid it (the call banner's shrink). */
    fun narrow(toW: Int) {
        if (toW == width) return
        width = toW
        if (!insets) return
        params.width = toW
        if (attached) update()
    }

    private fun update() {
        try { wm.updateViewLayout(root, params) } catch (t: Throwable) { Log.w("AuraOverlay", "$name: $t") }
    }

    /** The theme changed by a path that does not tell open windows. */
    fun syncLook() {
        if (lookDark != Palette.dark) Look.apply(root)
    }
}

/** A window's touches limited to a part of it: `ViewTreeObserver.addOnComputeInternalInsetsListener` and `TOUCHABLE_INSETS_REGION`, hidden. */
private object TouchRegion {
    private const val INSETS_REGION = 3

    /** True when the listener is on [root]; false: this firmware has no such thing, and the window must end where its card does. */
    fun listen(root: View, rect: Rect): Boolean = try {
        val listener = Class.forName("android.view.ViewTreeObserver\$OnComputeInternalInsetsListener")
        val info = Class.forName("android.view.ViewTreeObserver\$InternalInsetsInfo")
        val add = ViewTreeObserver::class.java.getMethod("addOnComputeInternalInsetsListener", listener)
        val setInsets = info.getMethod("setTouchableInsets", Int::class.javaPrimitiveType)
        val region = info.getField("touchableRegion")
        val proxy = Proxy.newProxyInstance(listener.classLoader, arrayOf(listener)) { self, m, a ->
            when (m.name) {
                "onComputeInternalInsets" -> {
                    setInsets.invoke(a[0], INSETS_REGION)
                    (region.get(a[0]) as Region).set(rect)
                    null
                }
                "equals" -> self === a[0]
                "hashCode" -> System.identityHashCode(self)
                "toString" -> "OverlayWindow.TouchRegion"
                else -> null
            }
        }
        add.invoke(root.viewTreeObserver, proxy)
        true
    } catch (t: Throwable) {
        Log.w("AuraOverlay", "no touch region: $t")
        false
    }
}

/** A button drawn shorter than it takes touches (SPEC.md section 1: a banner's are 72 drawn and 98 to the touch): a finger above or below the drawing counts as on its edge. */
internal class TallHit(ctx: Context, private val drawnTop: Int, private val drawnH: Int) : FrameLayout(ctx) {
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        val bottom = drawnTop + drawnH - 1f
        val dy = when {
            e.y < drawnTop && e.y >= 0f -> drawnTop - e.y
            e.y > bottom && e.y < height -> bottom - e.y
            else -> 0f
        }
        e.offsetLocation(0f, dy)
        val r = super.dispatchTouchEvent(e)
        e.offsetLocation(0f, -dy)
        return r
    }
}

/** Cards and banners run at 0.7 of their time while the car moves (SPEC.md section 9). */
internal fun overlayMs(ms: Long): Long = if (DriveState.moving) (ms * 0.7f).toLong() else ms
