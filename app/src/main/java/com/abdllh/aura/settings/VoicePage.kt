package com.abdllh.aura.settings

import android.Manifest
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.abdllh.aura.R
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Shapes
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.iconView
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.lp
import com.abdllh.aura.util.dp
import com.abdllh.aura.voice.Amri
import com.abdllh.aura.voice.VoiceSetup

/** "عمري": the wake word (on/off, the owner's recordings, sensitivity), the answers, the microphone, a try. */
class VoicePage(private val act: SettingsActivity) : Page(R.string.set_voice, R.drawable.ic_mic) {
    override fun build(ctx: Context): View {
        val col = ctx.pageColumn()

        val intro = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20.dp, 18.dp, 20.dp, 18.dp)
            background = Shapes.card(18f)
        }
        intro.addView(LinearLayout(ctx).apply {
            gravity = Gravity.CENTER
            background = Shapes.oval(Palette.accent)
            addView(ctx.iconView(R.drawable.ic_mic, 30, Palette.onColor(Palette.accent)), lp(30.dp, 30.dp))
        }, lp(58.dp, 58.dp).apply { marginEnd = 18.dp })
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(ctx.label(22f, Palette.text, Fonts.MEDIUM).apply { setText(R.string.amri_name) })
        texts.addView(ctx.label(15f, Palette.text2, Fonts.REGULAR, lines = 0).apply {
            setText(R.string.voice_intro)
            setLineSpacing(0f, 1.15f)
        }, lp(MATCH, WRAP).apply { topMargin = 4.dp })
        intro.addView(texts, lp(0, WRAP, 1f))
        col.addRow(intro, 0)

        // what the unit's firmware lacks, from the Play Store: requests need a speech recogniser, spoken answers a voice
        if (!Amri.canRecognize(ctx)) {
            col.addRow(ctx.settingRow(R.drawable.ic_download, ctx.getString(R.string.voice_get_google),
                ctx.getString(R.string.voice_get_google_sub), ctx.chevron()) { VoiceSetup.openStore(ctx, VoiceSetup.GOOGLE_APP) }, 10)
        }
        if (!VoiceSetup.hasVoice(ctx)) {
            col.addRow(ctx.settingRow(R.drawable.ic_download, ctx.getString(R.string.voice_get_tts),
                ctx.getString(R.string.voice_get_tts_sub), ctx.chevron()) { VoiceSetup.openStore(ctx, VoiceSetup.GOOGLE_TTS) }, 8)
        }

        // the microphone permission first (asked once; what was asked for goes on when it is given)
        fun withMic(then: () -> Unit) {
            if (Amri.hasMicPermission(ctx)) then()
            else { act.afterMic = then; act.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), SettingsActivity.REQ_MIC) }
        }

        val enrolled = VoiceSetup.enrolled(ctx)
        col.addRow(ctx.switchRow(R.drawable.ic_mic, ctx.getString(R.string.voice_wake), ctx.getString(R.string.voice_wake_sub), Amri.enabled && enrolled) { on ->
            if (!on) { Amri.enabled = false; return@switchRow }
            // on only with the microphone and the owner's voice
            withMic {
                if (VoiceSetup.enrolled(ctx)) { Amri.enabled = true; act.rebuild() }
                else VoiceSetup.enroll(act) { ok -> if (ok) Amri.enabled = true; act.rebuild() }
            }
        }, 14)
        col.addRow(ctx.settingRow(R.drawable.ic_user, ctx.getString(R.string.voice_enroll),
            ctx.getString(if (enrolled) R.string.voice_enroll_sub_done else R.string.voice_enroll_sub_none), ctx.chevron()) {
            // (set even when it already was: the setter starts the listener with the new recordings)
            withMic { VoiceSetup.enroll(act) { ok -> if (ok) Amri.enabled = true; act.rebuild() } }
        }, 8)

        val sens = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp, 16.dp, 20.dp, 18.dp)
            background = Shapes.card(18f)
        }
        sens.addView(ctx.label(17f, Palette.text, Fonts.MEDIUM).apply { setText(R.string.voice_sens) })
        sens.addView(ctx.label(13.5f, Palette.text2, Fonts.REGULAR, lines = 2).apply { setText(R.string.voice_sens_sub) }, lp(MATCH, WRAP).apply { topMargin = 3.dp })
        sens.addView(Segmented(ctx, listOf(ctx.getString(R.string.voice_sens_strict), ctx.getString(R.string.voice_sens_normal),
            ctx.getString(R.string.voice_sens_relaxed)), Amri.sensitivity) { i -> Amri.sensitivity = i }, lp(MATCH, WRAP).apply { topMargin = 14.dp })
        col.addRow(sens, 8)

        col.addRow(ctx.switchRow(R.drawable.ic_volume, ctx.getString(R.string.voice_speak), null, Amri.spoken) { Amri.spoken = it }, 8)

        // the microphone: the system's choice, or one of the inputs (each tap: the next one)
        val inputs = try {
            (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getDevices(AudioManager.GET_DEVICES_INPUTS)
                .filter { it.type != AudioDeviceInfo.TYPE_TELEPHONY && it.type != AudioDeviceInfo.TYPE_REMOTE_SUBMIX }
        } catch (_: Throwable) { emptyList() }
        val choices = listOf(0 to ctx.getString(R.string.voice_mic_auto)) + inputs.map { it.id to VoiceSetup.name(ctx, it) }
        val now = choices.indexOfFirst { it.first == Amri.micId }.coerceAtLeast(0)
        col.addRow(ctx.settingRow(R.drawable.ic_mic, ctx.getString(R.string.voice_mic), null, ctx.valueText(choices[now].second)) {
            Amri.micId = choices[(now + 1) % choices.size].first
            act.rebuild()
        }, 8)

        col.addRow(ctx.settingRow(R.drawable.ic_zap, ctx.getString(R.string.voice_try), ctx.getString(R.string.voice_try_sub), ctx.chevron()) {
            withMic { Amri.wake(ctx) }
        }, 8)
        return col
    }
}
