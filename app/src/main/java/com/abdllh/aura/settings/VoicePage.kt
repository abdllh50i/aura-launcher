package com.abdllh.aura.settings

import android.Manifest
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.abdllh.aura.R
import com.abdllh.aura.ui.Confirm
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Menu
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Segmented
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.Space
import com.abdllh.aura.ui.Touch
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.iconView
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lp
import com.abdllh.aura.util.dp
import com.abdllh.aura.voice.Amri
import com.abdllh.aura.voice.OfflineCommands
import com.abdllh.aura.voice.VoiceSetup

/**
 * "عمري": the wake word (on/off, the owner's recordings, sensitivity, the sounds it learned to ignore), the answers,
 * the microphone, a try.
 */
class VoicePage(private val act: SettingsActivity) : Page(R.string.set_voice, R.drawable.ic_mic) {
    override fun build(ctx: Context): View {
        val col = ctx.pageColumn()

        val intro = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(20.dp, Space.L.dp, 20.dp, Space.L.dp)
            background = Shapes.card()
        }
        intro.addView(LinearLayout(ctx).apply {
            gravity = Gravity.CENTER
            background = Shapes.oval(Palette.accent)
            addView(ctx.iconView(R.drawable.ic_mic, 28, Palette.onColor(Palette.accent)), lp(28.dp, 28.dp))
        }, lp(Touch.MIN.dp, Touch.MIN.dp).apply { marginEnd = 18.dp })
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(ctx.label(Type.TITLE, Palette.text).apply { setText(R.string.amri_name) })
        texts.addView(ctx.label(Type.FOOTNOTE, Palette.text2, lines = 0).apply { setText(R.string.voice_intro) }, lp(MATCH, WRAP).apply { topMargin = Space.XS.dp })
        texts.addView(ctx.label(Type.FOOTNOTE, Palette.text2, lines = 0).apply { setText(R.string.voice_more) }, lp(MATCH, WRAP).apply { topMargin = Space.XS.dp })
        intro.addView(texts, lp(0, WRAP, 1f))
        col.addRow(intro)

        // what the unit's firmware lacks, from the Play Store: requests need a speech recogniser, spoken answers a voice
        val needGoogle = !Amri.canRecognize(ctx)
        val needVoice = !VoiceSetup.hasVoice(ctx)
        if (needGoogle || needVoice) col.addRow(ctx.group(
            if (!needGoogle) null else ctx.settingRow(R.drawable.ic_download, ctx.getString(R.string.voice_get_google),
                ctx.getString(R.string.voice_get_google_sub), ctx.chevron()) { VoiceSetup.openStore(ctx, VoiceSetup.GOOGLE_APP) },
            if (!needVoice) null else ctx.settingRow(R.drawable.ic_download, ctx.getString(R.string.voice_get_tts),
                ctx.getString(R.string.voice_get_tts_sub), ctx.chevron()) { VoiceSetup.openStore(ctx, VoiceSetup.GOOGLE_TTS) }
        ))

        // the microphone permission first (asked once; what was asked for goes on when it is given)
        fun withMic(then: () -> Unit) {
            if (Amri.hasMicPermission(ctx)) then()
            else { act.afterMic = then; act.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), SettingsActivity.REQ_MIC) }
        }

        val enrolled = VoiceSetup.enrolled(ctx)
        val sens = Segmented(ctx, listOf(ctx.getString(R.string.voice_sens_strict), ctx.getString(R.string.voice_sens_normal),
            ctx.getString(R.string.voice_sens_relaxed)), equal = true, textSp = Type.BUTTON.sp) { i -> Amri.sensitivity = i }.apply {
            trackColor = Palette.card2
            minimumHeight = Touch.MIN.dp
            select(Amri.sensitivity, animate = false)
        }
        // the sounds it learned not to take for its name (a wake not meant: nothing asked, tapped away), forgettable
        val learned = Amri.learnedSounds(ctx)
        val learnedRow = ctx.settingRow(R.drawable.ic_shield, ctx.getString(R.string.voice_learned),
            if (learned == 0) ctx.getString(R.string.voice_learned_none)
            else ctx.resources.getQuantityString(R.plurals.voice_learned_some, learned, learned),
            if (learned == 0) null else ctx.chevron(), onClick = if (learned == 0) null else { {
                Confirm.show(ctx, ctx.getString(R.string.voice_learned_forget_title), ctx.getString(R.string.voice_learned_forget_text),
                    ctx.getString(R.string.voice_learned_forget)) { Amri.forgetLearnedSounds(ctx); act.rebuild() }
            } })
        col.addRow(ctx.group(
            ctx.switchRow(R.drawable.ic_mic, ctx.getString(R.string.voice_wake), ctx.getString(R.string.voice_wake_sub), Amri.enabled && enrolled) { on ->
                if (!on) { Amri.enabled = false; return@switchRow }
                // on only with the microphone and the owner's voice
                withMic {
                    if (VoiceSetup.enrolled(ctx)) { Amri.enabled = true; act.rebuild() }
                    else VoiceSetup.enroll(act) { ok -> if (ok) Amri.enabled = true; act.rebuild() }
                }
            },
            ctx.settingRow(R.drawable.ic_user, ctx.getString(R.string.voice_enroll),
                ctx.getString(if (enrolled) R.string.voice_enroll_sub_done else R.string.voice_enroll_sub_none), ctx.chevron()) {
                // (set even when it already was: the setter starts the listener with the new recordings)
                withMic { VoiceSetup.enroll(act) { ok -> if (ok) Amri.enabled = true; act.rebuild() } }
            },
            ctx.block(R.drawable.ic_sliders, ctx.getString(R.string.voice_sens), ctx.getString(R.string.voice_sens_sub), sens),
            if (enrolled) learnedRow else null
        ))

        // the microphone: the system's choice or one of the inputs, picked from a menu (the one in use ticked)
        val inputs = try {
            (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getDevices(AudioManager.GET_DEVICES_INPUTS)
                .filter { it.type != AudioDeviceInfo.TYPE_TELEPHONY && it.type != AudioDeviceInfo.TYPE_REMOTE_SUBMIX }
        } catch (_: Throwable) { emptyList() }
        val choices = listOf(0 to ctx.getString(R.string.voice_mic_auto)) + inputs.map { it.id to VoiceSetup.name(ctx, it) }
        val now = choices.indexOfFirst { it.first == Amri.micId }.coerceAtLeast(0)
        val mic = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ctx.valueText(choices[now].second).apply { maxWidth = 260.dp }, lp(WRAP, WRAP).apply { marginEnd = Space.XS.dp })
            addView(ctx.chevron())
        }
        col.addRow(ctx.group(
            ctx.switchRow(R.drawable.ic_volume, ctx.getString(R.string.voice_speak), null, Amri.spoken) { Amri.spoken = it },
            ctx.settingRow(R.drawable.ic_mic, ctx.getString(R.string.voice_mic), null, mic) {
                Menu.show(mic, choices.map { (id, name) ->
                    Menu.Item(if (id == Amri.micId) R.drawable.ic_check else R.drawable.ic_mic, name) {
                        if (id != Amri.micId) { Amri.micId = id; act.rebuild() }
                    }
                })
            },
            ctx.settingRow(R.drawable.ic_zap, ctx.getString(R.string.voice_try), ctx.getString(R.string.voice_try_sub), ctx.chevron()) {
                withMic { Amri.wake(ctx) }
            }
        ))

        // requests without the internet: each taught in the owner's voice (a tick once it is)
        col.addSection(ctx.getString(R.string.voice_offline_title))
        col.addHint(ctx.getString(R.string.voice_offline_sub))
        val rows = OfflineCommands.items.map { item ->
            val taught = OfflineCommands.taught(ctx, item.id)
            val tick = if (taught) ctx.iconView(R.drawable.ic_check, 24, Palette.accentFg) else ctx.chevron()
            ctx.settingRow(null, ctx.getString(R.string.voice_cmd_quoted, ctx.getString(item.label)),
                ctx.getString(if (taught) R.string.voice_cmd_taught else R.string.voice_cmd_untaught), tick) {
                withMic { VoiceSetup.teach(act, item) { act.rebuild() } }
            }
        }
        col.addRow(ctx.group(*rows.toTypedArray()))
        return col
    }
}
