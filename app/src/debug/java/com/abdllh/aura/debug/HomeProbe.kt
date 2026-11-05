package com.abdllh.aura.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Log
import com.abdllh.aura.home.HomePanel
import com.abdllh.aura.media.NowPlaying
import com.abdllh.aura.nav.Turn

/**
 * Debug builds: Home's states with the mockups' words, for a picture beside them (AuraHomeProbe in the log).
 *   am broadcast -n com.abdllh.aura/.debug.HomeProbe
 *      --es song "سهر الطريق" --es artist "فرقة الساحل" --es src "من نغم" --es art sea --ef at .347 --ez playing true    the widget's song (no art: the no-cover state); --ef at -1: no bar (BT)
 *      --ez nosong true                       nothing playing
 *      --ei face 0|1|2                        parked, moving, on a trip, with no fix and no route
 *      --ei kmh 87 --ei limit 120 --ez over false    the speed in the search's slot
 *      --es dist "2.4 كم" --es road "..." --es eta "7:38 م" --es left "5 دقائق"     the trip's pills
 *      --es third "الجبيل مول"                the third chip
 *      --es time 4:18 --es date "..."         the clock
 *      --es theme night|day|auto              through the real ThemeSwap, 700 ms
 *      --ez turn true                         where the turntable is, in the log
 */
class HomeProbe : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val p = HomePanel.current ?: return
        i.getStringExtra("theme")?.let { t ->
            com.abdllh.aura.util.Prefs.theme = if (t == "night") "dark" else if (t == "day") "light" else "auto"
            com.abdllh.aura.ui.ThemeSwap.run(p.shell)
        }
        if (i.getBooleanExtra("nosong", false)) p.debugWidget.debugSong(null, null, -1f)
        i.getStringExtra("song")?.let { title ->
            val art = i.getStringExtra("art")?.let { n -> c.assets.open("gallery/$n.jpg").use { BitmapFactory.decodeStream(it) } }
            p.debugWidget.debugSong(NowPlaying(title, i.getStringExtra("artist") ?: "", art, i.getBooleanExtra("playing", true), "bt"), i.getStringExtra("src"), i.getFloatExtra("at", .347f))
        }
        i.getStringExtra("third")?.let { p.debugCard.debugChips(it) }
        if (i.hasExtra("face")) {
            val f = i.getIntExtra("face", 0)
            p.debugFace(f == 1, f == 2)
        }
        if (i.hasExtra("kmh")) p.debugCard.debugSpeed(i.getIntExtra("kmh", 0), i.getIntExtra("limit", 0), i.getBooleanExtra("over", false))
        i.getStringExtra("dist")?.let { d ->
            p.debugCard.debugTrip(d, i.getStringExtra("road") ?: "", i.getStringExtra("eta") ?: "", i.getStringExtra("left") ?: "", Turn.SLIGHT_RIGHT)
        }
        i.getStringExtra("time")?.let { t -> p.debugClock.debugHold(t, i.getStringExtra("date") ?: "") }
        // (an emulator under load can stop drawing a window until its next relayout: DEV.md, "The gallery". On the unit this is not needed)
        p.root.requestLayout()
        if (i.getBooleanExtra("turn", false)) Log.i("AuraHomeProbe", "frames in ${p.debugStage.framesIn}, gl ${p.debugStage.gl?.drawing}, cheap ${p.debugStage.gl?.cheap}, fps ${p.debugStage.gl?.fps}")
    }
}
