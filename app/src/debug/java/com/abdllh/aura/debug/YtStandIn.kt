package com.abdllh.aura.debug

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import com.abdllh.aura.music.Library
import com.abdllh.aura.music.Player

/**
 * Debug builds: what YouTube's app does with a hand-over ([com.abdllh.aura.music.YtMusic.play]), which no emulator has: it comes
 * to the front with the link and a moment later plays, in a session of its own, and the real copy of AMRI is to come back.
 *   gradlew assembleDebug -PstandIn, install it, grant it the storage permission
 *   setprop debug.aura.ytpkg com.abdllh.aura.standin; setprop debug.aura.ytfake 1 (then start the real copy)
 */
class YtStandIn : Activity() {
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Stand-in for YouTube's app\n${intent?.data}"; textSize = 28f })
        main.postDelayed(::play, 1200L)
    }

    private fun play() {
        Player.init(this)
        if (Library.tracks.isNotEmpty()) Player.play(Library.tracks, 0) else main.postDelayed(::play, 500L)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
