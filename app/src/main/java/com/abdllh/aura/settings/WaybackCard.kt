package com.abdllh.aura.settings

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.R
import com.abdllh.aura.guard.GuardLog
import com.abdllh.aura.guard.GuardService
import com.abdllh.aura.system.CrashGuard
import com.abdllh.aura.system.Device
import com.abdllh.aura.system.SystemProps
import com.abdllh.aura.ui.ABtn
import com.abdllh.aura.ui.Confirm
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Snack
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.label
import com.abdllh.aura.update.Rollback

/**
 * Settings → About: the way back ([Rollback]), and what the guard has seen. Reads a small file for what it shows, so the
 * page opens without a root shell; the shell is asked when a button is pressed.
 */
internal object WaybackCard {
    private const val GUARD_LINES = 10

    fun addTo(col: LinearLayout, ctx: Context, act: SettingsPanel) {
        if (Device.isNwd || BuildConfig.DEBUG) way(col, ctx, act)
        guard(col, ctx)
    }

    private fun way(col: LinearLayout, ctx: Context, act: SettingsPanel) {
        col.addSection(ctx.getString(R.string.wayback_title))
        val info = Rollback.info(ctx)
        val p = info.payload
        val off = SystemProps.get(Device.DISABLED_PROP) == "1"
        val row = if (Rollback.usable(p)) {
            ctx.settingRow(R.drawable.ic_refresh, ctx.getString(R.string.wayback_back), ctx.getString(R.string.wayback_back_sub, p?.version),
                ABtn(ctx).apply {
                    kind = ABtn.Kind.TONAL
                    setText(R.string.wayback_back_btn)
                    setOnClickListener { ask(ctx, act, p?.version.orEmpty(), test = false) }
                })
        } else {
            val why = when {
                p != null && p.code >= BuildConfig.VERSION_CODE -> ctx.getString(R.string.wayback_this_one, p.version)
                p != null -> ctx.getString(R.string.wayback_below_rom, p.version)
                else -> whyNone(ctx, info)
            }
            ctx.settingRow(R.drawable.ic_refresh, ctx.getString(R.string.wayback_back), why)
        }
        // 1.19.x: both ends are known-good code, so trying it costs nothing and proves the way back on the real unit before 2.0 needs it
        val test = if (!off && p != null && p.version == Rollback.BASE_VERSION && Rollback.usable(p) && BuildConfig.VERSION_CODE < GuardService.ACTS_FROM_CODE) {
            ctx.settingRow(R.drawable.ic_zap, ctx.getString(R.string.wayback_test), ctx.getString(R.string.wayback_test_sub, p.version, BuildConfig.VERSION_NAME),
                ABtn(ctx).apply {
                    kind = ABtn.Kind.TONAL
                    setText(R.string.wayback_test_btn)
                    setOnClickListener { ask(ctx, act, p.version, test = true) }
                })
        } else null
        val last = if (info.lastExit.isEmpty()) null else ctx.settingRow(R.drawable.ic_info, ctx.getString(R.string.wayback_last), when {
            info.lastExit != "0" -> ctx.getString(R.string.wayback_last_failed, info.lastFrom, info.lastTo, info.lastPm)
            info.lastDataStayed -> ctx.getString(R.string.wayback_last_ok, info.lastFrom, info.lastTo)
            else -> ctx.getString(R.string.wayback_last_lost, info.lastFrom, info.lastTo)
        })
        col.addRow(ctx.group(row, test, last))
    }

    private fun whyNone(ctx: Context, info: Rollback.Info): String = when (info.why) {
        Rollback.Why.NO_ROOT -> ctx.getString(R.string.wayback_why_no_root)
        Rollback.Why.WAITING_NET -> ctx.getString(R.string.wayback_why_waiting_net, Rollback.BASE_VERSION)
        Rollback.Why.FETCHING -> ctx.getString(R.string.wayback_why_fetching, Rollback.BASE_VERSION)
        Rollback.Why.NO_SPACE -> ctx.getString(R.string.wayback_why_no_space)
        Rollback.Why.FAILED -> ctx.getString(R.string.wayback_why_failed, info.detail)
        Rollback.Why.NOT_UNIT -> ctx.getString(R.string.wayback_why_not_unit)
        else -> ctx.getString(R.string.wayback_why_looking)
    }

    private fun ask(ctx: Context, act: SettingsPanel, version: String, test: Boolean) {
        val title = if (test) ctx.getString(R.string.wayback_test_title) else ctx.getString(R.string.wayback_confirm_title, version)
        val text = if (test) ctx.getString(R.string.wayback_test_text, version, BuildConfig.VERSION_NAME) else ctx.getString(R.string.wayback_confirm_text, version)
        val go = ctx.getString(if (test) R.string.wayback_test_btn else R.string.wayback_back_btn)
        Confirm.show(act, title, text, go, danger = !test) {
            Snack.show(ctx, R.string.wayback_going, long = true, outlives = true)
            Rollback.goBack(ctx, test) { why, fellBack ->
                // (the script has fallen back to the stock launcher: everything stays in step with that)
                if (fellBack) try { CrashGuard.setAuraEnabled(ctx, false, "rollback") } catch (_: Throwable) { }
                val text = when (why) {
                    "no-root" -> ctx.getString(R.string.rescue_no_root)
                    "no-copy" -> ctx.getString(R.string.rescue_no_copy)
                    else -> ctx.getString(R.string.wayback_failed, why)
                }
                Snack.show(ctx, text, long = true, outlives = true)
                if (!act.isGone) act.rebuild()
            }
        }
    }

    private fun guard(col: LinearLayout, ctx: Context) {
        col.addSection(ctx.getString(R.string.guard_title))
        val lines = GuardLog.tail(ctx, GUARD_LINES)
        val body = ctx.label(Type.CAPTION, Palette.text2, lines = 0).apply {
            text = if (lines.isEmpty()) ctx.getString(R.string.guard_empty) else lines.joinToString("\n")
            textDirection = View.TEXT_DIRECTION_LTR
        }
        col.addRow(ctx.group(ctx.block(R.drawable.ic_shield, null, null, body)))
        if (BuildConfig.VERSION_CODE < GuardService.ACTS_FROM_CODE) col.addHint(ctx.getString(R.string.guard_watching))
    }
}
