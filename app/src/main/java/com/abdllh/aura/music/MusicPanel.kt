package com.abdllh.aura.music

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.BidiFormatter
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.abdllh.aura.R
import com.abdllh.aura.home.AppRepo
import com.abdllh.aura.home.HomeActivity
import com.abdllh.aura.home.Panel
import com.abdllh.aura.home.Screens
import com.abdllh.aura.media.MediaMonitor
import com.abdllh.aura.ui.ABtn
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.IconButton
import com.abdllh.aura.ui.Look
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.PlayPauseView
import com.abdllh.aura.ui.PressGesture
import com.abdllh.aura.ui.SeekView
import com.abdllh.aura.ui.Segmented
import com.abdllh.aura.ui.Snack
import com.abdllh.aura.ui.Spinner
import com.abdllh.aura.ui.Spring
import com.abdllh.aura.ui.Surface
import com.abdllh.aura.ui.SurfaceCard
import com.abdllh.aura.ui.ThemeSwap
import com.abdllh.aura.ui.Tokens2
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.anim
import com.abdllh.aura.ui.at
import com.abdllh.aura.ui.box
import com.abdllh.aura.ui.enter
import com.abdllh.aura.ui.fadeTo
import com.abdllh.aura.ui.flp
import com.abdllh.aura.ui.iconView
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.look
import com.abdllh.aura.ui.lp
import com.abdllh.aura.ui.plateView
import com.abdllh.aura.ui.pressScale
import com.abdllh.aura.ui.riseIn
import com.abdllh.aura.ui.scrollFade
import com.abdllh.aura.util.Prefs
import kotlin.math.abs

/**
 * AMRI Music, a panel of the one window (SPEC.md section 6.4, CHANGES.md sections 6 and 7), the same whatever the car and a trip are doing. With lyrics: the song's wash, the cover 232, the title, seek and transport on the left, three lines of lyrics on the right, or the library
 * when the source is USB and files; the map FROZEN under it. With nothing to read: the cover 400 on the driver's side and the song large beside it ([bigOn]). What used to stand round the cover is behind one
 * quiet button ([MoreSheet]). A second tap on the row's music cell, or Back, is the panel he came from. Its pixels are the spec's, from the left
 * in both languages (section 7); text inside goes the language's way.
 *
 * Built ahead of time when the home screen is quiet ([PanelStack.prepareWhenQuiet]) and kept from then on: what the owner left stays (source, tab, open group, where each list was, the lyrics), and an open has
 * nothing to build. Out of sight it listens to nothing ([onHide]). The theme comes in place ([recolours]); the wash of the other theme is made ready before it flips ([readyStep]). (A class name is left for whoever starts
 * it by name: [MusicActivity].)
 */
class MusicPanel(shell: HomeActivity) : Panel(shell, Screens.MUSIC) {
    override val keep: Boolean get() = true
    override val flightCover: View? get() = if (::coverSmall.isInitialized) (if (bigOn) coverBig else coverSmall) else null
    override val flightRadius: Int get() = if (bigOn) COVER_BIG_R else COVER_R
    override val recolours: Boolean get() = true
    override val opaque: Boolean get() = true

    private enum class Tab { SONGS, ALBUMS, ARTISTS, FOLDERS }

    /** How a list comes in: another tab fades in place, a group comes from the end side, Back from the start side. */
    private enum class Nav { NONE, TAB, INTO, BACK }

    override val root = FrameLayout(this)
    private lateinit var content: FrameLayout
    private lateinit var wash: ImageView
    private lateinit var washOld: ImageView
    private lateinit var room: View
    /** The cover and its shadow, at section 6's rect: all that moves between the layouts is its transform ([poseCover]). */
    private lateinit var coverGroup: FrameLayout
    /** Under a song's cover only: the empty panel's grey plate lies flat, and in the day theme this would show through it. */
    private lateinit var coverShadow: ImageView
    /** The cover at each size, rounded at that size and shown 1:1. One is in sight at rest, both only on the way. */
    private lateinit var coverSmall: CoverSlot
    private lateinit var coverBig: CoverSlot
    /** The song's names, its place and its transport, under the small cover and beside the big one. */
    private lateinit var sideSmall: SongSide
    private lateinit var sideBig: SongSide
    /** What lies beside the small cover: the lyrics, the library or the four places. */
    private lateinit var beside: FrameLayout
    private lateinit var lyricsView: LyricsView

    // the library
    private lateinit var library: SurfaceCard
    private lateinit var tabs: Segmented
    private lateinit var crumb: LinearLayout
    private lateinit var crumbText: AText
    private lateinit var listHolder: FrameLayout
    private lateinit var listFade: ImageView
    private lateinit var empty: LinearLayout
    private lateinit var emptyText: AText
    private lateinit var emptyBtn: ABtn
    private lateinit var emptySpinner: Spinner

    // no song at all: the four places to listen from
    private lateinit var emptyCard: SurfaceCard
    private lateinit var emptyRows: List<SourceRowView>

    // the full lyrics
    private lateinit var fullLayer: FrameLayout
    private lateinit var fullHead: AText
    private lateinit var lyricsBig: LyricsView
    private lateinit var fullPlay: PlayPauseView
    private lateinit var fullPrev: IconButton
    private lateinit var fullNext: IconButton
    private var lyricsFull = false

    // the sheets, made the first time they are asked for
    private var moreSheet: MoreSheet? = null
    private var sourceSheet: SourceSheet? = null
    private var queueSheet: QueueSheet? = null

    /** The song whose lyrics are asked for (the key on this screen). */
    private var lyricsKey: String? = null
    /** The song whose lines the lyrics show (coming back to the screen on it does not make them again). */
    private var lyricsShown: String? = null
    /** The song whose lyrics were asked for last, as they were asked for (timed lines that come later are matched to it). */
    private var lyricsSong: LyricSong? = null
    /** The lyrics shown for [lyricsSong] (a correction is kept with exactly these lines). */
    private var lyricsResult: Lyrics.Result? = null
    /**
     * The owner's correction of the shown timing (1.15, ms: lyrics time minus the song's clock), a Bluetooth song's
     * only: kept with its lyrics, so the next play of the song has it at once.
     */
    private var lyricsOffset = 0L
    /**
     * What is added to the song's position to light the lines: for the Bluetooth song it was made on, the owner's
     * correction; without one (1.18) the Bluetooth sound's delay taken off, so the lines follow what the speakers play
     * (a correction made by tapping was measured against what was heard: the delay is already in it).
     */
    private val lyricsShift: Long get() = when {
        !bt || lyricsSong?.bt != true -> 0L
        lyricsOffset != 0L -> lyricsOffset
        else -> -BtMusic.delayMs
    }
    /** A line just tapped on Naghm's song stays lit until its sound, which comes the delay later, catches up. */
    private var tapLitMs = 0L
    private var tapLitUntil = 0L

    /**
     * A song as its lyrics are asked for: its key on this screen, its names, the length the lookup used, and where it
     * plays from (the unit's own file, or the phone over Bluetooth).
     */
    private class LyricSong(val key: String, val title: String, val artist: String, val sec: Int, val track: Track? = null, val bt: Boolean = false) {
        /** The length "no lyrics for this song" is kept by: not a Bluetooth song's, which the phone tells on some plays and not on others. */
        val offSec: Int get() = if (bt) 0 else sec
    }

    private var tab = Tab.SONGS
    private var group: Group? = null
    /** Where the music comes from: the unit's own files, the phone over Bluetooth, or YouTube's app on the unit. */
    private enum class Src(val pref: String) { LOCAL("local"), BT("bt"), YT("yt") }
    private var src = Src.LOCAL
        set(v) { field = v; onBluetooth = v == Src.BT }
    private val bt get() = src == Src.BT
    private val yt get() = src == Src.YT
    private val rtl get() = Fonts.arabicUi
    /** USB and files was chosen with no song of it yet: its library shows, not the four places. */
    private var libraryAsked = false
    /** What YouTube's app last told (read by the progress tick without asking it again). */
    private var ytHas = false
    private var ytDurationMs = 0L
    private var ytPlaying = false
    private var ytCover: Bitmap? = null
    private var lastArtKey = Long.MIN_VALUE
    private var btCover: Bitmap? = null
    /** The song shown (its title comes in with a little movement when it changes). */
    private var shownSong: String? = null
    /** The cover as its source gave it; the rounded copy at its size is what shows. */
    private var shownCover: Bitmap? = null
    private var coverSet = false
    /** The song the cover shown belongs to: another cover for the same song only crossfades. */
    private var artSong: String? = null
    private var artGen = 0

    /** It is in front, or on its way there; out of sight nothing asks for a picture or for lyrics. */
    private var showing = false
    private var shownAt = 0L

    /** The source it opens on: the one chosen, or Bluetooth when only the phone plays (which is no choice: nothing is written). */
    private fun sourceNow() = when {
        Prefs.raw.getString("musicSource", "local") == "bt" || (BtMusic.playing && !Player.playing) -> Src.BT
        Prefs.raw.getString("musicSource", "local") == "yt" -> Src.YT
        else -> Src.LOCAL
    }

    /**
     * Opened in place of a stock music screen ([com.abdllh.aura.home.Screens.musicOn]): its source, playing as the stock screen would. Back then
     * returns to Aura's screen below, not to the stock screen.
     */
    private fun handle(args: Bundle) {
        val src = args.getString(MusicActivity.EXTRA_SOURCE) ?: return
        if (src == "bt") {
            if (bt) BtMusic.activate() else selectSource(Src.BT) // selectSource activates it
        } else {
            // the music button: the unit's own music — YouTube's app when that is what was listened to last
            if (bt) selectSource(if (Prefs.raw.getString("musicSourceOwn", "local") == "yt") Src.YT else Src.LOCAL)
            if (yt) MediaMonitor.appControl(YtMusic.players, MediaMonitor.Do.PLAY)
            else if (Player.current != null && !Player.playing) Player.resume()
        }
    }

    override fun onArgs(args: Bundle) = handle(args)

    private fun askPermission() = requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 3)

    override fun onPermission(code: Int, grants: IntArray) {
        if (Library.hasPermission(this)) Library.load(this)
        showList()
    }

    /**
     * Comes to the front (was onStart, and onCreate's handle). As far as anything in it can tell it is not in sight until the
     * end of this, as in an activity's onStart: what changed while it was away is simply there, and nothing moves inside the
     * move that brings it in.
     */
    override fun onShow(args: Bundle?) {
        debugFail("show")
        catchUpLook()
        root.visibility = View.INVISIBLE
        root.removeCallbacks(letGo)
        showing = true
        shownAt = SystemClock.uptimeMillis()
        shownSong = null
        lastArtKey = Long.MIN_VALUE
        // (back from off the stack, as a new screen would: the phone may have started playing. Not when the window comes
        // back with it on top: from YouTube's app it stays on YouTube, whatever the phone does meanwhile)
        if (newOnStack) src = sourceNow()
        if (args != null) handle(args)
        Player.addListener(playerListener)
        Library.addListener(libraryListener)
        BtMusic.addListener(btListener)
        PhoneLink.addListener(phoneListener)
        MediaMonitor.observe(appListener)
        Lyrics.onTimed = timedListener
        LyricsSync.onStatus = syncStatus
        // (the setting may have changed in Settings meanwhile)
        val w = Words.on
        lyrics { it.wordsOn = w }
        Player.restore()
        showNowPlaying()
        showList()
        // (at once: the first frame shows where the song is now, and the lit line stands in its slot with no glide)
        root.removeCallbacks(progressTick)
        progressTick.run()
        (if (lyricsFull) lyricsBig else lyricsView).settle()
        root.visibility = View.VISIBLE
    }

    /**
     * Something came over it, it left, or the window stopped (was onStop). It stays built, so all that keeps something alive or
     * running stops here, and it lets go of the rows' pictures.
     */
    override fun onHide() {
        Player.removeListener(playerListener)
        Library.removeListener(libraryListener)
        BtMusic.removeListener(btListener)
        PhoneLink.removeListener(phoneListener)
        MediaMonitor.unobserve(appListener)
        root.removeCallbacks(progressTick)
        // (listening for the lyrics' timing is the screen's: it stops with it, and starts again when it comes back)
        if (Lyrics.onTimed === timedListener) Lyrics.onTimed = null
        if (LyricsSync.onStatus === syncStatus) LyricsSync.onStatus = null
        LyricsSync.end()
        lyricsView.removeCallbacks(lyricsRetry)
        lyricsView.removeCallbacks(lyricsLoading)
        lyrics { it.note(null) }
        if (bigMove != null) settleBig()
        lyricsKey = null // looked up again when the screen comes back
        // a cover still waiting for its successor (Bluetooth): the screen comes back with whatever there is by then
        cancelArtWait()
        showing = false
        // (not now: it is on the screen until the move away has ended)
        root.removeCallbacks(letGo)
        root.postDelayed(letGo, LET_GO_AFTER_MS)
    }

    override fun onOff() {
        libraryAsked = false
    }

    private val letGo = Runnable { if (!showing && !isGone) letGoOfPictures() }

    /** What a hidden screen would keep for nothing: the rows' pictures, all in a cache with a limit. Bound again when it comes back. The cover and its wash stay: he comes back to them at once. */
    private fun letGoOfPictures() {
        for (l in listOf<AbsListView>(listView, gridView)) for (i in 0 until l.childCount) when (val row = l.getChildAt(i)) {
            is SongRowView -> row.dropPicture()
            is AlbumCell -> row.dropPicture()
        }
    }

    /** Back leaves the full lyrics, then an album or an artist, first. */
    override fun onBack(): Boolean = when {
        lyricsFull -> { showFull(false); true }
        group != null -> { group = null; showList(Nav.BACK); true }
        else -> false
    }

    override fun onDestroy() {
        root.removeCallbacks(letGo)
        onBluetooth = null
        ThemeSwap.readySteps.remove(readyStep)
    }

    /**
     * Short of memory: the decoded covers go, and out of sight also what takes a moment to make again (the lyrics' lines, the
     * lists' rows). Not at UI_HIDDEN, which only says another app came to the front.
     */
    override fun onTrimMemory(level: Int) {
        val low = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE
        if (!low) return
        ArtLoader.clear()
        if (showing) return
        lyrics { it.showStatus("") }
        lyricsShown = null
        rememberSpot()
        shownList = null
        listView.adapter = null
        gridView.adapter = null
    }

    /** Debug builds: the stack must drop this panel and go on (setprop debug.aura.musicfail build|show). */
    private fun debugFail(at: String) {
        if (com.abdllh.aura.BuildConfig.DEBUG && com.abdllh.aura.system.SystemProps.get("debug.aura.musicfail") == at) throw IllegalStateException("music: $at")
    }

    // ------------------------------------------------------------------------------------------ the theme
    /**
     * The theme it last recoloured in. [ThemeSwap] marks a hidden panel and the stack catches it up; a theme that came another way (`Theme.refresh`: old Settings, old Controls, a test hook) marks nothing, and
     * half the panel, what reads [Palette] as it draws, would have gone with it and half not. So it looks for itself when it comes back.
     */
    private var lookedDark = Palette.dark

    private fun catchUpLook() {
        if (lookedDark == Palette.dark) return
        Look.apply(root)
        moreSheet?.let { Look.apply(it) }
        sourceSheet?.let { Look.apply(it) }
        queueSheet?.let { Look.apply(it) }
    }

    /** Before a flip, on the worker: the song's wash for that theme, so the swap decodes nothing. [Backdrop] keeps it by the cover. */
    private val readyStep: (Boolean, () -> Unit) -> Unit = { night, done -> Backdrop.washAsync(this, shownCover, night) { done() } }

    /** At the swap, or as the panel catches up when it comes back: what is a bitmap of one theme, and the rows that are off the list. */
    private fun takeTheme() {
        lookedDark = Palette.dark
        if (washNow?.night != Palette.dark) {
            val g = artGen
            Backdrop.washAsync(this, shownCover, Palette.dark) { w -> if (g == artGen && !isGone && w.night == Palette.dark) applyWash(w, animate = false) }
        }
        if (shownCover == null) { coverSmall.show(noArtSmall()); coverBig.show(noArtBig()) }
        if (::listView.isInitialized) (listView.adapter as? BaseAdapter)?.notifyDataSetChanged()
    }

    // ------------------------------------------------------------------------------------------ the listeners
    private val playerListener: () -> Unit = {
        if (src == Src.LOCAL) showNowPlaying()
        followPlaying()
        if (listView.adapter === tracks) tracks.notifyDataSetChanged()
        queueSheet?.refresh()
    }
    private val libraryListener: () -> Unit = { Player.restore(); showList(); showNowPlaying() }
    private val btListener: () -> Unit = { if (bt) showNowPlaying() else syncRows() }
    /** Naghm's lyrics came (or new ones): shown for its song in place of the screen's own (1.19, [phoneLyrics]). */
    private val phoneListener: () -> Unit = {
        val s = lyricsSong
        val r = phoneLyrics(s)
        if (s != null && r != null && lyricsKey == s.key && lyricsResult !== r && phoneSeen !== r && !isGone && !lyricsOff) {
            phoneSeen = r
            val own = lyricsResult?.takeIf { lyricsShown == s.key }
            if (own == null || phoneTakesOver(own, r)) {
                lyricsView.removeCallbacks(lyricsLoading)
                lyricsAnswer(s.key, r, false)
            }
        }
        syncRows()
    }
    /** Naghm's lyrics already looked at (the listener runs with its every state). */
    private var phoneSeen: Lyrics.Result? = null

    /**
     * Naghm's lines in place of the screen's own [own], already showing. Not when they are the very same lines (the owner's
     * correction kept with them goes on applying), and lines without times do not take the place of timed ones.
     */
    private fun phoneTakesOver(own: Lyrics.Result, r: Lyrics.Result) = !Lyrics.sameLines(own.lines, r.lines) && (r.synced || !own.synced)

    /** Naghm's own lyrics for the Bluetooth song [s] (1.19), when it sent some for this very song. */
    private fun phoneLyrics(s: LyricSong?): Lyrics.Result? {
        if (s == null || !s.bt || !PhoneLink.active) return null
        val r = PhoneLink.lyrics ?: return null
        return if (PhoneLink.lyricsId == PhoneLink.id && BtMusic.sameSong(PhoneLink.lyricsTitle, s.title)) r else null
    }
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
            val playing = nowPlaying()
            // (the lyrics' time: the song's, with the owner's correction of a Bluetooth song's timing, or the sound's delay)
            var lyricAt = at + lyricsShift
            if (SystemClock.uptimeMillis() < tapLitUntil) lyricAt = maxOf(lyricAt, tapLitMs)
            val unknown = positionUnknown
            lyrics { it.needSync = unknown; it.at(lyricAt, playing) }
            // (each side is told: the one out of sight draws nothing for it, and is right the moment it is shown)
            when (src) {
                // YouTube's app tells its song's length and where it is (the real position)
                Src.YT -> {
                    val p = MediaMonitor.appPosition(YtMusic.players)
                    showProgress(ytHas && ytDurationMs > 0 && p != null, at, ytDurationMs)
                }
                // the phone does not say where a song is (BtMusic keeps a clock): an unknown position shows one line in the slot
                Src.BT -> {
                    val d = BtMusic.durationSec * 1000L
                    val has = BtMusic.title.isNotBlank()
                    when {
                        has && BtMusic.positionKnown && d > 0 -> showProgress(BtMusic.connected, at, d)
                        has && BtMusic.positionKnown -> showSlotText(clockText(at))
                        has && BtMusic.connected && lyricsResult?.synced == true && lyricsShown == lyricsKey -> showSlotText(getString(R.string.music_tap_line))
                        else -> showSlotText("")
                    }
                }
                Src.LOCAL -> {
                    val d = Player.duration()
                    showProgress(Player.current != null && d > 0, at, d)
                }
            }
            // the lyrics follow the song closely: the next tick comes when the next line is due, not up to a tick late
            val next = if (playing) lyricsView.msToNext(lyricAt) else Long.MAX_VALUE
            root.postDelayed(this, minOf(TICK_MS, next).coerceAtLeast(16L))
        }
    }

    private var shownSec = -1L
    private var shownLen = -1L

    /** The bar and the times, one change a second. With the word's pill on its way the tick waits for the next look: a tick on the left and a pill on the right would redraw the whole panel (SPEC.md section 4). */
    private fun showProgress(known: Boolean, p: Long, d: Long) {
        if (!known) { showSlotText(""); return }
        sides { it.slot(bar = true) }
        if (sideSmall.seek.dragging || sideBig.seek.dragging) return
        val sec = p / 1000L
        if (sec == shownSec && d == shownLen) return
        if (lyricsView.pillMoving || lyricsBig.pillMoving) return
        shownSec = sec
        shownLen = d
        val now = clockText(p.coerceAtMost(d))
        val total = clockText(d)
        val f = (p.toFloat() / d).coerceIn(0f, 1f)
        sides { it.seek.setTimes(now, total); it.seek.progress = f }
    }

    /** No bar: one line in the slot, or nothing. */
    private fun showSlotText(s: CharSequence) {
        shownSec = -1L
        sides { it.slot(bar = false, line = s) }
    }

    /** A bar let go of, [f] of the way through the song. */
    private fun seekTo(f: Float) {
        if (yt) {
            val d = ytDurationMs
            if (d > 0) { val to = (d * f).toLong(); MediaMonitor.appControl(YtMusic.players, MediaMonitor.Do.PLAY, seekToMs = to); holdSeek(to) }
        } else if (bt) {
            // the phone's song, through Naghm (the bar takes no drags otherwise: enabledSeek)
            val d = BtMusic.durationSec * 1000L
            if (d > 0) { val to = (d * f).toLong(); if (BtMusic.seekTo(to)) lyrics { it.at(to + lyricsShift) } }
        } else { val to = (Player.duration() * f).toLong(); Player.seekTo(to); holdSeek(to) }
    }

    // ------------------------------------------------------------------------------------------ lyrics
    /** Both sizes of the lyrics (beside the song, and alone on the screen) are told the same things. */
    private inline fun lyrics(f: (LyricsView) -> Unit) { f(lyricsView); f(lyricsBig) }

    /** Both ways of showing the song are told the same things. */
    private inline fun sides(f: (SongSide) -> Unit) { f(sideSmall); f(sideBig) }

    /** The lyrics alone on the screen ([on]) or back beside the song: one fades over the other, the left column stays laid out. */
    private fun showFull(on: Boolean, animate: Boolean = true, remember: Boolean = true) {
        val moving = animate && root.isShown
        lyricsFull = on
        // ([remember]: the owner's own press, not a song with nothing to read)
        if (remember) Prefs.raw.edit().putBoolean("musicLyricsFull", on).apply()
        (if (on) lyricsBig else lyricsView).settle()
        if (!moving) {
            content.animate().cancel()
            fullLayer.animate().cancel()
            content.alpha = 1f
            fullLayer.alpha = 1f
            content.visibility = if (on) View.INVISIBLE else View.VISIBLE
            fullLayer.visibility = if (on) View.VISIBLE else View.INVISIBLE
            return
        }
        if (on) {
            fullLayer.visibility = View.VISIBLE
            if (fullLayer.alpha >= 1f) fullLayer.alpha = 0f
            fullLayer.fadeTo(1f, Tokens2.MS_lyricsIn, Motion.MOVE)
            content.fadeTo(0f, Tokens2.MS_cardOut, Motion.EXIT) { if (lyricsFull) content.visibility = View.INVISIBLE }
        } else {
            content.visibility = View.VISIBLE
            if (content.alpha >= 1f) content.alpha = 0f
            content.fadeTo(1f, Tokens2.MS_lyricsIn, Motion.MOVE)
            fullLayer.fadeTo(0f, Tokens2.MS_cardOut, Motion.EXIT) { if (!lyricsFull) fullLayer.visibility = View.INVISIBLE }
        }
    }

    // ---- the big cover (CHANGES.md section 7)
    /** The song has nothing to read beside it: no lyrics found, none to be had just now, or he turned them off. As it was while still looking: the last state stays (section 12). */
    private var noLyrics = false
    /** "No lyrics for this song" is on for the one playing. */
    private var lyricsOff = false
    /** The full lyrics closed for a song with none: they come back with the next that has some. */
    private var fullWaits = false
    /** The big cover is what shows, or what it is on its way to. */
    private var bigOn = false
    /** 0: section 6, 1: section 7, in between only on the way. */
    private var bigK = 0f
    private var bigMove: ValueAnimator? = null

    /**
     * Which layout the song lies in, and the move to it: the cover and its shadow by a transform, the rest by alpha, 320 `move`, each side one layer for the move, nothing measured. To the lyrics it starts only when their
     * lines stand laid out, at alpha 0 ([LyricsView.whenLaid]). The library and the four places always lie beside the small cover. A screen that is not in sight, or has only just come up, simply has it.
     */
    private fun syncBig(linesLaid: Boolean = false) {
        val on = noLyrics && !noSong && src != Src.LOCAL
        if (on == bigOn) {
            // (lines were being readied for a move that no longer comes)
            if (on && bigMove == null) beside.visibility = View.INVISIBLE
            return
        }
        val live = coverGroup.isShown && SystemClock.uptimeMillis() - shownAt > ARRIVING_MS
        if (!on && live && !linesLaid && !noSong && src != Src.LOCAL) {
            beside.alpha = 0f
            beside.visibility = View.VISIBLE
            lyricsView.whenLaid { syncBig(linesLaid = true) }
            return
        }
        bigOn = on
        if (!live) { settleBig(); return }
        val from = bigK
        stopBigMove()
        for (v in listOf(sideSmall, sideBig, beside, coverSmall, coverBig)) v.visibility = View.VISIBLE
        for (v in listOf(sideSmall, sideBig, beside)) v.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        val a = ValueAnimator.ofFloat(from, if (on) 1f else 0f)
        a.duration = Tokens2.MS_flightMusic
        a.interpolator = Motion.MOVE
        a.addUpdateListener { setBigK(it.animatedValue as Float) }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) { if (bigMove === animation) settleBig() }
        })
        bigMove = a
        a.start()
    }

    private fun stopBigMove() {
        val a = bigMove
        bigMove = null
        a?.cancel()
    }

    /** At rest in the layout it is going to: one side in sight, no layers, and with nothing to read nothing kept for it. */
    private fun settleBig() {
        stopBigMove()
        setBigK(if (bigOn) 1f else 0f)
        for (v in listOf(sideSmall, sideBig, beside)) v.setLayerType(View.LAYER_TYPE_NONE, null)
        sideSmall.visibility = if (bigOn) View.INVISIBLE else View.VISIBLE
        coverSmall.visibility = sideSmall.visibility
        beside.visibility = sideSmall.visibility
        sideBig.visibility = if (bigOn) View.VISIBLE else View.INVISIBLE
        coverBig.visibility = sideBig.visibility
        if (bigOn) { if (lyricsResult == null) lyrics { it.showStatus("") } }
        else if (!lyricsFull) lyricsView.settle()
    }

    /**
     * One step of the move. The two copies of the cover cross under the shadow's scale: the big one comes up over the small one in the first half, the small one goes under it in the second, so nothing shows through. The
     * small cover's side and what lies beside it show only near the end, once the cover has left them room; the big side from 40 % of the way.
     */
    private fun setBigK(k: Float) {
        bigK = k
        poseCover()
        coverBig.alpha = (k * 2f).coerceAtMost(1f)
        coverSmall.alpha = ((1f - k) * 2f).coerceAtMost(1f)
        val small = (1f - k * 4f).coerceAtLeast(0f)
        sideSmall.alpha = small
        beside.alpha = small
        sideBig.alpha = ((k - 0.4f) / 0.6f).coerceAtLeast(0f)
    }

    /** The group's transform: the big cover's rect at [bigK], the grey one's of no song at [emptyK], and the breath of a paused song on top. */
    private fun poseCover() {
        val k = bigK
        val e = emptyK.value
        coverGroup.translationX = BIG_DX * k + EMPTY_DX * e
        coverGroup.translationY = BIG_DY * k + EMPTY_DX * e
        val s = (1f + (BIG_SCALE - 1f) * k) * (1f + (EMPTY_SCALE - 1f) * e) * breath.value
        coverGroup.scaleX = s
        coverGroup.scaleY = s
        sideSmall.names.translationX = EMPTY_DX * e
        sideSmall.names.translationY = EMPTY_NAMES_DY * e
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

    /** The lyrics of what plays now, looked up once per song. */
    private fun refreshLyrics() {
        if (!::lyricsView.isInitialized || !showing) return
        val s = if (yt) {
            // (YouTube's app tells the song's own length: the right recording's lyrics)
            MediaMonitor.app(YtMusic.players)
                ?.let { LyricSong("yt:${it.title}|${it.artist}", it.title, it.artist, (it.durationMs / 1000).toInt()) }
        } else if (bt) {
            if (BtMusic.title.isBlank()) null
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
        lyricsView.removeCallbacks(lyricsLoading)
        if (s == null) { lyricsShown = null; lyricsOff = false; noLyrics = false; lyrics { it.showStatus("") }; return }
        lyricsOff = Lyrics.isOff(this, s.title, s.artist, s.offSec)
        if (lyricsOff) { lyricsAnswer(s.key, null, false); return }
        // 1.19: Naghm's song with the phone's lyrics already here: those, at once (the same lines as on the phone, timed
        // on the very recording; the screen's own lookup does not always find them)
        phoneLyrics(s)?.let { r ->
            val own = lyricsResult?.takeIf { lyricsShown == s.key }
            lyricsAnswer(s.key, if (own == null || phoneTakesOver(own, r)) r else own, false)
            return
        }
        // (while they are looked up their place stays as it is, stepped back)
        if (lyricsShown != s.key) {
            lyrics { it.dim() }
            // (what shows is stepped back and no longer follows: back on that song before this answer comes — the
            // phone skipping one way and back — its lines are shown again, not left dimmed)
            lyricsShown = null
            lyricsLoadingKey = s.key
            lyricsView.postDelayed(lyricsLoading, LOADING_AFTER_MS)
        }
        Lyrics.get(this, s.title, s.artist, s.sec) { r, failed ->
            if (lyricsKey != s.key || isGone) return@get
            // (the phone's lyrics came first meanwhile: they stay)
            if (phoneLyrics(s) != null) return@get
            lyricsView.removeCallbacks(lyricsLoading)
            // the length of the recording the lines were timed on: the Bluetooth song's, while the phone tells none
            // (timed lines only: an untimed record's length says no more than a catalogue's)
            if (bt && r != null && r.synced && r.durationSec > 0) BtMusic.offerDuration(r.durationSec, s.title, s.artist, BtMusic.FROM_LYRICS)
            val wait = songMovesUntil - SystemClock.uptimeMillis()
            if (wait > 0) root.postDelayed({ if (lyricsKey == s.key && !isGone && phoneLyrics(s) == null) lyricsAnswer(s.key, r, failed) }, wait)
            else lyricsAnswer(s.key, r, failed)
        }
    }

    private fun lyricsAnswer(key: String, r: Lyrics.Result?, failed: Boolean) {
        lyricsView.removeCallbacks(lyricsRetry)
        when {
            // (an answer that was on its way when he turned them off counts for nothing)
            r == null || r.instrumental || lyricsOff -> {
                // nothing to read: the big cover ([syncBig]), which takes what still shows with it
                lyricsShown = null
                lyricsResult = null
                noLyrics = true
                if (lyricsFull) { fullWaits = true; showFull(false, remember = false) }
                if (src == Src.LOCAL) lyrics { it.showStatus("") }
                // asked again in a while (the internet, or the server, may be back): one retry at a time
                if (r == null && failed && !lyricsOff) {
                    lyricsRetryKey = key
                    lyricsView.postDelayed(lyricsRetry, 20_000L)
                }
            }
            else -> {
                noLyrics = false
                val unknown = positionUnknown
                // (the same song's lines again stay where they are; other ones, Naghm's after the screen's own, go up)
                val old = lyricsResult
                val same = lyricsShown == key && old != null && Lyrics.sameLines(old.lines, r.lines)
                lyricsResult = r
                // the owner's correction kept with these lyrics (a Bluetooth song's: see onLineTap; the very lyrics that
                // show go on with the one they have, which may be newer than what they were read with)
                if (r !== old) lyricsOffset = if (lyricsSong?.bt == true) r.offsetMs else 0L
                val at = positionNow() + lyricsShift
                if (!same) lyrics { it.show(r) }
                if (com.abdllh.aura.BuildConfig.DEBUG) android.util.Log.d("AuraMusic", "lyrics up: ${r.source}, ${r.lines.size} lines, offset $lyricsOffset, put up: ${!same}")
                lyricsShown = key
                lyrics { it.needSync = unknown; it.at(at) }
                listenFor(r)
                if (r.synced && lyricsSong?.bt == true) offerDelayHint()
                if (fullWaits) { fullWaits = false; showFull(true, remember = false) }
            }
        }
        syncBig()
        moreSheet?.takeIf { it.isOpen }?.refresh()
    }

    /**
     * Untimed lines (or a timing by listening that is not complete) for a Bluetooth song or one of the unit's own:
     * the owner's server may time them by listening ([LyricsSync]: only with his switch on, and only while the song
     * plays with its position known). YouTube's songs are not listened to.
     * 1.16: timed lines whose words have no real times are listened to as well, for their words only, while the word
     * effect is on ("timed": the server keeps the lines' times and finds each line's words around its own time). The
     * times it is given are the song's clock, the one the pieces of sound are placed on: the shown time minus the
     * owner's correction of a Bluetooth song's timing.
     */
    private fun listenFor(r: Lyrics.Result) {
        val s = lyricsSong
        val words = !r.improvable && r.synced && r.wantsWords && Words.on
        if (s == null || (!r.improvable && !words) || yt || (!s.bt && s.track == null)) { LyricsSync.end(); return }
        val shift = lyricsShift
        // (a Bluetooth song's length only when the phone told it: a catalogue's may be another recording's, and the
        // server's alignment leans a little on where in the song's length each line should be)
        val song = LyricsSync.Song(s.title, s.artist, s.sec, r.lines.map { it.text }, s.track,
            lengthSec = if (s.bt) BtMusic.phoneDurationSec else s.sec,
            times = if (words) r.lines.map { l -> (l.ms - shift).takeIf { it >= 0L } } else null,
            shown = if (words) r else null, offsetMs = if (words) shift else 0L)
        val position: () -> Long? = if (s.bt) {
            { if (bt && BtMusic.connected && BtMusic.playing && BtMusic.positionKnown && BtMusic.title == s.title) BtMusic.positionMs() else null }
        } else {
            { if (src == Src.LOCAL && Player.playing && Player.current?.id == s.track?.id) Player.position() else null }
        }
        LyricsSync.begin(this, song, position)
    }

    /**
     * Timed lines for the song on the screen, after its untimed ones (the owner's server found them, or made them by
     * listening): the same lines are timed where they stand ([LyricsView.timeLines]), other ones replace them. Since
     * 1.16 also the words heard for timed lines (the same lines, the same correction, their words new). Listening goes
     * on while what shows still wants it ([listenFor]: the same song's listening is kept as it is).
     */
    private val timedListener: (String, String, Int, Lyrics.Result) -> Unit = { title, artist, sec, r ->
        val s = lyricsSong
        if (s != null && s.title == title && s.artist == artist && s.sec == sec && lyricsShown == s.key && !isGone) {
            // (another source's timed lyrics carry their recording's length, as LRCLIB's do: see refreshLyrics)
            if (s.bt && r.durationSec > 0) BtMusic.offerDuration(r.durationSec, title, artist, BtMusic.FROM_LYRICS)
            val unknown = positionUnknown
            // (a new timing: a correction made on the one before does not apply to it, and was not kept with it)
            lyricsResult = r
            lyricsOffset = r.offsetMs
            val at = positionNow() + lyricsShift
            lyrics { it.timeLines(r); it.needSync = unknown; it.at(at) }
            listenFor(r)
        }
    }

    /**
     * The listening at work, said in the lyrics' corner: what it does, and how much of the song is timed (since 1.16
     * for timed lyrics: how many of the lines have their words).
     */
    private val syncStatus: (Int, Float) -> Unit = { what, covered ->
        if (::lyricsView.isInitialized && !isGone) {
            val pct = "${(covered * 100).toInt()}%"
            val words = LyricsSync.wordsOnly
            val text = when (what) {
                LyricsSync.LISTENING -> when {
                    words && covered > 0f -> getString(R.string.cap_words_listening_pct, pct)
                    words -> getString(R.string.cap_words_listening)
                    covered > 0f -> getString(R.string.lyrics_sync_listening_pct, pct)
                    else -> getString(R.string.lyrics_sync_listening)
                }
                LyricsSync.WORKING -> getString(if (words) R.string.cap_words_working else R.string.lyrics_sync_working, pct)
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
        } else if (bt && BtMusic.canSeek) {
            // Naghm on the phone: the song itself jumps to the line, as the unit's own songs do (to where the line is in
            // the song: only the owner's correction moves that, the sound's delay only makes it heard later)
            BtMusic.seekTo(l.ms - lyricsOffset)
            if (!BtMusic.playing) BtMusic.control(BtMusic.PLAY)
            // (until the line's sound comes out of the speakers, the clock minus the delay is still before it)
            tapLitMs = l.ms
            tapLitUntil = SystemClock.uptimeMillis() + BtMusic.delayMs + TAP_LIT_SLACK_MS
            lyrics { it.needSync = false; it.at(l.ms) }
        } else if (bt) {
            val target = l.ms + TAP_REACTION_MS
            val s = lyricsSong
            val r = lyricsResult
            // the clock already known (the song's start seen, or set by a tap before): the tap corrects the lyrics'
            // timing for this song, kept with its lyrics (1.15). Unless it is far off: then the phone was moved or the
            // song restarted, and it is the clock that is wrong.
            if (BtMusic.positionKnown && s != null && s.bt && r != null && r.synced && lyricsShown == s.key &&
                BtMusic.title == s.title && abs(target - BtMusic.positionMs() - lyricsShift) <= MAX_CORRECTION_MS) {
                val clock = BtMusic.positionMs()
                lyricsOffset = target - clock
                Lyrics.keepOffset(this, s.title, s.artist, s.sec, lyricsOffset, r.lines)
                lyrics { it.at(clock + lyricsOffset) }
                Snack.show(this, R.string.kar_timing_saved, aboveDp = snackAbove)
                // (listening for the words: every line moved on the song's clock, so the server is told anew)
                if (LyricsSync.wordsOnly) listenFor(r)
                return
            }
            // the clock unknown: the tap says where the song is (with the song's kept correction, the lyrics land on
            // the tapped line all the same)
            BtMusic.syncTo(target - lyricsShift)
            val at = BtMusic.positionMs() + lyricsShift
            lyrics { it.needSync = false; it.at(at) }
            Snack.show(this, R.string.music_synced)
        } else if (Player.current != null) {
            Player.seekTo(l.ms)
            holdSeek(l.ms)
            if (!Player.playing) Player.toggle()
            lyrics { it.at(l.ms) }
        }
    }

    private val snackAbove: Int get() = Snack.ABOVE_DP

    /**
     * A lyric line held down: the song's saved timing correction is forgotten (1.15). Without one, on Naghm's song
     * (1.18), the line is held the moment its singing is heard: that measures how late the Bluetooth sound comes.
     */
    private fun onLineHold(l: Lyrics.Line): Boolean {
        val s = lyricsSong
        val r = lyricsResult
        if (!bt || s == null || !s.bt || r == null || lyricsShown != s.key) return false
        if (lyricsOffset == 0L) {
            if (BtMusic.canSeek && BtMusic.playing) measureDelay(l)
            // (held without a saved correction on another app's song: nothing, rather than the tap it would become)
            return true
        }
        lyricsOffset = 0L
        Lyrics.keepOffset(this, s.title, s.artist, s.sec, 0L, r.lines)
        val at = positionNow() + lyricsShift
        lyrics { it.at(at) }
        Snack.show(this, R.string.kar_timing_reset, aboveDp = snackAbove)
        if (LyricsSync.wordsOnly) listenFor(r) // (the lines' clock times moved back: see onLineTap)
        return true
    }

    /**
     * The Bluetooth sound's delay from a line held on Naghm's song (1.18). Naghm says exactly where its song is: when
     * the finger came down (a long press is told only once it has lasted) the phone was that far into the song, and
     * what the speakers played then was the line's start, a reaction earlier. The difference is the delay, kept for
     * every song after it.
     */
    private fun measureDelay(l: Lyrics.Line) {
        val down = PhoneLink.positionMs() - ViewConfiguration.getLongPressTimeout()
        val delay = down - HOLD_REACTION_MS - l.ms
        if (delay < -DELAY_SLACK_MS || delay > BtMusic.DELAY_MAX_MS) {
            Snack.show(this, R.string.bt_delay_missed, long = true, aboveDp = snackAbove)
            return
        }
        BtMusic.delayMs = (delay.coerceAtLeast(0L) + 5L) / 10L * 10L
        tapLitUntil = 0L
        lyrics { it.at(positionNow() + lyricsShift) }
        Snack.show(this, getString(R.string.bt_delay_set, MusicActivity.delaySeconds(BtMusic.delayMs)), long = true, aboveDp = snackAbove)
    }

    /**
     * Once, the first time lyrics light on Naghm's song while no delay was ever set: how to make them follow the car's
     * speakers (1.18).
     */
    private fun offerDelayHint() {
        if (!bt || !BtMusic.viaNaghm || BtMusic.delaySet || Prefs.raw.getBoolean(DELAY_HINT_KEY, false)) return
        Prefs.raw.edit().putBoolean(DELAY_HINT_KEY, true).apply()
        Snack.show(this, R.string.bt_delay_hint, long = true, aboveDp = snackAbove)
    }

    // ------------------------------------------------------------------------------------------ now playing
    /** Nothing to show: the room, the grey cover and the four places (`empty-no-song-music`). */
    private val noSong: Boolean get() = when (src) {
        Src.YT -> !ytHas
        Src.BT -> BtMusic.title.isBlank()
        Src.LOCAL -> Player.current == null && !libraryAsked
    }

    private fun showNowPlaying() {
        val canSeek = !bt || BtMusic.canSeek // (a phone song moves only through Naghm)
        sides { it.seek.enabledSeek = canSeek }
        when (src) {
            Src.YT -> {
                // what YouTube's app plays, as its own session tells it
                val s = MediaMonitor.app(YtMusic.players)
                ytHas = s != null
                ytDurationMs = s?.durationMs ?: 0L
                ytPlaying = s?.playing == true
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
                if (s == null) names(getString(R.string.media_nothing), getString(R.string.music_pick_source))
                else names(s.title, wrap(s.artist))
                playState(ytPlaying, on = s != null)
            }
            Src.BT -> {
                val linked = BtMusic.available && BtMusic.connected
                songChanged("bt:${BtMusic.title}|${BtMusic.artist}")
                btArt()
                // (where the song is cannot be shown until it is known: its length goes beside the artist meanwhile)
                val d = BtMusic.durationSec * 1000L
                val by = if (!BtMusic.positionKnown && d > 0) join(wrap(BtMusic.artist), clockText(d)) else wrap(BtMusic.artist)
                when {
                    BtMusic.title.isBlank() -> names(getString(R.string.media_nothing), getString(if (linked) R.string.music_bt_idle else R.string.music_pick_source))
                    !linked -> names(BtMusic.title, getString(R.string.music_bt_lost))
                    else -> names(BtMusic.title, by)
                }
                playState(BtMusic.playing, on = linked)
            }
            Src.LOCAL -> {
                val t = Player.current
                songChanged("lib:${t?.id}")
                if (t == null) {
                    names(getString(R.string.media_nothing), getString(if (libraryAsked) sourceWord(SourceRow.USB, true) else R.string.music_pick_source))
                    if (lastArtKey != NO_ART) setArt(null, NO_ART)
                } else {
                    names(t.title, wrap(t.artist.ifBlank { getString(R.string.music_unknown_artist) }))
                    if (lastArtKey != t.albumId) {
                        lastArtKey = t.albumId
                        ArtLoader.get(this, t, ART_PX) { b -> if (Player.current?.albumId == t.albumId && src == Src.LOCAL) setArt(b, t.albumId) }
                    }
                }
                // (play also starts the library from its first song when nothing was played yet)
                playState(Player.playing, on = t != null || Library.tracks.isNotEmpty())
            }
        }
        syncEmpty()
        syncRows()
        refreshLyrics()
        syncBig()
    }

    /** Two pictures of one cover, the same picture: compared once, when a session sends a new copy of it. */
    private fun sameCover(a: Bitmap, b: Bitmap): Boolean =
        try { a.width == b.width && a.height == b.height && a.sameAs(b) } catch (_: Throwable) { false }

    // ---- the source, as it is named
    private fun rowNow(): SourceRow = when (src) {
        Src.BT -> if (BtMusic.viaNaghm) SourceRow.NAGHM else SourceRow.BT
        Src.LOCAL -> SourceRow.USB
        Src.YT -> SourceRow.YT
    }

    private fun sourceWord(r: SourceRow, full: Boolean): Int = when (r) {
        SourceRow.NAGHM -> if (full) R.string.music_src_naghm else R.string.music_src_naghm_short
        SourceRow.BT -> if (full) R.string.music_src_bt else R.string.music_src_bt_short
        SourceRow.USB -> if (full) R.string.music_src_usb else R.string.music_src_usb_short
        SourceRow.YT -> if (full) R.string.music_src_yt else R.string.music_src_yt_short
    }

    internal fun rowTitle(r: SourceRow, full: Boolean): String = getString(sourceWord(r, full))

    /** What the row says of its state, what is true now (SPEC.md section 12). */
    internal fun rowState(r: SourceRow): String = when (r) {
        SourceRow.NAGHM -> getString(if (PhoneLink.connected) R.string.music_iphone_on else R.string.music_naghm_off)
        SourceRow.BT -> when {
            !BtMusic.connected -> getString(R.string.music_phone_off)
            BtMusic.phone.isBlank() -> getString(R.string.music_bt_phone_connected)
            else -> getString(R.string.music_bt_connected, BtMusic.phone)
        }
        SourceRow.USB -> if (Library.tracks.isEmpty()) getString(R.string.music_no_drive) else songCount(Library.tracks.size)
        SourceRow.YT -> getString(if (YtMusic.player(this) != null) R.string.music_yt_opens else R.string.music_yt_missing)
    }

    internal fun rowChosen(r: SourceRow): Boolean = r == rowNow()

    /** Western digits in both languages (SPEC.md section 3): a `%d` would follow the locale's. */
    private fun songCount(n: Int) = String.format(java.util.Locale.ROOT, resources.getQuantityString(R.plurals.music_songs, n), n)

    /** A row tapped, in the sheet or on the empty panel. */
    internal fun chooseRow(r: SourceRow) {
        when (r) {
            SourceRow.NAGHM -> {
                selectSource(Src.BT)
                if (!PhoneLink.connected) Snack.show(this, R.string.music_naghm_off)
            }
            SourceRow.BT -> {
                selectSource(Src.BT)
                // (the way to pair or connect a phone is in the firmware's own app)
                if (!BtMusic.connected) openBluetooth(this)
            }
            SourceRow.USB -> {
                // (the music files are asked for when the owner chooses them)
                if (!Library.hasPermission(this)) askPermission()
                libraryAsked = true
                selectSource(Src.LOCAL)
                showNowPlaying()
                showList()
            }
            SourceRow.YT -> {
                selectSource(Src.YT)
                // (no search in AMRI: its own app; what he plays there comes back by its session)
                if (!YtMusic.openApp(this)) {
                    Snack.show(this, R.string.yt_no_app, long = true, outlives = true)
                    YtMusic.openStore(this)
                }
            }
        }
    }

    private fun syncRows() {
        if (::emptyRows.isInitialized && noSong) for (r in emptyRows) r.set(rowTitle(r.row, full = r.row != SourceRow.NAGHM), if (r.row == SourceRow.NAGHM && !PhoneLink.connected) getString(R.string.music_from_iphone) else rowState(r.row), false)
        if (sourceSheet?.isOpen == true) sourceSheet?.refresh()
        if (moreSheet?.isOpen == true) moreSheet?.refresh()
    }

    private fun sheet(s: com.abdllh.aura.ui.Sheet) {
        catchUpLook()
        val sheets = shell.root.sheets
        if (s.parent == null) sheets.addView(s, FrameLayout.LayoutParams(MATCH, MATCH))
        shell.root.closeSheets()
        s.open()
    }

    private fun showMore() = sheet(moreSheet ?: MoreSheet(this, this).also { moreSheet = it })

    internal fun showSources() = sheet(sourceSheet ?: SourceSheet(this, this).also { sourceSheet = it })

    internal fun showQueue() = sheet(queueSheet ?: QueueSheet(this, this).also { queueSheet = it })

    // ---- what the More sheet asks
    internal val sourceGlyph: Int get() = rowNow().icon
    internal val sourceName: String get() = rowTitle(rowNow(), full = false)
    internal val nextName: String get() = if (queueIsLocal) Player.upcoming().getOrNull(1)?.title.orEmpty() else getString(queueElsewhere)
    /** The lyrics on the whole screen need something to read. */
    internal val fullLyricsOk: Boolean get() = lyricsResult != null && lyricsShown != null && lyricsShown == lyricsKey
    internal val lyricsAreOff: Boolean get() = lyricsOff
    /** Off is for a song, and with none to be had for this one there is nothing to turn off. */
    internal val lyricsCanGoOff: Boolean get() = lyricsSong != null && (lyricsOff || lyricsResult != null)

    internal fun openFull() {
        shell.root.closeSheets()
        showFull(true)
    }

    /** The switch: kept with the song ([Lyrics.setOff]), and the song looked at again: off, the big cover; back on, its lyrics are asked for. */
    internal fun setLyricsOff(off: Boolean) {
        val s = lyricsSong ?: return
        Lyrics.setOff(this, s.title, s.artist, s.offSec, off)
        lyricsKey = null
        refreshLyrics()
    }

    // ---- what the queue sheet asks
    internal val queueIsLocal: Boolean get() = src == Src.LOCAL
    internal val songTitle: String get() = sideSmall.title.text.toString()
    internal val songArtist: String get() = when (src) {
        Src.BT -> BtMusic.artist
        Src.YT -> MediaMonitor.app(YtMusic.players)?.artist.orEmpty()
        Src.LOCAL -> Player.current?.artist.orEmpty()
    }
    internal val songArt: Bitmap? get() = shownCover
    internal val queueElsewhere: Int get() = if (bt) R.string.music_queue_on_phone else R.string.music_queue_in_app

    internal fun playQueue(t: Track) {
        val q = Player.queue
        val i = q.indexOf(t)
        if (i < 0) return
        skipDir = 0; skipAt = SystemClock.uptimeMillis()
        Player.play(q, i)
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
            coverSmall.dim()
            coverBig.dim()
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

    // the song's names as they are shown
    private fun names(t: String, s: CharSequence) {
        sides { it.title.setTextIfChanged(t); it.sub.setTextIfChanged(s) }
        fullHead.setTextIfChanged(if (noSong) "" else join(t, songArtist))
    }

    private fun wrap(s: String): String = if (s.isBlank()) "" else BidiFormatter.getInstance().unicodeWrap(s)

    private fun join(a: String, b: String) = listOf(a, b).filter { it.isNotBlank() }.joinToString(" · ")

    /** Playing or not, and whether there is anything for the buttons to do. */
    private fun playState(playing: Boolean, on: Boolean) {
        sides { it.play.setPlaying(playing) }
        fullPlay.setPlaying(playing)
        // (no song: the grey cover stands at its full size, nothing is paused)
        breathe(playing || noSong)
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
        transportSet = true
        transportOn = on
        for (v in listOf<View>(sideSmall.prev, sideSmall.play, sideSmall.next, sideBig.prev, sideBig.play, sideBig.next, fullPrev, fullPlay, fullNext)) {
            v.isEnabled = on
            v.isClickable = on
            v.alpha = if (on) 1f else DISABLED_ALPHA
        }
    }

    /**
     * The cover breathes out a little while the music is paused, and back in when it plays, on a spring: the largest thing on the
     * screen says at a glance whether music plays. The group with its shadow is scaled, one transform over two bitmaps.
     */
    private val breath = Spring(1f, 380f, 0.78f) { poseCover() }
    private var breathSet = false
    /** 1: no song, the grey cover at its own rect ([poseCover]). */
    private val emptyK = Spring(0f, 300f, 1f) { poseCover() }

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
        songMovesUntil = SystemClock.uptimeMillis() + Motion.MEDIUM
        // (only what is on the screen: the other sets of names are out of sight)
        if (lyricsFull || !root.isShown) return
        val side = if (bigOn) sideBig else sideSmall
        side.title.riseIn(8f, 0L, Motion.SHORT)
        side.sub.riseIn(8f, 40L, Motion.SHORT)
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

    /**
     * A cover, from its source. Rounded once at each size and its wash built on [Backdrop]'s worker, then they land in one frame ([land]). What is on the screen stays until then; nothing per frame.
     * Late on a screen already up: a crossfade from the side of the skip, as [CoverSlot.swap] says; a screen just coming up simply has it.
     */
    private fun setArt(b: Bitmap?, key: Long) {
        lastArtKey = key
        cancelArtWait()
        if (coverSet && b === shownCover) { coverSmall.undim(); coverBig.undim(); return }
        val changed = coverSet
        val sameSong = artSong == shownSong
        artSong = shownSong
        shownCover = b
        coverSet = true
        val g = ++artGen
        val c = applicationContext
        val night = Palette.dark
        if (b == null) {
            Backdrop.washAsync(c, null, night) { w -> if (g == artGen && !isGone) land(noArtSmall(), noArtBig(), w, changed, sameSong) }
        } else {
            Backdrop.roundAsync(c, b, COVER, COVER_R) { small ->
                Backdrop.roundAsync(c, b, COVER_BIG, COVER_BIG_R) { big ->
                    Backdrop.washAsync(c, b, night) { w -> if (g == artGen && !isGone) land(BitmapDrawable(resources, small), BitmapDrawable(resources, big), w, changed, sameSong) }
                }
            }
        }
    }

    private fun noArtSmall() = Backdrop.noArt(this, COVER, COVER_R)
    private fun noArtBig() = Backdrop.noArt(this, COVER_BIG, COVER_BIG_R, glyphPx = NO_ART_BIG_GLYPH)

    /** The copy in sight comes in as a cover does; the other simply has it, for when the layout moves. */
    private fun land(small: Drawable, big: Drawable, w: Backdrop.Wash, changed: Boolean, sameSong: Boolean) {
        val animate = changed && root.isShown && SystemClock.uptimeMillis() - shownAt > ARRIVING_MS
        val seen = if (bigOn) coverBig else coverSmall
        val d = if (bigOn) big else small
        if (bigOn) coverSmall.show(small) else coverBig.show(big)
        when {
            !animate -> seen.show(d)
            sameSong -> seen.swap(d, null) // (a better copy of the same song's cover: a plain crossfade)
            else -> seen.swap(d, takeSkip())
        }
        if (w.night == Palette.dark) applyWash(w, animate)
    }

    /** The cover at one size; alpha and the flight are the slot's own, what a new cover does is the two pictures'. */
    private inner class CoverSlot : FrameLayout(this@MusicPanel) {
        private val art = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_XY }
        /** The cover before, leaving under the new one (see [swap]). */
        private val artOld = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_XY; visibility = View.INVISIBLE }

        init {
            addView(artOld, flp(MATCH, MATCH))
            addView(art, flp(MATCH, MATCH))
        }

        override fun hasOverlappingRendering() = false

        fun show(d: Drawable?) {
            art.animate().cancel(); artOld.animate().cancel()
            art.setImageDrawable(d)
            art.alpha = 1f; art.translationX = 0f; art.scaleX = 1f; art.scaleY = 1f
            artOld.setImageDrawable(null)
            artOld.visibility = View.INVISIBLE
        }

        /** A Bluetooth song's cover is on its way: the one before steps back ([btArt]). */
        fun dim() = art.fadeTo(0.45f, Motion.SHORT)

        fun undim() {
            art.animate().cancel()
            art.alpha = 1f
        }

        /**
         * A new cover over the old one. [dir] ±1: it comes in from that side, fast and settling, while the old one leaves
         * the other way, quicker; 0: it settles in place from a little larger; null: a plain crossfade. Only the two
         * pictures move, inside the cover's box (which the layouts' move and the flight own), clipped to its corners.
         */
        fun swap(next: Drawable?, dir: Int?) {
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
    }

    // ---- the wash: one small opaque bitmap stretched over the panel
    private var washNow: Backdrop.Wash? = null

    private fun applyWash(w: Backdrop.Wash, animate: Boolean) {
        val had = washNow != null
        washNow = w
        lyrics { it.wordColours(w.wordPill, w.wordPillInk) }
        wash.animate().cancel()
        if (!animate || !had) {
            wash.alpha = 1f
            wash.setImageBitmap(w.panel)
            wash.background = null
            washOld.setImageDrawable(null)
            washOld.visibility = View.INVISIBLE
            return
        }
        // the new one over the old one (both opaque: the old one needs no fading of its own)
        washOld.setImageDrawable(wash.drawable)
        washOld.visibility = View.VISIBLE
        wash.setImageBitmap(w.panel)
        wash.alpha = 0f
        wash.animate().alpha(1f).setStartDelay(0).setDuration(Tokens2.MS_coverWash).setInterpolator(Motion.MOVE).withEndAction {
            washOld.setImageDrawable(null)
            washOld.visibility = View.INVISIBLE
        }.start()
    }

    private var emptyShown: Boolean? = null

    /** The room and the four places, or the song: a crossfade, and the grey cover with the two lines go to their own rect by a transform. */
    private fun syncEmpty() {
        val e = noSong
        val lib = src == Src.LOCAL
        if (lyricsFull && e) showFull(false, animate = false, remember = false)
        val animate = emptyShown != null && emptyShown != e && root.isShown
        val rightNow: View = when { e -> emptyCard; lib -> library; else -> lyricsView }
        for (v in listOf(emptyCard, library, lyricsView)) {
            val on = v === rightNow
            if (on == (v.visibility == View.VISIBLE)) continue
            if (on) {
                v.visibility = View.VISIBLE
                if (root.isShown) { v.alpha = 0f; v.fadeTo(1f, Tokens2.MS_swap, Motion.MOVE) } else { v.animate().cancel(); v.alpha = 1f }
            } else { v.animate().cancel(); v.visibility = View.INVISIBLE; v.alpha = 1f }
        }
        sideSmall.title.setRole(if (e) Type.stateTitle else Type.musicTitle)
        sideSmall.sub.setRole(if (e) Type.stateBody else Type.musicSub)
        if (emptyShown == e) return
        emptyShown = e
        val tools = sideSmall.tools
        if (animate) {
            room.visibility = View.VISIBLE
            room.fadeTo(if (e) 1f else 0f, Tokens2.MS_cover, Motion.MOVE) { room.visibility = if (emptyShown == true) View.VISIBLE else View.INVISIBLE }
            tools.visibility = View.VISIBLE
            tools.fadeTo(if (e) 0f else 1f, Tokens2.MS_swap, Motion.MOVE) { tools.visibility = if (emptyShown == true) View.INVISIBLE else View.VISIBLE }
            coverShadow.fadeTo(if (e) 0f else 1f, Tokens2.MS_swap, Motion.MOVE)
            emptyK.animateTo(if (e) 1f else 0f)
        } else {
            room.animate().cancel()
            tools.animate().cancel()
            coverShadow.animate().cancel()
            room.alpha = if (e) 1f else 0f
            room.visibility = if (e) View.VISIBLE else View.INVISIBLE
            tools.alpha = if (e) 0f else 1f
            tools.visibility = if (e) View.INVISIBLE else View.VISIBLE
            coverShadow.alpha = if (e) 0f else 1f
            emptyK.snapTo(if (e) 1f else 0f)
        }
    }

    /** A label's role in its slot: the view stays where it is, only its size and weight go with the state. */
    private fun AText.setRole(r: Type.Role) {
        if (tag === r) return
        tag = r
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, r.sp)
        typeface = Fonts.of(r)
    }

    private fun paintTabs() {
        if (tabs.selected != tab.ordinal) tabs.select(tab.ordinal)
    }

    private fun selectSource(to: Src) {
        if (to == src) return
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
        // (the library lay beside the small cover whatever the song had: that is the last state, not its lyrics)
        if (was == Src.LOCAL) noLyrics = false
        cancelArtWait()
        lastArtKey = Long.MIN_VALUE
        seekTarget = -1L
        skipAt = 0L
        showNowPlaying()
        showList()
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

    /** The groups of [t] as the library has them now (null for the songs). */
    private fun groupsOf(t: Tab): List<Group>? = when (t) {
        Tab.SONGS -> null
        Tab.ALBUMS -> Library.albums
        Tab.ARTISTS -> Library.artists
        Tab.FOLDERS -> Library.folders
    }

    /**
     * The library's list for the tab and group. A list keeps its place when its songs change (a USB stick still being indexed reloads every few seconds) and when the owner comes back to it
     * from another tab or a group; [nav] is how a new list comes in. Its states stand in the card's list area.
     */
    private fun showList(nav: Nav = Nav.NONE) {
        if (!::listHolder.isInitialized) return
        paintTabs()
        // (a reload makes new groups: the one open is found again by its key, or left when it is gone)
        group = group?.let { g -> groupsOf(tab)?.firstOrNull { it.key == g.key } }
        val g0 = group
        crumb.visibility = if (g0 != null) View.VISIBLE else View.INVISIBLE
        tabs.visibility = if (g0 != null) View.INVISIBLE else View.VISIBLE
        crumbText.setTextIfChanged(g0?.title.orEmpty())
        val state = when {
            !Library.hasPermission(this) -> 0
            !Library.loaded -> 1
            Library.tracks.isEmpty() -> 2
            else -> -1
        }
        if (state >= 0) {
            rememberSpot()
            shownList = null
            listHolder.visibility = View.INVISIBLE
            when (state) {
                0 -> showEmpty(getString(R.string.music_permission), getString(R.string.music_allow)) { askPermission() }
                1 -> showEmpty(getString(R.string.music_loading), null) {}
                else -> showEmpty(getString(R.string.music_no_drive), getString(R.string.music_src_bt)) { chooseRow(SourceRow.BT) }
            }
            return
        }
        empty.visibility = View.GONE
        listHolder.visibility = View.VISIBLE
        val g = group
        val key = "${tab.name}|${g?.key}"
        val grid = g == null && tab == Tab.ALBUMS
        val fresh = key != shownList
        if (fresh) rememberSpot()
        when {
            g != null -> tracks.items = g.tracks
            tab == Tab.SONGS -> tracks.items = Library.tracks
            tab == Tab.ALBUMS -> albums.items = Library.albums
            tab == Tab.ARTISTS -> groups.set(Library.artists, R.drawable.ic_person)
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
                listHolder.fadeTo(1f, Tokens2.MS_swap, Motion.MOVE)
            }
            Nav.INTO -> listHolder.enter(dx = 12f * toward, dur = Tokens2.MS_cardIn)
            Nav.BACK -> listHolder.enter(dx = -12f * toward, dur = Tokens2.MS_cardIn)
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
        setPadding(0, 0, 0, 28)
        if (this is ListView) divider = null
    }

    private inner class TracksAdapter : BaseAdapter() {
        var items: List<Track> = emptyList()
        override fun getCount() = items.size
        override fun getItem(i: Int) = items[i]
        override fun getItemId(i: Int) = items[i].id
        override fun hasStableIds() = true
        override fun getView(i: Int, convert: View?, parent: ViewGroup): View {
            val row = (convert as? SongRowView) ?: SongRowView(this@MusicPanel)
            val t = items[i]
            row.bind(t.title, t.artist.ifBlank { t.album }, t.durationText, t.id == Player.current?.id && src == Src.LOCAL)
            row.picture(t.albumId, showing) { done -> ArtLoader.get(this@MusicPanel, t, SongRowView.COVER, Tokens2.R_small, done) }
            row.setOnClickListener { if (i < items.size) playFrom(items, i) }
            return row
        }
    }

    private inner class GroupsAdapter : BaseAdapter() {
        var items: List<Group> = emptyList()
        private var icon = R.drawable.ic_person
        fun set(g: List<Group>, ic: Int) { items = g; icon = ic }
        override fun getCount() = items.size
        override fun getItem(i: Int) = items[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, convert: View?, parent: ViewGroup): View {
            val row = (convert as? SongRowView) ?: SongRowView(this@MusicPanel)
            val g = items[i]
            row.bindGroup(icon, g.title, songCount(g.tracks.size))
            row.setOnClickListener { openGroup(g) }
            return row
        }
    }

    private inner class AlbumsAdapter : BaseAdapter() {
        var items: List<Group> = emptyList()
        override fun getCount() = items.size
        override fun getItem(i: Int) = items[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, convert: View?, parent: ViewGroup): View {
            val cell = (convert as? AlbumCell) ?: AlbumCell(this@MusicPanel)
            cell.bind(items[i])
            return cell
        }
    }

    /** Album cell (SPEC.md section 5): cover 120 radius 16, `gridLabel`, two lines at most. Recycled; a late cover for a recycled cell is ignored. */
    private inner class AlbumCell(ctx: Context) : FrameLayout(ctx) {
        private val img = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_XY }
        private val name = ctx.label(Type.gridLabel, { Palette.ink }, lines = 2, gravity = Gravity.CENTER_HORIZONTAL)
        private var key = Long.MIN_VALUE
        private var g: Group? = null
        private var seenDark = Palette.dark

        init {
            isClickable = true
            pressScale(PressGesture.SURFACE)
            setOnClickListener { g?.let { openGroup(it) } }
            layoutParams = AbsListView.LayoutParams(MATCH, ALBUM_H)
            addView(ctx.plateView(Tokens2.R_mid) { Palette.fill2 }, flp(ALBUM, ALBUM, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            addView(ctx.iconView(R.drawable.ic_disc, 44) { Palette.ink2 }, flp(44, 44, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = (ALBUM - 44) / 2 })
            addView(img, flp(ALBUM, ALBUM, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            addView(name, flp(MATCH, WRAP, Gravity.TOP).apply { topMargin = ALBUM + 6; marginStart = 4; marginEnd = 4 })
        }

        fun dropPicture() { img.animate().cancel(); img.setImageDrawable(null) }

        fun bind(group: Group) {
            if (seenDark != Palette.dark) { seenDark = Palette.dark; Look.apply(this) }
            g = group
            name.setTextIfChanged(group.title)
            val t = group.tracks.first()
            if (key == t.albumId && img.drawable != null) return
            key = t.albumId
            img.animate().cancel()
            img.alpha = 1f
            img.setImageDrawable(null)
            if (!showing) { ArtLoader.get(context, t, ALBUM, Tokens2.R_mid) { }; return }
            var sync = true
            ArtLoader.get(context, t, ALBUM, Tokens2.R_mid) { b ->
                if (b != null && key == t.albumId && showing) {
                    img.setImageBitmap(b)
                    if (!sync) { img.alpha = 0f; img.fadeTo(1f) }
                }
            }
            sync = false
        }
    }

    // ------------------------------------------------------------------------------------------ the views, at the spec's pixels
    private fun buildUi() {
        // (the bottom of the room: until the first wash lands, and the window has no background)
        washOld = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY; visibility = View.INVISIBLE }
        wash = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY }.look { if (washNow == null) setBackgroundColor(Palette.bg) }
        root.addView(washOld, at(0, 0, 1024, 600))
        root.addView(wash, at(0, 0, 1024, 600))
        room = lateImage("room").apply { visibility = View.INVISIBLE; alpha = 0f }
        root.addView(room, at(0, 0, 1024, 600))
        content = FrameLayout(this)
        root.addView(content, at(0, 0, 1024, 600))

        // ---- the cover, with its baked shadow: 340 at (38, -6), scaled to 588 at (-62, -16) for the big one. Each copy is 1:1 on the screen where it is the one in sight
        coverGroup = FrameLayout(this).apply { clipChildren = false }
        coverShadow = lateImage("cover-shadow")
        coverGroup.addView(coverShadow, flp(MATCH, MATCH))
        coverSmall = CoverSlot().apply { show(noArtSmall()) }
        coverGroup.addView(coverSmall, at((COVER_GROUP - COVER) / 2, (COVER_GROUP - COVER) / 2, COVER, COVER))
        coverBig = CoverSlot().apply { show(noArtBig()); scaleX = 1f / BIG_SCALE; scaleY = 1f / BIG_SCALE; alpha = 0f; visibility = View.INVISIBLE }
        coverGroup.addView(coverBig, at((COVER_GROUP - COVER_BIG) / 2, (COVER_GROUP - COVER_BIG) / 2, COVER_BIG, COVER_BIG))
        content.addView(coverGroup, at(38, -6, COVER_GROUP, COVER_GROUP))

        sideSmall = SongSide(false)
        content.addView(sideSmall, at(SMALL_X, SMALL_Y, 420, 516 - SMALL_Y))

        // ---- the right: the lyrics, the library, or the four places
        beside = FrameLayout(this)
        content.addView(beside, at(BESIDE_X, 0, 1024 - BESIDE_X, 600))
        lyricsView = LyricsView(this).apply { onLineTap = { l -> onLineTap(l) }; onLineHold = { l -> onLineHold(l) }; wordsOn = Words.on }
        beside.addView(lyricsView, at(424 - BESIDE_X - LyricsView.SIDE, LyricsView.TOP, 584 + 2 * LyricsView.SIDE, LyricsView.HEIGHT))
        buildLibrary()
        beside.addView(library, Surface.CARD.at(424 - BESIDE_X, 48, 584, 456))
        emptyCard = SurfaceCard(this, Surface.CARD).apply { visibility = View.INVISIBLE }
        val rowsCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        emptyRows = SourceRow.values().mapIndexed { i, r ->
            SourceRowView(this, r, chevron = true, first = i == 0) { chooseRow(it) }.also { rowsCol.addView(it, lp(MATCH, SourceRowView.HEIGHT)) }
        }
        emptyCard.addView(rowsCol, flp(MATCH, MATCH))
        beside.addView(emptyCard, Surface.CARD.at(424 - BESIDE_X, 48, 584, 4 * SourceRowView.HEIGHT))

        sideBig = SongSide(true).apply { alpha = 0f; visibility = View.INVISIBLE }
        content.addView(sideBig, at(BIG_X, BIG_Y, 544, 476))

        buildFull()
        root.addView(fullLayer, at(0, 0, 1024, 600))
        root.look { takeTheme() }
    }

    /** A big bitmap of the theme, decoded off the main thread: a hardware bitmap there holds the build, or the swap, for what can be a second on a loaded GPU. */
    private fun lateImage(stem: String): ImageView = ImageView(this).apply {
        scaleType = ImageView.ScaleType.FIT_XY
        look {
            val night = Palette.dark
            val c = applicationContext
            Thread({
                val b = com.abdllh.aura.ui.Surfaces.themed(c, stem, night)
                post { if (night == Palette.dark) setImageBitmap(b) }
            }, "music-art").start()
        }
    }

    /**
     * The song's names, its place and its transport in one of the two layouts: under the cover (`music`, CHANGES.md section 6) or beside the big one (`music-no-lyrics`, section 7). Both stay laid out and are told the same
     * things; the panel goes between them by alpha alone ([setBigK]). Its rects are the tables', from the panel's corner.
     */
    private inner class SongSide(private val big: Boolean) : FrameLayout(this@MusicPanel) {
        private val ox = if (big) BIG_X else SMALL_X
        private val oy = if (big) BIG_Y else SMALL_Y
        val names = FrameLayout(context)
        val title: AText = label(if (big) Type.musicTitleBig else Type.musicTitle, { Palette.ink }, gravity = Gravity.CENTER)
        val sub: AText = label(if (big) Type.musicSubBig else Type.musicSub, { Palette.ink2 }, gravity = Gravity.CENTER)
        val tools = FrameLayout(context)
        val seek = SeekView(context).apply { onSeek = { f -> seekTo(f) }; if (big) trackH = 6f }
        private val line: AText = label(Type.musicSub, { Palette.ink2 }, gravity = Gravity.CENTER).apply { visibility = View.INVISIBLE }
        val prev = IconButton(context, R.drawable.ic_prev, if (big) 56 else 44, if (big) 88 else 76, if (big) 112 else 84, token = { Palette.ink }).apply {
            contentDescription = getString(R.string.media_prev)
            setOnClickListener { transport(0) }
        }
        val play = PlayPauseView(context).apply {
            disc = { Palette.ink }
            token = { Palette.wash }
            glyphPx = if (big) 54 else 42
            isClickable = true
            pressScale(PressGesture.CONTROL)
            contentDescription = getString(R.string.media_play)
            setOnClickListener { transport(1) }
        }
        val next = IconButton(context, R.drawable.ic_next, if (big) 56 else 44, if (big) 88 else 76, if (big) 112 else 84, token = { Palette.ink }).apply {
            contentDescription = getString(R.string.media_next)
            setOnClickListener { transport(2) }
        }

        init {
            clipChildren = false
            addView(names, flp(MATCH, MATCH))
            addView(tools, flp(MATCH, MATCH))
            val more = IconButton(context, R.drawable.ic_more, 30, 56, 56, IconButton.Kind.TONAL, plateRadius = Tokens2.R_mid, plateToken = { Palette.fill }, token = { Palette.ink2 }).apply {
                contentDescription = getString(R.string.music_more_title)
                setOnClickListener { showMore() }
            }
            if (big) {
                names.addView(title, Type.musicTitleBig.box(476 - ox, 131 - oy, 520))
                names.addView(sub, Type.musicSubBig.box(476 - ox, 197 - oy, 520))
                // (30 drawn, 48 to take: its centre is the slot's)
                tools.addView(seek, at(496 - ox, 254 - oy, 480, 48))
                tools.addView(line, Type.musicSub.box(496 - ox, 262 - oy, 480))
                tools.addView(prev, at(572 - ox, 313 - oy, 88, 112))
                tools.addView(play, at(680 - ox, 313 - oy, 112, 112))
                tools.addView(next, at(812 - ox, 313 - oy, 88, 112))
                tools.addView(more, at(496 - ox, 341 - oy, 56, 56))
            } else {
                names.addView(title, Type.musicTitle.box(16 - ox, 288 - oy, 384))
                names.addView(sub, Type.musicSub.box(16 - ox, 328 - oy, 384))
                tools.addView(seek, at(32 - ox, 359 - oy, 352, 48))
                tools.addView(line, Type.musicSub.box(16 - ox, 367 - oy, 384))
                tools.addView(prev, at(78 - ox, 410 - oy, 76, 84))
                tools.addView(play, at(166 - ox, 410 - oy, 84, 84))
                tools.addView(next, at(262 - ox, 410 - oy, 76, 84))
                tools.addView(more, at(16 - ox, 424 - oy, 56, 56))
            }
        }

        /** The bar, or one line in its slot, or neither: the big side then closes up, its column still centred. */
        fun slot(bar: Boolean, line: CharSequence = "") {
            val barSeen = if (bar) View.VISIBLE else View.INVISIBLE
            if (seek.visibility != barSeen) seek.visibility = barSeen
            this.line.setTextIfChanged(line)
            val lineSeen = if (line.isEmpty()) View.INVISIBLE else View.VISIBLE
            if (this.line.visibility != lineSeen) this.line.visibility = lineSeen
            if (big) {
                val closed = !bar && line.isEmpty()
                names.translationY = if (closed) NO_BAR_DY else 0f
                tools.translationY = if (closed) -NO_BAR_DY else 0f
            }
        }
    }

    private fun buildLibrary() {
        library = SurfaceCard(this, Surface.CARD).apply { visibility = View.INVISIBLE }
        tabs = Segmented(this, listOf(getString(R.string.music_songs), getString(R.string.music_albums),
            getString(R.string.music_tab_artists), getString(R.string.music_folders)), equal = false) { i ->
            tab = Tab.values()[i]
            group = null
            showList(Nav.TAB)
        }
        // (each as wide as its word, and what is left shared out: as drawn, and no word cut)
        for (i in 0 until tabs.childCount) (tabs.getChildAt(i).layoutParams as LinearLayout.LayoutParams).weight = 1f
        // (at the reading edge)
        library.addView(tabs, at(if (rtl) 136 else 8, 8, 440, 60))
        // the open album, artist or folder: the way back, its name, and Play all
        crumb = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.INVISIBLE
        }
        crumb.addView(IconButton(this, R.drawable.ic_back, 28, 56, 60, token = { Palette.ink }).apply {
            contentDescription = getString(R.string.music_back)
            setOnClickListener { group = null; showList(Nav.BACK) }
        }, lp(56, 60))
        crumbText = label(Type.resultTitle, { Palette.ink })
        crumb.addView(crumbText, lp(0, Type.resultTitle.viewH, 1f).apply { marginStart = 8; marginEnd = 8 })
        crumb.addView(ABtn(this).apply {
            kind = ABtn.Kind.TONAL
            icon(R.drawable.ic_play)
            setText(R.string.music_play_all)
            setOnClickListener { group?.let { playFrom(it.tracks, 0) } }
        }, lp(PLAY_ALL_W, ABtn.Size.M.height))
        library.addView(crumb, at(8, 8, 568, 60))

        listHolder = FrameLayout(this)
        listView = ListView(this).apply { libraryStyle() }
        listHolder.addView(listView, flp(MATCH, MATCH))
        gridView = GridView(this).apply {
            numColumns = ALBUM_COLUMNS
            verticalSpacing = 0
            horizontalSpacing = 0
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            libraryStyle()
            visibility = View.GONE
        }
        listHolder.addView(gridView, flp(MATCH, MATCH))
        listFade = scrollFade(Surface.CARD, top = false)
        listHolder.addView(listFade, flp(MATCH, 28, Gravity.BOTTOM))
        library.addView(listHolder, at(8, 72, 568, 376)) // (the bottom fade stands clear of the card's round corners)

        empty = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; visibility = View.GONE }
        emptyText = label(Type.stateTitle, { Palette.ink }, lines = 3, gravity = Gravity.CENTER)
        empty.addView(emptyText, lp(MATCH, WRAP).apply { setMargins(40, 0, 40, 0) })
        emptyBtn = ABtn(this).apply { kind = ABtn.Kind.TONAL }
        empty.addView(emptyBtn, lp(EMPTY_BTN_W, ABtn.Size.M.height).apply { topMargin = 18 })
        emptySpinner = Spinner(this, 24).apply { visibility = View.GONE }
        empty.addView(emptySpinner, lp(24, 24).apply { topMargin = 18 })
        library.addView(empty, at(0, 72, 584, 384))
    }

    /** The full lyrics: no cover, the song's name above, the larger lines, small transport bottom left, collapse bottom right. */
    private fun buildFull() {
        fullLayer = FrameLayout(this).apply { isClickable = true; visibility = View.INVISIBLE } // (taps do not reach the panel below)
        lyricsBig = LyricsView(this, big = true).apply { onLineTap = { l -> onLineTap(l) }; onLineHold = { l -> onLineHold(l) }; wordsOn = Words.on }
        fullLayer.addView(lyricsBig, at(40 - LyricsView.SIDE, LyricsView.TOP_BIG, 944 + 2 * LyricsView.SIDE, LyricsView.HEIGHT_BIG))
        fullHead = label(Type.musicSub, { Palette.ink2 })
        fullLayer.addView(fullHead, Type.musicSub.box(40, 40, 944))
        fullPrev = IconButton(this, R.drawable.ic_prev, 34, 72, 52, token = { Palette.ink }).apply {
            contentDescription = getString(R.string.media_prev)
            setOnClickListener { transport(0) }
        }
        fullLayer.addView(fullPrev, at(24, 452, 72, 52))
        fullPlay = PlayPauseView(this).apply {
            token = { Palette.ink }
            glyphPx = 38
            isClickable = true
            pressScale(PressGesture.ICON)
            contentDescription = getString(R.string.media_play)
            setOnClickListener { transport(1) }
        }
        fullLayer.addView(fullPlay, at(96, 452, 72, 52))
        fullNext = IconButton(this, R.drawable.ic_next, 34, 72, 52, token = { Palette.ink }).apply {
            contentDescription = getString(R.string.media_next)
            setOnClickListener { transport(2) }
        }
        fullLayer.addView(fullNext, at(168, 452, 72, 52))
        fullLayer.addView(IconButton(this, R.drawable.ic_collapse, 30, 72, 52, token = { Palette.ink2 }).apply {
            contentDescription = getString(R.string.lyrics_smaller)
            setOnClickListener { showFull(false) }
        }, at(928, 452, 72, 52))
    }

    // Was onCreate, here after every field of the class (Kotlin runs these in order). Runs when the home screen is quiet or, when
    // Music is asked for before that, at that moment. All it can already say is said, with the list's rows, so that they are laid
    // out with the rest while out of sight and the first open has no more to do than a later one; the rows' pictures and the lyrics
    // wait for it ([showing]).
    init {
        debugFail("build")
        Player.init(this)
        BtMusic.init(this)
        MediaMonitor.start(this) // (other apps' sessions: the YouTube source reads one)
        src = sourceNow()
        lyricsFull = src != Src.LOCAL && Prefs.raw.getBoolean("musicLyricsFull", false)
        buildUi()
        if (lyricsFull) showFull(true, animate = false, remember = false)
        showNowPlaying()
        showList()
        btCover = null
        ytCover = null
        ThemeSwap.readySteps.add(readyStep)
        // Asked for only where the music files are wanted (the library's own button, or choosing its source): the
        // screen opened for the phone's music used to start with a system dialog over the song.
        if (Library.hasPermission(this) && !Library.loaded) Library.load(this)
    }

    companion object {
        /** The source the screen is on while there is one (null: none), for [com.abdllh.aura.system.MusicKeys]. */
        @Volatile var onBluetooth: Boolean? = null
            private set

        // art keys for "no cover" states (album ids are >= 0)
        private const val BT_ART = Long.MIN_VALUE + 1
        private const val NO_ART = -2L
        private const val YT_ART = Long.MIN_VALUE + 2
        /** How long after a line starts the owner, hearing it, taps it. */
        private const val TAP_REACTION_MS = 450L
        /** A tap further than this from the line lit is the clock being wrong (the phone moved), not the lyrics' timing. */
        private const val MAX_CORRECTION_MS = 15_000L
        /**
         * How long after hearing a line start the owner, asked to hold it the moment it is heard, puts the finger down
         * (1.18: shorter than [TAP_REACTION_MS], a tap on "what is being sung"; he is waiting for it).
         */
        private const val HOLD_REACTION_MS = 200L
        /** A measured delay this far below zero is a line held before it was heard: measured again, not kept. */
        private const val DELAY_SLACK_MS = 300L
        /** A tapped line stays lit this much past the delay (Naghm's word that it moved takes a moment over the air). */
        private const val TAP_LIT_SLACK_MS = 400L
        private const val DELAY_HINT_KEY = "btDelayHinted"

        /** The cover beside the lyrics, and the big one with no lyrics. */
        private const val COVER = 232
        private const val COVER_R = 23
        private const val COVER_BIG = 400
        private const val COVER_BIG_R = 26
        private const val NO_ART_BIG_GLYPH = 150
        /** The group of the cover and its shadow, at section 6's rect: 340, the cover's centre at (208, 164). */
        private const val COVER_GROUP = 340
        /** To the big cover's rect, centre (232, 278). */
        private const val BIG_SCALE = COVER_BIG.toFloat() / COVER
        private const val BIG_DX = 24f
        private const val BIG_DY = 114f
        /** To the grey cover of no song, 204 at (92, 48) with its two lines 30 higher, as SPEC.md section 12 has it. */
        private const val EMPTY_SCALE = 204f / COVER
        private const val EMPTY_DX = -14f
        private const val EMPTY_NAMES_DY = -30f
        /** The two sides' corners: `nowPlaying` of the table, and room for the two lines of no song under the small cover. */
        private const val BIG_X = 464
        private const val BIG_Y = 40
        private const val SMALL_X = 0
        private const val SMALL_Y = 240
        /** The big side with no bar to show: names and transport closing up by this each. */
        private const val NO_BAR_DY = 25f
        /** Where what lies beside the small cover starts: room for the library card's shadow. One layer in the move. */
        private const val BESIDE_X = 380
        /** Asked of the loader for the big cover. */
        private const val ART_PX = 600
        /** Buttons in the library, wide enough for their word in either language: an exact width, a wrapped one lost its padding. */
        private const val PLAY_ALL_W = 196
        private const val EMPTY_BTN_W = 220
        /** The album cell's cover, its height, and the columns of the grid: 568 / 4. */
        private const val ALBUM = 120
        private const val ALBUM_H = 196
        private const val ALBUM_COLUMNS = 4
        /** A paused cover, as seen (see [breathe]). */
        private const val PAUSED_SCALE = 0.94f
        /** Buttons with nothing to do (the same as a disabled [ABtn]). */
        private const val DISABLED_ALPHA = 0.4f
        /** How far a new cover comes from; the old one leaves half that the other way. */
        private const val COVER_SLIDE_DP = 28f
        /** A skip press counts for the next cover this long (a Bluetooth cover is looked up by name first). */
        private const val SKIP_MEMORY_MS = 5000L
        /** The cover before waits this long, dimmed, for a Bluetooth song's own. */
        private const val ART_WAIT_MS = 2500L
        /** A seek is shown where it was asked for at most this long while the source still says otherwise. */
        private const val SEEK_HOLD_MS = 1500L
        /** A screen that left this long ago is out of sight, whatever became of its move (the stack ends any at 2 s). */
        private const val LET_GO_AFTER_MS = 2500L
        /** A screen that came to the front this recently is still on its way in ([setArt]). */
        private const val ARRIVING_MS = 400L
        /** "Looking for the lyrics" shows only when the answer takes longer than this. */
        private const val LOADING_AFTER_MS = 600L
        /** How often it looks at where the song is. Nothing redraws unless something changed. */
        private const val TICK_MS = 250L

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
 * width wraps its content asks the whole screen for a layout pass: the progress tick and the phone's repeated song reports did both for text that had not changed.
 */
private fun TextView.setTextIfChanged(s: CharSequence?) {
    if (!TextUtils.equals(text, s)) text = s
}
