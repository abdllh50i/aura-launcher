package com.abdllh.aura.settings

import android.content.Context
import android.view.View
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.R
import com.abdllh.aura.system.CrashGuard
import com.abdllh.aura.system.Device
import com.abdllh.aura.ui.ABtn
import com.abdllh.aura.ui.Confirm
import com.abdllh.aura.ui.Snack
import com.abdllh.aura.update.Rollback

/**
 * طريق الرجوع, a sub-page of Car and system: the two ways out that must stay reachable: the previous version ([Rollback]) and the stock launcher ([CrashGuard.setAuraEnabled], the kill switch, which gives
 * back whatever AMRI took over). Reads a small file for what it shows, so the page opens without a root shell; the shell is asked when a button is pressed. What the guard saw, and how the last way
 * back went, are in the Screen check's report.
 */
class WaybackPage(private val act: SettingsPanel) : Page(R.string.wayback_title) {
    override val keep: Boolean get() = false
    override val scrolls: Boolean get() = false

    override fun build(ctx: Context): View = ctx.rows(previous(ctx), stock(ctx))

    private fun previous(ctx: Context): View {
        val info = Rollback.info(ctx)
        val p = info.payload
        if (Rollback.usable(p)) {
            return ctx.settingRow(null, ctx.getString(R.string.wayback_back), ctx.getString(R.string.wayback_back_sub, p?.version),
                ABtn(ctx).apply {
                    kind = ABtn.Kind.TONAL
                    setText(R.string.wayback_back_btn)
                    setOnClickListener { ask(ctx, p?.version.orEmpty()) }
                })
        }
        return ctx.settingRow(null, ctx.getString(R.string.wayback_back), when {
            p != null && p.code >= BuildConfig.VERSION_CODE -> ctx.getString(R.string.wayback_this_one, p.version)
            p != null -> ctx.getString(R.string.wayback_below_rom, p.version)
            else -> whyNone(ctx, info)
        })
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

    private fun ask(ctx: Context, version: String) = ctx.parked {
        Confirm.show(act, ctx.getString(R.string.wayback_confirm_title, version), ctx.getString(R.string.wayback_confirm_text, version), ctx.getString(R.string.wayback_back_btn), danger = true) {
            Snack.show(ctx, R.string.wayback_going, long = true, outlives = true)
            Rollback.goBack(ctx, false) { why, fellBack ->
                // (the script has fallen back to the stock launcher: everything stays in step with that)
                if (fellBack) try { CrashGuard.setAuraEnabled(ctx, false, "rollback") } catch (_: Throwable) { }
                val said = when (why) {
                    "no-root" -> ctx.getString(R.string.rescue_no_root)
                    "no-copy" -> ctx.getString(R.string.rescue_no_copy)
                    else -> ctx.getString(R.string.wayback_failed, why)
                }
                Snack.show(ctx, said, long = true, outlives = true)
                if (!act.isGone) act.rebuild()
            }
        }
    }

    private fun stock(ctx: Context): View? {
        if (!Device.isNwd && !BuildConfig.DEBUG) return null
        val cur = Device.homePackage()
        val isAura = cur == ctx.packageName
        return ctx.settingRow(null, ctx.getString(R.string.about_home_app), if (isAura) ctx.getString(R.string.app_name) else cur.ifEmpty { "-" },
            ABtn(ctx).apply {
                kind = if (isAura) ABtn.Kind.TONAL else ABtn.Kind.PRIMARY
                setText(if (isAura) R.string.about_use_stock else R.string.about_use_aura)
                setOnClickListener {
                    // also moves the boot-time kill switch, otherwise the next boot would undo the choice; the message outlives this screen (the home screen changes under it)
                    val ok = CrashGuard.setAuraEnabled(ctx, !isAura, "user")
                    Snack.show(ctx, if (ok) R.string.about_home_changed else R.string.about_home_failed, long = true, outlives = true)
                    act.rebuild()
                }
            })
    }
}
