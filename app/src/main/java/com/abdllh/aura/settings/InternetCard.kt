package com.abdllh.aura.settings

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.abdllh.aura.R
import com.abdllh.aura.system.WifiKeeper
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.iconView
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lp
import com.abdllh.aura.ui.pressScale
import com.abdllh.aura.util.dp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Settings › Vehicle & system: what the Wi-Fi internet is doing right now, and the latest drops with their cause
 * (from [WifiKeeper]'s log, kept across restarts), so a drop on the road can be explained afterwards.
 */
class InternetCard(private val ctx: Context) {
    private val dot = View(ctx)
    private val status: AText = ctx.label(15f, Palette.text2, Fonts.REGULAR, lines = 3)
    private val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val listener: () -> Unit = { refresh() }

    val view: LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(20.dp, 16.dp, 20.dp, 16.dp)
        background = Shapes.card(18f)
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(ctx.iconView(R.drawable.ic_wifi, 27, Palette.text2), lp(27.dp, 27.dp).apply { marginEnd = 18.dp })
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val titleRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(ctx.label(18.5f, Palette.text, Fonts.MEDIUM).apply { setText(R.string.net_title) }, lp(WRAP, WRAP))
        titleRow.addView(dot, lp(10.dp, 10.dp).apply { marginStart = 10.dp })
        texts.addView(titleRow, lp(MATCH, WRAP))
        texts.addView(status, lp(MATCH, WRAP).apply { topMargin = 3.dp })
        head.addView(texts, lp(0, WRAP, 1f))
        head.addView(ctx.label(16f, Palette.text, Fonts.MEDIUM, gravity = Gravity.CENTER).apply {
            setText(R.string.net_check)
            background = Shapes.tonal(16f)
            setPadding(20.dp, 0, 20.dp, 0)
            isClickable = true
            pressScale(0.95f)
            setOnClickListener { WifiKeeper.checkNow() }
        }, lp(WRAP, 50.dp).apply { marginStart = 14.dp })
        addView(head, lp(MATCH, WRAP))
        addView(View(ctx).apply { setBackgroundColor(Palette.stroke) }, lp(MATCH, 1).apply { topMargin = 14.dp; bottomMargin = 6.dp })
        addView(list, lp(MATCH, WRAP))
    }

    fun attach() {
        WifiKeeper.listener = listener
        refresh()
    }

    fun detach() {
        if (WifiKeeper.listener === listener) WifiKeeper.listener = null
    }

    fun refresh() {
        val (line, color) = when (WifiKeeper.state) {
            WifiKeeper.State.ONLINE -> (if (WifiKeeper.ssid.isNotEmpty()) ctx.getString(R.string.net_online_via, WifiKeeper.ssid)
                else ctx.getString(R.string.net_online)) to Palette.success
            WifiKeeper.State.OFFLINE -> {
                val down = WifiKeeper.downSeconds()
                val since = if (down > 0) " · " + duration(ctx, down) else ""
                ctx.getString(R.string.net_offline, cause(ctx, WifiKeeper.cause?.name)) + since + "\n" + signal(ctx, WifiKeeper.rssi) to Palette.danger
            }
            WifiKeeper.State.CHECKING -> ctx.getString(R.string.net_checking) to Palette.warn
            WifiKeeper.State.NO_WIFI -> ctx.getString(R.string.net_no_wifi) to Palette.text3
            WifiKeeper.State.OFF -> ctx.getString(R.string.net_off) to Palette.text3
            WifiKeeper.State.PAUSED -> ctx.getString(R.string.net_paused) to Palette.text3
        }
        status.text = line
        dot.background = Shapes.oval(color)

        list.removeAllViews()
        val events = WifiKeeper.log().asReversed().take(12)
        if (events.isEmpty()) {
            list.addView(ctx.label(14.5f, Palette.text3, Fonts.REGULAR, lines = 0).apply { setText(R.string.net_log_empty) }, lp(MATCH, WRAP).apply { topMargin = 6.dp })
            return
        }
        for (e in events) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
            row.addView(ctx.label(14.5f, Palette.text3, Fonts.REGULAR).apply {
                text = time(e.at)
                textDirection = View.TEXT_DIRECTION_LTR
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START // the start side of the row, also in Arabic
            }, lp(96.dp, WRAP).apply { marginEnd = 10.dp })
            row.addView(ctx.label(14.5f, if (e.type == "lost") Palette.text else Palette.text2, Fonts.REGULAR, lines = 2).apply {
                text = describe(ctx, e)
                ellipsize = TextUtils.TruncateAt.END
            }, lp(0, WRAP, 1f))
            list.addView(row, lp(MATCH, WRAP).apply { topMargin = 7.dp })
        }
    }

    companion object {
        fun describe(c: Context, e: WifiKeeper.Event): String = when (e.type) {
            "start" -> c.getString(R.string.net_ev_start)
            "join" -> if (e.a.isNotEmpty()) c.getString(R.string.net_ev_join, e.a) else c.getString(R.string.net_ev_join_any)
            "leave" -> c.getString(R.string.net_ev_leave)
            "online" -> c.getString(R.string.net_ev_online)
            "back" -> c.getString(R.string.net_ev_back, duration(c, e.a.toLongOrNull() ?: 0L))
            "lost" -> c.getString(R.string.net_ev_lost, cause(c, e.a)) +
                (e.b.toIntOrNull()?.takeIf { it < 0 }?.let { " · " + signal(c, it) } ?: "")
            "fix" -> c.getString(if (e.b == "restart") R.string.net_ev_restart else R.string.net_ev_reconnect, e.a)
            "fixfail" -> c.getString(R.string.net_ev_fixfail)
            "diag" -> c.getString(R.string.net_ev_diag, e.a)
            else -> e.type
        }

        fun cause(c: Context, name: String?): String = c.getString(when (name) {
            "LINK" -> R.string.net_cause_link
            "NO_DATA" -> R.string.net_cause_no_data
            "DNS" -> R.string.net_cause_dns
            "V6" -> R.string.net_cause_v6
            else -> R.string.net_cause_web
        })

        fun signal(c: Context, rssi: Int): String {
            if (rssi >= 0) return ""
            val word = c.getString(when {
                rssi >= -60 -> R.string.net_sig_strong
                rssi >= -70 -> R.string.net_sig_good
                rssi >= -80 -> R.string.net_sig_weak
                else -> R.string.net_sig_poor
            })
            return c.getString(R.string.net_signal, word)
        }

        fun duration(c: Context, secs: Long): String = when {
            secs < 60 -> c.getString(R.string.dur_s, secs.coerceAtLeast(1).toString())
            secs < 3600 -> c.getString(R.string.dur_min, ((secs + 30) / 60).toString())
            else -> c.getString(R.string.dur_h, (secs / 3600).toString(), ((secs % 3600) / 60).toString())
        }

        /** Today: the time; earlier: day/month and the time. Western digits, like the rest of Aura. */
        private fun time(at: Long): String {
            val now = Calendar.getInstance()
            val then = Calendar.getInstance().apply { timeInMillis = at }
            val today = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
            return SimpleDateFormat(if (today) "HH:mm:ss" else "d/M HH:mm", Locale.ENGLISH).format(Date(at))
        }
    }
}
