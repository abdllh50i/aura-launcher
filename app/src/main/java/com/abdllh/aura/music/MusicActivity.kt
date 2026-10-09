package com.abdllh.aura.music

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import com.abdllh.aura.R
import com.abdllh.aura.home.AppRepo
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.Theme
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.elevate
import com.abdllh.aura.ui.flp
import com.abdllh.aura.ui.iconView
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lp
import com.abdllh.aura.ui.pressScale
import com.abdllh.aura.ui.roundedClip
import com.abdllh.aura.util.LocaleHelper
import com.abdllh.aura.util.Prefs
import com.abdllh.aura.util.dp
import java.util.Locale

/**
 * Aura Music: now playing on the start side (cover, progress, controls) and the library on the other side
 * (songs, albums, artists, folders from the unit and USB drives), plus music from a phone over Bluetooth.
 */
class MusicActivity : Activity() {
    private enum class Tab { SONGS, ALBUMS, ARTISTS, FOLDERS }

    private lateinit var bg: View
    private lateinit var art: ImageView
    private lateinit var artIcon: ImageView
    private lateinit var title: AText
    private lateinit var artist: AText
    private lateinit var album: AText
    private lateinit var seek: SeekView
    private lateinit var tNow: AText
    private lateinit var tTotal: AText
    private lateinit var playIcon: ImageView
    private lateinit var shuffleBtn: ImageView
    private lateinit var repeatBtn: ImageView
    private lateinit var srcLocal: AText
    private lateinit var srcBt: AText
    private lateinit var tabsRow: LinearLayout
    private val tabViews = HashMap<Tab, AText>()
    private lateinit var listHolder: FrameLayout
    private lateinit var crumb: LinearLayout
    private lateinit var crumbText: AText
    private lateinit var empty: LinearLayout
    private lateinit var emptyText: AText
    private lateinit var emptyBtn: AText

    private var tab = Tab.SONGS
    private var group: Group? = null
    private var bt = false
    private var lastArtKey = Long.MIN_VALUE
    private var btCover: Bitmap? = null
    private val arabic get() = Locale.getDefault().language == "ar"

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fonts.refreshLocale(this)
        Theme.refresh()
        Theme.window(this, Palette.bg)
        Player.init(this)
        BtMusic.init(this)
        bt = Prefs.raw.getString("musicSource", "local") == "bt" || (BtMusic.playing && !Player.playing)
        setContentView(buildUi())
        if (!Library.hasPermission(this)) requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 3)
        else Library.load(this)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, grants: IntArray) {
        if (Library.hasPermission(this)) Library.load(this)
        showList()
    }

    override fun onStart() {
        super.onStart()
        Player.addListener(playerListener)
        Library.addListener(libraryListener)
        BtMusic.addListener(btListener)
        Player.restore()
        showNowPlaying()
        showList()
        seek.post(progressTick)
    }

    override fun onStop() {
        Player.removeListener(playerListener)
        Library.removeListener(libraryListener)
        BtMusic.removeListener(btListener)
        seek.removeCallbacks(progressTick)
        super.onStop()
    }

    @Deprecated("back leaves an album/artist first")
    override fun onBackPressed() {
        if (group != null) { group = null; showList() } else finish()
    }

    private val playerListener: () -> Unit = { if (!bt) showNowPlaying(); (listView?.adapter as? BaseAdapter)?.notifyDataSetChanged() }
    private val libraryListener: () -> Unit = { Player.restore(); showList(); showNowPlaying() }
    private val btListener: () -> Unit = { if (bt) showNowPlaying() }

    private val progressTick = object : Runnable {
        override fun run() {
            if (bt) {
                val d = BtMusic.durationSec * 1000L
                val p = BtMusic.position() * 1000L
                seek.progress = if (d > 0) p.toFloat() / d else 0f
                tNow.text = if (d > 0) time(p) else ""
                tTotal.text = if (d > 0) time(d) else ""
            } else if (!seek.dragging) {
                val d = Player.duration()
                val p = Player.position()
                seek.progress = if (d > 0) p.toFloat() / d else 0f
                tNow.text = time(p)
                tTotal.text = time(d)
            }
            seek.postDelayed(this, 500)
        }
    }

    // ------------------------------------------------------------------------------------------ now playing
    private fun showNowPlaying() {
        srcLocal.background = if (!bt) Shapes.rect(Palette.accent, 20f) else null
        srcLocal.setTextColor(if (!bt) Palette.onColor(Palette.accent) else Palette.text2)
        srcBt.background = if (bt) Shapes.rect(Palette.accent, 20f) else null
        srcBt.setTextColor(if (bt) Palette.onColor(Palette.accent) else Palette.text2)
        shuffleBtn.visibility = if (bt) View.INVISIBLE else View.VISIBLE
        repeatBtn.visibility = if (bt) View.INVISIBLE else View.VISIBLE
        seek.enabledSeek = !bt
        if (bt) {
            artIcon.setImageResource(R.drawable.ic_bluetooth)
            // once per cover (found online by title + artist): Bluetooth updates come every second
            val cover = BtMusic.art
            if (lastArtKey != BT_ART || cover !== btCover) { btCover = cover; setArt(cover, BT_ART) }
            when {
                !BtMusic.available || !BtMusic.connected -> {
                    title.setText(R.string.music_bt_not_connected)
                    artist.text = ""
                    album.text = ""
                }
                BtMusic.title.isBlank() -> {
                    title.setText(R.string.music_bt_idle)
                    artist.text = ""
                    album.text = getString(R.string.music_bt_connected, BtMusic.phone.ifBlank { "📱" })
                }
                else -> {
                    title.text = BtMusic.title
                    artist.text = BtMusic.artist
                    album.text = getString(R.string.music_bt_connected, BtMusic.phone.ifBlank { "📱" })
                }
            }
            playIcon.setImageResource(if (BtMusic.playing) R.drawable.ic_pause else R.drawable.ic_play)
            return
        }
        artIcon.setImageResource(R.drawable.ic_music)
        val t = Player.current
        if (t == null) {
            title.setText(R.string.media_nothing)
            artist.text = ""
            album.text = ""
            if (lastArtKey != NO_ART) setArt(null, NO_ART)
        } else {
            title.text = t.title
            artist.text = t.artist.ifBlank { getString(R.string.music_unknown_artist) }
            album.text = t.album
            if (lastArtKey != t.albumId) {
                lastArtKey = t.albumId
                ArtLoader.get(this, t, 600) { b -> if (Player.current?.albumId == t.albumId && !bt) setArt(b, t.albumId) }
            }
        }
        playIcon.setImageResource(if (Player.playing) R.drawable.ic_pause else R.drawable.ic_play)
        shuffleBtn.setColorFilter(if (Player.shuffle) Palette.accent else Palette.text2)
        repeatBtn.setColorFilter(if (Player.repeat != Player.Repeat.OFF) Palette.accent else Palette.text2)
        repeatBtn.alpha = if (Player.repeat == Player.Repeat.ONE) 1f else 0.9f
        repeatBtn.contentDescription = Player.repeat.name
    }

    private fun setArt(b: Bitmap?, key: Long) {
        lastArtKey = key
        art.setImageBitmap(b)
        artIcon.visibility = if (b == null) View.VISIBLE else View.GONE
        // tint the background with the cover's colour
        val tint = b?.let { averageColor(it) } ?: Palette.accent
        bg.background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(Palette.mix(Palette.bg, tint, if (Palette.dark) 0.30f else 0.22f), Palette.bg, Palette.bg))
    }

    private fun averageColor(b: Bitmap): Int {
        val s = Bitmap.createScaledBitmap(b, 8, 8, true)
        var r = 0L; var g = 0L; var bl = 0L
        for (x in 0 until 8) for (y in 0 until 8) {
            val p = s.getPixel(x, y)
            r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; bl += p and 0xFF
        }
        return (0xFF shl 24) or ((r / 64).toInt() shl 16) or ((g / 64).toInt() shl 8) or (bl / 64).toInt()
    }

    private fun selectSource(useBt: Boolean) {
        bt = useBt
        Prefs.raw.edit().putString("musicSource", if (useBt) "bt" else "local").apply()
        if (useBt) BtMusic.activate() else BtMusic.deactivate()
        lastArtKey = Long.MIN_VALUE
        showNowPlaying()
    }

    private fun transport(action: Int) {
        if (bt) {
            BtMusic.control(when (action) { 0 -> BtMusic.PREVIOUS; 1 -> BtMusic.TOGGLE; else -> BtMusic.NEXT })
            return
        }
        when (action) {
            0 -> Player.previous()
            1 -> if (Player.current == null && Library.tracks.isNotEmpty()) Player.play(Library.tracks, 0) else Player.toggle()
            else -> Player.next()
        }
    }

    private fun playFrom(list: List<Track>, i: Int) {
        if (bt) selectSource(false)
        Player.play(list, i)
    }

    // ------------------------------------------------------------------------------------------ library
    private var listView: ListView? = null

    private fun showList() {
        if (!::listHolder.isInitialized) return
        for ((t, v) in tabViews) {
            v.background = if (t == tab) Shapes.rect(Palette.card2, 18f) else null
            v.setTextColor(if (t == tab) Palette.text else Palette.text2)
        }
        crumb.visibility = if (group != null) View.VISIBLE else View.GONE
        crumbText.text = group?.title.orEmpty()
        listHolder.removeAllViews()
        listView = null
        if (!Library.hasPermission(this)) {
            showEmpty(getString(R.string.music_permission), getString(R.string.music_allow)) {
                requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 3)
            }
            return
        }
        if (Library.loaded && Library.tracks.isEmpty()) {
            showEmpty(getString(R.string.music_empty), getString(R.string.music_bluetooth)) { selectSource(true) }
            return
        }
        empty.visibility = View.GONE
        val g = group
        when {
            g != null -> listHolder.addView(trackList(g.tracks), MATCH, MATCH)
            tab == Tab.SONGS -> listHolder.addView(trackList(Library.tracks), MATCH, MATCH)
            tab == Tab.ALBUMS -> listHolder.addView(albumGrid(), MATCH, MATCH)
            tab == Tab.ARTISTS -> listHolder.addView(groupList(Library.artists, R.drawable.ic_user), MATCH, MATCH)
            else -> listHolder.addView(groupList(Library.folders, R.drawable.ic_folder), MATCH, MATCH)
        }
    }

    private fun showEmpty(text: String, button: String, onClick: () -> Unit) {
        empty.visibility = View.VISIBLE
        emptyText.text = text
        emptyBtn.text = button
        emptyBtn.setOnClickListener { onClick() }
    }

    private fun trackList(tracks: List<Track>): ListView = ListView(this).apply {
        divider = null
        selector = android.graphics.drawable.ColorDrawable(0)
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        clipToPadding = false
        setPadding(0, 4.dp, 0, 12.dp)
        adapter = object : BaseAdapter() {
            override fun getCount() = tracks.size
            override fun getItem(i: Int) = tracks[i]
            override fun getItemId(i: Int) = tracks[i].id
            override fun getView(i: Int, convert: View?, parent: ViewGroup): View {
                val row = (convert as? TrackRow) ?: TrackRow(this@MusicActivity)
                row.bind(tracks[i], tracks[i].id == Player.current?.id && !bt)
                row.setOnClickListener { playFrom(tracks, i) }
                return row
            }
        }
        listView = this
        Player.current?.let { cur -> val i = tracks.indexOfFirst { it.id == cur.id }; if (i > 3) setSelection(i - 2) }
    }

    private fun groupList(groups: List<Group>, icon: Int): ListView = ListView(this).apply {
        divider = null
        selector = android.graphics.drawable.ColorDrawable(0)
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        setPadding(0, 4.dp, 0, 12.dp)
        clipToPadding = false
        adapter = object : BaseAdapter() {
            override fun getCount() = groups.size
            override fun getItem(i: Int) = groups[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, convert: View?, parent: ViewGroup): View {
                val g = groups[i]
                val r = (convert as? GroupRow) ?: GroupRow(this@MusicActivity, icon)
                r.bind(g)
                r.setOnClickListener { group = g; showList() }
                return r
            }
        }
    }

    private fun albumGrid(): GridView = GridView(this).apply {
        numColumns = 3
        verticalSpacing = 14.dp
        horizontalSpacing = 14.dp
        stretchMode = GridView.STRETCH_COLUMN_WIDTH
        selector = android.graphics.drawable.ColorDrawable(0)
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        setPadding(6.dp, 8.dp, 6.dp, 14.dp)
        clipToPadding = false
        val groups = Library.albums
        adapter = object : BaseAdapter() {
            override fun getCount() = groups.size
            override fun getItem(i: Int) = groups[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, convert: View?, parent: ViewGroup): View {
                val g = groups[i]
                val cell = (convert as? AlbumCell) ?: AlbumCell(this@MusicActivity)
                cell.bind(g, ((parent.width - 2 * 14.dp - 12.dp) / 3).coerceAtLeast(80.dp))
                cell.setOnClickListener { group = g; showList() }
                return cell
            }
        }
    }

    /** Artist / folder row (recycled by the list). */
    private inner class GroupRow(ctx: Context, icon: Int) : LinearLayout(ctx) {
        private val title = ctx.label(18f, Palette.text, Fonts.MEDIUM)
        private val sub = ctx.label(14.5f, Palette.text2, Fonts.REGULAR)

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 74.dp
            setPaddingRelative(12.dp, 8.dp, 16.dp, 8.dp)
            background = Shapes.ghost(18f)
            isClickable = true
            layoutParams = android.widget.AbsListView.LayoutParams(MATCH, WRAP)
            val badge = FrameLayout(ctx).apply { background = Shapes.oval(Palette.card2) }
            badge.addView(ctx.iconView(icon, 24, Palette.text2).apply { layoutParams = flp(24.dp, 24.dp, Gravity.CENTER) })
            addView(badge, lp(54.dp, 54.dp))
            val col = LinearLayout(ctx).apply { orientation = VERTICAL }
            col.addView(title, lp(MATCH, WRAP))
            col.addView(sub, lp(MATCH, WRAP).apply { topMargin = 2.dp })
            addView(col, lp(0, WRAP, 1f).apply { marginStart = 16.dp })
        }

        fun bind(g: Group) {
            title.text = g.title
            sub.text = getString(R.string.music_tracks_count, g.tracks.size)
        }
    }

    /** Album cover + name in the grid (recycled; a late cover for a recycled cell is ignored). */
    private inner class AlbumCell(ctx: Context) : LinearLayout(ctx) {
        private val box = FrameLayout(ctx).apply { background = Shapes.rect(Palette.card2, 16f); roundedClip(16f) }
        private val img = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        private val title = ctx.label(15.5f, Palette.text, Fonts.MEDIUM)
        private val sub = ctx.label(13.5f, Palette.text2, Fonts.REGULAR)
        private var key = Long.MIN_VALUE

        init {
            orientation = VERTICAL
            isClickable = true
            pressScale(0.95f)
            box.addView(ctx.iconView(R.drawable.ic_disc, 40, Palette.text3).apply { layoutParams = flp(40.dp, 40.dp, Gravity.CENTER) })
            box.addView(img, flp(MATCH, MATCH))
            addView(box, lp(MATCH, 80.dp))
            addView(title, lp(MATCH, WRAP).apply { topMargin = 8.dp })
            addView(sub, lp(MATCH, WRAP))
        }

        fun bind(g: Group, side: Int) {
            val blp = box.layoutParams
            if (blp.height != side) { blp.height = side; box.layoutParams = blp }
            title.text = g.title
            sub.text = g.subtitle
            val t = g.tracks.first()
            key = t.albumId
            img.setImageDrawable(null)
            ArtLoader.get(context, t, 300) { b -> if (key == t.albumId && b != null) img.setImageBitmap(b) }
        }
    }

    private inner class TrackRow(ctx: Context) : LinearLayout(ctx) {
        private val img = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        private val ic = ctx.iconView(R.drawable.ic_music, 22, Palette.text3)
        private val t1 = ctx.label(17.5f, Palette.text, Fonts.MEDIUM)
        private val t2 = ctx.label(14f, Palette.text2, Fonts.REGULAR)
        private val dur = ctx.label(14f, Palette.text3, Fonts.MEDIUM)
        private var key = 0L

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 74.dp
            setPaddingRelative(12.dp, 8.dp, 16.dp, 8.dp)
            isClickable = true
            layoutParams = android.widget.AbsListView.LayoutParams(MATCH, WRAP)
            val box = FrameLayout(ctx).apply { background = Shapes.rect(Palette.card2, 12f); roundedClip(12f) }
            box.addView(ic, flp(22.dp, 22.dp, Gravity.CENTER))
            box.addView(img, flp(MATCH, MATCH))
            addView(box, lp(54.dp, 54.dp))
            val col = LinearLayout(ctx).apply { orientation = VERTICAL }
            col.addView(t1, lp(MATCH, WRAP))
            col.addView(t2, lp(MATCH, WRAP).apply { topMargin = 2.dp })
            addView(col, lp(0, WRAP, 1f).apply { marginStart = 16.dp; marginEnd = 10.dp })
            addView(dur, lp(WRAP, WRAP))
        }

        fun bind(t: Track, now: Boolean) {
            t1.text = t.title
            t1.setTextColor(if (now) Palette.accent else Palette.text)
            t2.text = listOf(t.artist, t.album).filter { it.isNotBlank() }.joinToString("  ·  ")
            dur.text = time(t.durationMs)
            background = if (now) Shapes.rect(Palette.accentSoft(), 18f) else Shapes.ghost(18f)
            key = t.albumId
            img.setImageDrawable(null)
            ArtLoader.get(context, t, 120) { b -> if (key == t.albumId && b != null) img.setImageBitmap(b) }
        }
    }

    // ------------------------------------------------------------------------------------------ UI
    private fun buildUi(): View {
        val root = FrameLayout(this)
        bg = View(this)
        root.addView(bg, MATCH, MATCH)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(18.dp, 14.dp, 18.dp, 16.dp) }

        // ---- now playing
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(FrameLayout(this).apply {
            background = Shapes.ghostOval()
            isClickable = true
            pressScale(0.9f)
            setOnClickListener { finish() }
            addView(iconView(R.drawable.ic_chevron_left, 28, Palette.text).apply {
                layoutParams = flp(28.dp, 28.dp, Gravity.CENTER)
                if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) scaleX = -1f
            })
        }, lp(58.dp, 58.dp))
        val seg = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; background = Shapes.rect(Palette.card2, 24f); setPadding(4.dp, 4.dp, 4.dp, 4.dp) }
        srcLocal = label(16f, Palette.text2, Fonts.MEDIUM, gravity = Gravity.CENTER).apply {
            setText(R.string.music_library); setPadding(18.dp, 0, 18.dp, 0); isClickable = true; setOnClickListener { selectSource(false) }
        }
        srcBt = label(16f, Palette.text2, Fonts.MEDIUM, gravity = Gravity.CENTER).apply {
            setText(R.string.music_bluetooth); setPadding(18.dp, 0, 18.dp, 0); isClickable = true; setOnClickListener { selectSource(true) }
        }
        seg.addView(srcLocal, lp(WRAP, 46.dp))
        seg.addView(srcBt, lp(WRAP, 46.dp))
        top.addView(View(this), lp(0, 1, 1f))
        top.addView(seg, lp(WRAP, WRAP))
        left.addView(top, lp(MATCH, WRAP))

        val artBox = FrameLayout(this).apply { background = Shapes.rect(Palette.card2, 24f); roundedClip(24f); elevate(14f) }
        artIcon = iconView(R.drawable.ic_music, 72, Palette.text3)
        artBox.addView(artIcon, flp(72.dp, 72.dp, Gravity.CENTER))
        art = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        artBox.addView(art, flp(MATCH, MATCH))
        val artSlot = object : FrameLayout(this) {
            override fun onMeasure(w: Int, h: Int) {
                // the cover is the largest square that fits the space left between the header and the title
                val s = minOf(MeasureSpec.getSize(w), MeasureSpec.getSize(h))
                (artBox.layoutParams as LayoutParams).apply { width = s; height = s }
                super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(w), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(h), MeasureSpec.EXACTLY))
            }
        }
        artSlot.addView(artBox, flp(1, 1, Gravity.CENTER))
        left.addView(artSlot, lp(MATCH, 0, 1f).apply { topMargin = 12.dp; bottomMargin = 14.dp })

        title = label(26f, Palette.text, Fonts.MEDIUM, gravity = Gravity.CENTER)
        artist = label(18f, Palette.text2, Fonts.REGULAR, gravity = Gravity.CENTER)
        album = label(15f, Palette.text3, Fonts.REGULAR, gravity = Gravity.CENTER)
        left.addView(title, lp(MATCH, WRAP))
        left.addView(artist, lp(MATCH, WRAP).apply { topMargin = 4.dp })
        left.addView(album, lp(MATCH, WRAP).apply { topMargin = 2.dp })

        seek = SeekView(this).apply { onSeek = { f -> Player.seekTo((Player.duration() * f).toLong()) } }
        left.addView(seek, lp(MATCH, 34.dp).apply { topMargin = 12.dp })
        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        tNow = label(14f, Palette.text2, Fonts.MEDIUM)
        tTotal = label(14f, Palette.text2, Fonts.MEDIUM)
        times.addView(tNow, lp(0, WRAP, 1f))
        times.addView(tTotal, lp(WRAP, WRAP))
        left.addView(times, lp(MATCH, WRAP).apply { setMargins(6.dp, 0, 6.dp, 0) })

        val ctl = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        shuffleBtn = ImageView(this)
        repeatBtn = ImageView(this)
        ctl.addView(ctlButton(R.drawable.ic_shuffle, 26, shuffleBtn) { Player.setShuffle(!Player.shuffle) }, lp(60.dp, 60.dp))
        ctl.addView(ctlButton(R.drawable.ic_skip_back, 32, null) { transport(0) }, lp(76.dp, 76.dp).apply { marginStart = 14.dp })
        playIcon = ImageView(this)
        val play = FrameLayout(this).apply {
            background = Shapes.pressable(Shapes.oval(Palette.text), Shapes.oval(Palette.text2))
            isClickable = true
            pressScale(0.92f)
            setOnClickListener { transport(1) }
            addView(playIcon.apply { setColorFilter(Palette.bg); scaleType = ImageView.ScaleType.FIT_CENTER }, flp(34.dp, 34.dp, Gravity.CENTER))
        }
        ctl.addView(play, lp(92.dp, 92.dp).apply { marginStart = 16.dp; marginEnd = 16.dp })
        ctl.addView(ctlButton(R.drawable.ic_skip_fwd, 32, null) { transport(2) }, lp(76.dp, 76.dp).apply { marginEnd = 14.dp })
        ctl.addView(ctlButton(R.drawable.ic_repeat, 26, repeatBtn) { Player.cycleRepeat() }, lp(60.dp, 60.dp))
        left.addView(ctl, lp(MATCH, WRAP).apply { topMargin = 8.dp })
        row.addView(left, lp(0, MATCH, 0.46f).apply { marginEnd = 18.dp })

        // ---- library
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Shapes.card(26f)
            setPadding(14.dp, 14.dp, 14.dp, 0)
        }
        tabsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; background = Shapes.rect(Palette.card3, 22f); setPadding(4.dp, 4.dp, 4.dp, 4.dp) }
        for ((t, res) in listOf(Tab.SONGS to R.string.music_songs, Tab.ALBUMS to R.string.music_albums, Tab.ARTISTS to R.string.music_artists, Tab.FOLDERS to R.string.music_folders)) {
            val v = label(16f, Palette.text2, Fonts.MEDIUM, gravity = Gravity.CENTER).apply {
                setText(res); isClickable = true; setOnClickListener { tab = t; group = null; showList() }
            }
            tabViews[t] = v
            tabsRow.addView(v, lp(0, 48.dp, 1f))
        }
        panel.addView(tabsRow, lp(MATCH, WRAP))
        crumb = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            isClickable = true
            setOnClickListener { group = null; showList() }
            setPaddingRelative(6.dp, 10.dp, 6.dp, 4.dp)
        }
        crumb.addView(iconView(R.drawable.ic_chevron_left, 24, Palette.accent).apply {
            if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) scaleX = -1f
        }, lp(24.dp, 24.dp))
        crumbText = label(18f, Palette.text, Fonts.MEDIUM)
        crumb.addView(crumbText, lp(0, WRAP, 1f).apply { marginStart = 8.dp })
        crumb.addView(label(15f, Palette.accent, Fonts.MEDIUM).apply {
            setText(R.string.music_play_all)
            setPadding(14.dp, 8.dp, 14.dp, 8.dp)
            isClickable = true
            setOnClickListener { group?.let { playFrom(it.tracks, 0) } }
        }, lp(WRAP, WRAP))
        panel.addView(crumb, lp(MATCH, WRAP))
        val body = FrameLayout(this)
        listHolder = FrameLayout(this)
        body.addView(listHolder, flp(MATCH, MATCH))
        empty = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; visibility = View.GONE }
        empty.addView(iconView(R.drawable.ic_usb, 48, Palette.text3), lp(48.dp, 48.dp))
        emptyText = label(17f, Palette.text2, Fonts.REGULAR, lines = 3, gravity = Gravity.CENTER)
        empty.addView(emptyText, lp(MATCH, WRAP).apply { topMargin = 14.dp; setMargins(30.dp, 14.dp, 30.dp, 0) })
        emptyBtn = label(17f, Palette.onColor(Palette.accent), Fonts.MEDIUM, gravity = Gravity.CENTER).apply {
            background = Shapes.accent(18f); setPadding(26.dp, 0, 26.dp, 0); isClickable = true; pressScale(0.95f)
        }
        empty.addView(emptyBtn, lp(WRAP, 58.dp).apply { topMargin = 18.dp })
        body.addView(empty, flp(MATCH, MATCH))
        panel.addView(body, lp(MATCH, 0, 1f).apply { topMargin = 8.dp })
        row.addView(panel, lp(0, MATCH, 0.54f))
        root.addView(row, MATCH, MATCH)
        setArt(null, -3)
        return root
    }

    private fun ctlButton(icon: Int, size: Int, img: ImageView?, onClick: () -> Unit): FrameLayout = FrameLayout(this).apply {
        background = Shapes.ghostOval()
        isClickable = true
        pressScale(0.88f)
        setOnClickListener { onClick() }
        val v = img ?: ImageView(this@MusicActivity)
        v.setImageResource(icon)
        if (img == null) v.setColorFilter(Palette.text)
        v.scaleType = ImageView.ScaleType.FIT_CENTER
        addView(v, flp(size.dp, size.dp, Gravity.CENTER))
    }

    private fun time(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
        else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }

    companion object {
        // art keys for "no cover" states (album ids are >= 0)
        private const val BT_ART = Long.MIN_VALUE + 1
        private const val NO_ART = -2L

        fun open(ctx: Context) {
            try { ctx.startActivity(Intent(ctx, MusicActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) { }
        }

        /** Bluetooth settings live in the stock phone app on this firmware (pairing, connect). */
        fun openBluetooth(ctx: Context) {
            if (!AppRepo.launch(ctx, "com.nwd.android.phone")) {
                try { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) { }
            }
        }
    }
}

/** Thin progress bar with a knob; drag to seek. Always left-to-right, like the time labels. */
class SeekView(ctx: Context) : View(ctx) {
    var progress = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var onSeek: ((Float) -> Unit)? = null
    var enabledSeek = true
    var dragging = false
        private set
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    init { layoutDirection = LAYOUT_DIRECTION_LTR }

    override fun onDraw(c: Canvas) {
        val h = 6f.dp
        val y = height / 2f
        val pad = 12f.dp
        val w = width - 2 * pad
        r.set(pad, y - h / 2, pad + w, y + h / 2)
        p.color = Palette.card3
        c.drawRoundRect(r, h / 2, h / 2, p)
        r.set(pad, y - h / 2, pad + w * progress, y + h / 2)
        p.color = Palette.accent
        c.drawRoundRect(r, h / 2, h / 2, p)
        if (enabledSeek) {
            p.color = 0x33000000
            c.drawCircle(pad + w * progress, y + 1f.dp, (if (dragging) 13f else 10f).dp, p)
            p.color = 0xFFFFFFFF.toInt()
            c.drawCircle(pad + w * progress, y, (if (dragging) 12f else 9f).dp, p)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!enabledSeek) return false
        val pad = 12f.dp
        val f = ((e.x - pad) / (width - 2 * pad)).coerceIn(0f, 1f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dragging = true; parent?.requestDisallowInterceptTouchEvent(true); progress = f }
            MotionEvent.ACTION_MOVE -> progress = f
            MotionEvent.ACTION_UP -> { dragging = false; progress = f; onSeek?.invoke(f) }
            MotionEvent.ACTION_CANCEL -> { dragging = false; invalidate() }
        }
        return true
    }
}
