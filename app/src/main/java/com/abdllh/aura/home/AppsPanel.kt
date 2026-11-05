package com.abdllh.aura.home

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.abdllh.aura.R
import com.abdllh.aura.nav.NavSession
import com.abdllh.aura.system.AppRemover
import com.abdllh.aura.system.DriveState
import com.abdllh.aura.system.IdleGc
import com.abdllh.aura.ui.Confirm
import com.abdllh.aura.ui.Menu
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.PressGesture
import com.abdllh.aura.ui.Snack
import com.abdllh.aura.ui.SnapScroll
import com.abdllh.aura.ui.Surface
import com.abdllh.aura.ui.SurfaceCard
import com.abdllh.aura.ui.Surfaces
import com.abdllh.aura.ui.Tokens2
import com.abdllh.aura.ui.Tones
import com.abdllh.aura.ui.TurnAway
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.anim
import com.abdllh.aura.ui.at
import com.abdllh.aura.ui.box
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.look
import com.abdllh.aura.ui.surfaceImage
import kotlin.math.ceil

/**
 * Apps (SPEC.md section 6.6): six by two a page, on the colour tiles of Home, in a room of its own; on a trip four by three in a card beside the column, which
 * stays. A known app of the firmware has its own glyph on its tile and AMRI's word for it, any other its own icon on the dark tile. A long press: its Android
 * page, and removing it where the root shell can (as the old drawer did; a pin is gone with the old dock). The list and the icons are read when it opens; the
 * frame answers in the first frame, what is in it follows.
 */
class AppsPanel(shell: HomeActivity) : Panel(shell, Screens.APPS) {
    override val root = FrameLayout(this)
    override val opaque: Boolean get() = NavSession.state == NavSession.State.IDLE
    override val solid: Boolean get() = opaque
    override val keep: Boolean get() = true
    override val recolours: Boolean get() = true
    override val besideColumn: Boolean get() = true

    private val rtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
    private var builtDark = Palette.dark
    private var builtOnTrip: Boolean? = null
    private var list: List<AppInfo> = emptyList()
    private var entries: List<AppEntry> = emptyList()
    private var scroll: SnapScroll? = null
    private var grid: FrameLayout? = null
    private var dots: PageDots? = null
    private var turnAway: TurnAway? = null

    override fun onShow(args: Bundle?) {
        // (a theme set meanwhile by an old path, or by a debug hook)
        if (Palette.dark != builtDark) { com.abdllh.aura.ui.Look.apply(root); builtDark = Palette.dark }
        frameFor(!opaque)
        turnAway?.on()
        AppRepo.cached?.let { fill(it, false) }
        AppRepo.loadAsync(this) { fresh ->
            if (isGone || fresh.isEmpty()) return@loadAsync
            fill(fresh, true)
            IdleGc.request("app list", GC_AFTER_MS)
        }
    }

    override fun onHide() { turnAway?.off() }

    override fun onTrip(on: Boolean) {
        frameFor(on)
        turnAway?.on()
    }

    override fun onLook() {
        if (Palette.dark != builtDark) { com.abdllh.aura.ui.Look.apply(root); builtDark = Palette.dark }
    }

    /** The room and the title, or the card and the title: at once. */
    private fun frameFor(onTrip: Boolean) {
        if (builtOnTrip == onTrip) return
        turnAway?.off()
        builtOnTrip = onTrip
        root.removeAllViews()
        entries = emptyList()
        grid = null
        val host: FrameLayout
        if (onTrip) {
            host = SurfaceCard(this, Surface.OVERLAY)
            root.addView(host, Surface.OVERLAY.at(CARD_X, CARD_Y, CARD_W, CARD_H))
            turnAway = TurnAway(host, 12f)
            val title = label(Type.pageTitle, { Palette.ink }).apply { setText(R.string.dock_apps) }
            host.addView(title, Type.pageTitle.box(22, 10, CARD_W - 44))
        } else {
            host = root
            turnAway = null
            root.addView(surfaceImage("room"), at(0, 0, 1024, 600))
            val title = label(Type.pageTitle, { Palette.ink }).apply { setText(R.string.dock_apps) }
            root.addView(title, Type.pageTitle.box(24, 46, 976))
        }
        val g = FrameLayout(this)
        val s = SnapScroll(this)
        s.addView(g, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        if (onTrip) host.addView(s, at(GRID_TRIP_X - CARD_X, GRID_TRIP_Y - CARD_Y, GRID_TRIP_W, 3 * ROW_TRIP))
        else host.addView(s, at(GRID_X, GRID_Y, GRID_W, 2 * ROW + WINDOW_MORE))
        val d = PageDots(this)
        if (onTrip) host.addView(d, at(CARD_W - 8, GRID_TRIP_Y - CARD_Y, 8, 3 * ROW_TRIP))
        else host.addView(d, at(GRID_X + GRID_W + 2, GRID_Y, 8, 2 * ROW))
        s.onMoved = { y -> d.at(y, s.most) }
        scroll = s
        grid = g
        dots = d
        if (list.isNotEmpty()) fill(list, false, force = true)
    }

    /** What is on the grid. An unchanged list binds nothing. */
    private fun fill(fresh: List<AppInfo>, reveal: Boolean, force: Boolean = false) {
        if (!force && fresh == list && entries.isNotEmpty()) return
        list = fresh
        val g = grid ?: return
        val onTrip = builtOnTrip == true
        val hadNone = entries.isEmpty()
        entries = order(this, fresh)
        // (an emulator has ten apps and a unit more than a page: setprop debug.aura.appsmany 3 shows each that many times)
        if (com.abdllh.aura.BuildConfig.DEBUG) com.abdllh.aura.system.SystemProps.get("debug.aura.appsmany").toIntOrNull()?.let { n -> entries = (1..n).flatMap { entries } }
        val cols = if (onTrip) 4 else 6
        val cellW = (if (onTrip) GRID_TRIP_W else GRID_W) / cols.toFloat()
        val rowH = if (onTrip) ROW_TRIP else ROW
        val cellH = if (onTrip) ROW_TRIP else CELL_H
        g.removeAllViews()
        for ((i, e) in entries.withIndex()) {
            val col = i % cols
            val left = Math.round((if (rtl) cols - 1 - col else col) * cellW)
            val right = Math.round(((if (rtl) cols - 1 - col else col) + 1) * cellW)
            g.addView(AppCell(this, e, onTrip), at(left, (i / cols) * rowH, right - left, cellH))
        }
        val rows = ceil(entries.size / cols.toDouble()).toInt()
        // (as tall as the window on its rows, so the last stop is a row's as well: the first page and the last stand on the same line)
        g.minimumHeight = rows * rowH + if (onTrip) 0 else WINDOW_MORE
        val s = scroll ?: return
        s.pitch = rowH
        dots?.set(ceil(rows / (if (onTrip) 3.0 else 2.0)).toInt())
        if (s.scrollY > g.minimumHeight - s.height) s.scrollTo(0, 0)
        // (read for the first time: it comes up as one; a list that was there just stands)
        if (reveal && hadNone) {
            s.alpha = 0f
            s.translationY = RISE
            s.anim(Tokens2.MS_cardIn, Motion.ENTER).alpha(1f).translationY(0f).start()
        }
    }

    private fun longPress(e: AppEntry, anchor: View): Boolean {
        val pkg = e.app?.pkg ?: return false
        if (pkg.startsWith("aura:")) return false
        val items = ArrayList<Menu.Item>()
        items.add(Menu.Item(R.drawable.ic_info, getString(R.string.drawer_app_info)) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Throwable) {
            }
        })
        // (apps installed on the unit, a dealer's launcher...: removed as root, the unit's own manager cannot)
        if (AppRemover.removable(this, pkg)) items.add(Menu.Item(R.drawable.ic_trash, getString(R.string.drawer_uninstall)) { remove(pkg, e.label) })
        Menu.show(anchor, items)
        return true
    }

    private fun remove(pkg: String, name: String) {
        // (a dialog never while the car moves: section 5)
        if (DriveState.moving) { Snack.show(this, R.string.st_parked_only); return }
        Confirm.show(this, getString(R.string.uninstall_title, name), getString(R.string.uninstall_text), getString(R.string.drawer_uninstall), true) {
            AppRemover.uninstall(this, pkg) { ok ->
                Snack.show(this, getString(if (ok) R.string.uninstall_done else R.string.uninstall_failed, name))
                if (ok && !isGone) {
                    AppRepo.forget(pkg)
                    fill(list.filter { it.pkg != pkg }, false)
                }
            }
        }
    }

    /** One cell: the tile, which sinks under the finger, and its word. A click and a long press belong to the whole cell. */
    private inner class AppCell(ctx: Context, private val e: AppEntry, onTrip: Boolean) : FrameLayout(ctx) {
        private val size = if (onTrip) TILE_TRIP else TILE
        private val tile = TileView(ctx, e, size, if (onTrip) GLYPH_TRIP else GLYPH, if (onTrip) ICON_TRIP else ICON)
        private val gesture = PressGesture(tile, PressGesture.ICON)

        init {
            // (the two heights of section 5: tile, gap and line box, centred in the cell)
            val role = if (onTrip) Type.rowValue else Type.gridLabel
            val gap = if (onTrip) 4 else 12
            val cellH = if (onTrip) ROW_TRIP else CELL_H
            val top = (cellH - size - gap - role.line) / 2
            addView(tile, FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = top })
            val word = label(role, { Palette.ink }, gravity = Gravity.CENTER).apply { text = e.label }
            addView(word, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, role.viewH, Gravity.TOP).apply { topMargin = top + size + gap + role.viewTop })
            isClickable = true
            setOnClickListener {
                Tones.tick(context)
                if (!e.open(context)) Snack.show(context, R.string.err_app_missing)
            }
            setOnLongClickListener { longPress(e, tile) }
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            val dx = -tile.left.toFloat()
            val dy = -tile.top.toFloat()
            ev.offsetLocation(dx, dy)
            gesture.onTouch(ev)
            ev.offsetLocation(-dx, -dy)
            return super.dispatchTouchEvent(ev)
        }

        override fun onDetachedFromWindow() {
            gesture.reset()
            super.onDetachedFromWindow()
        }

        override fun hasOverlappingRendering() = false
    }

    private companion object {
        const val GRID_X = 16
        const val GRID_Y = 96
        const val GRID_W = 992
        const val CELL_H = 196
        const val ROW = 204
        const val WINDOW_MORE = 2
        const val TILE = 104
        const val GLYPH = 56
        const val ICON = 72
        const val CARD_X = 392
        const val CARD_Y = 48
        const val CARD_W = 616
        const val CARD_H = 456
        const val GRID_TRIP_X = 400
        const val GRID_TRIP_Y = 108
        const val GRID_TRIP_W = 600
        const val ROW_TRIP = 132
        const val TILE_TRIP = 84
        const val GLYPH_TRIP = 46
        const val ICON_TRIP = 58
        const val RISE = 8f
        const val GC_AFTER_MS = 3000L
    }
}

/** One thing on the grid: an app, or a screen inside the firmware's launcher that has its own place here (the wheel's learning, the tool box). */
private class AppEntry(val label: String, val tile: Int, val glyph: Int, val app: AppInfo?, val open: (Context) -> Boolean)

/** A known one: the firmware's own apps, and what a car has beside them. Their order is the mockup's; everything else follows, by name. */
private class KnownTile(val pkgs: List<String>, val tile: Int, val glyph: Int, val word: Int, val cls: String? = null)

private val KNOWN = listOf(
    KnownTile(listOf("com.google.android.apps.youtube.music"), R.drawable.apptile_red, R.drawable.ic_yt, R.string.apps_yt),
    KnownTile(listOf(Known.RADIO), R.drawable.apptile_orange, R.drawable.ic_radio, R.string.apps_radio),
    KnownTile(listOf(Known.VIDEO), R.drawable.apptile_violet, R.drawable.ic_video, R.string.apps_video),
    KnownTile(listOf(Known.CAR_SETTING), R.drawable.apptile_blue, R.drawable.ic_car, R.string.apps_car),
    KnownTile(listOf(Known.STOCK_LAUNCHER), R.drawable.apptile_grey, R.drawable.ic_steering, R.string.apps_wheel, Known.ACT_WHEEL),
    KnownTile(listOf(Known.STOCK_LAUNCHER), R.drawable.apptile_teal, R.drawable.ic_tools, R.string.apps_tools, Known.ACT_TOOLBOX),
    KnownTile(listOf(Known.FILES), R.drawable.apptile_orange, R.drawable.ic_files, R.string.apps_files),
    KnownTile(listOf("com.android.chrome", "org.chromium.chrome", "com.android.browser"), R.drawable.apptile_blue, R.drawable.ic_globe, R.string.apps_browser),
    KnownTile(listOf("com.android.deskclock", "com.google.android.deskclock"), R.drawable.apptile_ink, R.drawable.ic_clock, R.string.apps_clock),
    KnownTile(listOf(Known.ZLINK), R.drawable.apptile_green, R.drawable.ic_link, R.string.apps_zlink),
    KnownTile(listOf("com.android.camera2", "com.android.camera"), R.drawable.apptile_ink, R.drawable.ic_camera_app, R.string.apps_camera),
    KnownTile(listOf("com.android.gallery3d", "com.google.android.apps.photos"), R.drawable.apptile_violet, R.drawable.ic_gallery, R.string.apps_gallery),
    KnownTile(listOf(Known.CAM360), R.drawable.apptile_ink, R.drawable.ic_cam360, R.string.apps_cam360),
    KnownTile(listOf(Known.MYCAR), R.drawable.apptile_blue, R.drawable.ic_car, R.string.apps_mycar),
    KnownTile(listOf(Known.PHONE), R.drawable.apptile_green, R.drawable.ic_phone, R.string.apps_phone)
)

/** Android's own settings are in Settings › Car and system, so one settings AppEntry stays on the grid; AMRI's own screens are in the dock; the stock launcher's are by name. */
private val NOT_HERE = setOf(Known.ANDROID_SETTINGS, Known.STOCK_LAUNCHER)

private fun order(c: Context, apps: List<AppInfo>): List<AppEntry> {
    val byPkg = apps.associateBy { it.pkg }
    val out = ArrayList<AppEntry>()
    val taken = HashSet<String>()
    for (k in KNOWN) {
        if (k.cls != null) {
            if (AppRepo.isInstalled(c, k.pkgs[0])) out.add(AppEntry(c.getString(k.word), k.tile, k.glyph, null) { x -> AppRepo.launchComponent(x, k.pkgs[0], k.cls) })
            continue
        }
        val a = k.pkgs.mapNotNull { byPkg[it] }.firstOrNull() ?: continue
        taken.add(a.pkg)
        out.add(AppEntry(c.getString(k.word), k.tile, k.glyph, a) { x -> AppRepo.launch(x, a.pkg) })
    }
    for (a in apps) {
        if (a.pkg in taken || a.pkg in NOT_HERE || a.pkg.startsWith("aura:")) continue
        out.add(AppEntry(a.label, R.drawable.apptile_ink, 0, a) { x -> AppRepo.launch(x, a.pkg) })
    }
    return out
}

/** The tile: one view with no children. Its own glyph in white, or the app's icon, which comes when it comes. */
private class TileView(ctx: Context, e: AppEntry, private val size: Int, private val glyphPx: Int, private val iconPx: Int) : View(ctx) {
    private val tile: Bitmap? = Surfaces.res(ctx, e.tile)
    private val glyph: Drawable? = if (e.glyph != 0) ctx.getDrawable(e.glyph)!!.mutate().also { it.setTint(WHITE) } else null
    private var icon: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val whole = Rect(0, 0, size, size)
    private val inner = Rect((size - iconPx) / 2, (size - iconPx) / 2, (size + iconPx) / 2, (size + iconPx) / 2)
    private var pkg: String? = null

    init {
        if (glyph != null) {
            val o = (size - glyphPx) / 2
            glyph.setBounds(o, o, o + glyphPx, o + glyphPx)
        } else e.app?.pkg?.let { p ->
            pkg = p
            IconLoader.get(ctx, p, iconPx) { b ->
                if (b != null) {
                    icon = b
                    invalidate()
                }
            }
        }
    }

    override fun hasOverlappingRendering() = false

    override fun onDraw(c: Canvas) {
        tile?.let { c.drawBitmap(it, null, whole, paint) }
        glyph?.draw(c)
        icon?.let { c.drawBitmap(it, null, inner, paint) }
    }

    private companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}

/** Where in the pages the list is, only with more than one: a dot a page in the margin, as a lift's. It redraws when the page changes. */
private class PageDots(ctx: Context) : View(ctx), com.abdllh.aura.ui.Recolours {
    private var pages = 1
    private var page = 0
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    fun set(pages: Int) {
        this.pages = pages
        invalidate()
    }

    fun at(y: Int, most: Int) {
        val now = if (most <= 0) 0 else Math.round(y.toFloat() / most * (pages - 1)).coerceIn(0, pages - 1)
        if (now != page) {
            page = now
            invalidate()
        }
    }

    override fun onLook() = invalidate()

    override fun hasOverlappingRendering() = false

    override fun onDraw(c: Canvas) {
        if (pages < 2) return
        val pitch = 16f
        val top = (height - (pages - 1) * pitch) / 2
        for (i in 0 until pages) {
            p.color = if (i == page) Palette.ink2 else Palette.track
            c.drawCircle(width / 2f, top + i * pitch, 3f, p)
        }
    }
}
