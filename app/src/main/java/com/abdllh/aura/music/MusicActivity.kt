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
import com.abdllh.aura.media.MediaMonitor
import com.abdllh.aura.home.AppRepo
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.LayoutMove
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.PlayPauseView
import com.abdllh.aura.ui.Segmented
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.Theme
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.elevate
import com.abdllh.aura.ui.flp
import com.abdllh.aura.ui.iconView
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lp
import com.abdllh.aura.ui.pressScale
import com.abdllh.aura.ui.riseIn
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
    private lateinit var playIcon: PlayPauseView
    private lateinit var shuffleBtn: ImageView
    private lateinit var repeatBtn: ImageView
    private lateinit var source: Segmented
    private lateinit var tabs: Segmented
    private lateinit var listHolder: FrameLayout
    private lateinit var crumb: LinearLayout
    private lateinit var crumbText: AText
    private lateinit var empty: LinearLayout
    private lateinit var emptyText: AText
    private lateinit var emptyBtn: AText

    private lateinit var bgArt: ImageView
    private lateinit var artBox: FrameLayout
    private lateinit var playBtn: FrameLayout
    private lateinit var libraryBox: LinearLayout
    private lateinit var btBox: LinearLayout
    // YouTube: a search of its catalogue; what is chosen is played by YouTube's own app (YtMusic)
    private lateinit var ytBox: LinearLayout
    private lateinit var ytInput: android.widget.EditText
    private lateinit var ytList: ListView
    private lateinit var ytNote: LinearLayout
    private lateinit var ytNoteText: AText
    private lateinit var ytNoteBtn: AText
    private var ytSongs: List<YtMusic.Song> = emptyList()
    private var ytAsked = 0
    private var ytCover: Bitmap? = null
    private lateinit var btState: AText
    private lateinit var btBadge: FrameLayout
    private lateinit var btBadgeIcon: ImageView
    // the layout: in Bluetooth mode the song takes the whole screen (cover beside the title and the controls)
    private lateinit var left: LinearLayout
    private lateinit var panel: FrameLayout
    private lateinit var stage: LinearLayout
    private lateinit var artSlot: FrameLayout
    private lateinit var info: LinearLayout
    private lateinit var btSettings: AText
    // the lyrics take the library's place (and, with Bluetooth, the song makes room for them)
    private lateinit var lyricsView: LyricsView
    private lateinit var lyricsBtn: FrameLayout
    private lateinit var lyricsIcon: ImageView
    /** The owner's switch (the button): lyrics wanted. */
    private var lyricsWanted = false
    /** Their place is open: wanted, and the song has some (or the owner asked on this very song, see [lyricsPinned]). */
    private var lyricsOn = false
    private var lyricsKey: String? = null
    /** The button was just pressed on: the song then in front keeps their place open even when it has none ("" = no song). */
    private var lyricsAsked = false
    private var lyricsPinned: String? = null
    // ...or the whole screen, in large type, over a small bar with the song and its buttons
    private lateinit var root: FrameLayout
    private lateinit var content: LinearLayout
    private lateinit var expandBtn: FrameLayout
    private lateinit var fullLayer: FrameLayout
    private lateinit var lyricsBig: LyricsView
    private lateinit var fullClose: FrameLayout
    private lateinit var fullBar: FrameLayout
    private lateinit var fullArtBox: FrameLayout
    private lateinit var fullArt: ImageView
    private lateinit var fullTitle: AText
    private lateinit var fullArtist: AText
    private lateinit var fullPlay: FrameLayout
    private lateinit var fullPlayIcon: PlayPauseView
    private lateinit var fullLine: ThinProgress
    private lateinit var flyer: ImageView
    private var flight: android.animation.ValueAnimator? = null
    private var lyricsFull = false

    private var tab = Tab.SONGS
    private var group: Group? = null
    /** Where the music comes from: the unit's own files, the phone over Bluetooth, or YouTube's app on the unit. */
    private enum class Src(val pref: String) { LOCAL("local"), BT("bt"), YT("yt") }
    private var src = Src.LOCAL
    private val bt get() = src == Src.BT
    private val yt get() = src == Src.YT
    private var lastArtKey = Long.MIN_VALUE
    private var btCover: Bitmap? = null
    /** The song shown (its title comes in with a little movement when it changes). */
    private var shownSong: String? = null
    /** The cover shown, and whether one was set yet (the first comes without the fade). */
    private var shownCover: Bitmap? = null
    private var coverShown = false
    /** The colour of the current cover (progress, play button, the playing row); the theme accent without one. */
    private var coverAccent = Palette.accent
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
        MediaMonitor.start(this) // (other apps' sessions: the YouTube source reads one)
        src = when {
            Prefs.raw.getString("musicSource", "local") == "bt" || (BtMusic.playing && !Player.playing) -> Src.BT
            Prefs.raw.getString("musicSource", "local") == "yt" -> Src.YT
            else -> Src.LOCAL
        }
        lyricsWanted = Prefs.raw.getBoolean("musicLyrics", false)
        lyricsOn = lyricsWanted // (until the song in front turns out to have none)
        lyricsFull = lyricsOn && Prefs.raw.getBoolean("musicLyricsFull", false)
        setContentView(buildUi())
        if (lyricsFull) showFull(true, animate = false)
        if (!Library.hasPermission(this)) requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 3)
        else Library.load(this)
        // not again when the task comes back from Recents after the process died (the old intent is delivered again)
        if (savedInstanceState == null && (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    /**
     * Opened in place of a stock music screen ([openInstead]): its source, playing as the stock screen would. Back then
     * returns to Aura's screen below (this activity joins the home screen's task), not to the stock screen.
     */
    private fun handle(i: Intent?) {
        val src = i?.getStringExtra(EXTRA_SOURCE) ?: return
        i.removeExtra(EXTRA_SOURCE)
        if (src == "bt") {
            if (bt) BtMusic.activate() else selectSource(Src.BT) // selectSource activates it
        } else {
            // the music button: the unit's own music — YouTube's app when that is what was listened to last
            if (bt) selectSource(if (Prefs.raw.getString("musicSourceOwn", "local") == "yt") Src.YT else Src.LOCAL)
            if (yt) MediaMonitor.appControl(YtMusic.players, MediaMonitor.Do.PLAY)
            else if (Player.current != null && !Player.playing) Player.resume()
        }
    }

    private fun close() = finish()

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, grants: IntArray) {
        if (Library.hasPermission(this)) Library.load(this)
        showList()
    }

    override fun onStart() {
        super.onStart()
        Player.addListener(playerListener)
        Library.addListener(libraryListener)
        BtMusic.addListener(btListener)
        MediaMonitor.observe(appListener)
        Player.restore()
        showNowPlaying()
        showList()
        seek.post(progressTick)
    }

    override fun onStop() {
        Player.removeListener(playerListener)
        Library.removeListener(libraryListener)
        BtMusic.removeListener(btListener)
        MediaMonitor.unobserve(appListener)
        seek.removeCallbacks(progressTick)
        if (::lyricsView.isInitialized) lyricsView.removeCallbacks(lyricsRetry)
        lyricsKey = null // looked up again when the screen comes back
        super.onStop()
    }

    @Deprecated("back leaves the large lyrics, then an album/artist, first")
    override fun onBackPressed() {
        when {
            lyricsFull -> showFull(false)
            group != null -> { group = null; showList(fade = true) }
            else -> close()
        }
    }

    override fun onResume() {
        super.onResume()
        com.abdllh.aura.system.Animations.ensureAnimators()
    }

    private val playerListener: () -> Unit = { if (src == Src.LOCAL) showNowPlaying(); (listView?.adapter as? BaseAdapter)?.notifyDataSetChanged() }
    private val libraryListener: () -> Unit = { Player.restore(); showList(); showNowPlaying() }
    private val btListener: () -> Unit = { if (bt) showNowPlaying() }
    private val appListener: (com.abdllh.aura.media.NowPlaying?) -> Unit = { if (yt) showNowPlaying() }

    /** Where the song of the source in front is (ms), and whether that is not really known (a Bluetooth song's). */
    private fun positionNow(): Long = when (src) {
        Src.BT -> BtMusic.positionMs()
        Src.YT -> MediaMonitor.appPosition(YtMusic.players) ?: 0L
        Src.LOCAL -> Player.position()
    }

    private val positionUnknown get() = bt && !BtMusic.positionKnown

    private val progressTick = object : Runnable {
        override fun run() {
            if (lyricsOn) {
                val unknown = positionUnknown
                val at = positionNow()
                lyrics { it.needSync = unknown; it.at(at) }
            }
            if (yt) {
                // YouTube's app tells its song's length and where it is (the real position)
                val s = MediaMonitor.app(YtMusic.players)
                val d = s?.durationMs ?: 0L
                val p = MediaMonitor.appPosition(YtMusic.players)
                seek.visibility = if (s != null) View.VISIBLE else View.INVISIBLE
                if (!seek.dragging) {
                    seek.progress = if (d > 0 && p != null) (p.toFloat() / d).coerceIn(0f, 1f) else 0f
                    tNow.text = if (s == null) "" else if (p != null) time(p.coerceAtMost(if (d > 0) d else p)) else UNKNOWN_TIME
                    tTotal.text = if (s == null) "" else if (d > 0) time(d) else UNKNOWN_TIME
                }
                fullLine.progress = if (d > 0 && p != null) (p.toFloat() / d).coerceIn(0f, 1f) else -1f
            } else if (bt) {
                // the phone does not say where a song is (BtMusic keeps a clock): unknown parts are left as dashes
                val song = BtMusic.connected && BtMusic.title.isNotBlank()
                val d = BtMusic.durationSec * 1000L
                val known = BtMusic.positionKnown
                val p = BtMusic.positionMs()
                seek.progress = if (d > 0 && known) p.toFloat() / d else 0f
                seek.visibility = if (song) View.VISIBLE else View.INVISIBLE // (no song: no empty bar either)
                tNow.text = if (!song) "" else if (known) time(p) else UNKNOWN_TIME
                tTotal.text = if (!song) "" else if (d > 0) time(d) else UNKNOWN_TIME
                fullLine.progress = if (song && d > 0 && known) p.toFloat() / d else -1f
            } else if (!seek.dragging) {
                seek.visibility = View.VISIBLE
                val d = Player.duration()
                val p = Player.position()
                seek.progress = if (d > 0) p.toFloat() / d else 0f
                tNow.text = time(p)
                tTotal.text = time(d)
                fullLine.progress = if (d > 0) p.toFloat() / d else -1f
            }
            seek.postDelayed(this, if (lyricsOn) 200L else 500L) // the lyrics follow the song closely
        }
    }

    // ------------------------------------------------------------------------------------------ lyrics
    /** Both sizes of the lyrics (beside the song, and alone on the screen) are told the same things. */
    private inline fun lyrics(f: (LyricsView) -> Unit) { f(lyricsView); f(lyricsBig) }

    private fun toggleLyrics() {
        val on = !lyricsWanted
        relayout(panelAfter = !(bt && !on)) {
            lyricsWanted = on
            lyricsOn = on
            lyricsAsked = on
            Prefs.raw.edit().putBoolean("musicLyrics", on).apply()
            lyricsKey = null
            showList()
            showNowPlaying()
        }
    }

    /**
     * The song in front has lyrics to show, or not: their place opens or closes with it, and the button stays on. A
     * song without any gives the room back (to the cover, to the library) instead of a line saying so; the next one
     * that has some brings them back the size they were. Not for the song the owner pressed the button on: there the
     * line that says why nothing shows is the answer.
     */
    private fun lyricsHave(some: Boolean) {
        if (!lyricsWanted) return
        val show = some || lyricsPinned == (lyricsKey ?: "")
        if (show == lyricsOn) return
        val moving = root.isLaidOut && root.isShown
        when {
            show && Prefs.raw.getBoolean("musicLyricsFull", false) -> {
                lyricsOn = true
                if (moving) {
                    showFull(true, remember = false)
                    // the song's side takes its new layout once it is out of sight
                    root.postDelayed({ if (lyricsFull && lyricsOn) showList() }, 240L)
                } else {
                    showList()
                    showFull(true, animate = false, remember = false)
                }
            }
            show -> relayout(panelAfter = true) { lyricsOn = true; showList() }
            lyricsFull -> {
                // the large lyrics are left first; large stays the owner's choice for the next song that has some
                lyricsOn = false
                showList() // (the song's side is out of sight: it comes back in its new layout)
                if (moving) afterLayout { if (lyricsFull && !lyricsOn) showFull(false, remember = false) }
                else showFull(false, animate = false, remember = false)
            }
            else -> relayout(panelAfter = !bt) { lyricsOn = false; showList() }
        }
    }

    private var relaying = false

    /** Runs [then] once the layout asked for is done, just before it is drawn. */
    private fun afterLayout(then: () -> Unit) {
        val o = root.viewTreeObserver
        o.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                (if (o.isAlive) o else root.viewTreeObserver).removeOnPreDrawListener(this)
                then()
                return true
            }
        })
        root.requestLayout()
    }

    /**
     * A change of layout (the lyrics on or off, another source): the cover, the song's names and the buttons travel to
     * their new places, what leaves fades out where it stood and what comes in rises into its place.
     * [panelAfter]: the panel beside the song is still there afterwards (then only what is in it changes).
     */
    private fun relayout(panelAfter: Boolean, change: () -> Unit) {
        // (a change inside a change, such as the lyrics closing as the source changes: one movement, the outer one's)
        if (relaying || !::root.isInitialized || !root.isLaidOut || !root.isShown || lyricsFull) { change(); return }
        val hadPanel = panel.visibility == View.VISIBLE
        val inside = listOf(libraryBox, btBox, ytBox, lyricsView)
        val was = inside.firstOrNull { hadPanel && it.visibility == View.VISIBLE }
        val move = LayoutMove(root, listOf(artBox, info, lyricsBtn, source), setOf(artBox),
            leaving = if (hadPanel && !panelAfter) listOf(panel) else listOfNotNull(was))
        relaying = true
        try { change() } finally { relaying = false }
        val hasPanel = panel.visibility == View.VISIBLE
        val now = inside.firstOrNull { hasPanel && it.visibility == View.VISIBLE }
        // (the same thing in the same panel: nothing left, nothing came)
        if (was != null && was === now) move.keepLeaving() else if (now != null) now.riseIn(12f, 40L, Motion.MEDIUM)
        if (hasPanel && !hadPanel) {
            // the panel itself comes in from its side
            val rtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
            panel.animate().cancel()
            panel.alpha = 0f
            panel.translationX = (if (rtl) -36 else 36).dp.toFloat()
            panel.animate().alpha(1f).translationX(0f).setStartDelay(60L).setDuration(Motion.LONG).setInterpolator(Motion.EMPHASIZED).start()
        }
        move.play()
    }

    /**
     * The lyrics alone on the whole screen ([on]) or back beside the song: the screen below steps back and fades, the
     * large lines come up, and the cover travels between its place and the small bar's.
     */
    private fun showFull(on: Boolean, animate: Boolean = true, remember: Boolean = true) {
        val moving = animate && root.isLaidOut && root.isShown
        lyricsFull = on
        // ([remember]: the owner's own press, not a song without lyrics passing by)
        if (remember) Prefs.raw.edit().putBoolean("musicLyricsFull", on).apply()
        flight?.cancel()
        for (v in listOf(content, fullBar, fullClose, lyricsBig)) v.animate().cancel()
        if (!moving) {
            content.visibility = if (on) View.INVISIBLE else View.VISIBLE
            content.alpha = 1f; content.scaleX = 1f; content.scaleY = 1f
            // (out of sight, never gone: it stays laid out, so nothing is measured when it comes in)
            fullLayer.visibility = if (on) View.VISIBLE else View.INVISIBLE
            for (v in listOf(fullBar, fullClose, lyricsBig)) { v.alpha = 1f; v.translationY = 0f }
            fullArtBox.alpha = 1f
            flyer.visibility = View.GONE
            if (on) lyricsBig.post { lyricsBig.settle() }
            return
        }
        if (on) {
            fullLayer.visibility = View.VISIBLE
            lyricsBig.settle()
            fly(toBar = true)
            content.animate().alpha(0f).scaleX(0.97f).scaleY(0.97f).setStartDelay(0L).setDuration(200L).setInterpolator(Motion.EXIT)
                .withEndAction { if (lyricsFull) content.visibility = View.INVISIBLE }.start()
            lyricsBig.alpha = 0f
            lyricsBig.translationY = 20.dp.toFloat()
            lyricsBig.animate().alpha(1f).translationY(0f).setStartDelay(70L).setDuration(Motion.LONG).setInterpolator(Motion.EMPHASIZED).withEndAction(null).start()
            fullBar.alpha = 0f
            fullBar.translationY = 14.dp.toFloat()
            fullBar.animate().alpha(1f).translationY(0f).setStartDelay(40L).setDuration(Motion.MEDIUM).setInterpolator(Motion.EMPHASIZED).start()
            fullClose.alpha = 0f
            fullClose.animate().alpha(1f).setStartDelay(120L).setDuration(Motion.SHORT).start()
        } else {
            // leaving is quicker than arriving
            content.visibility = View.VISIBLE
            content.alpha = 0f
            content.scaleX = 1f; content.scaleY = 1f
            lyricsView.settle()
            fly(toBar = false)
            content.animate().alpha(1f).setStartDelay(40L).setDuration(220L).setInterpolator(Motion.EXIT).withEndAction(null).start()
            lyricsBig.animate().alpha(0f).translationY(12.dp.toFloat()).setStartDelay(0L).setDuration(140L).setInterpolator(Motion.EXIT)
                .withEndAction { if (!lyricsFull) fullLayer.visibility = View.INVISIBLE }.start()
            fullBar.animate().alpha(0f).translationY(10.dp.toFloat()).setStartDelay(0L).setDuration(140L).setInterpolator(Motion.EXIT).start()
            fullClose.animate().alpha(0f).setStartDelay(0L).setDuration(100L).start()
        }
    }

    /**
     * The cover on its way between the song's cover and the bar's small one: a copy that travels above both (the two
     * real ones stay where the layout put them), its corners keeping their roundness as it changes size.
     */
    private fun fly(toBar: Boolean) {
        val cover = shownCover
        val big = artBox
        val small = fullArtBox
        if (cover == null || big.width == 0 || small.width == 0 || !big.isLaidOut || !small.isLaidOut) {
            // (no cover to carry, or nothing laid out yet: the two layouts just fade)
            fullArtBox.alpha = 1f
            big.alpha = 1f
            flyer.visibility = View.GONE
            return
        }
        val o = IntArray(2); root.getLocationInWindow(o)
        val a = IntArray(2); big.getLocationInWindow(a)
        val b = IntArray(2); small.getLocationInWindow(b)
        // (the bar is on its way in or out: where it rests, not where it is this frame)
        val bx = b[0] - o[0] - fullBar.translationX
        val by = b[1] - o[1] - fullBar.translationY
        val ax = (a[0] - o[0]).toFloat()
        val ay = (a[1] - o[1]).toFloat()
        val side = big.width
        val to = small.width.toFloat() / side
        flyer.layoutParams = flyer.layoutParams.apply { width = side; height = side }
        flyer.setImageBitmap(cover)
        flyer.pivotX = 0f; flyer.pivotY = 0f
        flyer.visibility = View.VISIBLE
        // (the copy stands in for both real ones until it lands)
        fullArtBox.alpha = 0f
        big.alpha = 0f
        var radius = 0f
        flyer.clipToOutline = true
        flyer.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, out: android.graphics.Outline) { out.setRoundRect(0, 0, v.width, v.height, radius) }
        }
        fun place(t: Float) { // 0 = the song's cover, 1 = the bar's
            val s = 1f + (to - 1f) * t
            flyer.scaleX = s; flyer.scaleY = s
            flyer.translationX = ax + (bx - ax) * t
            flyer.translationY = ay + (by - ay) * t
            radius = (COVER_RADIUS + (BAR_COVER_RADIUS - COVER_RADIUS) * t).dp / s // (as seen: the copy is scaled)
            flyer.invalidateOutline()
        }
        place(if (toBar) 0f else 1f)
        flight = android.animation.ValueAnimator.ofFloat(if (toBar) 0f else 1f, if (toBar) 1f else 0f).apply {
            duration = if (toBar) Motion.LONG else Motion.MEDIUM
            interpolator = Motion.STANDARD
            addUpdateListener { place(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    flyer.visibility = View.GONE
                    fullArtBox.alpha = 1f
                    big.alpha = 1f
                }
            })
            start()
        }
    }

    private var lyricsRetryKey: String? = null
    private val lyricsRetry = Runnable { if (lyricsKey == lyricsRetryKey) { lyricsKey = null; refreshLyrics() } }

    /** The lyrics of what plays now, looked up once per song while the owner wants them (see [lyricsHave]). */
    private fun refreshLyrics() {
        if (!lyricsWanted || !::lyricsView.isInitialized) return
        class Song(val key: String, val title: String, val artist: String, val sec: Int)
        val s = if (yt) {
            // (YouTube's app tells the song's own length: the right recording's lyrics)
            MediaMonitor.app(YtMusic.players)
                ?.let { Song("yt:${it.title}|${it.artist}", it.title, it.artist, (it.durationMs / 1000).toInt()) }
        } else if (bt) {
            if (!BtMusic.connected || BtMusic.title.isBlank()) null
            // (once per song: the phone's own length, when it told one, helps to pick the right recording)
            else Song("bt:${BtMusic.title}|${BtMusic.artist}", BtMusic.title, BtMusic.artist, BtMusic.phoneDurationSec)
        } else Player.current?.let { Song("lib:${it.id}", it.title, it.artist, (it.durationMs / 1000).toInt()) }
        if (s?.key == lyricsKey && lyricsKey != null) return
        lyricsKey = s?.key
        val pin = s?.key ?: ""
        if (lyricsAsked) { lyricsAsked = false; lyricsPinned = pin } else if (pin != lyricsPinned) lyricsPinned = null
        if (s == null) { lyrics { it.showStatus(getString(R.string.lyrics_nothing)) }; lyricsHave(false); return }
        // (while they are looked up their place stays as it is: open with this line, or closed)
        lyrics { it.showStatus(getString(R.string.lyrics_loading)) }
        Lyrics.get(this, s.title, s.artist, s.sec) { r, failed ->
            if (lyricsKey != s.key || isDestroyed) return@get
            // the length of the recording the lines were timed on: the Bluetooth song's, while the phone tells none
            // (timed lines only: an untimed record's length says no more than a catalogue's)
            if (bt && r != null && r.synced && r.durationSec > 0) BtMusic.offerDuration(r.durationSec, s.title, s.artist, BtMusic.FROM_LYRICS)
            when {
                r == null && failed -> {
                    lyrics { it.showStatus(getString(R.string.lyrics_failed)) }
                    // asked again in a while (the internet, or the server, may be back): one retry at a time
                    lyricsView.removeCallbacks(lyricsRetry)
                    lyricsRetryKey = s.key
                    lyricsView.postDelayed(lyricsRetry, 20_000L)
                    lyricsHave(false)
                }
                r == null -> { lyrics { it.showStatus(getString(R.string.lyrics_none)) }; lyricsHave(false) }
                r.instrumental -> { lyrics { it.showStatus(getString(R.string.lyrics_instrumental)) }; lyricsHave(false) }
                else -> {
                    val unknown = positionUnknown
                    val at = positionNow()
                    lyrics { it.show(r); it.needSync = unknown; it.at(at) }
                    lyricsHave(true)
                }
            }
        }
    }

    /**
     * A tapped line: the unit's own song jumps there; a Bluetooth song cannot be moved from here, so the line tells
     * where it is (the owner taps what is being sung: a moment after the line started).
     */
    private fun onLineTap(l: Lyrics.Line) {
        if (yt) {
            // (YouTube's app can be moved, like the unit's own player)
            MediaMonitor.appControl(YtMusic.players, MediaMonitor.Do.PLAY, seekToMs = l.ms)
            lyrics { it.at(l.ms) }
        } else if (bt) {
            BtMusic.syncTo(l.ms + TAP_REACTION_MS)
            val at = BtMusic.positionMs()
            lyrics { it.needSync = false; it.at(at) }
            (if (lyricsFull) lyricsBig else lyricsView).flashSynced()
        } else if (Player.current != null) {
            Player.seekTo(l.ms)
            if (!Player.playing) Player.toggle()
            lyrics { it.at(l.ms) }
        }
    }

    // ------------------------------------------------------------------------------------------ now playing
    private fun showNowPlaying() {
        paintAccents()
        shuffleBtn.visibility = if (src != Src.LOCAL) View.INVISIBLE else View.VISIBLE
        repeatBtn.visibility = if (src != Src.LOCAL) View.INVISIBLE else View.VISIBLE
        seek.enabledSeek = !bt
        btSettings.visibility = if (bt && (!BtMusic.available || !BtMusic.connected)) View.VISIBLE else View.GONE
        if (yt) {
            // what YouTube's app plays, as its own session tells it
            val s = MediaMonitor.app(YtMusic.players)
            artIcon.setImageResource(R.drawable.ic_music)
            val cover = s?.art
            if (lastArtKey != YT_ART || cover !== ytCover) { ytCover = cover; setArt(cover, YT_ART) }
            if (s == null) {
                title.setText(R.string.yt_idle)
                artist.text = ""
                album.text = ""
            } else {
                title.text = s.title
                artist.text = s.artist
                album.text = s.album
            }
            songChanged("yt:${s?.title}|${s?.artist}")
            playIcon.setPlaying(s?.playing == true)
            syncBar(s?.playing == true)
            refreshLyrics()
            return
        }
        if (bt) {
            artIcon.setImageResource(R.drawable.ic_bluetooth)
            // once per cover (found online by title + artist): Bluetooth updates come every second
            val cover = BtMusic.art
            if (lastArtKey != BT_ART || cover !== btCover) { btCover = cover; setArt(cover, BT_ART) }
            val phone = if (BtMusic.phone.isBlank()) getString(R.string.music_bt_phone_connected)
                else getString(R.string.music_bt_connected, BtMusic.phone)
            btState.text = if (!BtMusic.available || !BtMusic.connected) getString(R.string.music_bt_not_connected) else phone
            when {
                !BtMusic.available || !BtMusic.connected -> {
                    title.setText(R.string.music_bt_not_connected)
                    artist.text = ""
                    album.text = ""
                }
                BtMusic.title.isBlank() -> {
                    title.setText(R.string.music_bt_idle)
                    artist.text = ""
                    album.text = phone
                }
                else -> {
                    title.text = BtMusic.title
                    artist.text = BtMusic.artist
                    album.text = phone
                }
            }
            songChanged("bt:${BtMusic.title}|${BtMusic.artist}")
            playIcon.setPlaying(BtMusic.playing)
            syncBar(BtMusic.playing)
            refreshLyrics()
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
                ArtLoader.get(this, t, 600) { b -> if (Player.current?.albumId == t.albumId && src == Src.LOCAL) setArt(b, t.albumId) }
            }
        }
        songChanged("lib:${t?.id}")
        playIcon.setPlaying(Player.playing)
        syncBar(Player.playing)
        paintAccents()
        repeatBtn.alpha = if (Player.repeat == Player.Repeat.ONE) 1f else 0.9f
        repeatBtn.contentDescription = Player.repeat.name
        refreshLyrics()
    }

    /** The small bar under the large lyrics says what the song's side says. */
    private fun syncBar(playing: Boolean) {
        fullTitle.text = title.text
        fullArtist.text = artist.text
        fullArtist.visibility = if (artist.text.isNullOrBlank()) View.GONE else View.VISIBLE
        fullPlayIcon.setPlaying(playing)
    }

    /** A new song: its title and names come in with a little movement (not on every refresh of the same song). */
    private fun songChanged(key: String) {
        val first = shownSong == null
        if (key == shownSong) return
        shownSong = key
        if (first) return
        // (only what is on the screen: the other set of names is out of sight)
        if (lyricsFull) {
            fullTitle.riseIn(8f, 0L, Motion.MEDIUM)
            fullArtist.riseIn(8f, 50L, Motion.MEDIUM)
            return
        }
        title.riseIn(10f, 0L, Motion.MEDIUM)
        artist.riseIn(10f, 50L, Motion.MEDIUM)
        album.riseIn(10f, 90L, Motion.MEDIUM)
    }

    private fun setArt(b: Bitmap?, key: Long) {
        // a new picture on a screen that already showed one (the keys are set before a cover loads: not those)
        val changed = b !== shownCover && coverShown
        shownCover = b
        coverShown = true
        lastArtKey = key
        // the new cover fades in over the old one, and the box breathes in a little
        val shown = art.drawable
        // (the cover a fade ended on, not the fade itself: no chain of old covers kept alive)
        val old = (shown as? android.graphics.drawable.TransitionDrawable)?.getDrawable(1) ?: shown
        val next = b?.let { android.graphics.drawable.BitmapDrawable(resources, it) }
        if (changed && old != null && next != null) {
            val fade = android.graphics.drawable.TransitionDrawable(arrayOf(old, next)).apply {
                isCrossFadeEnabled = true
                startTransition(Motion.MEDIUM.toInt())
            }
            art.setImageDrawable(fade)
            // then the cover alone (the fade's layers share one size: a cover that is not square would be stretched)
            art.postDelayed({ if (art.drawable === fade) art.setImageDrawable(next) }, Motion.MEDIUM + 40)
        } else art.setImageDrawable(next)
        if (changed) { artBox.scaleX = 0.95f; artBox.scaleY = 0.95f; artBox.animate().scaleX(1f).scaleY(1f).setDuration(Motion.LONG).setInterpolator(Motion.EMPHASIZED).start() }
        artIcon.visibility = if (b == null) View.VISIBLE else View.GONE
        coverAccent = b?.let { vivid(dominantColor(it)) } ?: Palette.accent
        // the cover's colour in its shadow: it glows on the background
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            artBox.outlineSpotShadowColor = if (b != null) coverAccent else Palette.shadow
            artBox.outlineAmbientShadowColor = if (b != null) coverAccent else Palette.shadow
            playBtn.outlineSpotShadowColor = coverAccent
            playBtn.outlineAmbientShadowColor = coverAccent
        }
        // The cover, blurred, fills the screen behind everything: a tiny copy scaled up with filtering is blur enough
        // (and costs nothing on this GPU).
        if (b != null) {
            val small = Bitmap.createScaledBitmap(b, 18, 18, true)
            bgArt.setImageDrawable(android.graphics.drawable.BitmapDrawable(resources, small).apply { isFilterBitmap = true })
            bgArt.alpha = 0f
            bgArt.animate().alpha(if (Palette.dark) 0.9f else 0.55f).setDuration(600).setInterpolator(Motion.STANDARD).start()
        } else {
            bgArt.animate().alpha(0f).setDuration(300).start()
        }
        bg.background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(Palette.mix(Palette.bg, coverAccent, if (Palette.dark) 0.30f else 0.22f), Palette.bg, Palette.bg))
        seek.color = coverAccent
        playBtn.background = Shapes.pressable(Shapes.oval(coverAccent), Shapes.oval(Palette.mix(coverAccent, 0xFF000000.toInt(), 0.2f)))
        playIcon.color = Palette.onColor(coverAccent)
        // the same cover and colour in the small bar under the large lyrics
        fullArt.setImageBitmap(b)
        if (changed && lyricsFull) { fullArt.alpha = 0f; fullArt.animate().alpha(1f).setDuration(Motion.MEDIUM).start() }
        fullPlay.background = Shapes.pressable(Shapes.oval(coverAccent), Shapes.oval(Palette.mix(coverAccent, 0xFF000000.toInt(), 0.2f)))
        fullPlayIcon.color = Palette.onColor(coverAccent)
        fullLine.color = coverAccent
        (listView?.adapter as? BaseAdapter)?.notifyDataSetChanged()
        paintTabs()
        paintAccents()
    }

    /** A cover's average colour made lively enough for buttons; grey covers keep the theme accent. */
    private fun vivid(c: Int): Int {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(c, hsv)
        if (hsv[1] < 0.12f) return Palette.accent
        hsv[1] = hsv[1].coerceAtLeast(0.55f)
        hsv[2] = hsv[2].coerceIn(0.62f, 0.92f)
        return android.graphics.Color.HSVToColor(hsv)
    }

    /** Everything that takes the cover's colour: the source switch, shuffle and repeat. */
    private fun paintAccents() {
        source.indicator = coverAccent
        if (source.selected != src.ordinal) source.select(src.ordinal)
        shuffleBtn.setColorFilter(if (Player.shuffle) coverAccent else Palette.text2)
        repeatBtn.setColorFilter(if (Player.repeat != Player.Repeat.OFF) coverAccent else Palette.text2)
        btBadge.background = Shapes.oval(Palette.withAlpha(coverAccent, 0.18f))
        btBadgeIcon.setColorFilter(coverAccent)
        if (::lyricsBtn.isInitialized) {
            lyricsBtn.background = if (lyricsWanted) Shapes.oval(coverAccent) else Shapes.ghostOval()
            lyricsIcon.setColorFilter(if (lyricsWanted) Palette.onColor(coverAccent) else Palette.text)
        }
    }

    private fun paintTabs() {
        tabs.indicator = Palette.withAlpha(coverAccent, if (Palette.dark) 0.32f else 0.24f)
        tabs.onIndicator = Palette.text
        if (tabs.selected != tab.ordinal) tabs.select(tab.ordinal)
    }

    /**
     * The cover's most striking colour: pixels grouped by hue, each weighted by how saturated and bright it is; the
     * strongest group wins (an average of a blue-and-orange cover would be a muddy grey-pink).
     */
    private fun dominantColor(b: Bitmap): Int {
        val s = Bitmap.createScaledBitmap(b, 12, 12, true)
        val weight = FloatArray(12)
        val sumR = FloatArray(12); val sumG = FloatArray(12); val sumB = FloatArray(12)
        val hsv = FloatArray(3)
        for (x in 0 until 12) for (y in 0 until 12) {
            val p = s.getPixel(x, y)
            android.graphics.Color.colorToHSV(p, hsv)
            val w = hsv[1] * hsv[2] * hsv[2]
            if (w < 0.02f) continue
            val bin = ((hsv[0] / 30f).toInt()).coerceIn(0, 11)
            weight[bin] += w
            sumR[bin] += ((p shr 16) and 0xFF) * w; sumG[bin] += ((p shr 8) and 0xFF) * w; sumB[bin] += (p and 0xFF) * w
        }
        val best = weight.indices.maxByOrNull { weight[it] } ?: return averageColor(b)
        val w = weight[best]
        if (w <= 0f) return averageColor(b)
        return (0xFF shl 24) or ((sumR[best] / w).toInt() shl 16) or ((sumG[best] / w).toInt() shl 8) or (sumB[best] / w).toInt()
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

    private fun selectSource(to: Src) {
        if (to == src) return
        relayout(panelAfter = !(to == Src.BT && !lyricsOn)) {
            val was = src
            src = to
            val e = Prefs.raw.edit().putString("musicSource", to.pref)
            if (to != Src.BT) e.putString("musicSourceOwn", to.pref) // (what the music key comes back to)
            e.apply()
            // the speakers go with the source: the phone's music unmuted or muted, the unit's own player and
            // YouTube's app paused when they are left
            if (to == Src.BT) BtMusic.activate() else BtMusic.deactivate()
            if (to == Src.YT) Player.pauseForOtherSource()
            if (was == Src.YT) MediaMonitor.appControl(YtMusic.players, MediaMonitor.Do.PAUSE)
            lastArtKey = Long.MIN_VALUE
            showNowPlaying()
            showList()
        }
    }

    private fun transport(action: Int) {
        if (yt) {
            MediaMonitor.appControl(YtMusic.players, when (action) { 0 -> MediaMonitor.Do.PREVIOUS; 1 -> MediaMonitor.Do.TOGGLE; else -> MediaMonitor.Do.NEXT })
            return
        }
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
        if (src != Src.LOCAL) selectSource(Src.LOCAL)
        Player.play(list, i)
    }

    // ------------------------------------------------------------------------------------------ YouTube
    private fun buildYt(): View {
        ytBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(14.dp, 14.dp, 14.dp, 0); visibility = View.GONE }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Shapes.rect(Palette.withAlpha(Palette.card3, 0.75f), 28f)
            setPaddingRelative(18.dp, 0, 6.dp, 0)
        }
        bar.addView(iconView(R.drawable.ic_search, 24, Palette.text2), lp(24.dp, 24.dp))
        ytInput = android.widget.EditText(this).apply {
            setHint(R.string.yt_search_hint)
            setHintTextColor(Palette.text3)
            setTextColor(Palette.text)
            textSize = 18f
            typeface = Fonts.get(Fonts.REGULAR)
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            background = null
            setPadding(4.dp, 0, 4.dp, 0)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) = ytTyped(s?.toString().orEmpty())
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
            setOnEditorActionListener { _, action, ev ->
                if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH || ev?.keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
                    removeCallbacks(ytSearchLater)
                    if (text.trim().length >= 2) ytSearch(text.toString())
                    ytHideKeyboard() // (it covers most of a car screen: the results show)
                    true
                } else false
            }
        }
        bar.addView(ytInput, lp(0, MATCH, 1f).apply { marginStart = 10.dp })
        bar.addView(FrameLayout(this).apply {
            background = Shapes.ghostOval()
            isClickable = true
            pressScale(0.9f)
            setOnClickListener { ytInput.setText(""); ytHideKeyboard() }
            addView(iconView(R.drawable.ic_close, 22, Palette.text2).apply { layoutParams = flp(22.dp, 22.dp, Gravity.CENTER) })
        }, lp(46.dp, 46.dp))
        ytBox.addView(bar, lp(MATCH, 56.dp))

        val body = FrameLayout(this)
        ytList = ListView(this).apply {
            divider = null
            selector = android.graphics.drawable.ColorDrawable(0)
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(0, 4.dp, 0, 12.dp)
            adapter = object : BaseAdapter() {
                override fun getCount() = ytSongs.size
                override fun getItem(i: Int) = ytSongs[i]
                override fun getItemId(i: Int) = i.toLong()
                override fun getView(i: Int, convert: View?, parent: ViewGroup): View {
                    val row = (convert as? YtRow) ?: YtRow(this@MusicActivity)
                    val s = ytSongs[i]
                    row.bind(s)
                    row.setOnClickListener { ytPlay(s) }
                    return row
                }
            }
        }
        body.addView(ytList, flp(MATCH, MATCH))
        ytNote = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(30.dp, 0, 30.dp, 20.dp) }
        ytNote.addView(iconView(R.drawable.ic_music_list, 44, Palette.text3), lp(44.dp, 44.dp))
        ytNoteText = label(16.5f, Palette.text2, Fonts.REGULAR, lines = 5, gravity = Gravity.CENTER)
        ytNote.addView(ytNoteText, lp(MATCH, WRAP).apply { topMargin = 14.dp })
        ytNoteBtn = label(17f, Palette.onColor(Palette.accent), Fonts.MEDIUM, gravity = Gravity.CENTER).apply {
            background = Shapes.accent(18f); setPadding(26.dp, 0, 26.dp, 0); isClickable = true; pressScale(0.95f)
            setText(R.string.yt_get_app)
            setOnClickListener { YtMusic.openStore(this@MusicActivity) }
        }
        ytNote.addView(ytNoteBtn, lp(WRAP, 58.dp).apply { topMargin = 18.dp })
        body.addView(ytNote, flp(MATCH, MATCH))
        ytBox.addView(body, lp(MATCH, 0, 1f).apply { topMargin = 8.dp })
        ytShow()
        return ytBox
    }

    private val ytSearchLater = Runnable { ytSearch(ytInput.text.toString()) }

    /** Typed: searched once the typing rests (the catalogue is asked over the phone's internet). */
    private fun ytTyped(q: String) {
        ytInput.removeCallbacks(ytSearchLater)
        if (q.trim().length < 2) {
            ytAsked++ // (an answer still on its way is for a query that is gone)
            ytSongs = emptyList()
            ytShow()
        } else ytInput.postDelayed(ytSearchLater, 700L)
    }

    private fun ytSearch(q: String) {
        val asked = ++ytAsked
        if (ytSongs.isEmpty()) ytShow(getString(R.string.yt_searching))
        YtMusic.search(q) { songs ->
            if (asked != ytAsked || isDestroyed) return@search
            ytSongs = songs.orEmpty()
            ytShow(if (songs == null) getString(R.string.yt_failed) else null)
            if (songs != null) ytList.setSelection(0)
        }
    }

    /** The results, or the line that stands in their place ([note], or what the state calls for). */
    private fun ytShow(note: String? = null) {
        (ytList.adapter as BaseAdapter).notifyDataSetChanged()
        val noApp = YtMusic.player(this) == null
        val text = note ?: when {
            ytSongs.isNotEmpty() -> null
            ytInput.text.trim().length >= 2 -> getString(R.string.yt_none)
            noApp -> getString(R.string.yt_no_app)
            else -> getString(R.string.yt_hint)
        }
        ytNote.visibility = if (text != null) View.VISIBLE else View.GONE
        ytList.visibility = if (text != null) View.INVISIBLE else View.VISIBLE
        ytNoteText.text = text.orEmpty()
        ytNoteBtn.visibility = if (noApp && note == null && ytSongs.isEmpty() && ytInput.text.trim().length < 2) View.VISIBLE else View.GONE
    }

    /** A song chosen: YouTube's own app takes it and plays; this screen comes back as soon as it does. */
    private fun ytPlay(s: YtMusic.Song) {
        ytHideKeyboard()
        val c = applicationContext
        val ok = YtMusic.play(this, s) {
            try { c.startActivity(Intent(c, MusicActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) { }
        }
        if (!ok) {
            android.widget.Toast.makeText(this, R.string.yt_no_app, android.widget.Toast.LENGTH_LONG).show()
            YtMusic.openStore(this)
        }
    }

    private fun ytHideKeyboard() {
        try {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(ytInput.windowToken, 0)
            ytInput.clearFocus()
        } catch (_: Throwable) {
        }
    }

    /** A found song (recycled by the list; a late picture for a recycled row is ignored). */
    private inner class YtRow(ctx: Context) : LinearLayout(ctx) {
        private val img = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        private val t1 = ctx.label(18f, Palette.text, Fonts.MEDIUM)
        private val t2 = ctx.label(14.5f, Palette.text2, Fonts.REGULAR)
        private val dur = ctx.label(14f, Palette.text3, Fonts.MEDIUM)
        private var shown = ""

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 76.dp
            setPaddingRelative(10.dp, 8.dp, 18.dp, 8.dp)
            isClickable = true
            background = Shapes.ghost(18f)
            layoutParams = android.widget.AbsListView.LayoutParams(MATCH, WRAP)
            val box = FrameLayout(ctx).apply { background = Shapes.rect(Palette.card2, 14f); roundedClip(14f) }
            box.addView(ctx.iconView(R.drawable.ic_music, 22, Palette.text3).apply { layoutParams = flp(22.dp, 22.dp, Gravity.CENTER) })
            box.addView(img, flp(MATCH, MATCH))
            addView(box, lp(58.dp, 58.dp))
            val col = LinearLayout(ctx).apply { orientation = VERTICAL }
            col.addView(t1, lp(MATCH, WRAP))
            col.addView(t2, lp(MATCH, WRAP).apply { topMargin = 3.dp })
            addView(col, lp(0, WRAP, 1f).apply { marginStart = 16.dp; marginEnd = 10.dp })
            addView(dur, lp(WRAP, WRAP))
        }

        fun bind(s: YtMusic.Song) {
            t1.text = s.title
            t2.text = listOf(s.artist, s.album).filter { it.isNotBlank() }.joinToString("  ·  ")
            dur.text = if (s.sec > 0) time(s.sec * 1000L) else ""
            shown = s.thumb
            img.setImageDrawable(null)
            YtMusic.thumb(s.thumb) { b -> if (shown == s.thumb && b != null) img.setImageBitmap(b) }
        }
    }

    // ------------------------------------------------------------------------------------------ library
    private var listView: ListView? = null

    /**
     * Bluetooth mode has no library to show: the song takes the whole screen, the cover beside its title and controls.
     * The unit's own music keeps the cover above them, beside the library.
     */
    private fun applyLayout() {
        if (!::stage.isInitialized) return
        val wide = bt && !lyricsOn // (the lyrics need the panel)
        panel.visibility = if (wide) View.GONE else View.VISIBLE
        (left.layoutParams as LinearLayout.LayoutParams).apply {
            weight = if (wide) 1f else 0.46f
            marginEnd = if (wide) 0 else 18.dp
        }
        stage.orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        stage.gravity = if (wide) Gravity.CENTER_VERTICAL else Gravity.NO_GRAVITY
        artSlot.layoutParams = if (wide) LinearLayout.LayoutParams(0, MATCH, 0.44f).apply { topMargin = 18.dp; bottomMargin = 18.dp }
            else LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = 12.dp; bottomMargin = 14.dp }
        info.layoutParams = if (wide) LinearLayout.LayoutParams(0, WRAP, 0.56f).apply { marginStart = 40.dp; marginEnd = 16.dp }
            else LinearLayout.LayoutParams(MATCH, WRAP)
        title.textSize = if (wide) 34f else 28f
        title.maxLines = if (wide) 2 else 1
        artist.textSize = if (wide) 22f else 19f
        album.textSize = if (wide) 17f else 15f
        left.requestLayout()
    }

    /** [fade]: another tab or group, the new list comes in with a short fade. */
    private fun showList(fade: Boolean = false) {
        if (!::listHolder.isInitialized) return
        if (fade) {
            listHolder.alpha = 0.2f
            listHolder.translationY = 8.dp.toFloat()
            listHolder.animate().alpha(1f).translationY(0f).setDuration(Motion.MEDIUM).setInterpolator(Motion.EMPHASIZED).start()
        }
        applyLayout()
        // Bluetooth: the library makes way for the song (see applyLayout); the lyrics, when shown, take the panel
        libraryBox.visibility = if (src != Src.LOCAL || lyricsOn) View.GONE else View.VISIBLE
        ytBox.visibility = if (yt && !lyricsOn) View.VISIBLE else View.GONE
        btBox.visibility = if (bt && !lyricsOn) View.VISIBLE else View.GONE
        lyricsView.visibility = if (lyricsOn) View.VISIBLE else View.GONE
        expandBtn.visibility = if (lyricsOn) View.VISIBLE else View.GONE
        paintTabs()
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
            showEmpty(getString(R.string.music_empty), getString(R.string.music_bluetooth)) { selectSource(Src.BT) }
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
                row.bind(tracks[i], tracks[i].id == Player.current?.id && src == Src.LOCAL)
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
                r.setOnClickListener { group = g; showList(fade = true) }
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
                cell.setOnClickListener { group = g; showList(fade = true) }
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
        private val t1 = ctx.label(18f, Palette.text, Fonts.MEDIUM)
        private val t2 = ctx.label(14.5f, Palette.text2, Fonts.REGULAR)
        private val dur = ctx.label(14f, Palette.text3, Fonts.MEDIUM)
        private val eq = EqualizerView(ctx)
        private var key = 0L

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 76.dp
            setPaddingRelative(10.dp, 8.dp, 18.dp, 8.dp)
            isClickable = true
            layoutParams = android.widget.AbsListView.LayoutParams(MATCH, WRAP)
            val box = FrameLayout(ctx).apply { background = Shapes.rect(Palette.card2, 14f); roundedClip(14f) }
            box.addView(ic, flp(22.dp, 22.dp, Gravity.CENTER))
            box.addView(img, flp(MATCH, MATCH))
            addView(box, lp(58.dp, 58.dp))
            val col = LinearLayout(ctx).apply { orientation = VERTICAL }
            col.addView(t1, lp(MATCH, WRAP))
            col.addView(t2, lp(MATCH, WRAP).apply { topMargin = 3.dp })
            addView(col, lp(0, WRAP, 1f).apply { marginStart = 16.dp; marginEnd = 10.dp })
            addView(dur, lp(WRAP, WRAP))
            addView(eq, lp(22.dp, 20.dp))
        }

        fun bind(t: Track, now: Boolean) {
            t1.text = t.title
            t1.setTextColor(if (now) coverAccent else Palette.text)
            t2.text = listOf(t.artist, t.album).filter { it.isNotBlank() }.joinToString("  ·  ")
            dur.text = time(t.durationMs)
            // the song that is playing: its row tinted with the cover's colour, the bars instead of the duration
            dur.visibility = if (now) View.GONE else View.VISIBLE
            eq.visibility = if (now) View.VISIBLE else View.GONE
            eq.color = coverAccent
            eq.animating = now && Player.playing
            background = if (now) Shapes.rect(Palette.withAlpha(coverAccent, 0.16f), 18f) else Shapes.ghost(18f)
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
        // the blurred cover, then a veil that keeps text readable on any cover
        bgArt = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        root.addView(bgArt, MATCH, MATCH)
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
                Palette.withAlpha(Palette.bg, if (Palette.dark) 0.35f else 0.45f),
                Palette.withAlpha(Palette.bg, if (Palette.dark) 0.62f else 0.7f),
                Palette.withAlpha(Palette.bg, if (Palette.dark) 0.88f else 0.9f)))
        }, MATCH, MATCH)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(18.dp, 14.dp, 18.dp, 16.dp) }

        // ---- now playing
        left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(FrameLayout(this).apply {
            background = Shapes.pressable(Shapes.oval(Palette.withAlpha(Palette.card, if (Palette.dark) 0.55f else 0.75f)), Shapes.oval(Palette.card3))
            isClickable = true
            pressScale(0.9f)
            setOnClickListener { close() }
            addView(iconView(R.drawable.ic_chevron_left, 28, Palette.text).apply {
                layoutParams = flp(28.dp, 28.dp, Gravity.CENTER)
                if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) scaleX = -1f
            })
        }, lp(56.dp, 56.dp))
        source = Segmented(this, listOf(getString(R.string.music_library), getString(R.string.music_bluetooth), getString(R.string.music_youtube)), equal = false) { i ->
            selectSource(Src.values()[i])
        }.apply { trackColor = Palette.withAlpha(Palette.card, if (Palette.dark) 0.55f else 0.75f) }
        top.addView(View(this), lp(0, 1, 1f))
        lyricsIcon = iconView(R.drawable.ic_lyrics, 26, Palette.text)
        lyricsBtn = FrameLayout(this).apply {
            isClickable = true
            pressScale(0.9f)
            contentDescription = getString(R.string.lyrics_toggle)
            setOnClickListener { toggleLyrics() }
            addView(lyricsIcon, flp(26.dp, 26.dp, Gravity.CENTER))
        }
        top.addView(lyricsBtn, lp(56.dp, 56.dp).apply { marginEnd = 10.dp })
        top.addView(source, lp(WRAP, 56.dp))
        left.addView(top, lp(MATCH, WRAP))

        artBox = FrameLayout(this).apply { background = Shapes.rect(Palette.card2, COVER_RADIUS); roundedClip(COVER_RADIUS); elevate(24f) }
        artIcon = iconView(R.drawable.ic_music, 72, Palette.text3)
        artBox.addView(artIcon, flp(72.dp, 72.dp, Gravity.CENTER))
        art = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        artBox.addView(art, flp(MATCH, MATCH))
        artSlot = object : FrameLayout(this) {
            override fun onMeasure(w: Int, h: Int) {
                // the cover is the largest square that fits the space left between the header and the title
                val s = minOf(MeasureSpec.getSize(w), MeasureSpec.getSize(h))
                (artBox.layoutParams as LayoutParams).apply { width = s; height = s }
                super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(w), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(h), MeasureSpec.EXACTLY))
            }
        }
        artSlot.addView(artBox, flp(1, 1, Gravity.CENTER))
        // the cover and the song's info: one above the other beside the library, side by side in Bluetooth mode
        stage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        stage.addView(artSlot, lp(MATCH, 0, 1f))
        info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        stage.addView(info, lp(MATCH, WRAP))
        left.addView(stage, lp(MATCH, 0, 1f))

        title = label(28f, Palette.text, Fonts.MEDIUM, gravity = Gravity.CENTER)
        artist = label(19f, Palette.text2, Fonts.REGULAR, gravity = Gravity.CENTER)
        album = label(15f, Palette.text3, Fonts.REGULAR, gravity = Gravity.CENTER)
        info.addView(title, lp(MATCH, WRAP))
        info.addView(artist, lp(MATCH, WRAP).apply { topMargin = 4.dp })
        info.addView(album, lp(MATCH, WRAP).apply { topMargin = 2.dp })

        seek = SeekView(this).apply {
            onSeek = { f ->
                if (yt) {
                    val d = MediaMonitor.app(YtMusic.players)?.durationMs ?: 0L
                    if (d > 0) MediaMonitor.appControl(YtMusic.players, MediaMonitor.Do.PLAY, seekToMs = (d * f).toLong())
                } else Player.seekTo((Player.duration() * f).toLong())
            }
        }
        info.addView(seek, lp(MATCH, 36.dp).apply { topMargin = 12.dp })
        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        // (digits of one width: the times do not wobble as they count)
        tNow = label(13.5f, Palette.text2, Fonts.MEDIUM).apply { fontFeatureSettings = "tnum" }
        tTotal = label(13.5f, Palette.text2, Fonts.MEDIUM).apply { fontFeatureSettings = "tnum" }
        times.addView(tNow, lp(0, WRAP, 1f))
        times.addView(tTotal, lp(WRAP, WRAP))
        info.addView(times, lp(MATCH, WRAP).apply { setMargins(12.dp, 0, 12.dp, 0) })

        val ctl = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        shuffleBtn = ImageView(this)
        repeatBtn = ImageView(this)
        ctl.addView(ctlButton(R.drawable.ic_shuffle, 28, shuffleBtn) { Player.setShuffle(!Player.shuffle) }, lp(64.dp, 64.dp))
        ctl.addView(ctlButton(R.drawable.ic_skip_back, 36, null) { transport(0) }, lp(82.dp, 82.dp).apply { marginStart = 12.dp })
        playIcon = PlayPauseView(this)
        playBtn = FrameLayout(this).apply {
            isClickable = true
            pressScale(0.9f)
            elevate(16f)
            contentDescription = getString(R.string.media_play)
            setOnClickListener { transport(1) }
            addView(playIcon, flp(44.dp, 44.dp, Gravity.CENTER))
        }
        ctl.addView(playBtn, lp(100.dp, 100.dp).apply { marginStart = 14.dp; marginEnd = 14.dp })
        ctl.addView(ctlButton(R.drawable.ic_skip_fwd, 36, null) { transport(2) }, lp(82.dp, 82.dp).apply { marginEnd = 12.dp })
        ctl.addView(ctlButton(R.drawable.ic_repeat, 28, repeatBtn) { Player.cycleRepeat() }, lp(64.dp, 64.dp))
        info.addView(ctl, lp(MATCH, WRAP).apply { topMargin = 8.dp })
        // Bluetooth mode without a phone: the way to pair or connect one
        btSettings = label(17f, Palette.text, Fonts.MEDIUM, gravity = Gravity.CENTER).apply {
            setText(R.string.music_bt_settings)
            background = Shapes.tonal(20f)
            setPadding(28.dp, 0, 28.dp, 0)
            isClickable = true
            pressScale(0.95f)
            visibility = View.GONE
            setOnClickListener { openBluetooth(this@MusicActivity) }
        }
        info.addView(btSettings, lp(WRAP, 58.dp).apply { topMargin = 18.dp })
        row.addView(left, lp(0, MATCH, 0.46f).apply { marginEnd = 18.dp })

        // ---- library (frosted panel over the blurred cover); Bluetooth mode hides it
        panel = FrameLayout(this).apply {
            background = Shapes.rect(Palette.withAlpha(Palette.card, if (Palette.dark) 0.72f else 0.82f), 28f, if (Palette.dark) 0x14FFFFFF else 0)
        }
        libraryBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(14.dp, 14.dp, 14.dp, 0) }
        panel.addView(libraryBox, flp(MATCH, MATCH))
        val libPanel = libraryBox
        tabs = Segmented(this, listOf(getString(R.string.music_songs), getString(R.string.music_albums),
            getString(R.string.music_artists), getString(R.string.music_folders)), equal = true) { i ->
            tab = Tab.values()[i]
            group = null
            showList(fade = true)
        }.apply { trackColor = Palette.withAlpha(Palette.card3, 0.75f) }
        libPanel.addView(tabs, lp(MATCH, 56.dp))
        crumb = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            isClickable = true
            setOnClickListener { group = null; showList(fade = true) }
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
        libPanel.addView(crumb, lp(MATCH, WRAP))
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
        libPanel.addView(body, lp(MATCH, 0, 1f).apply { topMargin = 8.dp })

        btBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(36.dp, 24.dp, 36.dp, 24.dp); visibility = View.GONE }
        btBadgeIcon = iconView(R.drawable.ic_bluetooth, 56, Palette.accent).apply { layoutParams = flp(56.dp, 56.dp, Gravity.CENTER) }
        btBadge = FrameLayout(this).apply { addView(btBadgeIcon) }
        btBox.addView(btBadge, lp(124.dp, 124.dp))
        btState = label(22f, Palette.text, Fonts.MEDIUM, lines = 2, gravity = Gravity.CENTER)
        btBox.addView(btState, lp(MATCH, WRAP).apply { topMargin = 22.dp })
        btBox.addView(label(16f, Palette.text2, Fonts.REGULAR, lines = 3, gravity = Gravity.CENTER).apply { setText(R.string.music_bt_hint) },
            lp(MATCH, WRAP).apply { topMargin = 10.dp })
        btBox.addView(label(17f, Palette.text, Fonts.MEDIUM, gravity = Gravity.CENTER).apply {
            setText(R.string.music_bt_settings)
            background = Shapes.tonal(20f)
            setPadding(28.dp, 0, 28.dp, 0)
            isClickable = true
            pressScale(0.95f)
            setOnClickListener { openBluetooth(this@MusicActivity) }
        }, lp(WRAP, 60.dp).apply { topMargin = 24.dp })
        panel.addView(btBox, flp(MATCH, MATCH))
        panel.addView(buildYt(), flp(MATCH, MATCH))
        lyricsView = LyricsView(this).apply { visibility = View.GONE; onLineTap = { l -> onLineTap(l) } }
        panel.addView(lyricsView, flp(MATCH, MATCH))
        // over the lyrics, in the panel's corner: the lyrics alone on the whole screen
        expandBtn = FrameLayout(this).apply {
            background = Shapes.pressable(Shapes.oval(Palette.withAlpha(Palette.card3, 0.7f)), Shapes.oval(Palette.card3))
            isClickable = true
            pressScale(0.9f)
            visibility = View.GONE
            contentDescription = getString(R.string.lyrics_bigger)
            setOnClickListener { showFull(true) }
            addView(iconView(R.drawable.ic_expand, 22, Palette.text).apply { layoutParams = flp(22.dp, 22.dp, Gravity.CENTER) })
        }
        panel.addView(expandBtn, flp(50.dp, 50.dp, Gravity.TOP or Gravity.END).apply { setMargins(12.dp, 12.dp, 12.dp, 0) })

        row.addView(panel, lp(0, MATCH, 0.54f))
        root.addView(row, MATCH, MATCH)
        content = row
        // (what travels between two layouts crosses its parents' edges on the way: see relayout)
        for (g in listOf(root, row, left, top, stage, artSlot)) { g.clipChildren = false; g.clipToPadding = false }
        root.addView(buildFull(), MATCH, MATCH)
        // the cover on its way between the two layouts (see fly)
        flyer = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.GONE }
        root.addView(flyer, flp(1, 1))
        this.root = root
        setArt(null, -3)
        return root
    }

    /** The lyrics alone on the whole screen: large lines, a way back in the corner, and a small bar for the song. */
    private fun buildFull(): View {
        fullLayer = FrameLayout(this).apply { isClickable = true; visibility = View.INVISIBLE } // (taps do not reach the screen below)
        lyricsBig = LyricsView(this, big = true).apply { onLineTap = { l -> onLineTap(l) } }
        fullLayer.addView(lyricsBig, flp(MATCH, MATCH).apply { bottomMargin = (FULL_BAR_DP + 22).dp })

        val frost = Palette.withAlpha(Palette.card, if (Palette.dark) 0.55f else 0.75f)
        fullClose = FrameLayout(this).apply {
            background = Shapes.pressable(Shapes.oval(frost), Shapes.oval(Palette.card3))
            isClickable = true
            pressScale(0.9f)
            contentDescription = getString(R.string.lyrics_smaller)
            setOnClickListener { showFull(false) }
            addView(iconView(R.drawable.ic_collapse, 26, Palette.text).apply { layoutParams = flp(26.dp, 26.dp, Gravity.CENTER) })
        }
        // (where the screen's own way back is: the corner that leaves)
        fullLayer.addView(fullClose, flp(56.dp, 56.dp, Gravity.TOP or Gravity.START).apply { setMargins(18.dp, 14.dp, 18.dp, 0) })

        fullBar = FrameLayout(this).apply {
            background = Shapes.rect(Palette.withAlpha(Palette.card, if (Palette.dark) 0.66f else 0.84f), FULL_BAR_DP / 2f, if (Palette.dark) 0x14FFFFFF else 0)
            isClickable = true
        }
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(12.dp, 0, 14.dp, 0)
        }
        fullArtBox = FrameLayout(this).apply { background = Shapes.rect(Palette.card2, BAR_COVER_RADIUS); roundedClip(BAR_COVER_RADIUS) }
        fullArtBox.addView(iconView(R.drawable.ic_music, 26, Palette.text3).apply { layoutParams = flp(26.dp, 26.dp, Gravity.CENTER) })
        fullArt = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        fullArtBox.addView(fullArt, flp(MATCH, MATCH))
        line.addView(fullArtBox, lp(64.dp, 64.dp))
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fullTitle = label(19f, Palette.text, Fonts.MEDIUM)
        fullArtist = label(15f, Palette.text2, Fonts.REGULAR)
        names.addView(fullTitle, lp(MATCH, WRAP))
        names.addView(fullArtist, lp(MATCH, WRAP).apply { topMargin = 3.dp })
        line.addView(names, lp(0, WRAP, 1f).apply { marginStart = 16.dp; marginEnd = 12.dp })
        // (previous, play, next: left to right in every language, like the buttons of the song's side)
        val ctl = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        ctl.addView(ctlButton(R.drawable.ic_skip_back, 30, null) { transport(0) }, lp(64.dp, 64.dp))
        fullPlayIcon = PlayPauseView(this)
        fullPlay = FrameLayout(this).apply {
            isClickable = true
            pressScale(0.9f)
            contentDescription = getString(R.string.media_play)
            setOnClickListener { transport(1) }
            addView(fullPlayIcon, flp(32.dp, 32.dp, Gravity.CENTER))
        }
        ctl.addView(fullPlay, lp(68.dp, 68.dp).apply { marginStart = 6.dp; marginEnd = 6.dp })
        ctl.addView(ctlButton(R.drawable.ic_skip_fwd, 30, null) { transport(2) }, lp(64.dp, 64.dp))
        line.addView(ctl, lp(WRAP, WRAP))
        fullBar.addView(line, flp(MATCH, MATCH))
        // how far the song is: a thin line inside the bar's bottom edge
        fullLine = ThinProgress(this)
        fullBar.addView(fullLine, flp(MATCH, 3.dp, Gravity.BOTTOM).apply { setMargins(34.dp, 0, 34.dp, 7.dp) })
        fullLayer.addView(fullBar, flp(MATCH, FULL_BAR_DP.dp, Gravity.BOTTOM).apply { setMargins(18.dp, 0, 18.dp, 14.dp) })
        return fullLayer
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
        private const val YT_ART = Long.MIN_VALUE + 2
        /** A time the phone does not tell (Bluetooth). */
        private const val UNKNOWN_TIME = "–:––"
        /** How long after a line starts the owner, hearing it, taps it. */
        private const val TAP_REACTION_MS = 450L
        /** Corner radius of the song's cover, and of the small one in the bar under the large lyrics (dp). */
        private const val COVER_RADIUS = 26f
        private const val BAR_COVER_RADIUS = 16f
        /** Height of that bar (dp). */
        private const val FULL_BAR_DP = 88

        fun open(ctx: Context) {
            try { ctx.startActivity(Intent(ctx, MusicActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) { }
        }

        private const val EXTRA_SOURCE = "source" // "bt" / "local"

        /** In place of the stock Bluetooth music screen ([bt]) or the stock music player that just came up. */
        fun openInstead(ctx: Context, bt: Boolean) {
            try {
                ctx.startActivity(Intent(ctx, MusicActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(EXTRA_SOURCE, if (bt) "bt" else "local"))
            } catch (_: Throwable) {
            }
        }

        /** Bluetooth settings live in the stock phone app on this firmware (pairing, connect). */
        fun openBluetooth(ctx: Context) {
            if (!AppRepo.launch(ctx, "com.nwd.android.phone")) {
                try { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) { }
            }
        }
    }
}

/** Progress bar with a knob; drag to seek. Always left-to-right, like the time labels. */
class SeekView(ctx: Context) : View(ctx) {
    var progress = 0f
        set(v) { val n = v.coerceIn(0f, 1f); if (n != field) { field = n; invalidate() } }
    /** Played part (the cover's colour). */
    var color = Palette.accent
        set(v) { if (v != field) { field = v; invalidate() } }
    var onSeek: ((Float) -> Unit)? = null
    var enabledSeek = true
        set(v) { if (v != field) { field = v; invalidate() } }
    var dragging = false
        private set
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    init { layoutDirection = LAYOUT_DIRECTION_LTR }

    override fun onDraw(c: Canvas) {
        val h = (if (dragging) 10f else 6f).dp // (thicker under the finger)
        val y = height / 2f
        val pad = 12f.dp
        val w = width - 2 * pad
        r.set(pad, y - h / 2, pad + w, y + h / 2)
        p.color = if (Palette.dark) 0x2EFFFFFF else 0x22000000
        c.drawRoundRect(r, h / 2, h / 2, p)
        if (progress > 0f) {
            r.set(pad, y - h / 2, pad + (w * progress).coerceAtLeast(h), y + h / 2)
            p.color = color
            c.drawRoundRect(r, h / 2, h / 2, p)
        }
        if (enabledSeek) {
            p.color = 0x30000000
            c.drawCircle(pad + w * progress, y + 1f.dp, (if (dragging) 14f else 9f).dp, p)
            p.color = 0xFFFFFFFF.toInt()
            c.drawCircle(pad + w * progress, y, (if (dragging) 13f else 8f).dp, p)
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

/** How far the song is, as a thin line (nothing at all while that is unknown). Left to right, like [SeekView]. */
class ThinProgress(ctx: Context) : View(ctx) {
    /** 0..1, or negative when unknown. */
    var progress = -1f
        set(v) { val n = if (v < 0f) -1f else v.coerceAtMost(1f); if (n != field) { field = n; invalidate() } }
    var color = Palette.accent
        set(v) { if (v != field) { field = v; invalidate() } }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(c: Canvas) {
        if (progress < 0f) return
        val h = height.toFloat()
        r.set(0f, 0f, width.toFloat(), h)
        p.color = if (Palette.dark) 0x26FFFFFF else 0x1A000000
        c.drawRoundRect(r, h / 2, h / 2, p)
        if (progress > 0f) {
            r.set(0f, 0f, (width * progress).coerceAtLeast(h), h)
            p.color = color
            c.drawRoundRect(r, h / 2, h / 2, p)
        }
    }
}

/** Three bars beside the song that is playing; they move while it plays (a dozen frames a second is plenty). */
class EqualizerView(ctx: Context) : View(ctx) {
    var color = Palette.accent
        set(v) { if (v != field) { field = v; invalidate() } }
    var animating = false
        set(v) { if (v != field) { field = v; invalidate() } }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(c: Canvas) {
        p.color = color
        val bw = width / 5f
        val t = android.os.SystemClock.uptimeMillis() / 1000.0
        for (i in 0 until 3) {
            val f = if (animating) 0.3 + 0.7 * kotlin.math.abs(kotlin.math.sin(t * (2.3 + i * 0.8) + i * 1.7)) else 0.35 + i * 0.2
            val bh = (height * f).toFloat()
            val x = bw * 2 * i
            r.set(x, height - bh, x + bw, height.toFloat())
            c.drawRoundRect(r, bw / 2, bw / 2, p)
        }
        if (animating && isAttachedToWindow) postInvalidateDelayed(80)
    }
}
