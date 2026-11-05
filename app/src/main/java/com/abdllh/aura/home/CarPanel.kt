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
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Radius
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.Space
import com.abdllh.aura.ui.Touch
import com.abdllh.aura.ui.Type
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

/**
 * Start side of the home screen: clock and the user's car on its turntable. The car stands directly
 * on the screen's background (its frames are composed over that colour, see CarEtc): no card may go under it.
 */
class CarPanel(ctx: Context, private val host: HomeHost) : LinearLayout(ctx) {
    // Plex Regular in both languages (the clock's figures are the same in Arabic): the thin weight it had until 1.11
    // was about 2 px wide on this panel and broke up in daylight
    private val clock: AText = ctx.label(Type.DISPLAY_L).apply { typeface = Fonts.light() }
    private val ampm: AText = ctx.label(Type.BUTTON, Palette.text2)
    private val date: AText = ctx.label(Type.BODY, Palette.text2)
    private val greet: AText = ctx.label(Type.CAPTION, Palette.text3)
    private val updateText: AText = ctx.label(Type.CAPTION, Palette.accentFg).apply { weight = Fonts.MEDIUM }
    private val updateChip: LinearLayout
    val stage = CarStage(ctx)
    val header: LinearLayout

    init {
        orientation = VERTICAL
        setPadding(0, Space.M.dp, 0, Space.L.dp) // the car stage spans the whole width; header and buttons are inset

        // (the clock's start edge is the one of the controls button in the dock under it)
        header = LinearLayout(ctx).apply { orientation = HORIZONTAL; setPaddingRelative(20.dp, 0, Space.L.dp, 0) }
        val left = LinearLayout(ctx).apply { orientation = VERTICAL }
        val timeRow = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.BOTTOM }
        timeRow.addView(clock, lp(WRAP, WRAP))
        timeRow.addView(ampm, lp(WRAP, WRAP).apply { marginStart = Space.S.dp; bottomMargin = 11.dp })
        left.addView(timeRow, lp(WRAP, WRAP))
        left.addView(date, lp(WRAP, WRAP))
        left.addView(greet, lp(WRAP, WRAP).apply { topMargin = Space.XS.dp })
        header.addView(left, lp(0, WRAP, 1f))

        updateChip = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(Space.M.dp, 0, 14.dp, 0)
            isClickable = true
            pressScale(Motion.PRESS_CONTROL)
            background = Shapes.touchInset(Shapes.accentSoft(Radius.PILL), 40) // (a 40 dp chip, a 56 dp target)
            setOnClickListener { host.openSettings(SettingsActivity.PAGE_UPDATE) }
            addView(ctx.iconView(R.drawable.ic_download, 18, Palette.accentFg), lp(18.dp, 18.dp))
            addView(updateText, lp(WRAP, WRAP).apply { marginStart = Space.S.dp })
        }
        // the end side: the gear (P R N D) on top, the update chip under it
        val end = LinearLayout(ctx).apply { orientation = VERTICAL; gravity = Gravity.END }
        end.addView(GearView(ctx), lp(WRAP, WRAP).apply { topMargin = 18.dp })
        end.addView(updateChip, lp(WRAP, Touch.MIN.dp).apply { topMargin = Space.XS.dp })
        header.addView(end, lp(WRAP, WRAP))
        addView(header, lp(MATCH, WRAP))

        stage.onTap = { host.openControls() }
        addView(stage, lp(MATCH, 0, 1f))
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
        refreshUpdate()
    }

    fun refreshUpdate() {
        val has = UpdateManager.hasUpdateBadge()
        updateChip.visibility = if (has) View.VISIBLE else View.GONE
        if (has) updateText.text = context.getString(R.string.ctl_update_available, Prefs.availableTag.removePrefix("v"))
    }

}
