package com.abdllh.aura.home

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.abdllh.aura.R
import com.abdllh.aura.settings.SettingsActivity
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.CarStage
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.RoundBtn
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.iconView
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lp
import com.abdllh.aura.ui.pressScale
import com.abdllh.aura.update.UpdateManager
import com.abdllh.aura.util.Fmt
import com.abdllh.aura.util.Prefs
import com.abdllh.aura.util.dp
import java.util.Calendar
import java.util.Date

/** Start side of the home screen: clock, the user's car on its turntable, and quick buttons. */
class CarPanel(ctx: Context, private val host: HomeHost) : LinearLayout(ctx) {
    private val clock: AText = ctx.label(58f, Palette.text, Fonts.LIGHT).apply { typeface = Fonts.thin() }
    private val ampm: AText = ctx.label(16f, Palette.text2, Fonts.MEDIUM)
    private val date: AText = ctx.label(15f, Palette.text2, Fonts.REGULAR)
    private val greet: AText = ctx.label(13f, Palette.text3, Fonts.REGULAR)
    private val updateText: AText = ctx.label(13f, Palette.accent, Fonts.MEDIUM)
    private val updateChip: LinearLayout
    val stage = CarStage(ctx)
    val header: LinearLayout
    val quick: LinearLayout

    private val wifi = RoundBtn(ctx, R.drawable.ic_wifi, 52)
    private val link = RoundBtn(ctx, R.drawable.ic_smartphone, 52)
    private val screenOff = RoundBtn(ctx, R.drawable.ic_power, 52)
    private val car = RoundBtn(ctx, R.drawable.ic_car, 52)

    init {
        orientation = VERTICAL
        setPadding(0, 12.dp, 0, 16.dp) // the car stage spans the whole width; header and buttons are inset

        header = LinearLayout(ctx).apply { orientation = HORIZONTAL; setPaddingRelative(26.dp, 0, 18.dp, 0) }
        val left = LinearLayout(ctx).apply { orientation = VERTICAL }
        val timeRow = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.BOTTOM }
        timeRow.addView(clock, lp(WRAP, WRAP))
        timeRow.addView(ampm, lp(WRAP, WRAP).apply { marginStart = 6.dp; bottomMargin = 11.dp })
        left.addView(timeRow, lp(WRAP, WRAP))
        left.addView(date, lp(WRAP, WRAP))
        left.addView(greet, lp(WRAP, WRAP).apply { topMargin = 3.dp })
        header.addView(left, lp(0, WRAP, 1f))

        updateChip = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(12.dp, 0, 14.dp, 0)
            isClickable = true
            pressScale(0.95f)
            setOnClickListener { host.openSettings(SettingsActivity.PAGE_UPDATE) }
            addView(ctx.iconView(R.drawable.ic_download, 17, Palette.accent), lp(17.dp, 17.dp))
            addView(updateText, lp(WRAP, WRAP).apply { marginStart = 7.dp })
        }
        header.addView(updateChip, lp(WRAP, 36.dp).apply { topMargin = 16.dp })
        addView(header, lp(MATCH, WRAP))

        stage.onTap = { host.openControls() }
        addView(stage, lp(MATCH, 0, 1f))

        quick = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
        for ((i, b) in listOf(wifi, link, screenOff, car).withIndex()) {
            quick.addView(b, lp(52.dp, 52.dp).apply { if (i > 0) marginStart = 18.dp })
        }
        addView(quick, lp(MATCH, WRAP))

        wifi.contentDescription = ctx.getString(R.string.ctl_wifi)
        link.contentDescription = ctx.getString(R.string.dock_link)
        screenOff.contentDescription = ctx.getString(R.string.ctl_screen_off)
        car.contentDescription = ctx.getString(R.string.ctl_car)
        wifi.setOnClickListener { Actions.wifi(context) }
        link.setOnClickListener { if (!Actions.phoneLink(context)) host.toast(context.getString(R.string.err_app_missing)) }
        screenOff.setOnClickListener { Actions.screenOff(context) }
        car.setOnClickListener { if (!Actions.car(context)) host.toast(context.getString(R.string.err_app_missing)) }
        refresh()
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
        wifi.active = Actions.wifiConnected(context)
        screenOff.visibility = if (Actions.hasScreenOff(context)) VISIBLE else GONE
        refreshUpdate()
    }

    fun refreshUpdate() {
        val has = UpdateManager.hasUpdateBadge()
        updateChip.visibility = if (has) View.VISIBLE else View.GONE
        if (has) {
            updateText.text = context.getString(R.string.ctl_update_available, Prefs.availableTag.removePrefix("v"))
            updateChip.background = Shapes.pressable(Shapes.rect(Palette.accentSoft(), 18f), Shapes.rect(Palette.withAlpha(Palette.accent, 0.3f), 18f))
        }
    }
}
