package com.abdllh.aura.settings

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.PixelCopy
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.abdllh.aura.R
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Radius
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.Space
import com.abdllh.aura.ui.Spring
import com.abdllh.aura.ui.Theme
import com.abdllh.aura.ui.Touch
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.enter
import com.abdllh.aura.ui.exit
import com.abdllh.aura.ui.fadeTo
import com.abdllh.aura.ui.flp
import com.abdllh.aura.ui.iconView
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lp
import com.abdllh.aura.ui.pressScale
import com.abdllh.aura.update.UpdateManager
import com.abdllh.aura.util.LocaleHelper
import com.abdllh.aura.util.Prefs
import com.abdllh.aura.util.dp
import kotlin.math.abs
import kotlin.math.sign

/**
 * Settings: category list on the start side, selected page on the other.
 * A page is built the first time it is shown and kept (with where it was scrolled to) while the screen is up; a
 * preference that changes what a page shows rebuilds that page in place ([rebuild]); a new theme, accent or language
 * builds the screen again behind a picture of it that fades out ([restyle]). Until 1.11 every one of those recreated
 * the activity: a flash of the bare window and the page back at its top, for picking a navigation app or a microphone.
 */
class SettingsActivity : Activity() {
    companion object {
        const val EXTRA_PAGE = "page"
        /** Where the page was scrolled to (kept across a language change, which recreates the activity). */
        private const val EXTRA_SCROLL = "scrollY"
        const val PAGE_GENERAL = 0
        const val PAGE_UPDATE = 1
        const val PAGE_DISPLAY = 2
        const val PAGE_NAVIGATION = 3
        const val PAGE_VEHICLE = 4
        const val PAGE_VOICE = 5
        const val PAGE_ABOUT = 6
        const val REQ_MIC = 21

        /** The old screen's picture, handed from the activity a language change ends to the one it starts. */
        private var cover: Bitmap? = null
    }

    /** Goes on once the microphone permission is given (the voice page asked for it on the owner's way somewhere). */
    var afterMic: (() -> Unit)? = null

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, grants: IntArray) {
        if (code != REQ_MIC) return
        val then = afterMic
        afterMic = null
        com.abdllh.aura.voice.Amri.refresh(this)
        if (grants.isNotEmpty() && grants[0] == android.content.pm.PackageManager.PERMISSION_GRANTED && then != null) then() else rebuild()
    }

    private lateinit var pages: List<Page>
    /** Each page's view once built (null: built when next shown). */
    private lateinit var built: Array<ScrollView?>
    private var current = -1
    private lateinit var root: FrameLayout
    private lateinit var navItems: List<LinearLayout>
    private lateinit var indicator: View
    private var indicatorPlaced = false
    private lateinit var indicatorSpring: Spring
    private lateinit var content: FrameLayout
    private lateinit var topEdge: View
    private lateinit var bottomEdge: View
    private lateinit var title: AText
    private var updateDot: View? = null
    private var builtDark = true
    private var builtAccent = 0
    private var restyling = false
    private val main = Handler(Looper.getMainLooper())

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fonts.refreshLocale(this)
        Theme.refresh()
        Theme.window(this)
        if (com.abdllh.aura.BuildConfig.DEBUG) {
            // test hooks:  am start -n .../.settings.SettingsActivity --es api_base http://10.0.2.2:8765 --es repo test/aura
            intent.getStringExtra("api_base")?.let { Prefs.apiBase = it }
            intent.getStringExtra("repo")?.let { Prefs.updateRepo = it }
            // screenshots:  --es theme dark|light|auto  --es lang ar|en|system  (then press Home)
            intent.getStringExtra("theme")?.let { Prefs.theme = it; Theme.refresh(); Theme.window(this) }
            intent.getStringExtra("lang")?.let { Prefs.lang = it }
        }
        build(intent.getIntExtra(EXTRA_PAGE, PAGE_GENERAL), intent.getIntExtra(EXTRA_SCROLL, 0))
        intent.removeExtra(EXTRA_SCROLL)
        cover?.let { cover = null; fadeCover(it) }
    }

    /** The whole screen, for the loaded theme and accent, showing [page] scrolled to [scrollY]. */
    private fun build(page: Int, scrollY: Int) {
        builtDark = Palette.dark
        builtAccent = Palette.accent
        pages = listOf(GeneralPage(this), UpdatePage(this), DisplaySoundPage(this), NavigationPage(this), VehiclePage(this), VoicePage(this), AboutPage(this))
        built = arrayOfNulls(pages.size)
        current = -1
        indicatorPlaced = false
        root = buildFrame()
        setContentView(root)
        show(page.coerceIn(0, pages.size - 1), false)
        if (scrollY > 0) built[current]?.let { scrollWhenLaidOut(it, scrollY) }
    }

    // (no backdrop view: the window's own background is the screen's colour, and a second full-screen fill under
    // every frame of a scroll or a page change is a cost this GPU feels)
    private fun buildFrame(): FrameLayout {
        val root = FrameLayout(this)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        // ---- sidebar
        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(Space.L.dp, Space.M.dp, Space.M.dp, Space.M.dp)
        }
        val back = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(Space.S.dp, 0, Space.L.dp, 0)
            background = Shapes.ghost(Radius.M)
            isClickable = true
            contentDescription = getString(R.string.set_title)
            pressScale(Motion.PRESS_CONTROL)
            setOnClickListener { finish() }
            addView(iconView(R.drawable.ic_chevron_left, 30, Palette.text).also {
                if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) it.scaleX = -1f
            }, lp(30.dp, 30.dp))
            addView(label(Type.HEADLINE, Palette.text).apply { setText(R.string.set_title) }, lp(WRAP, WRAP).apply { marginStart = Space.S.dp })
        }
        side.addView(back, lp(WRAP, Touch.LARGE.dp))

        // The selection is one tint behind the items that slides from the old one to the new one, the colours of
        // the items following it, instead of a background that jumps from item to item.
        val nav = FrameLayout(this).apply { clipChildren = false }
        indicator = View(this).apply { background = Shapes.rect(Palette.accentSoft(), Radius.L) }
        nav.addView(indicator, flp(MATCH, Touch.MIN.dp, Gravity.TOP))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val items = ArrayList<LinearLayout>()
        for ((i, p) in pages.withIndex()) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPaddingRelative(Space.L.dp, 0, Space.L.dp, 0)
                background = Shapes.ghost(Radius.L)
                isClickable = true
                pressScale(Motion.PRESS_SURFACE)
                setOnClickListener { show(i, true) }
            }
            item.addView(iconView(p.iconRes, 26, Palette.text2), lp(26.dp, 26.dp))
            item.addView(label(Type.LABEL, Palette.text2).apply { setText(p.titleRes) }, lp(0, WRAP, 1f).apply { marginStart = Space.L.dp })
            if (p is UpdatePage) {
                updateDot = View(this).apply { background = Shapes.oval(Palette.accent); visibility = if (UpdateManager.hasUpdateBadge()) View.VISIBLE else View.GONE }
                item.addView(updateDot, lp(10.dp, 10.dp))
            }
            items.add(item)
            col.addView(item, lp(MATCH, Touch.MIN.dp).apply { if (i > 0) topMargin = Space.XS.dp }) // seven pages fit the 600 px screen
        }
        nav.addView(col, flp(MATCH, WRAP))
        navItems = items
        indicatorSpring = Spring(0f, 520f, 0.86f, 0.5f) { y -> indicator.translationY = y; tintNav(y) }
        side.addView(nav, lp(MATCH, WRAP).apply { topMargin = Space.S.dp })
        row.addView(side, lp(280.dp, MATCH))

        // ---- content
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(Space.S.dp, Space.L.dp, Space.XL.dp, 0)
        }
        title = label(Type.DISPLAY_S, Palette.text)
        right.addView(title, lp(MATCH, WRAP).apply { bottomMargin = Space.M.dp; marginStart = Space.S.dp })
        content = FrameLayout(this)
        // The page scrolls under soft edges in the screen's colour, shown only where there is more to see, instead
        // of cards cut by a hard line under the title (a gradient on top: the fading edge of a ScrollView would draw
        // through an offscreen layer on every frame of a scroll).
        topEdge = edge(GradientDrawable.Orientation.TOP_BOTTOM)
        bottomEdge = edge(GradientDrawable.Orientation.BOTTOM_TOP)
        content.addView(topEdge, flp(MATCH, Space.XL.dp, Gravity.TOP))
        content.addView(bottomEdge, flp(MATCH, Space.XL.dp, Gravity.BOTTOM))
        right.addView(content, lp(MATCH, 0, 1f))
        row.addView(right, lp(0, MATCH, 1f))
        root.addView(row, MATCH, MATCH)
        return root
    }

    private fun edge(o: GradientDrawable.Orientation) = object : View(this) {
        override fun hasOverlappingRendering() = false
    }.apply {
        background = GradientDrawable(o, intArrayOf(Palette.bg, Palette.withAlpha(Palette.bg, 0f)))
        alpha = 0f
    }

    /** Each edge as strong as there is page beyond it, up to its own height (no edge at the very top or bottom). */
    private fun edges(sv: ScrollView) {
        val h = Space.XL.dp.toFloat()
        val below = ((sv.getChildAt(0)?.height ?: 0) - sv.height - sv.scrollY).toFloat()
        topEdge.alpha = (sv.scrollY / h).coerceIn(0f, 1f)
        bottomEdge.alpha = (below / h).coerceIn(0f, 1f)
    }

    /** The items take the indicator's colours by how much of each it covers (it slides past the ones between). */
    private fun tintNav(y: Float) {
        for (item in navItems) {
            val h = item.height.coerceAtLeast(1)
            val k = (1f - abs(item.top - y) / h).coerceIn(0f, 1f)
            (item.getChildAt(0) as ImageView).setColorFilter(Palette.mix(Palette.text2, Palette.accentFg, k))
            (item.getChildAt(1) as AText).setTextColor(Palette.mix(Palette.text2, Palette.text, k))
        }
    }

    private fun moveIndicator(index: Int, animate: Boolean) {
        val item = navItems[index]
        if (item.height == 0) { // not laid out yet: placed when it is
            item.post { if (current == index) moveIndicator(index, false) }
            return
        }
        val y = item.top.toFloat()
        if (animate && indicatorPlaced) indicatorSpring.animateTo(y) else indicatorSpring.snapTo(y)
        indicatorPlaced = true
    }

    /**
     * Shows page [index]. The page that was showing goes quickly (towards the side of the list it was on), the new
     * one comes in from the other side and settles, and the title with it; both through a layer, so the cards of a
     * page fade as one picture. A page is built once and kept, so going back to it is instant and finds it scrolled
     * where it was.
     */
    fun show(index: Int, animate: Boolean) {
        if (index == current) return
        val prev = current
        if (prev >= 0) pages[prev].onHide()
        current = index
        moveIndicator(index, animate)
        val page = pages[index]
        val view = built[index] ?: scrolled(page.build(this)).also { sv ->
            built[index] = sv
            sv.setOnScrollChangeListener { v, _, _, _, _ -> if (built.getOrNull(current) === v) edges(v as ScrollView) }
            sv.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> if (built.getOrNull(current) === v) edges(v as ScrollView) }
        }
        val dir = if (prev < 0) 0f else sign((index - prev).toFloat())
        // (pages left from earlier taps go at once; a page coming back while it was still leaving turns back)
        for (i in content.childCount - 1 downTo 0) {
            val c = content.getChildAt(i)
            if (c === view || c === topEdge || c === bottomEdge) continue
            if (animate && prev >= 0 && c === built[prev]) c.exit(dy = -dir * Space.S, dur = Motion.XSHORT) { content.removeView(c) }
            else { c.animate().cancel(); content.removeView(c) }
        }
        if (view.parent == null) content.addView(view, content.indexOfChild(topEdge), flp(MATCH, MATCH))
        title.setText(page.titleRes)
        if (animate) {
            view.enter(dy = dir * Space.L, delay = 40L)
            title.enter(dy = dir * Space.S, delay = 40L)
        } else {
            view.animate().cancel()
            view.alpha = 1f; view.translationY = 0f
        }
        edges(view)
        page.onShow()
    }

    /** Rebuilds the current page in place, where it was scrolled to (a preference changed what it shows). */
    fun rebuild() {
        // (after the tap that asked for it has finished with the view it came from)
        content.post {
            val i = current
            if (i < 0 || isDestroyed) return@post
            val old = built[i]
            val y = old?.scrollY ?: 0
            pages[i].onHide()
            built[i] = null
            old?.let { it.animate().cancel(); content.removeView(it) }
            current = -1
            show(i, false)
            built[i]?.let { scrollWhenLaidOut(it, y) }
        }
    }

    /**
     * A new theme, accent ([apply] sets it) or, with [newLocale], language: the screen as it is now is captured, then
     * built again with the change (the language needs a new activity for its resources), and the picture fades out
     * on top of it. The page and its scroll stay.
     */
    fun restyle(newLocale: Boolean = false, apply: () -> Unit) {
        if (restyling) return
        restyling = true
        capture { snap ->
            restyling = false
            if (isDestroyed) return@capture
            apply()
            val y = built.getOrNull(current)?.scrollY ?: 0
            if (newLocale) {
                cover = snap
                intent.putExtra(EXTRA_PAGE, current).putExtra(EXTRA_SCROLL, y)
                for (p in pages) p.onHide()
                recreate()
                return@capture
            }
            for (p in pages) p.onHide()
            Theme.refresh()
            Theme.window(this)
            build(current, y)
            snap?.let { fadeCover(it) }
        }
    }

    /** The screen's content as a picture (null when the copy fails or never answers: the change then just shows). */
    private fun capture(done: (Bitmap?) -> Unit) {
        val r = root
        if (r.width == 0 || r.height == 0 || !r.isAttachedToWindow) { done(null); return }
        val snap = try { Bitmap.createBitmap(r.width, r.height, Bitmap.Config.ARGB_8888) } catch (_: Throwable) { null }
        if (snap == null) { done(null); return }
        var answered = false
        val once = { b: Bitmap? -> if (!answered) { answered = true; done(b) } }
        val at = IntArray(2)
        r.getLocationInWindow(at)
        try {
            PixelCopy.request(window, Rect(at[0], at[1], at[0] + r.width, at[1] + r.height), snap,
                { result -> once(if (result == PixelCopy.SUCCESS) snap else null) }, main)
            main.postDelayed({ once(null) }, 600L)
        } catch (_: Throwable) {
            once(null)
        }
    }

    private fun fadeCover(snap: Bitmap) {
        val c = ImageView(this).apply { setImageBitmap(snap); scaleType = ImageView.ScaleType.FIT_XY }
        root.addView(c, MATCH, MATCH)
        c.fadeTo(0f, Motion.LONG) { root.removeView(c) }
    }

    /** Scrolls [sv] to [y] once its page is laid out (before that a ScrollView clamps every position to 0). */
    private fun scrollWhenLaidOut(sv: ScrollView, y: Int) {
        sv.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                sv.viewTreeObserver.removeOnPreDrawListener(this)
                sv.scrollTo(0, y)
                return true
            }
        })
    }

    private val dotObserver: (UpdateManager.State) -> Unit = {
        updateDot?.visibility = if (UpdateManager.hasUpdateBadge()) View.VISIBLE else View.GONE
    }

    override fun onRestart() {
        super.onRestart()
        // Back from another screen (Maps after picking Home, Android's settings, the Play Store): what the pages show
        // may have changed meanwhile. The other pages are built again when next shown, this one now; a theme or an
        // accent changed elsewhere (the home screen's controls, the automatic theme at sunset) builds it all again.
        Theme.refresh()
        if (Palette.dark != builtDark || Palette.accent != builtAccent) {
            for (p in pages) p.onHide()
            Theme.window(this)
            build(current, built.getOrNull(current)?.scrollY ?: 0)
            return
        }
        for (i in built.indices) if (i != current) built[i] = null
        rebuild()
    }

    override fun onResume() {
        super.onResume()
        com.abdllh.aura.system.Animations.ensureAnimators()
        UpdateManager.observe(dotObserver) // also refreshes the dot right away
    }

    override fun onPause() {
        UpdateManager.unobserve(dotObserver)
        super.onPause()
    }

    override fun onDestroy() {
        // every page lets go of its listeners (UpdateManager, the Wi-Fi log, the offline map would otherwise keep this
        // dead activity alive)
        if (::pages.isInitialized) for (p in pages) p.onHide()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
