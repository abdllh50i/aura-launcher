package com.abdllh.aura.home

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserManager
import android.util.Log

/**
 * AMRI's home screen for the first moments after the unit is powered on: a black screen that waits for the system to
 * open the user's storage, then asks for the home screen again — which is then [HomeActivity].
 *
 * Why it is needed. After a power-on (not after a sleep) Android starts the home screen while the user is still
 * "locked": only apps marked direct-boot aware exist for it then. [HomeActivity] is not one (its settings live in the
 * storage that is still closed). The firmware marks every package with "launcher" or "nwd" in its name as one
 * (PackageParser.parseBaseApplication, "force set … directBootAware=true"), so the stock launcher was the only home
 * screen to be found, and its patched home choice (PackageManagerService.chooseBestActivity) gives back the only
 * candidate without looking at persist.nwd.launcher.default. Nothing asks for the home screen again once the user is
 * unlocked: the stock home screen stayed until the owner pressed something that did (1.0–1.8, with "power off
 * completely when the car is switched off": every start of the car is then such a power-on).
 *
 * With this screen there are two candidates in that first moment, the choice reads the property, and AMRI's package
 * wins: this screen, since it is all of AMRI that exists yet. It runs in a process of its own (":boot": AuraApp does
 * nothing there) and touches nothing that is stored. Once the user is unlocked the home intent finds [HomeActivity]
 * (it comes first: this one's filter has a low priority), or the stock launcher when AMRI is switched off. It is what
 * Android's own FallbackHome does on a phone.
 */
class BootHome : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var startedAt = 0L
    private var handed = false
    private var registered = false

    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = look()
    }

    private val again = Runnable { look() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startedAt = SystemClock.elapsedRealtime()
        showWall()
        try {
            registerReceiver(unlocked, IntentFilter(Intent.ACTION_USER_UNLOCKED))
            registered = true
        } catch (t: Throwable) {
            Log.w(TAG, "receiver: $t")
        }
        look()
    }

    /**
     * Its picture (SPEC.md section 6.7): the wall with the logo 200 wide at (412, 266), the pixels Home's first frame starts from, so there is no seam. Which theme comes from the one flag kept in device-protected
     * storage ([BootFlag]); with none, night. Only what is in the APK besides that: the user's storage is not open yet. A picture that cannot be had leaves the black it always was.
     */
    private fun showWall() {
        try {
            val dp = createDeviceProtectedStorageContext()
            val night = BootFlag.night(dp)
            val arabic = BootFlag.arabic(dp)
            val root = android.widget.FrameLayout(this)
            val wall = assets.open("surfaces/wall-${if (night) "night" else "day"}.png").use {
                android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inScaled = false })
            }
            root.addView(android.widget.ImageView(this).apply {
                scaleType = android.widget.ImageView.ScaleType.FIT_XY
                setImageBitmap(wall)
            }, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
            HomePanel.bootLogo(this, night, arabic)?.let { logo ->
                root.addView(android.widget.ImageView(this).apply {
                    scaleType = android.widget.ImageView.ScaleType.FIT_XY
                    setImageBitmap(logo)
                }, android.widget.FrameLayout.LayoutParams(logo.width, logo.height, android.view.Gravity.LEFT or android.view.Gravity.TOP).apply {
                    leftMargin = 412
                    topMargin = 296 - logo.height / 2
                })
            }
            setContentView(root)
        } catch (t: Throwable) {
            Log.w(TAG, "picture: $t")
        }
    }

    override fun onDestroy() {
        main.removeCallbacks(again)
        if (registered) try { unregisterReceiver(unlocked) } catch (_: Throwable) { }
        super.onDestroy()
    }

    /** Unlocked yet? Then the real home screen; else looked at again shortly (and when the system says so). */
    private fun look() {
        if (handed) return
        main.removeCallbacks(again)
        val waited = SystemClock.elapsedRealtime() - startedAt
        val open = try { getSystemService(UserManager::class.java)?.isUserUnlocked == true } catch (_: Throwable) { false }
        if (!open) {
            // (the unlock follows this very screen's first frame; if it never comes, a screen that works is better
            // than a black one: the stock home screen, which exists in this state)
            if (waited > STUCK_MS) { hand(Intent().setClassName(STOCK_PKG, STOCK_HOME), "still locked after ${waited / 1000} s") ; return }
            main.postDelayed(again, 400L)
            return
        }
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        // (debug: the last resort below, which a stock Android cannot show by itself. docs/DEV.md, "One way to the screens")
        val found = if (com.abdllh.aura.BuildConfig.DEBUG && intent.getBooleanExtra("behind", false)) null else find(home)
        when {
            found != null && found.name != javaClass.name -> hand(home, "${found.packageName}/${found.name}")
            // the package manager still answers with this screen (a step behind the unlock): asked again
            waited < BEHIND_MS -> main.postDelayed(again, 400L)
            else -> {
                // then the home intent limited to this package, where HomeActivity comes first by its priority. Not
                // the class by name, which would make it an ordinary activity ([Screens]) and finds nothing more: the
                // package manager filters a name the same way. Nothing but this screen there either: as when the
                // user stays locked, a screen that works is better than a black one
                val own = Screens.homeIntent(this, Screens.FROM_BOOT)
                val mine = find(own)
                when {
                    mine != null && mine.name != javaClass.name -> hand(own, "own package: ${mine.name}")
                    waited > STUCK_MS -> hand(Intent().setClassName(STOCK_PKG, STOCK_HOME), "only this screen is found after ${waited / 1000} s")
                    else -> main.postDelayed(again, 400L)
                }
            }
        }
    }

    private fun find(i: Intent) = try { packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo } catch (_: Throwable) { null }

    private fun hand(to: Intent, what: String) {
        handed = true
        Log.i(TAG, "home after ${SystemClock.elapsedRealtime() - startedAt} ms: $what")
        try {
            startActivity(to.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            overridePendingTransition(0, 0)
        } catch (t: Throwable) {
            Log.w(TAG, "start: $t")
        }
        finish()
    }

    companion object {
        private const val TAG = "AuraBootHome"
        private const val STOCK_PKG = "com.android.launcher"
        private const val STOCK_HOME = "com.android.launcher2.Launcher"
        /** Unlocked, but the home intent still finds this screen. */
        private const val BEHIND_MS = 6000L
        /** Never unlocked. */
        private const val STUCK_MS = 40_000L

        /**
         * This screen exists on the car unit only (the manifest has it off): there the home screen is chosen by the
         * firmware's property and a second one in the package changes nothing else; a stock Android would forget the
         * chosen home app when the set of home screens changes. Called at every start of the app; the state is kept
         * by the system across restarts and updates.
         */
        fun ensure(c: Context) {
            try {
                val want = com.abdllh.aura.system.Device.isNwd ||
                    (com.abdllh.aura.BuildConfig.DEBUG && com.abdllh.aura.system.SystemProps.get("debug.aura.boothome") == "1")
                val pm = c.packageManager
                val name = android.content.ComponentName(c, BootHome::class.java)
                val target = if (want) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                if (pm.getComponentEnabledSetting(name) != target) {
                    pm.setComponentEnabledSetting(name, target, PackageManager.DONT_KILL_APP)
                    Log.i(TAG, "boot home screen ${if (want) "on" else "off"}")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "ensure: $t")
            }
        }
    }
}
