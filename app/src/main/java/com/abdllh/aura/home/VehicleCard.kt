package com.abdllh.aura.home

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.abdllh.aura.R
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.CarView
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.RoundBtn
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lp
import com.abdllh.aura.ui.roundedClip
import com.abdllh.aura.util.Fmt
import com.abdllh.aura.util.Prefs
import com.abdllh.aura.util.dp
import java.util.Calendar
import java.util.Date

/** Left card: clock, greeting, the car, and four quick buttons. */
class VehicleCard(ctx: Context, private val host: HomeHost) : FrameLayout(ctx) {
    private val clock: AText = ctx.label(66f, Palette.text, Fonts.LIGHT).apply { typeface = Fonts.thin() }
    private val ampm: AText = ctx.label(18f, Palette.text2, Fonts.MEDIUM)
    private val date: AText = ctx.label(16f, Palette.text2, Fonts.REGULAR)
    private val greet: AText = ctx.label(13f, Palette.text3, Fonts.REGULAR)
    val car = CarView(ctx)

    private val wifi = RoundBtn(ctx, R.drawable.ic_wifi)
    private val link = RoundBtn(ctx, R.drawable.ic_smartphone)
    private val screenOff = RoundBtn(ctx, R.drawable.ic_power)
    private val vehicle = RoundBtn(ctx, R.drawable.ic_car)

    init {
        background = Shapes.card(26f)
        roundedClip(26f)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp, 20.dp, 24.dp, 18.dp)
        }
        val timeRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        timeRow.addView(clock, lp(WRAP, WRAP))
        timeRow.addView(ampm, lp(WRAP, WRAP).apply { marginStart = 8.dp; bottomMargin = 12.dp })
        col.addView(timeRow, lp(WRAP, WRAP))
        col.addView(date, lp(MATCH, WRAP).apply { topMargin = 2.dp })
        col.addView(greet, lp(MATCH, WRAP).apply { topMargin = 4.dp })
        col.addView(car, lp(MATCH, 0, 1f).apply { topMargin = 6.dp; bottomMargin = 10.dp })

        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        for ((i, b) in listOf(wifi, link, screenOff, vehicle).withIndex()) {
            row.addView(b, lp(56.dp, 56.dp).apply { if (i > 0) marginStart = 16.dp })
        }
        col.addView(row, lp(MATCH, WRAP))
        addView(col, MATCH, MATCH)

        wifi.setOnClickListener { openWifi() }
        link.setOnClickListener {
            val pkg = if (AppRepo.isInstalled(context, Known.ZLINK)) Known.ZLINK else Known.PHONE
            if (!AppRepo.launch(context, pkg)) host.toast(context.getString(R.string.err_app_missing))
        }
        screenOff.setOnClickListener {
            if (!AppRepo.launchComponent(context, Known.STOCK_LAUNCHER, Known.ACT_SCREEN_OFF)) {
                try { context.startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) { }
            }
        }
        vehicle.setOnClickListener {
            val ok = AppRepo.launch(context, Known.MYCAR) || AppRepo.launch(context, Known.CAR_SETTING)
            if (!ok) host.toast(context.getString(R.string.err_app_missing))
        }
        refresh()
    }

    private fun openWifi() {
        val i = Intent(if (android.os.Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIFI_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context.startActivity(i) } catch (_: Throwable) {
            try { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) { }
        }
    }

    fun refresh() {
        val now = Date()
        clock.text = Fmt.time(now, Prefs.clock24)
        ampm.text = if (Prefs.clock24) "" else Fmt.ampm(now)
        date.text = Fmt.date(now)
        greet.text = context.getString(
            when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
                in 5..11 -> R.string.greet_morning
                in 12..16 -> R.string.greet_afternoon
                in 17..21 -> R.string.greet_evening
                else -> R.string.greet_night
            }
        )
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            wifi.active = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } catch (_: Throwable) { }
        screenOff.visibility = if (AppRepo.isInstalled(context, Known.STOCK_LAUNCHER)) VISIBLE else GONE
        wifi.refreshTheme(); link.refreshTheme(); screenOff.refreshTheme(); vehicle.refreshTheme()
    }

    fun playIntro() = car.playIntro()
}
