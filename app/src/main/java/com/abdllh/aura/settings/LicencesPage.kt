package com.abdllh.aura.settings

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import com.abdllh.aura.R
import com.abdllh.aura.nav.pack.PackPaths
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lp
import java.io.File

/**
 * What AMRI OS and its map are made of, behind the About row of Car and system: the map's credit whole, the repository's NOTICE, which ships in assets, and the map pack's own NOTICE-PACK.md with the
 * licences it names, read through [PackPaths] from the pack in use. With no pack on the unit none of those.
 */
class LicencesPage(private val act: SettingsPanel) : Page(R.string.st_licences) {
    override val keep: Boolean get() = false

    override fun build(ctx: Context): View {
        val rows = ArrayList<View?>()
        // the credit whole, as the map's data asks, for the data that draws: on its own line, reading left to right in Arabic as well
        rows.add(ctx.settingRow(null, ctx.getString(R.string.st_maps), "⁦${com.abdllh.aura.nav.MapStyle.attribution(null)}⁩"))
        rows.add(ctx.settingRow(null, ctx.getString(R.string.st_lic_app), null, ctx.chevron()) {
            act.open(TextPage(R.string.st_lic_app) { c -> c.assets.open("NOTICE.txt").use { it.reader().readText() } })
        })
        rows.add(ctx.settingRow(null, ctx.getString(R.string.st_lic_font), null, ctx.chevron()) {
            act.open(TextPage(R.string.st_lic_font) { c -> c.assets.open("fonts/OFL.txt").use { it.reader().readText() } })
        })
        val pack = PackPaths.current(ctx)
        if (pack != null) {
            for ((name, title) in listOf("NOTICE-PACK.md" to R.string.st_lic_pack, "CDLA-Permissive-2.0.txt" to R.string.st_lic_cdla, "Apache-2.0.txt" to R.string.st_lic_apache, "OFL-1.1.txt" to R.string.st_lic_ofl)) {
                val f: File = pack.notice(name) ?: continue
                rows.add(ctx.settingRow(null, ctx.getString(title), null, ctx.chevron()) {
                    act.open(TextPage(title) { f.readText() })
                })
            }
        }
        return ctx.rows(*rows.toTypedArray())
    }
}

/** A long text in the card, read on a thread: it comes when it comes, and laying it out once is all it costs. */
class TextPage(title: Int, private val read: (Context) -> String) : Page(title) {
    override fun build(ctx: Context): View {
        val text = ctx.label(Type.rowHint, { Palette.ink2 }, lines = 0).apply {
            textDirection = View.TEXT_DIRECTION_LTR
            textAlignment = View.TEXT_ALIGNMENT_TEXT_START
            setPaddingRelative(22, 0, 22, 22)
        }
        val main = Handler(Looper.getMainLooper())
        val app = ctx
        Thread({
            val s = try { read(app) } catch (t: Throwable) { t.toString() }
            main.post { text.text = s }
        }, "aura-licence").start()
        val col = ctx.pageColumn()
        col.addView(text, lp(MATCH, WRAP))
        return col
    }
}
