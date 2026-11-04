package com.abdllh.aura.ui

import android.content.Context
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import com.abdllh.aura.R

/**
 * The text field (SPEC.md section 5): 52 high, `plate-18` in `fill`, `search` type, a glyph 26 at the reading edge, a clear button 48 x
 * 52 at the far end once there is text. The text's direction is its first strong letter's, whatever the UI's language.
 * [input] is the EditText inside: its text, its inputType, its hint. Used by the Modal's field and by search.
 */
class TextField @JvmOverloads constructor(
    ctx: Context,
    glyphRes: Int = 0,
    hint: CharSequence? = null,
    role: Type.Role = Type.search
) : FrameLayout(ctx), Recolours {
    private val plate = Tint(ctx, R.drawable.plate_18) { Palette.fill }
    private val glyph: ImageView? = if (glyphRes != 0) ImageView(ctx).apply { setImageResource(glyphRes) } else null
    private val rtl = Fonts.arabicUi
    private val clear = IconButton(ctx, R.drawable.ic_close, 26, CLEAR_W, HEIGHT, token = { Palette.ink2 }).apply {
        visibility = View.GONE
        contentDescription = ctx.getString(R.string.btn_cancel)
    }

    val input: EditText = EditText(ctx).apply {
        background = null
        includeFontPadding = false
        textSize = role.sp
        typeface = Fonts.of(role)
        isSingleLine = true
        this.hint = hint
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_DONE
        setPadding(0, 0, 0, 0)
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
    }

    init {
        background = plate.drawable
        val start = if (glyph != null) INSET + GLYPH + GAP else INSET
        glyph?.let {
            addView(it, FrameLayout.LayoutParams(GLYPH, GLYPH, Gravity.CENTER_VERTICAL or if (rtl) Gravity.RIGHT else Gravity.LEFT).apply {
                if (rtl) rightMargin = INSET else leftMargin = INSET
            })
        }
        addView(input, FrameLayout.LayoutParams(MATCH, HEIGHT, Gravity.CENTER_VERTICAL).apply {
            if (rtl) { rightMargin = start; leftMargin = CLEAR_W + 2 } else { leftMargin = start; rightMargin = CLEAR_W + 2 }
        })
        addView(clear, FrameLayout.LayoutParams(CLEAR_W, HEIGHT, Gravity.CENTER_VERTICAL or if (rtl) Gravity.LEFT else Gravity.RIGHT).apply {
            if (rtl) leftMargin = 2 else rightMargin = 2
        })
        clear.setOnClickListener { input.setText("") }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                clear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
        })
        onLook()
    }

    override fun onLook() {
        plate.recolour()
        input.setTextColor(Palette.ink)
        input.setHintTextColor(Palette.ink2)
        glyph?.setColorFilter(Palette.ink2)
        if (Build.VERSION.SDK_INT >= 29) input.highlightColor = Palette.selectedSoft
    }

    override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, MeasureSpec.makeMeasureSpec(HEIGHT, MeasureSpec.EXACTLY))

    companion object {
        const val HEIGHT = 52
        private const val CLEAR_W = 48
        private const val GLYPH = 26
        private const val INSET = 14
        private const val GAP = 10
    }
}
