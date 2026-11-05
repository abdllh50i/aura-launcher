package com.abdllh.aura.settings

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.abdllh.aura.R
import com.abdllh.aura.home.HomeActivity
import com.abdllh.aura.home.Panel
import com.abdllh.aura.home.Screens
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.IconButton
import com.abdllh.aura.ui.Look
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.PressGesture
import com.abdllh.aura.ui.SnapScroll
import com.abdllh.aura.ui.Spring
import com.abdllh.aura.ui.Surface
import com.abdllh.aura.ui.SurfaceCard
import com.abdllh.aura.ui.Tint
import com.abdllh.aura.ui.Tokens2
import com.abdllh.aura.ui.Tones
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.at
import com.abdllh.aura.ui.box
import com.abdllh.aura.ui.enter
import com.abdllh.aura.ui.exit
import com.abdllh.aura.ui.flp
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.look
import com.abdllh.aura.ui.scrollFade
import com.abdllh.aura.ui.surfaceImage
import com.abdllh.aura.update.UpdateManager
import kotlin.math.abs

/**
 * Settings (SPEC.md section 6.6): the room, a side list of seven pages on the reading side, one card 666 x 414 that holds the page, or a sub-page with back at its reading edge. The panel
 * stays built, each page with it, and a theme comes by [Look]: no picture, nothing is built again.
 * (A class name is left for whoever starts it from outside: [SettingsActivity], which also has the page numbers.)
 */
class SettingsPanel(shell: HomeActivity) : Panel(shell, Screens.SETTINGS) {
    override val root = FrameLayout(this)
    override val opaque: Boolean get() = true
    override val keep: Boolean get() = true
    override val recolours: Boolean get() = true

    /** Goes on once the microphone permission is given (the voice page asked for it on the owner's way somewhere). */
    var afterMic: (() -> Unit)? = null

    override fun onPermission(code: Int, grants: IntArray) {
        if (code != SettingsActivity.REQ_MIC) return
        val then = afterMic
        afterMic = null
        com.abdllh.aura.voice.Amri.refresh(this)
        if (grants.isNotEmpty() && grants[0] == android.content.pm.PackageManager.PERMISSION_GRANTED && then != null) then() else rebuild()
    }

    /** The microphone permission first (asked once; what was asked for goes on when it is given). */
    fun withMic(then: () -> Unit) {
        if (com.abdllh.aura.voice.Amri.hasMicPermission(this)) then()
        else {
            afterMic = then
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), SettingsActivity.REQ_MIC)
        }
    }

    val rtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL

    /** What a page's rows share. */
    val fillH: Int get() = CARD_H
    val controlW: Int get() = 440

    private val pages: List<Page> = listOf(DisplayPage(this), MapsPage(this), MusicPage(this), AmriPage(this), PhonePage(this), CarPage(this), AboutPage(this))
    private var current = 0
    /** Sub-pages over the current page. */
    private val subs = ArrayList<Page>()
    private var shownPage: Page? = null
    private var shownView: View? = null
    private var live = false
    private var resetDue = false
    private val holders = HashMap<Page, View>()

    private var built = false
    private var builtDark = Palette.dark
    private lateinit var card: FrameLayout
    private var pageTitle: AText? = null
    private var navItems: List<NavItem> = emptyList()
    private var indicator: View? = null
    private var indicatorSpring: Spring? = null
    private var indicatorPlaced = false
    private var updateDot: View? = null

    // ------------------------------------------------------------------------------------------ the panel's life
    override fun onShow(args: Bundle?) {
        if (!built) buildFrame()
        catchUp()
        if (resetDue) {
            resetDue = false
            subs.clear()
            go(current, false)
        }
        when {
            args != null && args.containsKey(SettingsActivity.EXTRA_PAGE) -> go(args.getInt(SettingsActivity.EXTRA_PAGE), false)
            shownPage == null -> go(current, false)
            // (back from another screen: a place picked on the map, Android's settings, the Play Store; what a page read at its build may be old)
            shownPage?.keep == false -> rebuild()
            else -> goLive()
        }
    }

    /** Asked for again while it is up: the page asked for (a start from outside, which may also have set the theme). */
    override fun onArgs(args: Bundle) {
        catchUp()
        if (args.containsKey(SettingsActivity.EXTRA_PAGE)) go(args.getInt(SettingsActivity.EXTRA_PAGE), shell.resumedNow)
    }

    override fun onHide() {
        goQuiet()
    }

    /** A visit is over: the next one opens on the last page, not on a sub-page. */
    override fun onOff() {
        resetDue = subs.isNotEmpty()
    }

    override fun onResume() {
        UpdateManager.observe(dotObserver) // also refreshes the dot right away
    }

    override fun onPause() {
        UpdateManager.unobserve(dotObserver)
    }

    override fun onBack(): Boolean {
        if (subs.isNotEmpty()) { pop(); return true }
        return false
    }

    override fun onLook() = catchUp()

    /** A theme set by a path that does not walk this panel (a debug hook, an old screen). */
    private fun catchUp() {
        if (Palette.dark != builtDark) {
            builtDark = Palette.dark
            Look.apply(root)
        }
    }

    override fun onDestroy() {
        goQuiet()
    }

    private val dotObserver: (UpdateManager.State) -> Unit = {
        updateDot?.visibility = if (UpdateManager.hasUpdateBadge()) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------------------------------ the frame
    private fun buildFrame() {
        built = true
        builtDark = Palette.dark
        root.addView(surfaceImage("room"), at(0, 0, 1024, 600))
        val side = label(Type.pageTitle, { Palette.ink }).apply { setText(R.string.set_title) }
        root.addView(side, Type.pageTitle.box(if (rtl) 704 else 24, 46, 296))
        val title = label(Type.pageTitle, { Palette.ink })
        pageTitle = title
        root.addView(title, Type.pageTitle.box(if (rtl) 26 else 354, 46, 644))
        card = SurfaceCard(this, Surface.CARD)
        root.addView(card, Surface.CARD.at(if (rtl) 16 else 332, CARD_Y, CARD_W, CARD_H))

        val nav = FrameLayout(this)
        root.addView(nav, at(if (rtl) 704 else 16, CARD_Y, NAV_W, 7 * NAV_PITCH))
        val plate = object : View(this) {
            override fun hasOverlappingRendering() = false
        }.apply { background = context.getDrawable(R.drawable.nav_on_58) }
        nav.addView(plate, at(0, 0, NAV_W, NAV_H))
        indicator = plate
        indicatorSpring = Spring(0f, Tokens2.SPRING_dockPlate[0], Tokens2.SPRING_dockPlate[1], 0.5f) { y -> plate.translationY = y; tintNav(y) }
        navItems = pages.mapIndexed { i, p ->
            NavItem(p.iconRes, getString(p.titleRes)).also { item ->
                item.setOnClickListener {
                    Tones.tick(this)
                    show(i, true)
                }
                nav.addView(item, at(0, i * NAV_PITCH, NAV_W, NAV_H))
            }
        }
        // (a new version is on offer: its dot, on the page its row is on)
        updateDot = View(this).look {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Palette.accentInk) }
        }.also { dot ->
            dot.visibility = if (UpdateManager.hasUpdateBadge()) View.VISIBLE else View.GONE
            navItems[SettingsActivity.PAGE_VEHICLE].addView(dot, FrameLayout.LayoutParams(10, 10, Gravity.CENTER_VERTICAL or (if (rtl) Gravity.LEFT else Gravity.RIGHT)).apply { leftMargin = 16; rightMargin = 16 })
        }
    }

    /** One item of the side list: its plate, its glyph and its word take the sliding plate's colours by how much of it the plate covers. */
    private inner class NavItem(icon: Int, title: String) : FrameLayout(this@SettingsPanel) {
        private val tile = object : View(context) {
            override fun hasOverlappingRendering() = false
        }
        private val tileFill = GradientDrawable().apply { cornerRadius = Tokens2.R_navPlate.toFloat() }
        private val glyph = ImageView(context).apply { setImageResource(icon); scaleType = ImageView.ScaleType.FIT_CENTER }
        private val word = context.label(Type.navItem, Palette.ink).apply { text = title }
        private var share = 0f

        init {
            isClickable = true
            val press = Tint(context, R.drawable.plate_18) { Palette.fillPress }
            background = PressGesture.plate(press.drawable)
            look { press.recolour(); paint() }
            tile.background = tileFill
            val edge = if (rtl) Gravity.RIGHT else Gravity.LEFT
            addView(tile, FrameLayout.LayoutParams(38, 38, Gravity.CENTER_VERTICAL or edge).apply { leftMargin = 10; rightMargin = 10 })
            addView(glyph, FrameLayout.LayoutParams(24, 24, Gravity.CENTER_VERTICAL or edge).apply { leftMargin = 17; rightMargin = 17 })
            addView(word, FrameLayout.LayoutParams(MATCH, Type.navItem.viewH, Gravity.CENTER_VERTICAL).apply { leftMargin = 60; rightMargin = 60 })
        }

        fun cover(k: Float) {
            if (k == share) return
            share = k
            paint()
        }

        private fun paint() {
            val ink = Palette.mix(Palette.ink, Palette.onAccent, share)
            glyph.setColorFilter(ink)
            word.setTextColor(ink)
            tileFill.setColor(Palette.mix(Palette.fill2, Palette.topLight, share))
        }

        override fun hasOverlappingRendering() = false
    }

    private fun tintNav(y: Float) {
        for ((i, item) in navItems.withIndex()) item.cover((1f - abs(i * NAV_PITCH - y) / NAV_PITCH).coerceIn(0f, 1f))
    }

    private fun moveIndicator(index: Int, animate: Boolean) {
        val s = indicatorSpring ?: return
        val y = (index * NAV_PITCH).toFloat()
        if (animate && indicatorPlaced) s.animateTo(y) else s.snapTo(y)
        indicatorPlaced = true
        tintNav(s.value)
    }

    /** [px] in the reading direction: left in Arabic. */
    private fun reading(px: Float) = if (rtl) -px else px

    // ------------------------------------------------------------------------------------------ the pages
    /** A page number from outside, a sub-page's included. */
    private fun go(code: Int, animate: Boolean) {
        when (code) {
            SettingsActivity.PAGE_UPDATE -> { show(SettingsActivity.PAGE_VEHICLE, false); open(pageForCode(code)) }
            SettingsActivity.PAGE_PACK -> { show(SettingsActivity.PAGE_MAPS, false); open(pageForCode(code)) }
            else -> show(code.coerceIn(0, pages.size - 1), animate)
        }
    }

    private fun pageForCode(code: Int): Page = when (code) {
        SettingsActivity.PAGE_UPDATE -> UpdatePage(this)
        else -> PackPage(this)
    }

    /** Page [index] of the seven, what was over it gone. */
    fun show(index: Int, animate: Boolean) {
        val dir = if (animate && index < current) -1 else 1
        current = index
        subs.clear()
        moveIndicator(index, animate)
        val page = pages[index]
        if (page === shownPage) { goLive(); return }
        pageTitle?.let { it.setText(page.titleRes); if (animate) it.enter(dx = reading(dir * STEP), dur = Tokens2.MS_pageSwap) }
        present(page, dir, animate, sub = false)
    }

    /** A sub-page, in the same card. */
    fun open(page: Page) {
        subs.add(page)
        holders.remove(page)
        present(page, 1, shell.resumedNow, sub = true)
    }

    private fun pop() {
        subs.removeAt(subs.lastIndex)
        val page = subs.lastOrNull() ?: pages[current]
        present(page, -1, shell.resumedNow, sub = subs.isNotEmpty())
    }

    /** The page's view, built now or kept: for a sub-page with its head, in the room the page's rows alone. */
    private fun holderFor(page: Page, sub: Boolean): View {
        holders[page]?.takeIf { page.keep }?.let { return it }
        val w = CARD_W
        val h = CARD_H
        val surface = Surface.CARD
        val hold = FrameLayout(this)
        val top = if (sub) HEAD else 0
        if (sub) {
            val back = IconButton(this, R.drawable.ic_chev_left, 30, 56, 56, press = PressGesture.ICON, token = { Palette.ink2 }).apply {
                contentDescription = getString(R.string.set_title)
                setOnClickListener { Tones.tick(context); backTapped() }
            }
            hold.addView(back, at(if (rtl) w - 60 else 4, 8, 56, 56))
            val t = label(Type.pageTitle, { Palette.ink }).apply { setText(page.titleRes) }
            hold.addView(t, Type.pageTitle.box(60, 10, w - 120))
        }
        val content = page.build(this)
        if (page.scrolls) {
            val s = SnapScroll(this)
            s.addView(content, FrameLayout.LayoutParams(MATCH, FrameLayout.LayoutParams.WRAP_CONTENT))
            // (the bottom fade stands clear of the card's round corners, and what scrolls ends where it ends)
            hold.addView(s, at(0, top, w, h - top - FADE_INSET))
            val up = if (sub) fadeUnderHead(surface) else scrollFade(surface, true)
            val down = scrollFade(surface, false)
            hold.addView(up, at(FADE_INSET, top, w - 2 * FADE_INSET, FADE))
            hold.addView(down, at(FADE_INSET, h - FADE - FADE_INSET, w - 2 * FADE_INSET, FADE))
            s.onMoved = {
                up.visibility = if (s.roomAbove) View.VISIBLE else View.INVISIBLE
                down.visibility = if (s.roomBelow) View.VISIBLE else View.INVISIBLE
            }
            up.visibility = View.INVISIBLE
            down.visibility = View.INVISIBLE
            hold.tag = s
        } else hold.addView(content, at(0, top, w, h - top))
        holders[page] = hold
        return hold
    }

    /**
     * The top end of a page under a head. The baked fades are made for a card's own ends, and its top is lighter than where a head ends, which showed as a band: this one starts from the
     * card's own colour there, the middle of its 9-patch.
     */
    private fun fadeUnderHead(surface: Surface): View = View(this).look {
        val b = com.abdllh.aura.ui.Surfaces.res(context, surface.res)
        val c = b?.getPixel(b.width / 2, b.height / 2) ?: Palette.cardFlat
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(c, c and 0x00FFFFFF))
    }

    private fun backTapped() {
        if (subs.isNotEmpty()) pop() else finish()
    }

    private fun present(page: Page, dir: Int, animate: Boolean, sub: Boolean) {
        goQuiet()
        val old = shownView
        val oldPage = shownPage
        val v = holderFor(page, sub)
        shownPage = page
        shownView = v
        if (old !== v && old != null) {
            // A page that comes back stays in the card, hidden. Added to a parent again a view forgets its direction until its next measure, and a kept page is not measured again: a switch
            // showed its thumb at the wrong end and a theme missed it, not being in the tree.
            val stays = oldPage != null && oldPage.keep && (oldPage in pages || oldPage in subs)
            if (!stays && oldPage != null) holders.remove(oldPage)
            val off = { if (shownView !== old) { if (stays) old.visibility = View.INVISIBLE else card.removeView(old) } }
            if (animate) old.exit(dur = Tokens2.MS_pageSwap) { off() }
            else { old.animate().cancel(); old.setTag(R.id.aura_exiting, null); off() }
        }
        if (v.parent == null) card.addView(v, flp(MATCH, MATCH)) else card.bringChildToFront(v)
        if (animate) v.enter(dx = reading(dir * STEP), dur = Tokens2.MS_pageSwap)
        else {
            v.animate().cancel()
            v.setTag(R.id.aura_exiting, null)
            v.alpha = 1f
            v.translationX = 0f
            v.translationY = 0f
            v.visibility = View.VISIBLE
        }
        goLive()
    }

    private fun goLive() {
        if (live) return
        if (!shell.startedNow) return
        live = true
        shownPage?.onShow()
    }

    private fun goQuiet() {
        if (!live) return
        live = false
        shownPage?.onHide()
    }

    /** The page on screen again, where it was scrolled to: a preference changed what it shows. */
    fun rebuild() {
        // (after the tap that asked for it has finished with the view it came from)
        root.post {
            val p = shownPage ?: return@post
            if (isGone) return@post
            val y = (shownView?.tag as? SnapScroll)?.scrollY ?: 0
            holders.remove(p)
            goQuiet()
            val old = shownView
            shownView = null
            old?.animate()?.cancel()
            card.removeView(old)
            present(p, 0, false, sub = subs.isNotEmpty())
            (shownView?.tag as? SnapScroll)?.let { s -> s.post { s.scrollTo(0, y) } }
        }
    }

    /** A theme's choice: the control shows it landing, then the screen takes it. */
    fun takeTheme() {
        root.postDelayed({ com.abdllh.aura.ui.ThemeSwap.run(shell) { builtDark = Palette.dark } }, THEME_AFTER_MS)
    }

    /** A language's choice: the whole window again ([HomeActivity.languageChanged]), back on the page it came from. */
    fun takeLanguage() {
        goQuiet()
        shell.languageChanged(Bundle().apply { putInt(SettingsActivity.EXTRA_PAGE, current) })
    }

    private companion object {
        const val CARD_Y = 96
        const val CARD_W = 676
        const val CARD_H = 408
        const val NAV_W = 304
        const val NAV_H = 58
        const val NAV_PITCH = 60
        const val HEAD = 64
        const val FADE = 28
        const val FADE_INSET = 8
        const val STEP = 12f
        const val THEME_AFTER_MS = 200L
    }
}
