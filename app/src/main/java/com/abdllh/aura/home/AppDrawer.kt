package com.abdllh.aura.home

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import com.abdllh.aura.R
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Menu
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Radius
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.Sheet
import com.abdllh.aura.ui.Space
import com.abdllh.aura.ui.Touch
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.enter
import com.abdllh.aura.ui.flp
import com.abdllh.aura.ui.iconView
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lp
import com.abdllh.aura.ui.pressScale
import com.abdllh.aura.util.dp

/**
 * All apps: a sheet that slides up over the home screen, with search. Long-press an app to pin it to the dock.
 * Closed, it stays laid out (invisible instead of gone): [prewarm] builds its cells and loads their icons a few seconds
 * after the home screen starts, so the first open has nothing to create in the frames the sheet moves in.
 */
class AppDrawer(ctx: Context, private val host: HomeHost) : Sheet(ctx, true) {
    private val search: EditText
    private val grid = GridView(ctx)
    private val empty: AText = ctx.label(Type.BODY, Palette.text3, gravity = Gravity.CENTER)
    private var all: List<AppInfo> = emptyList()
    private var shown: List<AppInfo> = emptyList()
    private val adapter = Adapter()

    init {
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Space.XL.dp, Space.XS.dp, Space.XL.dp, 0)
        }
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(ctx.label(Type.HEADLINE).apply { setText(R.string.drawer_title) }, lp(WRAP, WRAP).apply { marginEnd = Space.XL.dp })
        search = EditText(ctx).apply {
            setHint(R.string.drawer_search)
            setHintTextColor(Palette.text3)
            setTextColor(Palette.text)
            textSize = Type.BODY.sp
            typeface = Fonts.get(Type.BODY.weight)
            maxLines = 1
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setPaddingRelative(52.dp, 0, Space.L.dp, 0)
            background = Shapes.pill(Palette.card2)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) = applyFilter(s?.toString().orEmpty())
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        val searchBox = FrameLayout(ctx)
        searchBox.addView(search, flp(MATCH, MATCH))
        searchBox.addView(ctx.iconView(R.drawable.ic_search, 22, Palette.text3).apply {
            layoutParams = flp(22.dp, 22.dp, Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = 18.dp }
        })
        top.addView(searchBox, lp(0, Touch.MIN.dp, 1f))
        val closeBtn = FrameLayout(ctx).apply {
            background = Shapes.tonalOval()
            isClickable = true
            contentDescription = ctx.getString(R.string.btn_cancel)
            pressScale(Motion.PRESS_ICON)
            setOnClickListener { close() }
            addView(ctx.iconView(R.drawable.ic_close, 24, Palette.text).apply { layoutParams = flp(24.dp, 24.dp, Gravity.CENTER) })
        }
        top.addView(closeBtn, lp(Touch.MIN.dp, Touch.MIN.dp).apply { marginStart = Space.M.dp })
        content.addView(top, lp(MATCH, WRAP))

        grid.apply {
            numColumns = 8
            verticalSpacing = Space.S.dp
            horizontalSpacing = Space.XS.dp
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = android.graphics.drawable.ColorDrawable(0)
            isVerticalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(0, Space.L.dp, 0, Space.L.dp)
            adapter = this@AppDrawer.adapter
        }
        val body = FrameLayout(ctx)
        body.addView(grid, flp(MATCH, MATCH))
        body.addView(empty, flp(MATCH, WRAP, Gravity.CENTER))
        empty.setText(R.string.drawer_empty)
        empty.visibility = GONE
        content.addView(body, lp(MATCH, 0, 1f))
        panel.addView(content, lp(MATCH, 0, 1f))
    }

    // Closed is INVISIBLE, not GONE: the grid keeps its cells and their layout between opens (an invisible view neither
    // draws nor takes touches; its measure is cached while nothing in it changes).
    override fun setVisibility(v: Int) = super.setVisibility(if (v == GONE) INVISIBLE else v)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        grid.numColumns = ((w - 72.dp) / 126.dp).coerceIn(4, 12)
    }

    /** The cells built and their icons loaded while closed (some seconds after the home screen starts). */
    fun prewarm() {
        if (isOpen || all.isNotEmpty()) return
        all = AppRepo.cached ?: return
        applyFilter("")
    }

    override fun onOpen() {
        // an unchanged list is not bound again in the frame the sheet starts moving in
        val cached = AppRepo.cached ?: emptyList()
        if (cached !== all || search.text.isNotEmpty()) {
            all = cached
            if (search.text.isNotEmpty()) search.setText("") else applyFilter("")
        }
        if (all.isEmpty()) empty.visibility = GONE // still loading: do not claim "no apps"
        if (grid.canScrollVertically(-1)) grid.setSelection(0)
        // the icons come up together, a little after the sheet (one layer, drawn once, instead of a fade per cell)
        grid.enter(dy = 12f, delay = 60L)
        AppRepo.loadAsync(context) { fresh ->
            if (fresh.isEmpty()) {
                if (all.isEmpty()) applyFilter(search.text.toString())
                return@loadAsync
            }
            val changed = fresh != all
            all = fresh // (the same apps: kept as the list that is now cached, so the next open binds nothing)
            if (changed) applyFilter(search.text.toString())
        }
    }

    override fun onClose() {
        try { (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken, 0) } catch (_: Throwable) { }
        search.clearFocus()
    }

    private fun applyFilter(q: String) {
        val s = q.trim().lowercase()
        shown = if (s.isEmpty()) all else all.filter { it.label.lowercase().contains(s) }
        adapter.notifyDataSetChanged()
        empty.visibility = if (shown.isEmpty()) VISIBLE else GONE
    }

    /**
     * An app was opened from the drawer: the drawer stays while the app's window comes up over it (closing it at the
     * same time made the sheet, the window animation and the app's start compete for this processor), and goes when
     * the home screen stops. An app that is shown over the home screen without stopping it (a translucent window)
     * gets the drawer closed behind it a moment later.
     */
    private fun leftFor() {
        postDelayed({ if (isOpen && !hasWindowFocus()) close() }, LEAVE_CHECK_MS)
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(i: Int) = shown[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, convert: View?, parent: ViewGroup): View {
            val cell = (convert as? Cell) ?: Cell(parent.context)
            cell.bind(shown[i])
            return cell
        }
    }

    private inner class Cell(ctx: Context) : LinearLayout(ctx) {
        private val icon = ImageView(ctx)
        private val name: AText = ctx.label(Type.CAPTION, Palette.text2, gravity = Gravity.CENTER)
        private var tag: String? = null

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(Space.XS.dp, Space.M.dp, Space.XS.dp, Space.M.dp)
            icon.scaleType = ImageView.ScaleType.FIT_CENTER
            addView(icon, lp(ICON.dp, ICON.dp))
            addView(name, lp(MATCH, WRAP).apply { topMargin = Space.S.dp })
            background = Shapes.ghost(Radius.L)
            isClickable = true
            pressScale(Motion.PRESS_CONTROL)
        }

        fun bind(a: AppInfo) {
            tag = a.pkg
            name.text = a.label
            icon.setImageDrawable(null)
            // (as many pixels as it is shown with: four times as many were decoded and uploaded on the first open)
            IconLoader.get(context, a.pkg, ICON.dp) { bmp: Bitmap? -> if (tag == a.pkg && bmp != null) icon.setImageBitmap(bmp) }
            setOnClickListener {
                if (AppRepo.launch(context, a.pkg)) leftFor() else host.toast(context.getString(R.string.err_app_missing))
            }
            setOnLongClickListener { v ->
                val items = ArrayList<Menu.Item>()
                if (DockPins.contains(a.pkg)) {
                    items.add(Menu.Item(R.drawable.ic_close, context.getString(R.string.dock_unpin)) { DockPins.remove(a.pkg) })
                } else {
                    items.add(Menu.Item(R.drawable.ic_plus, context.getString(R.string.dock_pin)) {
                        if (DockPins.add(a.pkg)) host.toast(context.getString(R.string.dock_pinned, a.label))
                        else host.toast(context.getString(R.string.dock_full, DockPins.MAX))
                    })
                }
                if (!a.pkg.startsWith("aura:")) items.add(Menu.Item(R.drawable.ic_info, context.getString(R.string.drawer_app_info)) {
                    try {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        leftFor()
                    } catch (_: Throwable) { }
                })
                // apps installed on the unit (a dealer's launcher...): removed as root, the unit's own manager cannot
                if (!a.pkg.startsWith("aura:") && com.abdllh.aura.system.AppRemover.removable(context, a.pkg)) {
                    items.add(Menu.Item(R.drawable.ic_trash, context.getString(R.string.drawer_uninstall)) {
                        com.abdllh.aura.ui.Confirm.show(context, context.getString(R.string.uninstall_title, a.label),
                            context.getString(R.string.uninstall_text), context.getString(R.string.drawer_uninstall), true) {
                            com.abdllh.aura.system.AppRemover.uninstall(context, a.pkg) { ok ->
                                host.toast(context.getString(if (ok) R.string.uninstall_done else R.string.uninstall_failed, a.label))
                                if (ok) {
                                    DockPins.remove(a.pkg)
                                    AppRepo.forget(a.pkg)
                                    all = all.filter { it.pkg != a.pkg }
                                    applyFilter(search.text.toString())
                                }
                            }
                        }
                    })
                }
                Menu.show(v, items)
                true
            }
        }
    }

    private companion object {
        const val ICON = 68 // dp, an app's icon
        const val LEAVE_CHECK_MS = 1000L
    }
}
