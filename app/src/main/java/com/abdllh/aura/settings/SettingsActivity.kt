package com.abdllh.aura.settings

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.abdllh.aura.R
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.BackdropView
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.Theme
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.flp
import com.abdllh.aura.ui.iconView
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lp
import com.abdllh.aura.ui.pressScale
import com.abdllh.aura.update.UpdateManager
import com.abdllh.aura.util.LocaleHelper
import com.abdllh.aura.util.dp

/** Settings: category list on the start side, selected page on the other. */
class SettingsActivity : Activity() {
    companion object {
        const val EXTRA_PAGE = "page"
        const val PAGE_GENERAL = 0
        const val PAGE_UPDATE = 1
        const val PAGE_DISPLAY = 2
        const val PAGE_NAVIGATION = 3
        const val PAGE_VEHICLE = 4
        const val PAGE_ABOUT = 5
    }

    private lateinit var pages: List<Page>
    private var current = -1
    private lateinit var navItems: List<LinearLayout>
    private lateinit var content: FrameLayout
    private lateinit var title: AText
    private var updateDot: View? = null

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
            intent.getStringExtra("api_base")?.let { com.abdllh.aura.util.Prefs.apiBase = it }
            intent.getStringExtra("repo")?.let { com.abdllh.aura.util.Prefs.updateRepo = it }
            // screenshots:  --es theme dark|light|auto  --es lang ar|en|system  (then press Home)
            intent.getStringExtra("theme")?.let { com.abdllh.aura.util.Prefs.theme = it; Theme.refresh(); Theme.window(this) }
            intent.getStringExtra("lang")?.let { com.abdllh.aura.util.Prefs.lang = it }
        }
        pages = listOf(GeneralPage(this), UpdatePage(this), DisplaySoundPage(this), NavigationPage(this), VehiclePage(this), AboutPage(this))
        setContentView(buildFrame())
        show(intent.getIntExtra(EXTRA_PAGE, PAGE_GENERAL).coerceIn(0, pages.size - 1), false)
    }

    private fun buildFrame(): View {
        val root = FrameLayout(this)
        root.addView(BackdropView(this), MATCH, MATCH)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        // ---- sidebar
        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(16.dp, 14.dp, 12.dp, 14.dp)
        }
        val back = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8.dp, 0, 8.dp, 0)
            background = Shapes.ghost(16f)
            isClickable = true
            pressScale(0.97f)
            setOnClickListener { finish() }
            addView(iconView(R.drawable.ic_chevron_left, 30, Palette.text).also {
                if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) it.scaleX = -1f
            }, lp(30.dp, 30.dp))
            addView(label(24f, Palette.text, Fonts.MEDIUM).apply { setText(R.string.set_title) }, lp(WRAP, WRAP).apply { marginStart = 8.dp })
        }
        side.addView(back, lp(MATCH, 60.dp))
        side.addView(View(this), lp(MATCH, 10.dp))

        val items = ArrayList<LinearLayout>()
        for ((i, p) in pages.withIndex()) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPaddingRelative(16.dp, 0, 14.dp, 0)
                isClickable = true
                pressScale(0.98f)
                setOnClickListener { show(i, true) }
            }
            item.addView(iconView(p.iconRes, 27, Palette.text2), lp(27.dp, 27.dp))
            item.addView(label(18f, Palette.text2, Fonts.MEDIUM).apply { setText(p.titleRes) }, lp(0, WRAP, 1f).apply { marginStart = 16.dp })
            if (p is UpdatePage) {
                updateDot = View(this).apply { background = Shapes.oval(Palette.accent); visibility = if (UpdateManager.hasUpdateBadge()) View.VISIBLE else View.GONE }
                item.addView(updateDot, lp(10.dp, 10.dp))
            }
            items.add(item)
            side.addView(item, lp(MATCH, 64.dp).apply { topMargin = 4.dp })
        }
        navItems = items
        row.addView(side, lp(280.dp, MATCH))

        // ---- content
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(8.dp, 18.dp, 24.dp, 0)
        }
        title = label(30f, Palette.text, Fonts.LIGHT)
        right.addView(title, lp(MATCH, WRAP).apply { bottomMargin = 12.dp; marginStart = 6.dp })
        content = FrameLayout(this)
        right.addView(content, lp(MATCH, 0, 1f))
        row.addView(right, lp(0, MATCH, 1f))
        root.addView(row, MATCH, MATCH)
        return root
    }

    fun show(index: Int, animate: Boolean) {
        if (index == current && animate) return
        if (current >= 0) pages[current].onHide()
        current = index
        val page = pages[index]
        for ((i, item) in navItems.withIndex()) {
            val sel = i == index
            item.background = if (sel) Shapes.rect(Palette.accentSoft(), 18f) else Shapes.ghost(18f)
            (item.getChildAt(0) as android.widget.ImageView).setColorFilter(if (sel) Palette.accent else Palette.text2)
            (item.getChildAt(1) as AText).setTextColor(if (sel) Palette.text else Palette.text2)
        }
        title.setText(page.titleRes)
        val view = scrolled(page.build(this))
        content.removeAllViews()
        content.addView(view, MATCH, MATCH)
        if (animate) {
            view.alpha = 0f
            view.translationX = (if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) -20 else 20).dp.toFloat()
            view.animate().alpha(1f).translationX(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
        }
        page.onShow()
    }

    /** Rebuild the current page (and sidebar colours) after a preference changed. */
    fun rebuild() {
        val i = current
        current = -1
        window.decorView.post { recreateKeepingPage(i) }
    }

    private fun recreateKeepingPage(i: Int) {
        intent.putExtra(EXTRA_PAGE, i)
        recreate()
    }

    private val dotObserver: (UpdateManager.State) -> Unit = {
        updateDot?.visibility = if (UpdateManager.hasUpdateBadge()) View.VISIBLE else View.GONE
    }

    override fun onResume() {
        super.onResume()
        UpdateManager.observe(dotObserver) // also refreshes the dot right away
    }

    override fun onPause() {
        UpdateManager.unobserve(dotObserver)
        super.onPause()
    }

    override fun onDestroy() {
        // Every page lets go of its listeners (rebuild() resets `current` before the activity is recreated, so a
        // page that was showing would otherwise stay registered with UpdateManager and keep this dead activity alive).
        if (::pages.isInitialized) for (p in pages) p.onHide()
        super.onDestroy()
    }
}
