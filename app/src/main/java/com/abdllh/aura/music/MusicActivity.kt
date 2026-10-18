package com.abdllh.aura.music

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.TransitionDrawable
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import com.abdllh.aura.R
import com.abdllh.aura.home.AppRepo
import com.abdllh.aura.media.MediaMonitor
import com.abdllh.aura.ui.ABtn
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Elev
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.LayoutMove
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.PlayPauseView
import com.abdllh.aura.ui.Radius
import com.abdllh.aura.ui.Segmented
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.Snack
import com.abdllh.aura.ui.Space
import com.abdllh.aura.ui.Spring
import com.abdllh.aura.ui.Theme
import com.abdllh.aura.ui.Touch
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.anim
import com.abdllh.aura.ui.enter
import com.abdllh.aura.ui.exit
import com.abdllh.aura.ui.fadeTo
import com.abdllh.aura.ui.flp
import com.abdllh.aura.ui.iconView
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lp
import com.abdllh.aura.ui.pressScale
import com.abdllh.aura.ui.riseIn
import com.abdllh.aura.ui.roundedClip
import com.abdllh.aura.ui.textRole
import com.abdllh.aura.util.LocaleHelper
import com.abdllh.aura.util.Prefs
import com.abdllh.aura.util.dp
import kotlin.math.abs

/**
 * Aura Music: now playing on the start side (cover, progress, controls) and the library on the other side
 * (songs, albums, artists, folders from the unit and USB drives), plus music from a phone over Bluetooth.
 */
class MusicActivity : Activity() {
    private enum class Tab { SONGS, ALBUMS, ARTISTS, FOLDERS }

    /** How a list comes in: another tab fades in place, a group comes from the end side, Back from the start side. */
    private enum class Nav { NONE, TAB, INTO, BACK }

    private lateinit var art: ImageView
    /** The cover before, leaving under the new one (see [swapCover]). */
    private lateinit var artOld: ImageView
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
    /** The "1" on the repeat button's corner: the one song again, not the whole list. */
    private lateinit var repeatOne: AText
    private val repeatOneBg = Shapes.oval(0)
    private lateinit var prevBtn: View
    private lateinit var nextBtn: View
    private lateinit var source: Segmented
    private lateinit var tabs: Segmented
    private lateinit var listHolder: FrameLayout
    private lateinit var crumb: LinearLayout
    private lateinit var crumbText: AText
    private lateinit var crumbChevron: ImageView
    private lateinit var playAllIcon: ImageView
    private lateinit var playAllText: AText
    private lateinit var empty: LinearLayout
    private lateinit var emptyText: AText
    private lateinit var emptyBtn: ABtn
    private lateinit var emptySpinner: ProgressBar

    private lateinit var artBox: FrameLayout
    private lateinit var playBtn: FrameLayout
    private lateinit var libraryBox: LinearLayout
    // YouTube: a search of its catalogue; what is chosen is played by YouTube's own app (YtMusic)
    private lateinit var ytBox: LinearLayout
    private lateinit var ytInput: android.widget.EditText
    private lateinit var ytSearchIcon: ImageView
    private lateinit var ytSpinner: ProgressBar
    private lateinit var ytList: ListView
    private lateinit var ytNote: LinearLayout
    private lateinit var ytNoteText: AText
    private lateinit var ytNoteBtn: ABtn
    private var ytSongs: List<YtMusic.Song> = emptyList()
    private var ytAsked = 0
    private var ytCover: Bitmap? = null
    /** What YouTube's app last told (read by the progress tick without asking it again). */
    private var ytHas = false
    private var ytDurationMs = 0L
    private var ytPlaying = false
    // the layout: in Bluetooth mode the song takes the whole screen (cover beside the title and the controls)
    private lateinit var left: LinearLayout
    private lateinit var panel: FrameLayout
    private lateinit var stage: LinearLayout
    private lateinit var artSlot: FrameLayout
    private lateinit var info: LinearLayout
    private lateinit var btSettings: ABtn
    // the lyrics take the library's place (and, with Bluetooth, the song makes room for them)
    private lateinit var lyricsView: LyricsView
    private lateinit var lyricsBtn: FrameLayout
    private lateinit var lyricsIcon: ImageView
    private lateinit var lyricsOffBg: Drawable
    /** The owner's switch (the button): lyrics wanted. */
    private var lyricsWanted = false
    /** Their place is open: wanted, and the song has some (or the owner asked on this very song, see [lyricsPinned]). */
    private var lyricsOn = false
    private var lyricsKey: String? = null
    /** The song whose lines the lyrics show (coming back to the screen on it does not make them again). */
    private var lyricsShown: String? = null
    /** The button was just pressed on: the song then in front keeps their place open even when it has none ("" = no song). */
    private var lyricsAsked = false
    private var lyricsPinned: String? = null
    /** The song whose lyrics were asked for last, as they were asked for (timed lines that come later are matched to it). */
    private var lyricsSong: LyricSong? = null
    /** The lyrics shown for [lyricsSong] (a correction is kept with exactly these lines). */
    private var lyricsResult: Lyrics.Result? = null
    /**
     * The owner's correction of the shown timing (1.15, ms: lyrics time minus the song's clock), a Bluetooth song's
     * only: kept with its lyrics, so the next play of the song has it at once.
     */
    private var lyricsOffset = 0L
    /** What is added to the song's position to light the lines (the correction, for the Bluetooth song it was made on). */
    private val lyricsShift: Long get() = if (bt && lyricsSong?.bt == true) lyricsOffset else 0L

    /**
     * A song as its lyrics are asked for: its key on this screen, its names, the length the lookup used, and where it
     * plays from (the unit's own file, or the phone over Bluetooth).
     */
    private class LyricSong(val key: String, val title: String, val artist: String, val sec: Int, val track: Track? = null, val bt: Boolean = false)
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
    private lateinit var fullPrev: View
    private lateinit var fullNext: View
    private lateinit var fullPlay: FrameLayout
    private lateinit var fullPlayIcon: PlayPauseView
    private lateinit var fullLine: ThinProgress
    private lateinit var flyer: ImageView
    private var flight: ValueAnimator? = null
    private var lyricsFull = false

    private var tab = Tab.SONGS
    private var group: Group? = null
    /** Where the music comes from: the unit's own files, the phone over Bluetooth, or YouTube's app on the unit. */
    private enum class Src(val pref: String) { LOCAL("local"), BT("bt"), YT("yt") }
    private var src = Src.LOCAL
    private val bt get() = src == Src.BT
    private val yt get() = src == Src.YT
    private val rtl get() = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
    private var lastArtKey = Long.MIN_VALUE
    private var btCover: Bitmap? = null
    /** The song shown (its title comes in with a little movement when it changes). */
    private var shownSong: String? = null
    /** The cover shown, and whether one was set yet (the first comes without the fade). */
    private var shownCover: Bitmap? = null
    private var coverShown = false
    /** The song the cover shown belongs to: another cover for the same song only crossfades. */
    private var artSong: String? = null
    /**
     * The cover's colour (see [Backdrop.Look]): [fill] for the progress, the play button, the indicators and the bars;
     * [fillText] where it is text or an icon (the playing row, Play all). The theme accent without a cover.
     */
    private var fill = Palette.accent
    private var fillText = Palette.accentFg

    // the theme, the accent and the language this screen was built in (see checkLook)
    private var builtDark = false
    private var builtAccent = 0
    private var builtLang = ""

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fonts.refreshLocale(this)
        Theme.refresh()
        Theme.window(this, Palette.bg)
        builtDark = Palette.dark
        builtAccent = Prefs.accent
        builtLang = Prefs.lang
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
        // Asked for only where the music files are wanted (the library's own button, or choosing its source): the
        // screen opened for the phone's music used to start with a system dialog over the song.
        if (Library.hasPermission(this)) Library.load(this)
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

    private fun askPermission() = requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 3)

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
        Lyrics.onTimed = timedListener
        LyricsSync.onStatus = syncStatus
        // (the setting may have changed in Settings meanwhile)
        if (::lyricsView.isInitialized) { val w = Words.on; lyrics { it.wordsOn = w } }
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
        // (listening for the lyrics' timing is the screen's: it stops with it, and starts again when it comes back)
        if (Lyrics.onTimed === timedListener) Lyrics.onTimed = null
        if (LyricsSync.onStatus === syncStatus) LyricsSync.onStatus = null
        LyricsSync.end()
        if (::lyricsView.isInitialized) { lyricsView.removeCallbacks(lyricsRetry); lyricsView.removeCallbacks(lyricsLoading); lyrics { it.note(null) } }
        lyricsKey = null // looked up again when the screen comes back
        // a cover still waiting for its successor (Bluetooth): the screen comes back with whatever there is by then
        // (tracked in a field: removeCallbacks says nothing about whether one was waiting)
        val waiting = artWaiting
        cancelArtWait()
        if (waiting && bt) lastArtKey = Long.MIN_VALUE
        super.onStop()
    }

    @Deprecated("back leaves the large lyrics, then an album/artist, first")
    override fun onBackPressed() {
        when {
            lyricsFull -> showFull(false)
            group != null -> { group = null; showList(Nav.BACK) }
            else -> close()
        }
    }

    override fun onResume() {
        super.onResume()
        com.abdllh.aura.system.Animations.ensureAnimators()
        if (checkLook()) return
        try {
            registerReceiver(looks, IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            })
        } catch (_: Throwable) {
        }
    }

    override fun onPause() {
        try { unregisterReceiver(looks) } catch (_: Throwable) { }
        super.onPause()
    }

    /** Sunset or sunrise under "auto" (a minute's tick), or a theme, accent or language chosen in Settings meanwhile. */
    private val looks = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { checkLook() }
    }

    /**
     * The screen is built again when what it was built in changed: it lives in its own task, so it came back from
     * Settings in the old language and direction, and stayed on the day colours all night under "auto".
     */
    private fun checkLook(): Boolean {
        if (Prefs.lang == builtLang && Theme.isDarkNow() == builtDark && Prefs.accent == builtAccent) return false
        Theme.refresh()
        recreate()
        return true
    }

    private val playerListener: () -> Unit = {
        if (src == Src.LOCAL) showNowPlaying()
        followPlaying()
        if (listView.adapter === tracks) tracks.notifyDataSetChanged()
    }
    private val libraryListener: () -> Unit = { Player.restore(); showList(); showNowPlaying() }
    private val btListener: () -> Unit = { if (bt) showNowPlaying() }
    private val appListener: (com.abdllh.aura.media.NowPlaying?) -> Unit = { if (yt) showNowPlaying() }

    // ------------------------------------------------------------------------------------------ position
    /** Where the song of the source in front is (ms) ([held] for a seek just asked for). */
    private fun positionNow(): Long = when (src) {
        Src.BT -> BtMusic.positionMs()
        Src.YT -> held(MediaMonitor.appPosition(YtMusic.players) ?: 0L, ytPlaying)
        Src.LOCAL -> held(Player.position(), Player.playing)
    }

    private val positionUnknown get() = bt && !BtMusic.positionKnown

    private var seekTarget = -1L
    private var seekSetAt = 0L

    /** A seek was just asked for (the bar let go, a lyric line tapped): it is where the song is shown meanwhile. */
    private fun holdSeek(ms: Long) {
        seekTarget = ms
        seekSetAt = SystemClock.uptimeMillis()
    }

    /**
     * Where the song is after a seek, while its source still tells where it was: the seek is not done at once (YouTube's
     * app answers with a new state some time later, a file on a USB stick is read first), and in between the knob and
     * the lit line jumped back for a moment, then forward again. The source is believed again as soon as it agrees, or
     * after [SEEK_HOLD_MS] (a seek the app refused).
     */
    private fun held(reported: Long, playing: Boolean): Long {
        if (seekTarget < 0L) return reported
        val since = SystemClock.uptimeMillis() - seekSetAt
        val expected = seekTarget + if (playing) since else 0L
        if (since > SEEK_HOLD_MS || abs(reported - expected) <= 1200L) { seekTarget = -1L; return reported }
        return expected
    }

    private val progressTick = object : Runnable {
        override fun run() {
            val at = positionNow()
            val playing = when (src) {
                Src.YT -> ytPlaying
                Src.BT -> BtMusic.playing
                Src.LOCAL -> Player.playing
            }
            // (the lyrics' time: the song's, with the owner's correction of a Bluetooth song's timing)
            val lyricAt = at + lyricsShift
            if (lyricsOn) {
                val unknown = positionUnknown
                lyrics { it.needSync = unknown; it.at(lyricAt, playing) }
            }
            when (src) {
                // YouTube's app tells its song's length and where it is (the real position)
                Src.YT -> {
                    val p = MediaMonitor.appPosition(YtMusic.players)
                    showProgress(ytHas && ytDurationMs > 0 && p != null, at, ytDurationMs)
                }
                // the phone does not say where a song is (BtMusic keeps a clock): an unknown position shows nothing
                Src.BT -> {
                    val d = BtMusic.durationSec * 1000L
                    showProgress(BtMusic.connected && BtMusic.title.isNotBlank() && BtMusic.positionKnown && d > 0, at, d)
                }
                Src.LOCAL -> {
                    val d = Player.duration()
                    showProgress(Player.current != null && d > 0, at, d)
                }
            }
            // the lyrics follow the song closely: the next tick comes when the next line is due, not up to a tick late
            val next = if (lyricsOn && playing) lyricsView.msToNext(lyricAt) else Long.MAX_VALUE
            seek.postDelayed(this, minOf(if (lyricsOn) 250L else 500L, next).coerceAtLeast(16L))
        }
    }

    /** The bar and the times; nothing at all where the song or its position is not known (no empty bar, no dashes). */
    private fun showProgress(known: Boolean, p: Long, d: Long) {
        seek.visibility = if (known) View.VISIBLE else View.INVISIBLE
        val f = if (known) (p.toFloat() / d).coerceIn(0f, 1f) else 0f
        if (!seek.dragging) {
            seek.progress = f
            tNow.setTextIfChanged(if (known) clockText(p.coerceAtMost(d)) else "")
            tTotal.setTextIfChanged(if (known) clockText(d) else "")
        }
        fullLine.progress = if (known) f else -1f
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
            show -> relayout(panelAfter = true) { lyricsOn = true; showList(); paintNames() }
            lyricsFull -> {
                // the large lyrics are left first; large stays the owner's choice for the next song that has some
                lyricsOn = false
                showList() // (the song's side is out of sight: it comes back in its new layout)
                paintNames()
                if (moving) afterLayout { if (lyricsFull && !lyricsOn) showFull(false, remember = false) }
                else showFull(false, animate = false, remember = false)
            }
            else -> relayout(panelAfter = !bt) { lyricsOn = false; showList(); paintNames() }
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
        val inside = listOf(libraryBox, ytBox, lyricsView)
        val was = inside.firstOrNull { hadPanel && it.visibility == View.VISIBLE }
        // (a paused cover's smaller size moves from its slot to the cover itself: the move measures the cover by its
        // own scale, and would otherwise start it from a place 6 % off)
        val k = artSlot.scaleX
        if (k != 1f) { breath.snapTo(1f); artBox.scaleX = k; artBox.scaleY = k }
        val move = LayoutMove(root, listOf(artBox, info, lyricsBtn, source), setOf(artBox),
            leaving = if (hadPanel && !panelAfter) listOf(panel) else listOfNotNull(was))
        relaying = true
        try { change() } finally { relaying = false }
        val hasPanel = panel.visibility == View.VISIBLE
        val now = inside.firstOrNull { hasPanel && it.visibility == View.VISIBLE }
        // (the same thing in the same panel: nothing left, nothing came)
        if (was != null && was === now) move.keepLeaving() else if (now != null) now.riseIn(12f, 40L, Motion.MEDIUM)
        // the panel itself comes in from its side
        if (hasPanel && !hadPanel) panel.enter(dx = if (rtl) -36f else 36f, delay = 60L, dur = Motion.LONG)
        move.play()
        // (and back to the slot as the cover travels to its full size, so the cover lands paused in one motion; started
        // after the move has measured where the cover arrives, with the slot still at full size)
        if (k != 1f) afterLayout { breathe(nowPlaying()) }
        redrawAfterMove()
    }

    /**
     * The song's labels drawn anew once a move has settled. After a relayout, specks of other letters showed beside
     * the title, the artist and the length until the screen was next hidden. Text that travels is drawn at a new
     * fraction of a pixel on every frame, and the GPU's store of letters fills and reuses its places; a label still
     * drawing the run of letters it recorded before the move then picks up pieces of what took those places (redrawing
     * the column alone does not help: the labels inside keep their recording). Until 1.12 the labels were set again
     * on every tick, which hid it. One frame, once per move.
     */
    private fun redrawAfterMove() {
        root.removeCallbacks(redrawText)
        root.postDelayed(redrawText, Motion.LONG + 32L)
    }

    private val redrawText = Runnable { if (!isDestroyed) rerecord(left) }

    private fun rerecord(v: View) {
        if (v is TextView) v.invalidate() else if (v is ViewGroup) for (i in 0 until v.childCount) rerecord(v.getChildAt(i))
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
        val parts = listOf(content, fullBar, fullClose, lyricsBig)
        if (!moving) {
            for (v in parts) { v.animate().cancel(); v.setTag(R.id.aura_exiting, null) }
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
            content.exit(scaleTo = 0.97f, dur = Motion.SHORT) { if (lyricsFull) content.visibility = View.INVISIBLE }
            lyricsBig.enter(dy = 20f, delay = 60L)
            fullBar.enter(dy = 14f, delay = 40L)
            fullClose.enter(delay = 120L, dur = Motion.SHORT)
        } else {
            // leaving is quicker than arriving
            lyricsView.settle()
            fly(toBar = false)
            content.enter(scaleFrom = 0.98f, delay = 40L)
            lyricsBig.exit(dy = 12f) { if (!lyricsFull) fullLayer.visibility = View.INVISIBLE }
            fullBar.exit(dy = 10f)
            fullClose.exit(dur = Motion.XSHORT)
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
        // (the cover as seen: a paused one is shown a little smaller, by its slot)
        val from = artSlot.scaleX
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
            val s = from + (to - from) * t
            flyer.scaleX = s; flyer.scaleY = s
            flyer.translationX = ax + (bx - ax) * t
            flyer.translationY = ay + (by - ay) * t
            radius = (COVER_RADIUS * from + (BAR_COVER_RADIUS - COVER_RADIUS * from) * t).dp / s // (as seen: the copy is scaled)
            flyer.invalidateOutline()
        }
        place(if (toBar) 0f else 1f)
        // (fast out, soft landing, both ways: a flight that starts slowly trails the screen that already left)
        flight = ValueAnimator.ofFloat(if (toBar) 0f else 1f, if (toBar) 1f else 0f).apply {
            duration = if (toBar) Motion.LONG else Motion.MEDIUM
            interpolator = Motion.EMPHASIZED
            addUpdateListener { place(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
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
    /** "Looking for the lyrics" only when the answer is slow (a cached one comes a moment later). */
    private var lyricsLoadingKey: String? = null
    private val lyricsLoading = Runnable {
        if (lyricsKey != null && lyricsKey == lyricsLoadingKey) { lyricsShown = null; lyrics { it.showStatus(getString(R.string.lyrics_loading)) } }
    }
    /** Until when a new song's names and cover are moving: new lyrics wait for it (making them is heavy). */
    private var songMovesUntil = 0L

    /** The lyrics of what plays now, looked up once per song while the owner wants them (see [lyricsHave]). */
    private fun refreshLyrics() {
        if (!::lyricsView.isInitialized) return
        if (!lyricsWanted) { LyricsSync.end(); return }
        val s = if (yt) {
            // (YouTube's app tells the song's own length: the right recording's lyrics)
            MediaMonitor.app(YtMusic.players)
                ?.let { LyricSong("yt:${it.title}|${it.artist}", it.title, it.artist, (it.durationMs / 1000).toInt()) }
        } else if (bt) {
            if (!BtMusic.connected || BtMusic.title.isBlank()) null
            // (once per song: the phone's own length, when it told one, helps to pick the right recording)
            else LyricSong("bt:${BtMusic.title}|${BtMusic.artist}", BtMusic.title, BtMusic.artist, BtMusic.phoneDurationSec, bt = true)
        } else Player.current?.let { LyricSong("lib:${it.id}", it.title, it.artist, (it.durationMs / 1000).toInt(), track = it) }
        if (s?.key == lyricsKey && lyricsKey != null) return
        // another song: what listened to the one before stops (its timing goes on being made, and is kept)
        LyricsSync.end()
        lyrics { it.note(null) }
        lyricsKey = s?.key
        lyricsSong = s
        // (another song: its own correction comes with its lyrics; the same one shown again keeps its own meanwhile)
        if (lyricsShown != s?.key) { lyricsOffset = 0L; lyricsResult = null }
        val pin = s?.key ?: ""
        if (lyricsAsked) { lyricsAsked = false; lyricsPinned = pin } else if (pin != lyricsPinned) lyricsPinned = null
        lyricsView.removeCallbacks(lyricsLoading)
        if (s == null) { lyricsShown = null; lyrics { it.showStatus(getString(R.string.lyrics_nothing)) }; lyricsHave(false); return }
        // (while they are looked up their place stays as it is: open with what it showed, stepped back, or closed)
        if (lyricsShown != s.key) {
            lyrics { it.dim() }
            // (what shows is stepped back and no longer follows: back on that song before this answer comes — the
            // phone skipping one way and back — its lines are shown again, not left dimmed)
            lyricsShown = null
            lyricsLoadingKey = s.key
            lyricsView.postDelayed(lyricsLoading, LOADING_AFTER_MS)
        }
        Lyrics.get(this, s.title, s.artist, s.sec) { r, failed ->
            if (lyricsKey != s.key || isDestroyed) return@get
            lyricsView.removeCallbacks(lyricsLoading)
            // the length of the recording the lines were timed on: the Bluetooth song's, while the phone tells none
            // (timed lines only: an untimed record's length says no more than a catalogue's)
            if (bt && r != null && r.synced && r.durationSec > 0) BtMusic.offerDuration(r.durationSec, s.title, s.artist, BtMusic.FROM_LYRICS)
            val wait = songMovesUntil - SystemClock.uptimeMillis()
            if (wait > 0) root.postDelayed({ if (lyricsKey == s.key && !isDestroyed) lyricsAnswer(s.key, r, failed) }, wait)
            else lyricsAnswer(s.key, r, failed)
        }
    }

    private fun lyricsAnswer(key: String, r: Lyrics.Result?, failed: Boolean) {
        when {
            r == null && failed -> {
                lyricsShown = null
                lyrics { it.showStatus(getString(R.string.lyrics_failed)) { lyricsView.removeCallbacks(lyricsRetry); lyricsKey = null; refreshLyrics() } }
                // asked again in a while (the internet, or the server, may be back): one retry at a time
                lyricsView.removeCallbacks(lyricsRetry)
                lyricsRetryKey = key
                lyricsView.postDelayed(lyricsRetry, 20_000L)
                lyricsHave(false)
            }
            r == null -> { lyricsShown = null; lyrics { it.showStatus(getString(R.string.lyrics_none)) }; lyricsHave(false) }
            r.instrumental -> { lyricsShown = null; lyrics { it.showStatus(getString(R.string.lyrics_instrumental)) }; lyricsHave(false) }
            else -> {
                val unknown = positionUnknown
                lyricsResult = r
                // the owner's correction kept with these lyrics (a Bluetooth song's: see onLineTap)
                lyricsOffset = if (lyricsSong?.bt == true) r.offsetMs else 0L
                val at = positionNow() + lyricsShift
                if (lyricsShown != key) lyrics { it.show(r) }
                lyricsShown = key
                lyrics { it.needSync = unknown; it.at(at) }
                lyricsHave(true)
                listenFor(r)
            }
        }
    }

    /**
     * Untimed lines (or a timing by listening that is not complete) for a Bluetooth song or one of the unit's own:
     * the owner's server may time them by listening ([LyricsSync]: only with his switch on, and only while the song
     * plays with its position known). YouTube's songs are not listened to.
     */
    private fun listenFor(r: Lyrics.Result) {
        val s = lyricsSong
        if (s == null || !r.improvable || yt || (!s.bt && s.track == null)) { LyricsSync.end(); return }
        // (a Bluetooth song's length only when the phone told it: a catalogue's may be another recording's, and the
        // server's alignment leans a little on where in the song's length each line should be)
        val song = LyricsSync.Song(s.title, s.artist, s.sec, r.lines.map { it.text }, s.track,
            lengthSec = if (s.bt) BtMusic.phoneDurationSec else s.sec)
        val position: () -> Long? = if (s.bt) {
            { if (bt && BtMusic.connected && BtMusic.playing && BtMusic.positionKnown && BtMusic.title == s.title) BtMusic.positionMs() else null }
        } else {
            { if (src == Src.LOCAL && Player.playing && Player.current?.id == s.track?.id) Player.position() else null }
        }
        LyricsSync.begin(this, song, position)
    }

    /**
     * Timed lines for the song on the screen, after its untimed ones (the owner's server found them, or made them by
     * listening): the same lines are timed where they stand ([LyricsView.timeLines]), other ones replace them.
     */
    private val timedListener: (String, String, Int, Lyrics.Result) -> Unit = { title, artist, sec, r ->
        val s = lyricsSong
        if (s != null && s.title == title && s.artist == artist && s.sec == sec && lyricsShown == s.key && !isDestroyed) {
            // (another source's timed lyrics carry their recording's length, as LRCLIB's do: see refreshLyrics)
            if (s.bt && r.durationSec > 0) BtMusic.offerDuration(r.durationSec, title, artist, BtMusic.FROM_LYRICS)
            val unknown = positionUnknown
            // (a new timing: a correction made on the one before does not apply to it, and was not kept with it)
            lyricsResult = r
            lyricsOffset = r.offsetMs
            val at = positionNow() + lyricsShift
            lyrics { it.timeLines(r); it.needSync = unknown; it.at(at) }
            if (!r.improvable) LyricsSync.end()
        }
    }

    /** The listening at work, said in the lyrics' corner: what it does, and how much of the song is timed. */
    private val syncStatus: (Int, Float) -> Unit = { what, covered ->
        if (::lyricsView.isInitialized && !isDestroyed) {
            val pct = "${(covered * 100).toInt()}%"
            val text = when (what) {
                LyricsSync.LISTENING -> if (covered > 0f) getString(R.string.lyrics_sync_listening_pct, pct) else getString(R.string.lyrics_sync_listening)
                LyricsSync.WORKING -> getString(R.string.lyrics_sync_working, pct)
                else -> null
            }
            lyrics { it.note(text, if (what == LyricsSync.LISTENING) R.drawable.ic_mic else R.drawable.ic_lyrics) }
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
            holdSeek(l.ms)
            lyrics { it.at(l.ms) }
        } else if (bt) {
            val target = l.ms + TAP_REACTION_MS
            val s = lyricsSong
            val r = lyricsResult
            // the clock already known (the song's start seen, or set by a tap before): the tap corrects the lyrics'
            // timing for this song, kept with its lyrics (1.15). Unless it is far off: then the phone was moved or the
            // song restarted, and it is the clock that is wrong.
            if (BtMusic.positionKnown && s != null && s.bt && r != null && r.synced && lyricsShown == s.key &&
                BtMusic.title == s.title && abs(target - BtMusic.positionMs() - lyricsOffset) <= MAX_CORRECTION_MS) {
                val clock = BtMusic.positionMs()
                lyricsOffset = target - clock
                Lyrics.keepOffset(this, s.title, s.artist, s.sec, lyricsOffset, r.lines)
                lyrics { it.at(clock + lyricsOffset) }
                Snack.show(this, R.string.kar_timing_saved, aboveDp = snackAbove)
                return
            }
            // the clock unknown: the tap says where the song is (with the song's kept correction, the lyrics land on
            // the tapped line all the same)
            BtMusic.syncTo(target - lyricsShift)
            val at = BtMusic.positionMs() + lyricsShift
            lyrics { it.needSync = false; it.at(at) }
            (if (lyricsFull) lyricsBig else lyricsView).flashSynced()
        } else if (Player.current != null) {
            Player.seekTo(l.ms)
            holdSeek(l.ms)
            if (!Player.playing) Player.toggle()
            lyrics { it.at(l.ms) }
        }
    }

    /** Where the lyrics' messages stand: above the small bar when the lyrics fill the screen. */
    private val snackAbove: Int get() = if (lyricsFull) FULL_BAR_DP + Space.L + Space.S else Snack.ABOVE_DP

    /** A lyric line held down: the song's saved timing correction is forgotten (1.15); nothing else to do there. */
    private fun onLineHold(): Boolean {
        val s = lyricsSong
        val r = lyricsResult
        if (!bt || s == null || !s.bt || r == null || lyricsShown != s.key) return false
        // (held without a saved correction: nothing, rather than the tap it would become on letting go)
        if (lyricsOffset == 0L) return true
        lyricsOffset = 0L
        Lyrics.keepOffset(this, s.title, s.artist, s.sec, 0L, r.lines)
        val at = positionNow()
        lyrics { it.at(at) }
        Snack.show(this, R.string.kar_timing_reset, aboveDp = snackAbove)
        return true
    }

    // ------------------------------------------------------------------------------------------ now playing
    private fun showNowPlaying() {
        val local = src == Src.LOCAL
        // (the whole button, not only its icon: an empty spot still took taps and turned the unit's own player's
        // shuffle and repeat while another source played)
        (shuffleBtn.parent as View).visibility = if (local) View.VISIBLE else View.INVISIBLE
        (repeatBtn.parent as View).visibility = if (local) View.VISIBLE else View.INVISIBLE
        seek.enabledSeek = !bt
        when (src) {
            Src.YT -> {
                // what YouTube's app plays, as its own session tells it
                val s = MediaMonitor.app(YtMusic.players)
                ytHas = s != null
                ytDurationMs = s?.durationMs ?: 0L
                ytPlaying = s?.playing == true
                sourceIcon(R.drawable.ic_music)
                songChanged("yt:${s?.title}|${s?.artist}")
                // Every publish of the app's session (play, pause, buffering, another app's news) used to bring a new
                // Bitmap for the same cover, and each one crossfaded the cover and blinked the backdrop: a new picture
                // only when it is another picture.
                val cover = s?.art
                if (lastArtKey != YT_ART) { ytCover = cover; setArt(cover, YT_ART) }
                else if (cover !== ytCover) {
                    val same = cover != null && ytCover?.let { sameCover(it, cover) } == true
                    ytCover = cover
                    if (!same) setArt(cover, YT_ART)
                }
                if (s == null) names(getString(R.string.yt_idle)) else names(s.title, s.artist, s.album)
                playState(ytPlaying, on = s != null)
            }
            Src.BT -> {
                sourceIcon(R.drawable.ic_bluetooth)
                val linked = BtMusic.available && BtMusic.connected
                songChanged("bt:${BtMusic.title}|${BtMusic.artist}")
                btArt()
                val phone = if (BtMusic.phone.isBlank()) getString(R.string.music_bt_phone_connected)
                    else getString(R.string.music_bt_connected, BtMusic.phone)
                // (where the song is cannot be shown until it is known: its length goes beside the artist meanwhile)
                val d = BtMusic.durationSec * 1000L
                val by = if (!BtMusic.positionKnown && d > 0) join(BtMusic.artist, clockText(d)) else BtMusic.artist
                when {
                    !linked -> names(getString(R.string.music_bt_not_connected), hint = true)
                    BtMusic.title.isBlank() -> names(getString(R.string.music_bt_idle), phone = phone)
                    else -> names(BtMusic.title, by, phone = phone)
                }
                btSettings.visibility = if (linked) View.GONE else View.VISIBLE
                playState(BtMusic.playing, on = linked)
            }
            Src.LOCAL -> {
                sourceIcon(R.drawable.ic_music)
                val t = Player.current
                songChanged("lib:${t?.id}")
                if (t == null) {
                    names(getString(R.string.media_nothing))
                    if (lastArtKey != NO_ART) setArt(null, NO_ART)
                } else {
                    names(t.title, t.artist.ifBlank { getString(R.string.music_unknown_artist) }, t.album)
                    if (lastArtKey != t.albumId) {
                        lastArtKey = t.albumId
                        ArtLoader.get(this, t, 600) { b -> if (Player.current?.albumId == t.albumId && src == Src.LOCAL) setArt(b, t.albumId) }
                    }
                }
                repeatBtn.contentDescription = Player.repeat.name
                // (play also starts the library from its first song when nothing was played yet)
                playState(Player.playing, on = t != null || Library.tracks.isNotEmpty())
            }
        }
        if (!bt) btSettings.visibility = View.GONE
        syncBar()
        paintToggles()
        refreshLyrics()
    }

    /** Two pictures of one cover, the same picture: compared once, when a session sends a new copy of it. */
    private fun sameCover(a: Bitmap, b: Bitmap): Boolean =
        try { a.width == b.width && a.height == b.height && a.sameAs(b) } catch (_: Throwable) { false }

    private var shownSourceIcon = 0

    /** The placeholder's icon under the cover (only set when it changes: a new drawable each second was garbage). */
    private fun sourceIcon(res: Int) {
        if (res == shownSourceIcon) return
        shownSourceIcon = res
        artIcon.setImageResource(res)
    }

    /**
     * Bluetooth: every new song arrives without its cover (it is looked up by name, which takes a moment). The cover
     * before stays, dimmed, for up to [ART_WAIT_MS], instead of flashing the empty placeholder on every song; the
     * new one replaces it as soon as it comes.
     */
    private fun btArt() {
        val cover = BtMusic.art
        if (lastArtKey != BT_ART) { cancelArtWait(); btCover = cover; setArt(cover, BT_ART); return }
        if (cover === btCover) return
        btCover = cover
        if (cover == null && shownCover != null && BtMusic.title.isNotBlank()) {
            art.fadeTo(0.45f, Motion.SHORT)
            root.removeCallbacks(artWait)
            root.postDelayed(artWait, ART_WAIT_MS)
            artWaiting = true
        } else setArt(cover, BT_ART)
    }

    private val artWait = Runnable { artWaiting = false; if (bt && lastArtKey == BT_ART) { btCover = BtMusic.art; setArt(btCover, BT_ART) } }
    /** [artWait] is posted and has not run. */
    private var artWaiting = false

    private fun cancelArtWait() {
        artWaiting = false
        root.removeCallbacks(artWait)
    }

    // the song's names as its source tells them; how they are laid out depends on the layout (paintNames)
    private var nTitle = ""
    private var nArtist = ""
    private var nAlbum = ""
    private var nPhone = ""
    private var nHint = false

    private fun names(title: String, artist: String = "", album: String = "", phone: String = "", hint: Boolean = false) {
        nTitle = title; nArtist = artist; nAlbum = album; nPhone = phone; nHint = hint
        paintNames()
    }

    private fun join(a: String, b: String) = listOf(a, b).filter { it.isNotBlank() }.joinToString("  ·  ")

    /**
     * Beside the library (or the lyrics) the names take two lines, the album joined to the artist, so the cover keeps
     * the room; the song alone on the screen (Bluetooth) has room for a third, the phone it comes from. Without a
     * phone the second line says how to get one.
     */
    private fun paintNames() {
        if (!::title.isInitialized) return
        val wide = bt && !lyricsOn
        title.setTextIfChanged(nTitle)
        val second = when {
            nHint -> getString(R.string.music_bt_hint)
            wide -> nArtist
            else -> join(nArtist, nAlbum)
        }
        val lines = if (nHint) 2 else 1
        if (artist.maxLines != lines) artist.maxLines = lines
        artist.setTextIfChanged(second)
        val third = if (wide && !nHint) nPhone.ifBlank { nAlbum } else ""
        album.setTextIfChanged(third)
        album.visibility = if (third.isEmpty()) View.GONE else View.VISIBLE
    }

    /** The small bar under the large lyrics says what the song's side says. */
    private fun syncBar() {
        fullTitle.setTextIfChanged(nTitle)
        fullArtist.setTextIfChanged(if (nHint) "" else nArtist)
        fullArtist.visibility = if (fullArtist.text.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    /** Playing or not, and whether there is anything for the buttons to do. */
    private fun playState(playing: Boolean, on: Boolean) {
        playIcon.setPlaying(playing)
        fullPlayIcon.setPlaying(playing)
        breathe(playing)
        setTransport(on)
    }

    private fun nowPlaying() = when (src) { Src.BT -> BtMusic.playing; Src.YT -> ytPlaying; Src.LOCAL -> Player.playing }

    private var transportOn = true
    private var transportSet = false

    /**
     * Previous, play and next with nothing to steer (no phone connected, no YouTube session, no music on the unit):
     * dimmed and not taking taps, instead of looking ready and doing nothing.
     */
    private fun setTransport(on: Boolean) {
        if (transportSet && on == transportOn) return
        val animate = transportSet && root.isShown
        transportSet = true
        transportOn = on
        for (v in listOf(prevBtn, playBtn, nextBtn, fullPrev, fullPlay, fullNext)) {
            v.isEnabled = on
            v.isClickable = on
            if (animate) v.fadeTo(if (on) 1f else DISABLED_ALPHA) else { v.animate().cancel(); v.alpha = if (on) 1f else DISABLED_ALPHA }
        }
    }

    /**
     * The cover breathes out a little while the music is paused, and back in when it plays: the largest thing on the
     * screen says at a glance whether music plays. Its slot is scaled, not the cover (LayoutMove and the flight to the
     * lyrics' bar move the cover itself).
     */
    private val breath = Spring(1f, 380f, 0.78f) { artSlot.scaleX = it; artSlot.scaleY = it }
    private var breathSet = false

    private fun breathe(playing: Boolean) {
        val to = if (playing) 1f else PAUSED_SCALE
        if (!breathSet || !root.isShown) { breathSet = true; breath.snapTo(to); return }
        if (breath.target != to) breath.animateTo(to)
    }

    /** A new song: its title and names come in with a little movement (not on every refresh of the same song). */
    private fun songChanged(key: String) {
        val first = shownSong == null
        if (key == shownSong) return
        shownSong = key
        seekTarget = -1L
        if (first) return
        songMovesUntil = SystemClock.uptimeMillis() + Motion.LONG
        // (only what is on the screen: the other set of names is out of sight)
        if (lyricsFull) {
            fullTitle.riseIn(8f, 0L, Motion.MEDIUM)
            fullArtist.riseIn(8f, 50L, Motion.MEDIUM)
            return
        }
        title.riseIn(10f, 0L, Motion.MEDIUM)
        artist.riseIn(10f, 50L, Motion.MEDIUM)
        if (album.visibility == View.VISIBLE) album.riseIn(10f, 90L, Motion.MEDIUM)
    }

    /** The direction of the last skip (-1 previous, +1 next) and when it was pressed, for the next cover's entrance. */
    private var skipDir = 0
    private var skipAt = 0L

    /**
     * Which way a new cover comes in: the way the owner skipped (previous / next, as the buttons sit: left to right in
     * every language), forward when the unit's own player went on by itself, and in place (0) when the phone or
     * YouTube's app changed the song without a press here.
     */
    private fun takeSkip(): Int {
        val d = when {
            SystemClock.uptimeMillis() - skipAt < SKIP_MEMORY_MS -> skipDir
            src == Src.LOCAL -> 1
            else -> 0
        }
        skipAt = 0L
        return d
    }

    private fun setArt(b: Bitmap?, key: Long) {
        // a new picture on a screen that already showed one (the keys are set before a cover loads: not those)
        val changed = b !== shownCover && coverShown
        val sameSong = artSong == shownSong
        artSong = shownSong
        shownCover = b
        coverShown = true
        lastArtKey = key
        cancelArtWait()
        val next = b?.let { BitmapDrawable(resources, it) }
        when {
            !changed -> setCover(next)
            sameSong -> swapCover(next, null) // (a better copy of the same song's cover: a plain crossfade)
            else -> swapCover(next, takeSkip())
        }
        artIcon.visibility = if (b == null) View.VISIBLE else View.GONE
        // the same cover in the small bar under the large lyrics
        fullArt.setImageBitmap(b)
        if (changed && lyricsFull) { fullArt.alpha = 0f; fullArt.fadeTo(1f, Motion.MEDIUM) }
        // its colours and the backdrop: worked out off the main thread (the first at once: there is nothing to fade from)
        val t = Backdrop.Theme(Palette.dark, Palette.bg, Palette.card, Palette.accent, Palette.accentFg,
            resources.displayMetrics.widthPixels.toFloat() / resources.displayMetrics.heightPixels.coerceAtLeast(1))
        val g = ++lookGen
        if (!changed) applyLook(Backdrop.look(b, t), animate = false)
        else Backdrop.lookAsync(b, t) { l -> if (g == lookGen && !isDestroyed) applyLook(l, animate = true) }
    }

    private var lookGen = 0

    private fun setCover(d: Drawable?) {
        art.animate().cancel(); artOld.animate().cancel()
        art.setImageDrawable(d)
        art.alpha = 1f; art.translationX = 0f; art.scaleX = 1f; art.scaleY = 1f
        artOld.setImageDrawable(null)
        artOld.visibility = View.INVISIBLE
    }

    /**
     * A new cover over the old one. [dir] ±1: it comes in from that side, fast and settling, while the old one leaves
     * the other way, quicker; 0: it settles in place from a little larger; null: a plain crossfade. Only the two
     * pictures move, inside the cover's box (which LayoutMove and the flight own), clipped to its corners.
     */
    private fun swapCover(next: Drawable?, dir: Int?) {
        val d = resources.displayMetrics.density
        art.animate().cancel(); artOld.animate().cancel()
        // the old picture, from wherever it was (a swap during a swap, a dimmed Bluetooth cover)
        artOld.setImageDrawable(art.drawable)
        artOld.alpha = art.alpha; artOld.translationX = art.translationX; artOld.scaleX = art.scaleX; artOld.scaleY = art.scaleY
        artOld.visibility = View.VISIBLE
        art.setImageDrawable(next)
        art.alpha = 0f
        val clear = { artOld.setImageDrawable(null); artOld.visibility = View.INVISIBLE; artOld.translationX = 0f; artOld.scaleX = 1f; artOld.scaleY = 1f }
        if (dir == null) {
            art.translationX = 0f; art.scaleX = 1f; art.scaleY = 1f
            art.anim(Motion.MEDIUM, Motion.STANDARD).alpha(1f).withEndAction(clear).start()
            return
        }
        art.translationX = COVER_SLIDE_DP * d * dir
        val from = if (dir == 0) 1.04f else 1f
        art.scaleX = from; art.scaleY = from
        art.anim(Motion.MEDIUM, Motion.EMPHASIZED).alpha(1f).translationX(0f).scaleX(1f).scaleY(1f).start()
        artOld.anim(Motion.SHORT, Motion.EXIT).alpha(0f).translationX(-COVER_SLIDE_DP / 2 * d * dir).withEndAction(clear).start()
    }

    // the screen's backdrop: the window draws it (see Backdrop)
    private var back: BitmapDrawable? = null

    private fun applyLook(l: Backdrop.Look, animate: Boolean) {
        val next = BitmapDrawable(resources, l.back).apply { isFilterBitmap = true }
        val old = back
        back = next
        if (!animate || old == null) window.setBackgroundDrawable(next)
        else {
            // the new backdrop over the old one (both opaque: the old one needs no fading of its own)
            val fade = TransitionDrawable(arrayOf(old, next)).apply { isCrossFadeEnabled = false }
            window.setBackgroundDrawable(fade)
            fade.startTransition(Motion.AMBIENT.toInt())
            root.postDelayed({ if (back === next) window.setBackgroundDrawable(next) }, Motion.AMBIENT + 40)
        }
        accentTo(l.fill, l.text, animate)
        // the cover's colour in its shadow: it glows on the background
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            val glow = if (shownCover != null) l.fill else Palette.shadow
            artBox.outlineSpotShadowColor = glow
            artBox.outlineAmbientShadowColor = glow
            playBtn.outlineSpotShadowColor = l.fill
            playBtn.outlineAmbientShadowColor = l.fill
        }
    }

    private var accentAnim: ValueAnimator? = null

    /** Everything in the cover's colour turns to the new one together, in step with the new cover coming in. */
    private fun accentTo(f: Int, t: Int, animate: Boolean) {
        accentAnim?.let { it.removeAllListeners(); it.cancel() }
        accentAnim = null
        if (!animate || !root.isShown) { paintAccent(f, t); repaintRows(); return }
        val f0 = fill
        val t0 = fillText
        if (f0 == f && t0 == t) return
        accentAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Motion.MEDIUM
            interpolator = Motion.STANDARD
            addUpdateListener { val k = it.animatedValue as Float; paintAccent(Palette.mix(f0, f, k), Palette.mix(t0, t, k)) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { paintAccent(f, t); repaintRows() }
            })
            start()
        }
    }

    // fills in the cover's colour, kept to be recoloured in place (a new drawable a frame was garbage)
    private val playTint = Tint { Shapes.oval(0) }
    private val fullPlayTint = Tint { Shapes.oval(0) }
    private val lyricsOnTint = Tint { Shapes.oval(0) }
    private val playAllTint = Tint { Shapes.pill(0) }

    private fun paintAccent(f: Int, t: Int) {
        fill = f
        fillText = t
        seek.color = f
        fullLine.color = f
        val pressed = Palette.mix(f, 0xFF000000.toInt(), 0.2f)
        playTint.set(f, pressed)
        fullPlayTint.set(f, pressed)
        lyricsOnTint.set(f, pressed)
        val on = Palette.onColor(f)
        playIcon.color = on
        fullPlayIcon.color = on
        source.indicator = f
        tabs.indicator = Palette.withAlpha(f, if (Palette.dark) 0.32f else 0.24f)
        crumbChevron.setColorFilter(t)
        playAllTint.set(Palette.withAlpha(f, 0.18f), Palette.withAlpha(f, 0.28f))
        playAllIcon.setColorFilter(t)
        playAllText.setTextColor(t)
        lyricsView.accent(f, t)
        lyricsBig.accent(f, t)
        paintToggles()
    }

    /** The rows that show the cover's colour (the playing one) take the new one. */
    private fun repaintRows() {
        if (::listView.isInitialized && listView.adapter === tracks) tracks.notifyDataSetChanged()
    }

    // what the toggles were last painted with: only a change repaints them
    private var paintedShuffle = 0
    private var paintedRepeat = 0
    private var paintedOne: Boolean? = null
    private var paintedOneFill = 0
    private var paintedLyrics = 0

    /** Shuffle, repeat and the lyrics button: in the cover's colour when on. */
    private fun paintToggles() {
        val sh = if (Player.shuffle) fillText else Palette.text2
        if (sh != paintedShuffle) { paintedShuffle = sh; shuffleBtn.setColorFilter(sh) }
        val rp = if (Player.repeat != Player.Repeat.OFF) fillText else Palette.text2
        if (rp != paintedRepeat) { paintedRepeat = rp; repeatBtn.setColorFilter(rp) }
        if (fill != paintedOneFill) { paintedOneFill = fill; repeatOneBg.setColor(fill); repeatOne.setTextColor(Palette.onColor(fill)) }
        val one = Player.repeat == Player.Repeat.ONE
        if (one != paintedOne) {
            val first = paintedOne == null
            paintedOne = one
            when {
                first -> repeatOne.visibility = if (one) View.VISIBLE else View.GONE
                one -> repeatOne.enter(scaleFrom = 0.4f, dur = Motion.SHORT)
                else -> repeatOne.exit(scaleTo = 0.4f, dur = Motion.XSHORT) { repeatOne.visibility = View.GONE }
            }
        }
        val ly = if (lyricsWanted) Palette.onColor(fill) else Palette.text
        if (ly != paintedLyrics) { paintedLyrics = ly; lyricsIcon.setColorFilter(ly) }
        val bg = if (lyricsWanted) lyricsOnTint.drawable else lyricsOffBg
        if (lyricsBtn.background !== bg) lyricsBtn.background = bg
    }

    private fun paintTabs() {
        if (tabs.selected != tab.ordinal) tabs.select(tab.ordinal)
        if (source.selected != src.ordinal) source.select(src.ordinal)
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
            cancelArtWait()
            lastArtKey = Long.MIN_VALUE
            seekTarget = -1L
            skipAt = 0L
            showNowPlaying()
            showList()
        }
    }

    private fun transport(action: Int) {
        if (action != 1) { skipDir = if (action == 0) -1 else 1; skipAt = SystemClock.uptimeMillis() }
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
        // (a song picked from the list is no step forward or back: its cover settles in place)
        skipDir = 0; skipAt = SystemClock.uptimeMillis()
        Player.play(list, i)
    }

    // ------------------------------------------------------------------------------------------ YouTube
    private fun buildYt(): View {
        ytBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(Space.M.dp, Space.M.dp, Space.M.dp, 0); visibility = View.GONE }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Shapes.pill(Palette.withAlpha(Palette.card3, 0.75f))
            setPaddingRelative(Space.L.dp, 0, 0, 0)
        }
        // the search icon, or a spinner while a search is on its way (the phone's internet can be slow)
        val glyph = FrameLayout(this)
        ytSearchIcon = iconView(R.drawable.ic_search, 24, Palette.text2)
        glyph.addView(ytSearchIcon, flp(24.dp, 24.dp, Gravity.CENTER))
        ytSpinner = spinner().apply { visibility = View.GONE }
        glyph.addView(ytSpinner, flp(22.dp, 22.dp, Gravity.CENTER))
        bar.addView(glyph, lp(24.dp, 24.dp))
        ytInput = android.widget.EditText(this).apply {
            setHint(R.string.yt_search_hint)
            setHintTextColor(Palette.text3)
            setTextColor(Palette.text)
            textSize = Type.BODY.sp
            typeface = Fonts.get(Type.BODY.weight)
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            background = null
            setPadding(Space.XS.dp, 0, Space.XS.dp, 0)
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
        bar.addView(ytInput, lp(0, MATCH, 1f).apply { marginStart = Space.S.dp })
        bar.addView(FrameLayout(this).apply {
            background = Shapes.ghostOval()
            isClickable = true
            pressScale(Motion.PRESS_ICON)
            contentDescription = getString(R.string.yt_clear)
            setOnClickListener { ytInput.setText(""); ytHideKeyboard() }
            addView(iconView(R.drawable.ic_close, 22, Palette.text2).apply { layoutParams = flp(22.dp, 22.dp, Gravity.CENTER) })
        }, lp(Touch.MIN.dp, Touch.MIN.dp))
        ytBox.addView(bar, lp(MATCH, Touch.MIN.dp))

        val body = FrameLayout(this)
        ytList = ListView(this).apply {
            libraryStyle()
            adapter = object : BaseAdapter() {
                override fun getCount() = ytSongs.size
                override fun getItem(i: Int) = ytSongs[i]
                override fun getItemId(i: Int) = i.toLong()
                override fun getView(i: Int, convert: View?, parent: ViewGroup): View {
                    val row = (convert as? YtRow) ?: YtRow(this@MusicActivity)
                    row.bind(ytSongs[i])
                    return row
                }
            }
        }
        body.addView(ytList, flp(MATCH, MATCH))
        ytNote = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(Space.XXL.dp, 0, Space.XXL.dp, Space.XL.dp) }
        ytNote.addView(iconView(R.drawable.ic_music_list, 40, Palette.text3), lp(40.dp, 40.dp))
        ytNoteText = label(Type.BODY, Palette.text2, lines = 5, gravity = Gravity.CENTER)
        ytNote.addView(ytNoteText, lp(MATCH, WRAP).apply { topMargin = Space.M.dp })
        ytNoteBtn = ABtn(this).apply {
            kind = ABtn.Kind.PRIMARY
            setText(R.string.yt_get_app)
            setOnClickListener { YtMusic.openStore(this@MusicActivity) }
        }
        ytNote.addView(ytNoteBtn, lp(WRAP, WRAP).apply { topMargin = Space.L.dp })
        body.addView(ytNote, flp(MATCH, MATCH))
        ytBox.addView(body, lp(MATCH, 0, 1f).apply { topMargin = Space.S.dp })
        ytShow()
        return ytBox
    }

    private fun spinner() = ProgressBar(this).apply {
        isIndeterminate = true
        indeterminateTintList = ColorStateList.valueOf(Palette.text3)
    }

    private val ytSearchLater = Runnable { ytSearch(ytInput.text.toString()) }

    /** Typed: searched once the typing rests (the catalogue is asked over the phone's internet). */
    private fun ytTyped(q: String) {
        ytInput.removeCallbacks(ytSearchLater)
        if (q.trim().length < 2) {
            ytAsked++ // (an answer still on its way is for a query that is gone)
            ytBusy(false)
            ytSongs = emptyList()
            ytShow()
        } else ytInput.postDelayed(ytSearchLater, 700L)
    }

    private fun ytSearch(q: String) {
        val asked = ++ytAsked
        ytBusy(true)
        if (ytSongs.isEmpty()) ytShow(getString(R.string.yt_searching))
        YtMusic.search(q) { songs ->
            if (asked != ytAsked || isDestroyed) return@search
            ytBusy(false)
            ytSongs = songs.orEmpty()
            ytShow(if (songs == null) getString(R.string.yt_failed) else null)
            if (songs != null) ytList.setSelection(0)
        }
    }

    /** A search on its way: the spinner in the bar, and the results there are step back until the new ones come. */
    private fun ytBusy(on: Boolean) {
        ytSearchIcon.visibility = if (on) View.INVISIBLE else View.VISIBLE
        ytSpinner.visibility = if (on) View.VISIBLE else View.GONE
        if (on) { if (ytSongs.isNotEmpty()) ytList.fadeTo(0.5f, Motion.XSHORT) } else if (ytList.alpha < 1f) ytList.fadeTo(1f)
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
        ytNoteText.setTextIfChanged(text.orEmpty())
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
            // (the store comes over this screen right after: the message must outlive it)
            Snack.show(this, R.string.yt_no_app, long = true, outlives = true)
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

    /**
     * A picture for a row or a cell: at once when it is at hand, faded in when it comes later (late covers popping in
     * one by one across a grid looked busy). [still]: the row still shows what asked for it (rows are recycled).
     */
    private fun picture(img: ImageView, load: (done: (Bitmap?) -> Unit) -> Unit, still: () -> Boolean) {
        img.animate().cancel()
        img.alpha = 1f
        img.setImageDrawable(null)
        var sync = true
        load { b ->
            if (b != null && still()) {
                img.setImageBitmap(b)
                if (!sync) { img.alpha = 0f; img.fadeTo(1f) }
            }
        }
        sync = false
    }

    /** A song row's parts, shared by the library's songs and YouTube's results (recycled by their lists). */
    private open inner class SongRow(ctx: Context) : LinearLayout(ctx) {
        protected val img = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        protected val t1 = ctx.label(Type.LABEL, Palette.text)
        protected val t2 = ctx.label(Type.FOOTNOTE, Palette.text2)
        protected val dur = ctx.label(Type.CAPTION, Palette.text3).apply { fontFeatureSettings = "tnum" }
        protected val ghostBg = Shapes.ghost(Radius.L)

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 76.dp
            setPaddingRelative(Space.S.dp, Space.S.dp, Space.L.dp, Space.S.dp)
            isClickable = true
            background = ghostBg
            pressScale(Motion.PRESS_SURFACE)
            layoutParams = AbsListView.LayoutParams(MATCH, WRAP)
            val box = FrameLayout(ctx).apply { background = Shapes.rect(Palette.card2, Radius.S); roundedClip(Radius.S) }
            box.addView(ctx.iconView(R.drawable.ic_music, 22, Palette.text3).apply { layoutParams = flp(22.dp, 22.dp, Gravity.CENTER) })
            box.addView(img, flp(MATCH, MATCH))
            addView(box, lp(THUMB_DP.dp, THUMB_DP.dp))
            val col = LinearLayout(ctx).apply { orientation = VERTICAL }
            col.addView(t1, lp(MATCH, WRAP))
            col.addView(t2, lp(MATCH, WRAP).apply { topMargin = Space.XXS.dp })
            addView(col, lp(0, WRAP, 1f).apply { marginStart = Space.L.dp; marginEnd = Space.S.dp })
            addView(dur, lp(WRAP, WRAP))
        }
    }

    /** A found song (a late picture for a recycled row is ignored). */
    private inner class YtRow(ctx: Context) : SongRow(ctx) {
        private var song: YtMusic.Song? = null

        init { setOnClickListener { song?.let { ytPlay(it) } } }

        fun bind(s: YtMusic.Song) {
            val again = song?.thumb == s.thumb && img.drawable != null
            song = s
            t1.setTextIfChanged(s.title)
            t2.setTextIfChanged(join(s.artist, s.album))
            dur.setTextIfChanged(if (s.sec > 0) clockText(s.sec * 1000L) else "")
            if (!again) picture(img, { done -> YtMusic.thumb(s.thumb, done) }) { song?.thumb == s.thumb }
        }
    }

    // ------------------------------------------------------------------------------------------ library
    /** One list (songs, a group's songs, artists, folders) and one grid (albums) for the screen's life. */
    private lateinit var listView: ListView
    private lateinit var gridView: GridView
    private val tracks = TracksAdapter()
    private val groups = GroupsAdapter()
    private val albums = AlbumsAdapter()
    /** The list on the screen (tab and group), and where each list was left (first row shown, its top). */
    private var shownList: String? = null
    private val listSpots = HashMap<String, IntArray>()
    /** The song the list last saw playing (see [followPlaying]). */
    private var followedId: Long? = null

    /**
     * Bluetooth mode has no library to show: the song takes the whole screen, the cover beside its title and controls.
     * The unit's own music keeps the cover above them, beside the library.
     */
    /** The layout [applyLayout] last set (wide: Bluetooth's); null before the first. */
    private var laidWide: Boolean? = null

    private fun applyLayout() {
        if (!::stage.isInitialized) return
        val wide = bt && !lyricsOn // (the lyrics need the panel)
        // (only a change lays the column out again: this runs on every reload of the library, every few seconds while
        // a USB stick is being indexed)
        if (wide == laidWide) return
        laidWide = wide
        panel.visibility = if (wide) View.GONE else View.VISIBLE
        // (set again, not only changed: a start / end margin is turned into left / right when the params are set, so
        // a changed one was not seen, and the gutter stayed 0 after the Bluetooth layout)
        left.layoutParams = (left.layoutParams as LinearLayout.LayoutParams).apply {
            weight = if (wide) 1f else 0.46f
            marginEnd = if (wide) 0 else Space.GUTTER.dp
        }
        stage.orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        stage.gravity = if (wide) Gravity.CENTER_VERTICAL else Gravity.NO_GRAVITY
        artSlot.layoutParams = if (wide) LinearLayout.LayoutParams(0, MATCH, 0.44f).apply { topMargin = Space.L.dp; bottomMargin = Space.L.dp }
            else LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = Space.M.dp; bottomMargin = Space.M.dp }
        info.layoutParams = if (wide) LinearLayout.LayoutParams(0, WRAP, 0.56f).apply { marginStart = 40.dp; marginEnd = Space.L.dp }
            else LinearLayout.LayoutParams(MATCH, WRAP)
        title.textSize = if (wide) TITLE_WIDE_SP else TITLE_SP
        title.maxLines = if (wide) 2 else 1
        artist.textRole(if (wide) Type.TITLE else Type.LABEL)
        left.requestLayout()
    }

    /** The groups of [t] as the library has them now (null for the songs). */
    private fun groupsOf(t: Tab): List<Group>? = when (t) {
        Tab.SONGS -> null
        Tab.ALBUMS -> Library.albums
        Tab.ARTISTS -> Library.artists
        Tab.FOLDERS -> Library.folders
    }

    /**
     * The panel's content for the source, and the library's list for the tab and group. A list keeps its place when its
     * songs change (a USB stick still being indexed reloads every few seconds) and when the owner comes back to it
     * from another tab or a group; [nav] is how a new list comes in.
     */
    private fun showList(nav: Nav = Nav.NONE) {
        if (!::listHolder.isInitialized) return
        applyLayout()
        // Bluetooth: the library makes way for the song (see applyLayout); the lyrics, when shown, take the panel
        libraryBox.visibility = if (src != Src.LOCAL || lyricsOn) View.GONE else View.VISIBLE
        ytBox.visibility = if (yt && !lyricsOn) View.VISIBLE else View.GONE
        lyricsView.visibility = if (lyricsOn) View.VISIBLE else View.GONE
        expandBtn.visibility = if (lyricsOn) View.VISIBLE else View.GONE
        paintTabs()
        // (a reload makes new groups: the one open is found again by its key, or left when it is gone)
        group = group?.let { g -> groupsOf(tab)?.firstOrNull { it.key == g.key } }
        crumb.visibility = if (group != null) View.VISIBLE else View.GONE
        crumbText.setTextIfChanged(group?.title.orEmpty())
        val state = when {
            !Library.hasPermission(this) -> 0
            !Library.loaded -> 1
            Library.tracks.isEmpty() -> 2
            else -> -1
        }
        if (state >= 0) {
            rememberSpot()
            shownList = null
            listView.visibility = View.GONE
            gridView.visibility = View.GONE
            when (state) {
                0 -> showEmpty(getString(R.string.music_permission), getString(R.string.music_allow)) { askPermission() }
                1 -> showEmpty(getString(R.string.music_loading), null) {}
                else -> showEmpty(getString(R.string.music_empty), getString(R.string.music_bluetooth)) { selectSource(Src.BT) }
            }
            return
        }
        empty.visibility = View.GONE
        val g = group
        val key = "${tab.name}|${g?.key}"
        val grid = g == null && tab == Tab.ALBUMS
        val fresh = key != shownList
        if (fresh) rememberSpot()
        when {
            g != null -> tracks.items = g.tracks
            tab == Tab.SONGS -> tracks.items = Library.tracks
            tab == Tab.ALBUMS -> albums.items = Library.albums
            tab == Tab.ARTISTS -> groups.set(Library.artists, R.drawable.ic_user)
            else -> groups.set(Library.folders, R.drawable.ic_folder)
        }
        val adapter: BaseAdapter = when {
            grid -> albums
            g != null || tab == Tab.SONGS -> tracks
            else -> groups
        }
        if (!fresh) { adapter.notifyDataSetChanged(); return } // (the same list, new songs: it stays where it is)
        listView.visibility = if (grid) View.GONE else View.VISIBLE
        gridView.visibility = if (grid) View.VISIBLE else View.GONE
        if (grid) gridView.adapter = albums else listView.adapter = adapter
        val spot = listSpots[key]
        when {
            spot != null && grid -> gridView.setSelection(spot[0])
            spot != null -> listView.setSelectionFromTop(spot[0], spot[1])
            adapter === tracks -> Player.current?.let { cur ->
                // the first time: the playing song in sight
                val i = tracks.items.indexOfFirst { it.id == cur.id }
                if (i > 3) listView.setSelectionFromTop(i - 2, 0)
            }
        }
        followedId = Player.current?.id
        shownList = key
        moveIn(nav)
    }

    /** Where the list on the screen was left, for when the owner comes back to it. */
    private fun rememberSpot() {
        val key = shownList ?: return
        val v: AbsListView = if (gridView.visibility == View.VISIBLE) gridView else listView
        if (v.childCount == 0) return
        listSpots[key] = intArrayOf(v.firstVisiblePosition, v.getChildAt(0).top)
    }

    /**
     * A list coming in: another tab fades in place; a group comes from the end side, as the next level of the
     * library, and Back brings the level before from the start side (mirrored in Arabic).
     */
    private fun moveIn(nav: Nav) {
        val toward = if (rtl) -1f else 1f
        when (nav) {
            Nav.NONE -> {}
            Nav.TAB -> {
                listHolder.animate().cancel()
                listHolder.translationX = 0f
                listHolder.alpha = 0f
                listHolder.fadeTo(1f)
            }
            Nav.INTO -> { listHolder.enter(dx = 32f * toward); crumb.riseIn(6f) }
            Nav.BACK -> listHolder.enter(dx = -32f * toward)
        }
    }

    /**
     * The song changed while the owner was looking at the one playing: the list goes on with it (the next song of an
     * album that played on was otherwise left below the edge). Not when they were looking elsewhere in the list.
     */
    private fun followPlaying() {
        val id = Player.current?.id
        val was = followedId
        if (id == was) return
        followedId = id
        if (id == null || was == null || src != Src.LOCAL || listView.adapter !== tracks || !listView.isShown) return
        val items = tracks.items
        val first = listView.firstVisiblePosition
        val last = listView.lastVisiblePosition
        val wi = items.indexOfFirst { it.id == was }
        val ni = items.indexOfFirst { it.id == id }
        if (wi in first..last && ni >= 0 && ni !in first..last) listView.smoothScrollToPosition(ni)
    }

    private fun openGroup(g: Group) {
        group = g
        showList(Nav.INTO)
    }

    /** What stands in for the list: a line, and its button ([button] null: a spinner, something is on its way). */
    private fun showEmpty(text: String, button: String?, onClick: () -> Unit) {
        empty.visibility = View.VISIBLE
        emptyText.setTextIfChanged(text)
        emptyBtn.visibility = if (button != null) View.VISIBLE else View.GONE
        emptySpinner.visibility = if (button != null) View.GONE else View.VISIBLE
        if (button != null) emptyBtn.setTextIfChanged(button)
        emptyBtn.setOnClickListener { onClick() }
    }

    private fun AbsListView.libraryStyle() {
        selector = ColorDrawable(0)
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        clipToPadding = false
        if (this is ListView) { divider = null; setPadding(0, Space.XS.dp, 0, Space.M.dp) }
    }

    private inner class TracksAdapter : BaseAdapter() {
        var items: List<Track> = emptyList()
        override fun getCount() = items.size
        override fun getItem(i: Int) = items[i]
        override fun getItemId(i: Int) = items[i].id
        override fun hasStableIds() = true
        override fun getView(i: Int, convert: View?, parent: ViewGroup): View {
            val row = (convert as? TrackRow) ?: TrackRow(this@MusicActivity)
            row.bind(items, i, items[i].id == Player.current?.id && src == Src.LOCAL)
            return row
        }
    }

    private inner class GroupsAdapter : BaseAdapter() {
        var items: List<Group> = emptyList()
        private var icon = R.drawable.ic_user
        fun set(g: List<Group>, ic: Int) { items = g; icon = ic }
        override fun getCount() = items.size
        override fun getItem(i: Int) = items[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, convert: View?, parent: ViewGroup): View {
            val row = (convert as? GroupRow) ?: GroupRow(this@MusicActivity)
            row.bind(items[i], icon)
            return row
        }
    }

    private inner class AlbumsAdapter : BaseAdapter() {
        var items: List<Group> = emptyList()
        override fun getCount() = items.size
        override fun getItem(i: Int) = items[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, convert: View?, parent: ViewGroup): View {
            val cell = (convert as? AlbumCell) ?: AlbumCell(this@MusicActivity)
            val gv = parent as GridView
            val side = (gv.width - gv.paddingLeft - gv.paddingRight - 2 * Space.M.dp) / 3
            cell.bind(items[i], side.coerceAtLeast(80.dp))
            return cell
        }
    }

    /** Artist / folder row (recycled by the list). */
    private inner class GroupRow(ctx: Context) : LinearLayout(ctx) {
        private val icon = ctx.iconView(R.drawable.ic_user, 24, Palette.text2)
        private val title = ctx.label(Type.LABEL, Palette.text)
        private val sub = ctx.label(Type.FOOTNOTE, Palette.text2)
        private var shownIcon = 0
        private var g: Group? = null

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 76.dp
            setPaddingRelative(Space.M.dp, Space.S.dp, Space.L.dp, Space.S.dp)
            background = Shapes.ghost(Radius.L)
            isClickable = true
            pressScale(Motion.PRESS_SURFACE)
            setOnClickListener { g?.let { openGroup(it) } }
            layoutParams = AbsListView.LayoutParams(MATCH, WRAP)
            val badge = FrameLayout(ctx).apply { background = Shapes.oval(Palette.card2) }
            badge.addView(icon, flp(24.dp, 24.dp, Gravity.CENTER))
            addView(badge, lp(54.dp, 54.dp))
            val col = LinearLayout(ctx).apply { orientation = VERTICAL }
            col.addView(title, lp(MATCH, WRAP))
            col.addView(sub, lp(MATCH, WRAP).apply { topMargin = Space.XXS.dp })
            addView(col, lp(0, WRAP, 1f).apply { marginStart = Space.L.dp })
        }

        fun bind(group: Group, ic: Int) {
            g = group
            if (ic != shownIcon) { shownIcon = ic; icon.setImageResource(ic) }
            title.setTextIfChanged(group.title)
            sub.setTextIfChanged(getString(R.string.music_tracks_count, group.tracks.size))
        }
    }

    /** Album cover + name in the grid (recycled; a late cover for a recycled cell is ignored). */
    private inner class AlbumCell(ctx: Context) : LinearLayout(ctx) {
        private val box = FrameLayout(ctx).apply { background = Shapes.rect(Palette.card2, Radius.M); roundedClip(Radius.M) }
        private val img = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        private val title = ctx.label(Type.BUTTON, Palette.text)
        private val sub = ctx.label(Type.CAPTION, Palette.text2)
        private var key = Long.MIN_VALUE
        private var size = 0
        private var g: Group? = null

        init {
            orientation = VERTICAL
            isClickable = true
            pressScale(Motion.PRESS_SURFACE)
            setOnClickListener { g?.let { openGroup(it) } }
            box.addView(ctx.iconView(R.drawable.ic_disc, 40, Palette.text3).apply { layoutParams = flp(40.dp, 40.dp, Gravity.CENTER) })
            box.addView(img, flp(MATCH, MATCH))
            addView(box, lp(MATCH, 80.dp))
            addView(title, lp(MATCH, WRAP).apply { topMargin = Space.S.dp })
            addView(sub, lp(MATCH, WRAP))
        }

        fun bind(group: Group, side: Int) {
            g = group
            val blp = box.layoutParams
            if (blp.height != side) { blp.height = side; box.layoutParams = blp }
            title.setTextIfChanged(group.title)
            sub.setTextIfChanged(group.subtitle)
            val t = group.tracks.first()
            if (key == t.albumId && size == side && img.drawable != null) return
            key = t.albumId
            size = side
            // (at the size it is shown: a cover decoded at twice that held four times the memory in the cache)
            picture(img, { done -> ArtLoader.get(context, t, side, done) }) { key == t.albumId }
        }
    }

    private inner class TrackRow(ctx: Context) : SongRow(ctx) {
        private val eq = EqualizerView(ctx)
        // the playing row's tint in the cover's colour, kept and recoloured (not a new drawable per bind)
        private val nowFill = Shapes.rect(0, Radius.L)
        private val nowPressed = Shapes.rect(0, Radius.L)
        private val nowBg = Shapes.pressable(nowFill, nowPressed)
        private var nowColour = 0
        private var items: List<Track> = emptyList()
        private var index = 0
        private var key = Long.MIN_VALUE

        init {
            addView(eq, lp(22.dp, 20.dp))
            setOnClickListener { if (index < items.size) playFrom(items, index) }
        }

        fun bind(list: List<Track>, i: Int, now: Boolean) {
            items = list
            index = i
            val t = list[i]
            t1.setTextIfChanged(t.title)
            t1.setTextColor(if (now) fillText else Palette.text)
            t2.setTextIfChanged(t.subtitle)
            dur.setTextIfChanged(t.durationText)
            // the song that is playing: its row tinted with the cover's colour, the bars instead of the duration
            dur.visibility = if (now) View.GONE else View.VISIBLE
            eq.visibility = if (now) View.VISIBLE else View.GONE
            eq.color = fill
            eq.animating = now && Player.playing
            if (now && nowColour != fill) {
                nowColour = fill
                nowFill.setColor(Palette.withAlpha(fill, 0.16f))
                nowPressed.setColor(Palette.withAlpha(fill, 0.26f))
            }
            val bg = if (now) nowBg else ghostBg
            if (background !== bg) background = bg
            if (key == t.albumId && img.drawable != null) return
            key = t.albumId
            picture(img, { done -> ArtLoader.get(context, t, THUMB_DP.dp, done) }) { key == t.albumId }
        }
    }

    // ------------------------------------------------------------------------------------------ UI
    private fun buildUi(): View {
        val root = FrameLayout(this)
        this.root = root
        // (no backdrop views: the window draws the backdrop, see Backdrop)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(Space.GUTTER.dp, Space.M.dp, Space.GUTTER.dp, Space.GUTTER.dp) }

        // ---- now playing
        left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val frost = Palette.withAlpha(Palette.card, if (Palette.dark) 0.55f else 0.75f)
        top.addView(FrameLayout(this).apply {
            background = Shapes.pressable(Shapes.oval(frost), Shapes.oval(Palette.card3))
            isClickable = true
            pressScale(Motion.PRESS_ICON)
            contentDescription = getString(R.string.music_back)
            setOnClickListener { close() }
            addView(iconView(R.drawable.ic_chevron_left, 28, Palette.text).apply {
                layoutParams = flp(28.dp, 28.dp, Gravity.CENTER)
                if (rtl) scaleX = -1f
            })
        }, lp(Touch.MIN.dp, Touch.MIN.dp))
        // (a step smaller than the panel's tabs: beside the two round buttons it ran 15 dp past the column in English)
        source = Segmented(this, listOf(getString(R.string.music_library), getString(R.string.music_bluetooth), getString(R.string.music_youtube)),
            equal = false, textSp = Type.FOOTNOTE.sp) { i ->
            val to = Src.values()[i]
            // (the music files are asked for when the owner chooses them: see onCreate)
            if (to == Src.LOCAL && !Library.hasPermission(this)) askPermission()
            selectSource(to)
        }.apply { trackColor = frost }
        top.addView(View(this), lp(0, 1, 1f))
        lyricsOffBg = Shapes.ghostOval()
        lyricsIcon = iconView(R.drawable.ic_lyrics, 26, Palette.text)
        lyricsBtn = FrameLayout(this).apply {
            isClickable = true
            pressScale(Motion.PRESS_ICON)
            contentDescription = getString(R.string.lyrics_toggle)
            setOnClickListener { toggleLyrics() }
            addView(lyricsIcon, flp(26.dp, 26.dp, Gravity.CENTER))
        }
        top.addView(lyricsBtn, lp(Touch.MIN.dp, Touch.MIN.dp).apply { marginEnd = Space.XS.dp })
        top.addView(source, lp(WRAP, Touch.MIN.dp))
        left.addView(top, lp(MATCH, WRAP))

        artBox = FrameLayout(this).apply {
            background = Shapes.rect(Palette.card2, COVER_RADIUS)
            roundedClip(COVER_RADIUS)
            // A glow in the cover's own colour (applyLook), on both themes: unlike lift()'s black shadow it shows on the
            // dark one. Not larger: a shadow is drawn again whenever the cover moves.
            elevation = Elev.OVERLAY.dp
        }
        artIcon = iconView(R.drawable.ic_music, 72, Palette.text3)
        artBox.addView(artIcon, flp(72.dp, 72.dp, Gravity.CENTER))
        artOld = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.INVISIBLE }
        artBox.addView(artOld, flp(MATCH, MATCH))
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

        title = label(TITLE_SP, Palette.text, Fonts.BOLD, gravity = Gravity.CENTER)
        artist = label(Type.LABEL, Palette.text2, gravity = Gravity.CENTER)
        album = label(Type.BODY, Palette.text3, gravity = Gravity.CENTER).apply { visibility = View.GONE }
        info.addView(title, lp(MATCH, WRAP))
        info.addView(artist, lp(MATCH, WRAP).apply { topMargin = Space.XS.dp })
        info.addView(album, lp(MATCH, WRAP).apply { topMargin = Space.XXS.dp })

        seek = SeekView(this).apply {
            onSeek = { f ->
                if (yt) {
                    val d = ytDurationMs
                    if (d > 0) { val to = (d * f).toLong(); MediaMonitor.appControl(YtMusic.players, MediaMonitor.Do.PLAY, seekToMs = to); holdSeek(to) }
                } else { val to = (Player.duration() * f).toLong(); Player.seekTo(to); holdSeek(to) }
            }
        }
        // (a 52 dp strip to grab, the bar itself thin in its middle; the times sit close under it)
        info.addView(seek, lp(MATCH, 52.dp).apply { topMargin = Space.XS.dp })
        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        // (digits of one width, and both halves as wide: the times neither wobble nor move the row as they count)
        tNow = label(Type.CAPTION, Palette.text2).apply { fontFeatureSettings = "tnum" }
        tTotal = label(Type.CAPTION, Palette.text2, gravity = Gravity.END).apply { fontFeatureSettings = "tnum" }
        times.addView(tNow, lp(0, WRAP, 1f))
        times.addView(tTotal, lp(0, WRAP, 1f))
        // (tucked into the strip's lower edge, below the largest knob: the room goes to the cover)
        info.addView(times, lp(MATCH, WRAP).apply { setMargins(Space.M.dp, -Space.S.dp, Space.M.dp, 0) })

        val ctl = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        shuffleBtn = ImageView(this)
        repeatBtn = ImageView(this)
        ctl.addView(ctlButton(R.drawable.ic_shuffle, 26, shuffleBtn, 0) { Player.setShuffle(!Player.shuffle) }, lp(Touch.MIN.dp, Touch.MIN.dp))
        prevBtn = ctlButton(R.drawable.ic_skip_back, 32, null, R.string.media_prev) { transport(0) }
        ctl.addView(prevBtn, lp(72.dp, 72.dp).apply { marginStart = Space.M.dp })
        playIcon = PlayPauseView(this)
        playBtn = FrameLayout(this).apply {
            background = playTint.drawable
            isClickable = true
            pressScale(Motion.PRESS_CONTROL)
            elevation = Elev.FLOAT.dp // (a glow in the cover's colour, see artBox)
            contentDescription = getString(R.string.media_play)
            setOnClickListener { transport(1) }
            addView(playIcon, flp(40.dp, 40.dp, Gravity.CENTER))
        }
        ctl.addView(playBtn, lp(88.dp, 88.dp).apply { marginStart = Space.M.dp; marginEnd = Space.M.dp })
        nextBtn = ctlButton(R.drawable.ic_skip_fwd, 32, null, R.string.media_next) { transport(2) }
        ctl.addView(nextBtn, lp(72.dp, 72.dp).apply { marginEnd = Space.M.dp })
        repeatOne = label(Type.CAPTION, Palette.text, gravity = Gravity.CENTER).apply {
            text = "1"
            // (the Latin face in every language: the Arabic one's taller lines did not fit the badge, and the digit is
            // the same in both)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            includeFontPadding = false
            background = repeatOneBg
            visibility = View.GONE
        }
        ctl.addView(ctlButton(R.drawable.ic_repeat, 26, repeatBtn, 0) { Player.cycleRepeat() }.apply {
            // (on the icon's upper corner, the side the arrows turn towards)
            addView(repeatOne, flp(16.dp, 16.dp, Gravity.TOP or Gravity.END).apply { setMargins(0, 7.dp, 7.dp, 0) })
        }, lp(Touch.MIN.dp, Touch.MIN.dp))
        info.addView(ctl, lp(MATCH, WRAP).apply { topMargin = Space.S.dp })
        // Bluetooth without a phone: the way to pair or connect one is the screen's main action
        btSettings = ABtn(this).apply {
            kind = ABtn.Kind.PRIMARY
            size = ABtn.Size.L
            setText(R.string.music_bt_settings)
            val ic = getDrawable(R.drawable.ic_bluetooth)?.mutate()?.apply { setTint(Palette.onColor(Palette.accent)); setBounds(0, 0, 22.dp, 22.dp) }
            setCompoundDrawablesRelative(ic, null, null, null)
            compoundDrawablePadding = Space.S.dp
            visibility = View.GONE
            setOnClickListener { openBluetooth(this@MusicActivity) }
        }
        info.addView(btSettings, lp(WRAP, WRAP).apply { topMargin = Space.L.dp })
        row.addView(left, lp(0, MATCH, 0.46f).apply { marginEnd = Space.GUTTER.dp })

        // ---- library (frosted panel over the blurred cover); Bluetooth mode hides it
        panel = FrameLayout(this).apply {
            background = Shapes.rect(Palette.withAlpha(Palette.card, if (Palette.dark) 0.72f else 0.82f), Radius.XL, Palette.glassStroke)
        }
        libraryBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(Space.M.dp, Space.M.dp, Space.M.dp, 0) }
        panel.addView(libraryBox, flp(MATCH, MATCH))
        tabs = Segmented(this, listOf(getString(R.string.music_songs), getString(R.string.music_albums),
            getString(R.string.music_artists), getString(R.string.music_folders)), equal = true) { i ->
            tab = Tab.values()[i]
            group = null
            showList(Nav.TAB)
        }.apply { trackColor = Palette.withAlpha(Palette.card3, 0.75f); onIndicator = Palette.text }
        libraryBox.addView(tabs, lp(MATCH, Touch.MIN.dp))
        // the open album / artist / folder: its name, the way back, and Play all
        crumb = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            minimumHeight = Touch.MIN.dp
            background = Shapes.ghost(Radius.M)
            isClickable = true
            setOnClickListener { group = null; showList(Nav.BACK) }
            setPaddingRelative(Space.XS.dp, 0, 0, 0)
        }
        crumbChevron = iconView(R.drawable.ic_chevron_left, 26, Palette.accentFg).apply { if (rtl) scaleX = -1f }
        crumb.addView(crumbChevron, lp(26.dp, 26.dp))
        crumbText = label(Type.LABEL, Palette.text)
        crumb.addView(crumbText, lp(0, WRAP, 1f).apply { marginStart = Space.S.dp })
        playAllIcon = iconView(R.drawable.ic_play, 20, Palette.accentFg)
        playAllText = label(Type.BUTTON, Palette.accentFg).apply { setText(R.string.music_play_all) }
        crumb.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // (a 48 dp pill inside a 56 dp target)
            background = Shapes.touchInset(playAllTint.drawable, 48)
            setPaddingRelative(Space.L.dp, 0, Space.L.dp + Space.XS.dp, 0)
            minimumHeight = Touch.MIN.dp
            isClickable = true
            pressScale(Motion.PRESS_CONTROL)
            setOnClickListener { group?.let { playFrom(it.tracks, 0) } }
            addView(playAllIcon, lp(20.dp, 20.dp))
            addView(playAllText, lp(WRAP, WRAP).apply { marginStart = Space.S.dp })
        }, lp(WRAP, Touch.MIN.dp))
        libraryBox.addView(crumb, lp(MATCH, WRAP).apply { topMargin = Space.XS.dp })
        val body = FrameLayout(this)
        listHolder = FrameLayout(this)
        listView = ListView(this).apply { libraryStyle() }
        listHolder.addView(listView, flp(MATCH, MATCH))
        gridView = GridView(this).apply {
            numColumns = 3
            verticalSpacing = Space.M.dp
            horizontalSpacing = Space.M.dp
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            libraryStyle()
            setPadding(Space.XS.dp, Space.S.dp, Space.XS.dp, Space.M.dp)
            visibility = View.GONE
        }
        listHolder.addView(gridView, flp(MATCH, MATCH))
        body.addView(listHolder, flp(MATCH, MATCH))
        empty = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; visibility = View.GONE }
        empty.addView(iconView(R.drawable.ic_usb, 48, Palette.text3), lp(48.dp, 48.dp))
        emptyText = label(Type.BODY, Palette.text2, lines = 3, gravity = Gravity.CENTER)
        empty.addView(emptyText, lp(MATCH, WRAP).apply { setMargins(Space.XXL.dp, Space.M.dp, Space.XXL.dp, 0) })
        emptyBtn = ABtn(this).apply { kind = ABtn.Kind.PRIMARY }
        empty.addView(emptyBtn, lp(WRAP, WRAP).apply { topMargin = Space.L.dp })
        emptySpinner = spinner().apply { visibility = View.GONE }
        empty.addView(emptySpinner, lp(28.dp, 28.dp).apply { topMargin = Space.L.dp })
        body.addView(empty, flp(MATCH, MATCH))
        libraryBox.addView(body, lp(MATCH, 0, 1f).apply { topMargin = Space.S.dp })

        panel.addView(buildYt(), flp(MATCH, MATCH))
        lyricsView = LyricsView(this).apply { visibility = View.GONE; onLineTap = { l -> onLineTap(l) }; onLineHold = { onLineHold() }; wordsOn = Words.on }
        panel.addView(lyricsView, flp(MATCH, MATCH))
        // over the lyrics, in the panel's corner: the lyrics alone on the whole screen
        expandBtn = FrameLayout(this).apply {
            background = Shapes.pressable(Shapes.oval(Palette.withAlpha(Palette.card3, 0.7f)), Shapes.oval(Palette.card3))
            isClickable = true
            pressScale(Motion.PRESS_ICON)
            visibility = View.GONE
            contentDescription = getString(R.string.lyrics_bigger)
            setOnClickListener { showFull(true) }
            addView(iconView(R.drawable.ic_expand, 24, Palette.text).apply { layoutParams = flp(24.dp, 24.dp, Gravity.CENTER) })
        }
        panel.addView(expandBtn, flp(Touch.MIN.dp, Touch.MIN.dp, Gravity.TOP or Gravity.END).apply { setMargins(Space.S.dp, Space.S.dp, Space.S.dp, 0) })

        row.addView(panel, lp(0, MATCH, 0.54f))
        root.addView(row, MATCH, MATCH)
        content = row
        // (what travels between two layouts crosses its parents' edges on the way: see relayout)
        for (g in listOf(root, row, left, top, stage, artSlot)) { g.clipChildren = false; g.clipToPadding = false }
        root.addView(buildFull(), MATCH, MATCH)
        // the cover on its way between the two layouts (see fly)
        flyer = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.GONE }
        root.addView(flyer, flp(1, 1))
        setArt(null, -3)
        return root
    }

    /** The lyrics alone on the whole screen: large lines, a way back in the corner, and a small bar for the song. */
    private fun buildFull(): View {
        fullLayer = FrameLayout(this).apply { isClickable = true; visibility = View.INVISIBLE } // (taps do not reach the screen below)
        lyricsBig = LyricsView(this, big = true).apply { onLineTap = { l -> onLineTap(l) }; onLineHold = { onLineHold() }; wordsOn = Words.on }
        fullLayer.addView(lyricsBig, flp(MATCH, MATCH).apply { bottomMargin = (FULL_BAR_DP + Space.XL).dp })

        val frost = Palette.withAlpha(Palette.card, if (Palette.dark) 0.55f else 0.75f)
        fullClose = FrameLayout(this).apply {
            background = Shapes.pressable(Shapes.oval(frost), Shapes.oval(Palette.card3))
            isClickable = true
            pressScale(Motion.PRESS_ICON)
            contentDescription = getString(R.string.lyrics_smaller)
            setOnClickListener { showFull(false) }
            addView(iconView(R.drawable.ic_collapse, 26, Palette.text).apply { layoutParams = flp(26.dp, 26.dp, Gravity.CENTER) })
        }
        // (where the screen's own way back is: the corner that leaves)
        fullLayer.addView(fullClose, flp(Touch.MIN.dp, Touch.MIN.dp, Gravity.TOP or Gravity.START).apply { setMargins(Space.GUTTER.dp, Space.M.dp, Space.GUTTER.dp, 0) })

        fullBar = FrameLayout(this).apply {
            background = Shapes.pill(Palette.withAlpha(Palette.card, if (Palette.dark) 0.66f else 0.84f), Palette.glassStroke)
            isClickable = true
        }
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(Space.M.dp, 0, Space.M.dp, 0)
        }
        fullArtBox = FrameLayout(this).apply { background = Shapes.rect(Palette.card2, BAR_COVER_RADIUS); roundedClip(BAR_COVER_RADIUS) }
        fullArtBox.addView(iconView(R.drawable.ic_music, 26, Palette.text3).apply { layoutParams = flp(26.dp, 26.dp, Gravity.CENTER) })
        fullArt = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        fullArtBox.addView(fullArt, flp(MATCH, MATCH))
        line.addView(fullArtBox, lp(64.dp, 64.dp))
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fullTitle = label(Type.LABEL, Palette.text)
        fullArtist = label(Type.FOOTNOTE, Palette.text2)
        names.addView(fullTitle, lp(MATCH, WRAP))
        names.addView(fullArtist, lp(MATCH, WRAP).apply { topMargin = Space.XXS.dp })
        line.addView(names, lp(0, WRAP, 1f).apply { marginStart = Space.L.dp; marginEnd = Space.M.dp })
        // (previous, play, next: left to right in every language, like the buttons of the song's side)
        val ctl = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        fullPrev = ctlButton(R.drawable.ic_skip_back, 28, null, R.string.media_prev) { transport(0) }
        ctl.addView(fullPrev, lp(Touch.LARGE.dp, Touch.LARGE.dp))
        fullPlayIcon = PlayPauseView(this)
        fullPlay = FrameLayout(this).apply {
            background = fullPlayTint.drawable
            isClickable = true
            pressScale(Motion.PRESS_ICON)
            contentDescription = getString(R.string.media_play)
            setOnClickListener { transport(1) }
            addView(fullPlayIcon, flp(30.dp, 30.dp, Gravity.CENTER))
        }
        ctl.addView(fullPlay, lp(Touch.LARGE.dp, Touch.LARGE.dp).apply { marginStart = Space.XS.dp; marginEnd = Space.XS.dp })
        fullNext = ctlButton(R.drawable.ic_skip_fwd, 28, null, R.string.media_next) { transport(2) }
        ctl.addView(fullNext, lp(Touch.LARGE.dp, Touch.LARGE.dp))
        line.addView(ctl, lp(WRAP, WRAP))
        fullBar.addView(line, flp(MATCH, MATCH))
        // how far the song is: a thin line inside the bar's bottom edge
        fullLine = ThinProgress(this)
        fullBar.addView(fullLine, flp(MATCH, 3.dp, Gravity.BOTTOM).apply { setMargins(34.dp, 0, 34.dp, 7.dp) })
        fullLayer.addView(fullBar, flp(MATCH, FULL_BAR_DP.dp, Gravity.BOTTOM).apply { setMargins(Space.GUTTER.dp, 0, Space.GUTTER.dp, Space.M.dp) })
        return fullLayer
    }

    private fun ctlButton(icon: Int, size: Int, img: ImageView?, desc: Int, onClick: () -> Unit): FrameLayout = FrameLayout(this).apply {
        background = Shapes.ghostOval()
        isClickable = true
        pressScale(Motion.PRESS_ICON)
        if (desc != 0) contentDescription = getString(desc)
        setOnClickListener { onClick() }
        val v = img ?: ImageView(this@MusicActivity)
        v.setImageResource(icon)
        if (img == null) v.setColorFilter(Palette.text)
        v.scaleType = ImageView.ScaleType.FIT_CENTER
        addView(v, flp(size.dp, size.dp, Gravity.CENTER))
    }

    /** A fill in the cover's colour with its pressed shade, recoloured in place as the colour changes. */
    private class Tint(shape: () -> GradientDrawable) {
        private val normal = shape()
        private val pressed = shape()
        val drawable: Drawable = Shapes.pressable(normal, pressed)
        fun set(c: Int, pressedColour: Int) { normal.setColor(c); pressed.setColor(pressedColour) }
    }

    companion object {
        // art keys for "no cover" states (album ids are >= 0)
        private const val BT_ART = Long.MIN_VALUE + 1
        private const val NO_ART = -2L
        private const val YT_ART = Long.MIN_VALUE + 2
        /** How long after a line starts the owner, hearing it, taps it. */
        private const val TAP_REACTION_MS = 450L
        /** A tap further than this from the line lit is the clock being wrong (the phone moved), not the lyrics' timing. */
        private const val MAX_CORRECTION_MS = 15_000L
        /** Corner radius of the song's cover, and of the small one in the bar under the large lyrics (dp). */
        private const val COVER_RADIUS = Radius.XL
        private const val BAR_COVER_RADIUS = Radius.M
        /** Height of that bar (dp). */
        private const val FULL_BAR_DP = 88
        /**
         * The song's title: the screen's display text, between the headline and display roles (bold, so the step to
         * the artist under it is one clear one); larger when the song has the screen to itself.
         */
        private const val TITLE_SP = 28f
        private const val TITLE_WIDE_SP = 34f
        /** List thumbnails (dp). */
        private const val THUMB_DP = 58
        /** A paused cover, as seen (see [breathe]). */
        private const val PAUSED_SCALE = 0.94f
        /** Buttons with nothing to do (the same as a disabled [ABtn]). */
        private const val DISABLED_ALPHA = 0.4f
        /** How far a new cover comes from (dp); the old one leaves half that the other way. */
        private const val COVER_SLIDE_DP = 28f
        /** A skip press counts for the next cover this long (a Bluetooth cover is looked up by name first). */
        private const val SKIP_MEMORY_MS = 5000L
        /** The cover before waits this long, dimmed, for a Bluetooth song's own. */
        private const val ART_WAIT_MS = 2500L
        /** A seek is shown where it was asked for at most this long while the source still says otherwise. */
        private const val SEEK_HOLD_MS = 1500L
        /** "Looking for the lyrics" shows only when the answer takes longer than this. */
        private const val LOADING_AFTER_MS = 600L

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

/**
 * Sets the text only when it is another text. A TextView lays its text out again on every setText, and one whose
 * width wraps its content asks the whole screen for a layout pass: the progress tick (four times a second with the
 * lyrics) and the phone's repeated song reports did both for text that had not changed.
 */
private fun TextView.setTextIfChanged(s: CharSequence?) {
    if (!TextUtils.equals(text, s)) text = s
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
    /** 0 resting, 1 held: the bar thickens and the knob grows under the finger (on a spring, not in one frame). */
    private var grab = 0f
    private val grabSpring = Spring(0f, 700f, 0.85f) { grab = it; invalidate() }

    init { layoutDirection = LAYOUT_DIRECTION_LTR }

    override fun onDraw(c: Canvas) {
        val h = (6f + 4f * grab).dp
        val y = height / 2f
        val pad = 12f.dp
        val w = width - 2 * pad
        r.set(pad, y - h / 2, pad + w, y + h / 2)
        p.color = Palette.track
        c.drawRoundRect(r, h / 2, h / 2, p)
        if (progress > 0f) {
            r.set(pad, y - h / 2, pad + (w * progress).coerceAtLeast(h), y + h / 2)
            p.color = color
            c.drawRoundRect(r, h / 2, h / 2, p)
        }
        if (enabledSeek) {
            val k = (8f + 4f * grab).dp
            p.color = 0x30000000
            c.drawCircle(pad + w * progress, y + 1f.dp, k + 1f.dp, p)
            p.color = 0xFFFFFFFF.toInt()
            c.drawCircle(pad + w * progress, y, k, p)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!enabledSeek) return false
        val pad = 12f.dp
        val f = ((e.x - pad) / (width - 2 * pad)).coerceIn(0f, 1f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dragging = true; parent?.requestDisallowInterceptTouchEvent(true); progress = f; grabSpring.animateTo(1f) }
            MotionEvent.ACTION_MOVE -> progress = f
            MotionEvent.ACTION_UP -> { dragging = false; progress = f; grabSpring.animateTo(0f); onSeek?.invoke(f) }
            MotionEvent.ACTION_CANCEL -> { dragging = false; grabSpring.animateTo(0f) }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        grabSpring.snapTo(0f)
        super.onDetachedFromWindow()
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
        p.color = Palette.track
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
