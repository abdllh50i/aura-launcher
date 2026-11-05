package com.abdllh.aura.settings

import android.content.Context
import android.view.View
import com.abdllh.aura.R
import com.abdllh.aura.music.LyricsServer
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Space
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.lp

/**
 * The owner's own lyrics server (tools/lyrics-server on his PC, published through his domain): its address, its key and, under them, whether it answers with that key, asked by itself at each visit
 * and after each change.
 */
class LyricsServerPage : Page(R.string.lyr_srv_title) {
    private lateinit var ctx: Context
    private lateinit var address: ValueChevron
    private lateinit var key: ValueChevron
    private lateinit var answer: com.abdllh.aura.ui.AText
    /** A newer question makes the answer to an older one old. */
    private var asked = 0

    override fun onShow() = ask()

    override fun build(ctx: Context): View {
        this.ctx = ctx
        address = ctx.valueChevron("")
        key = ctx.valueChevron("")
        answer = ctx.hint("")
        val col = ctx.pageColumn()
        col.addView(ctx.rows(
            ctx.settingRow(null, ctx.getString(R.string.lyr_srv_address), null, address) {
                InputDialog.show(ctx, ctx.getString(R.string.lyr_srv_address), ctx.getString(R.string.lyr_srv_address_hint), LyricsServer.address, uri = true) {
                    LyricsServer.address = it
                    ask()
                }
            },
            ctx.settingRow(null, ctx.getString(R.string.lyr_srv_key), null, key) {
                InputDialog.show(ctx, ctx.getString(R.string.lyr_srv_key), ctx.getString(R.string.lyr_srv_key_hint), LyricsServer.key, uri = false) {
                    LyricsServer.key = it
                    ask()
                }
            }
        ))
        col.addView(answer, lp(MATCH, WRAP).apply { topMargin = Space.S })
        return col
    }

    private fun ask() {
        address.value = LyricsServer.address.ifBlank { ctx.getString(R.string.st_not_set) }
        // (that a key is set, not any of it)
        key.value = ctx.getString(if (LyricsServer.key.isBlank()) R.string.st_not_set else R.string.st_saved)
        val mine = ++asked
        answer.setText(R.string.lyr_srv_testing)
        LyricsServer.check { res, info ->
            if (mine != asked) return@check
            answer.text = when (res) {
                LyricsServer.Check.OK -> ctx.getString(R.string.lyr_srv_ok, info)
                LyricsServer.Check.WRONG_KEY -> ctx.getString(R.string.lyr_srv_wrong_key)
                LyricsServer.Check.NOT_SERVER -> ctx.getString(R.string.lyr_srv_not_server, info)
                LyricsServer.Check.UNREACHABLE -> ctx.getString(R.string.lyr_srv_unreachable)
                LyricsServer.Check.CLEARTEXT -> ctx.getString(R.string.lyr_srv_cleartext)
                LyricsServer.Check.NO_ADDRESS -> ctx.getString(R.string.lyr_srv_no_address)
            }
        }
    }
}
