package com.abdllh.aura.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.abdllh.aura.home.HomeActivity
import com.abdllh.aura.home.Panel
import com.abdllh.aura.system.SystemProps

/**
 * Debug builds: a panel to try the stack out with what no real panel does yet, and the smallest example of one.
 *   am start -a android.intent.action.MAIN -c android.intent.category.HOME --es panel probe com.abdllh.aura
 * Not opaque and with ADJUST_RESIZE, as Maps will be: a coloured view in the map's slot shows through it and the field at
 * its bottom brings the keyboard, and the log (AuraProbe) tells each layer's size: only this panel follows the keyboard.
 * setprop debug.aura.probefail build|show|resume makes it throw there: the stack must drop it and bring back the panel
 * under it, and the home screen goes on. setprop debug.aura.probesurface 1 puts a real SurfaceView in the map's slot for
 * the window's life, as the map will be: the log tells every time its surface is made, changed or lost.
 */
class ProbePanel(shell: HomeActivity) : Panel(shell, "probe") {
    private val slot = View(this).apply { setBackgroundColor(Color.rgb(30, 90, 60)) }
    private val failAt = SystemProps.get("debug.aura.probefail")

    override val root = FrameLayout(this)
    override val keep: Boolean get() = SystemProps.get("debug.aura.probekeep") == "1"
    override val softInput: Int get() = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN

    init {
        if (failAt == "build") throw IllegalStateException("probe: build")
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM }
        col.addView(TextView(this).apply { text = "probe panel: tap to leave"; textSize = 24f; setTextColor(Color.WHITE); setOnClickListener { finish() } })
        col.addView(EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT; hint = "type"; setTextColor(Color.WHITE) })
        root.addView(col, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val r = shell.root
            val home = r.panelLayer.getChildAt(0)
            Log.i("AuraProbe", "root ${r.width}x${r.height}, map's slot ${r.mapHost.width}x${r.mapHost.height}, this panel ${root.width}x${root.height}, home ${home.width}x${home.height}")
        }
    }

    override fun onShow(args: Bundle?) {
        Log.i("AuraProbe", "onShow ${args?.keySet()?.joinToString()}")
        if (failAt == "show") throw IllegalStateException("probe: show")
        if (SystemProps.get("debug.aura.probesurface") == "1") surfaceInSlot(shell)
        else shell.root.mapHost.addView(slot, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    override fun onArgs(args: Bundle) {
        Log.i("AuraProbe", "onArgs ${args.keySet()?.joinToString()}")
    }

    override fun onResume() {
        Log.i("AuraProbe", "onResume")
        if (failAt == "resume") throw IllegalStateException("probe: resume")
    }

    override fun onPause() {
        Log.i("AuraProbe", "onPause")
    }

    override fun onHide() {
        Log.i("AuraProbe", "onHide, gone: $isGone")
        shell.root.mapHost.removeView(slot)
    }

    override fun onDestroy() {
        Log.i("AuraProbe", "onDestroy")
    }
}

/** A surface in the map's slot, a grid drawn on it, once for the window: nobody else touches it. */
private fun surfaceInSlot(shell: HomeActivity) {
    val slot = shell.root.mapHost
    if (slot.childCount > 0) return
    val v = SurfaceView(shell)
    v.holder.addCallback(object : SurfaceHolder.Callback {
        private fun paint(h: SurfaceHolder) {
            val c = h.lockCanvas() ?: return
            c.drawColor(Color.rgb(20, 60, 90))
            val p = Paint().apply { color = Color.WHITE; textSize = 20f }
            var y = 0
            while (y < c.height) {
                c.drawLine(0f, y.toFloat(), c.width.toFloat(), y.toFloat(), p)
                c.drawText("y $y", 620f, y + 22f, p)
                y += 100
            }
            h.unlockCanvasAndPost(c)
        }
        override fun surfaceCreated(h: SurfaceHolder) = Log.i("AuraProbe", "surface created").let { paint(h) }
        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) = Log.i("AuraProbe", "surface changed ${w}x$ht").let { paint(h) }
        override fun surfaceDestroyed(h: SurfaceHolder) = Log.i("AuraProbe", "surface destroyed").let { }
    })
    slot.addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
}

/**
 * Quick changes of mind, on the main thread, faster than a finger or adb:
 *   am broadcast -a com.abdllh.aura.debug.PANELS --ei rounds 60 --es panels settings,probe
 * Opens, goes back and opens again at random 0-300 ms apart, Home at the end; then says in the log (AuraProbe) how the
 * panel layer has come to rest: one frame, in place, no layer. --es prepare probe builds that one ahead of time first
 * (setprop debug.aura.probekeep 1 for one that stays).
 */
class PanelStress : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val main = Handler(Looper.getMainLooper())
        i.getStringExtra("prepare")?.let { HomeActivity.current?.panels?.prepare(it) }
        val r = java.util.Random(i.getLongExtra("seed", 7L))
        val names = (i.getStringExtra("panels") ?: "settings").split(',')
        var left = i.getIntExtra("rounds", 60)
        val step = object : Runnable {
            override fun run() {
                val s = HomeActivity.current ?: return
                if (left-- <= 0) {
                    s.panels.show("home")
                    main.postDelayed({ report(s) }, 1500L)
                    return
                }
                when (r.nextInt(5)) {
                    0, 1 -> s.panels.show(names[r.nextInt(names.size)])
                    2, 3 -> s.panels.back()
                    else -> s.panels.show("home")
                }
                main.postDelayed(this, r.nextInt(300).toLong())
            }
        }
        main.post(step)
    }

    private fun report(s: HomeActivity) {
        val l = s.root.panelLayer
        val frames = (0 until l.childCount).map { l.getChildAt(it) }.joinToString { f ->
            "vis=${f.visibility} alpha=${f.alpha} y=${f.translationY} layer=${f.layerType}"
        }
        Log.i("AuraProbe", "at rest: top=${s.panels.top.name} frames=${l.childCount} [$frames]")
    }
}
