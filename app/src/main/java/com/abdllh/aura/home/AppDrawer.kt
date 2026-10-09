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
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.Sheet
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.flp
import com.abdllh.aura.ui.iconView
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lp
import com.abdllh.aura.ui.pressScale
import com.abdllh.aura.util.dp

/** All apps: a sheet that slides up over the home screen, with search. Long-press an app to pin it to the dock. */
class AppDrawer(ctx: Context, private val host: HomeHost) : Sheet(ctx, true) {
    private val search: EditText
    private val grid = GridView(ctx)
    private val empty: AText = ctx.label(16f, Palette.text3, Fonts.REGULAR, gravity = Gravity.CENTER)
    private var all: List<AppInfo> = emptyList()
    private var shown: List<AppInfo> = emptyList()
    private val adapter = Adapter()

    init {
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(26.dp, 6.dp, 26.dp, 0)
        }
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(ctx.label(24f, Palette.text, Fonts.MEDIUM).apply { setText(R.string.drawer_title) }, lp(WRAP, WRAP).apply { marginEnd = 22.dp })
        search = EditText(ctx).apply {
            setHint(R.string.drawer_search)
            setHintTextColor(Palette.text3)
            setTextColor(Palette.text)
            textSize = 18f
            typeface = Fonts.get(Fonts.REGULAR)
            maxLines = 1
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setPaddingRelative(54.dp, 0, 18.dp, 0)
            background = Shapes.rect(Palette.card2, 29f)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) = applyFilter(s?.toString().orEmpty())
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        val searchBox = FrameLayout(ctx)
        searchBox.addView(search, flp(MATCH, MATCH))
        searchBox.addView(ctx.iconView(R.drawable.ic_search, 23, Palette.text3).apply {
            layoutParams = flp(23.dp, 23.dp, Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = 19.dp }
        })
        top.addView(searchBox, lp(0, 58.dp, 1f))
        val closeBtn = FrameLayout(ctx).apply {
            background = Shapes.tonalOval()
            isClickable = true
            contentDescription = ctx.getString(R.string.btn_cancel)
            pressScale(0.9f)
            setOnClickListener { close() }
            addView(ctx.iconView(R.drawable.ic_close, 24, Palette.text).apply { layoutParams = flp(24.dp, 24.dp, Gravity.CENTER) })
        }
        top.addView(closeBtn, lp(58.dp, 58.dp).apply { marginStart = 14.dp })
        content.addView(top, lp(MATCH, WRAP))

        grid.apply {
            numColumns = 8
            verticalSpacing = 6.dp
            horizontalSpacing = 4.dp
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = android.graphics.drawable.ColorDrawable(0)
            isVerticalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(0, 14.dp, 0, 18.dp)
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

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        grid.numColumns = ((w - 72.dp) / 126.dp).coerceIn(4, 12)
    }

    override fun dragZone(): Int = 30.dp

    override fun onOpen() {
        all = AppRepo.cached ?: emptyList()
        search.setText("")
        applyFilter("")
        if (all.isEmpty()) empty.visibility = GONE // still loading: do not claim "no apps"
        grid.setSelection(0)
        AppRepo.loadAsync(context) { fresh ->
            if (fresh.isNotEmpty() && fresh != all) {
                all = fresh
                applyFilter(search.text.toString())
            } else if (fresh.isEmpty() && all.isEmpty()) {
                applyFilter(search.text.toString())
            }
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
        private val name: AText = ctx.label(14f, Palette.text2, Fonts.REGULAR, gravity = Gravity.CENTER)
        private var tag: String? = null

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(4.dp, 10.dp, 4.dp, 10.dp)
            icon.scaleType = ImageView.ScaleType.FIT_CENTER
            addView(icon, lp(68.dp, 68.dp))
            addView(name, lp(MATCH, WRAP).apply { topMargin = 8.dp })
            background = Shapes.ghost(18f)
            isClickable = true
            pressScale(0.92f)
        }

        fun bind(a: AppInfo) {
            tag = a.pkg
            name.text = a.label
            icon.setImageDrawable(null)
            IconLoader.get(context, a.pkg, 136) { bmp: Bitmap? -> if (tag == a.pkg && bmp != null) icon.setImageBitmap(bmp) }
            setOnClickListener {
                close()
                if (!AppRepo.launch(context, a.pkg)) host.toast(context.getString(R.string.err_app_missing))
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
                        close()
                    } catch (_: Throwable) { }
                })
                Menu.show(v, items)
                true
            }
        }
    }
}
