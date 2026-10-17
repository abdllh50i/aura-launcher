package com.abdllh.aura.settings

import android.content.Context
import android.widget.LinearLayout
import com.abdllh.aura.R
import com.abdllh.aura.phone.MessageBanner
import com.abdllh.aura.system.Notices

/**
 * Settings → Display & sound → Notifications: Android's own notifications off the screen, the phone's messages shown
 * as AMRI's card, with or without what they say, and how to get WhatsApp's messages onto the unit ([Notices]).
 */
internal fun LinearLayout.noticesSection(ctx: Context) {
    addSection(ctx.getString(R.string.set_notices))
    addRow(ctx.group(
        ctx.switchRow(R.drawable.ic_bell_off, ctx.getString(R.string.set_notices_hide), ctx.getString(R.string.set_notices_hide_sub),
            Notices.hideAndroid) { on -> Notices.hideAndroid = on; Notices.apply(ctx) },
        ctx.switchRow(R.drawable.ic_message, ctx.getString(R.string.set_notices_messages), ctx.getString(R.string.set_notices_messages_sub),
            Notices.showMessages) { on -> Notices.showMessages = on; Notices.apply(ctx) },
        ctx.switchRow(R.drawable.ic_eye_off, ctx.getString(R.string.set_notices_text), ctx.getString(R.string.set_notices_text_sub),
            Notices.showText) { on -> Notices.showText = on },
        ctx.settingRow(R.drawable.ic_play, ctx.getString(R.string.set_notices_try), null, ctx.chevron()) { MessageBanner.preview(ctx) }
    ))
    addHint(ctx.getString(R.string.set_notices_whatsapp))
}
