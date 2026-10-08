package com.abdllh.aura.home

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
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
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.flp
import com.abdllh.aura.ui.iconView
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lp
import com.abdllh.aura.ui.pressScale
import com.abdllh.aura.util.dp

/** Full-screen app grid that slides up over the home screen. */
class AppDrawer(ctx: Context, private val host: HomeHost) : FrameLayout(ctx) {
    private val scrim = View(ctx).apply { setBackgroundColor(0xE6070A0D.toInt()); alpha = 0f }
    private val sheet = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val search: EditText
    private val grid = GridView(ctx)
    private val empty: AText = ctx.label(16f, Palette.text3, Fonts.REGULAR, gravity = Gravity.CENTER)
    private var all: List<AppInfo> = emptyList()
    private var shown: List<AppInfo> = emptyList()
    private val adapter = Adapter()
    var isOpen = false
        private set

    init {
        visibility = GONE
        isClickable = true
        addView(scrim, flp(MATCH, MATCH))
        scrim.setOnClickListener { close() }

        sheet.setPadding(28.dp, 18.dp, 28.dp, 10.dp)
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        search = EditText(ctx).apply {
            setHint(R.string.drawer_search)
            setHintTextColor(Palette.text3)
            setTextColor(Palette.text)
            textSize = 17f
            typeface = Fonts.get(Fonts.REGULAR)
            maxLines = 1
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setPadding(50.dp, 0, 20.dp, 0)
            background = Shapes.rect(Palette.card2, 26f, Palette.stroke)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) = applyFilter(s?.toString().orEmpty())
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        val searchBox = FrameLayout(ctx)
        searchBox.addView(search, flp(MATCH, MATCH))
        searchBox.addView(ctx.iconView(R.drawable.ic_search, 22, Palette.text3).apply { layoutParams = flp(22.dp, 22.dp, Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = 16.dp } })
        top.addView(searchBox, lp(0, 52.dp, 1f))
        val close = FrameLayout(ctx).apply {
            background = Shapes.pressable(Shapes.oval(Palette.card2, Palette.stroke), Shapes.oval(Palette.card3, Palette.stroke))
            isClickable = true
            pressScale(0.92f)
            setOnClickListener { close() }
            addView(ctx.iconView(R.drawable.ic_close, 22, Palette.text).apply { layoutParams = flp(22.dp, 22.dp, Gravity.CENTER) })
        }
        top.addView(close, lp(52.dp, 52.dp).apply { marginStart = 14.dp })
        sheet.addView(top, lp(MATCH, WRAP))

        grid.apply {
            numColumns = 8
            verticalSpacing = 8.dp
            horizontalSpacing = 4.dp
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = android.graphics.drawable.ColorDrawable(0)
            isVerticalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(0, 16.dp, 0, 8.dp)
            adapter = this@AppDrawer.adapter
        }
        sheet.addView(grid, lp(MATCH, 0, 1f))
        addView(sheet, flp(MATCH, MATCH))
        addView(empty, flp(MATCH, MATCH, Gravity.CENTER).also { it.topMargin = 60.dp })
        empty.setText(R.string.drawer_empty)
        empty.visibility = GONE
        sheet.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF0F1318.toInt(), 0xFF090B0F.toInt()))
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        grid.numColumns = (w / 126.dp).coerceIn(4, 12)
    }

    fun open() {
        if (isOpen) return
        isOpen = true
        all = AppRepo.load(context)
        search.setText("")
        applyFilter("")
        visibility = VISIBLE
        sheet.translationY = (height * 0.14f).coerceAtLeast(60f)
        sheet.alpha = 0f
        scrim.animate().alpha(1f).setDuration(220).start()
        sheet.animate().translationY(0f).alpha(1f).setDuration(280).setInterpolator(DecelerateInterpolator(1.6f)).setListener(null).start()
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        hideKeyboard()
        scrim.animate().alpha(0f).setDuration(180).start()
        sheet.animate().translationY(height * 0.10f).alpha(0f).setDuration(200).setInterpolator(DecelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) { if (!isOpen) visibility = GONE }
            }).start()
    }

    private fun hideKeyboard() {
        try { (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken, 0) } catch (_: Throwable) { }
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
        private val tile = FrameLayout(ctx)
        private val icon = ImageView(ctx)
        private val name: AText = ctx.label(12.5f, Palette.text2, Fonts.REGULAR, gravity = Gravity.CENTER)
        private var tag: String? = null

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(4.dp, 8.dp, 4.dp, 8.dp)
            tile.background = Shapes.rect(Palette.card2, 20f, Palette.stroke)
            icon.scaleType = ImageView.ScaleType.FIT_CENTER
            tile.addView(icon, flp(44.dp, 44.dp, Gravity.CENTER))
            addView(tile, lp(70.dp, 70.dp))
            addView(name, lp(MATCH, WRAP).apply { topMargin = 7.dp })
            background = Shapes.pressable(Shapes.rect(0x00000000, 20f), Shapes.rect(0x14FFFFFF, 20f))
            isClickable = true
            pressScale(0.94f)
        }

        fun bind(a: AppInfo) {
            tag = a.pkg
            name.text = a.label
            icon.setImageDrawable(null)
            IconLoader.get(context, a.pkg, 96) { bmp: Bitmap? -> if (tag == a.pkg && bmp != null) icon.setImageBitmap(bmp) }
            setOnClickListener {
                close()
                if (!AppRepo.launch(context, a.pkg)) host.toast(context.getString(R.string.err_app_missing))
            }
            setOnLongClickListener {
                try {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    close()
                } catch (_: Throwable) { }
                true
            }
        }
    }
}
