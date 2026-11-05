package com.abdllh.aura.ui

import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.Gravity
import android.widget.TextView
import com.abdllh.aura.R

/** TextView that takes its typeface from [Fonts] by weight or by role. Use app:weight in XML. */
open class AText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : TextView(context, attrs, defStyle), Recolours {
    /** The role of a label on a moving screen, whose weight goes with the theme. */
    private var movingRole: Type.Role? = null

    /** The one thing about a label's look that its tokens do not say. Only a moving screen's labels: a change of weight lays out that label. */
    override fun onLook() {
        movingRole?.let { typeface = Fonts.of(it, true) }
    }

    var weight: Int = Fonts.REGULAR
        set(value) {
            field = value
            typeface = Fonts.get(value)
        }

    /**
     * Text starts at the layout's start edge, whatever script it is in. A TextView's own START follows the text:
     * a Latin place name, song or repository in the Arabic UI went to the left while the Arabic around it stood on
     * the right. Only the alignment follows the layout; the text direction stays the text's own, so a Latin line
     * keeps its punctuation where it belongs. A gravity with another horizontal side (centre, end) set later wins.
     */
    var alignStart = false
        set(v) { field = v; applyAlign() }

    init {
        var w = Fonts.REGULAR
        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.AText)
            w = a.getInt(R.styleable.AText_weight, Fonts.REGULAR)
            a.recycle()
        }
        weight = w
        includeFontPadding = false
        Fonts.tune(paint)
    }

    /**
     * A one-line label of a role (SPEC.md section 3): 1.5 em tall, placed by [Type.Role.box] so that its centre is the line
     * box's. An emoji in a title cannot move its baseline. [moving]: its weight on a moving screen, heavier at night, in this
     * theme and the next; call it again when the screen goes from one to the other.
     * The line's direction is the language's: a name inside it keeps its own by BidiFormatter.unicodeWrap, where it comes from.
     */
    fun oneLine(role: Type.Role, moving: Boolean = false) {
        textDirection = TEXT_DIRECTION_LOCALE
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, role.sp)
        movingRole = if (moving) role else null
        typeface = Fonts.of(role, moving)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        if (Build.VERSION.SDK_INT >= 28) isFallbackLineSpacing = false
    }

    /** Text that wraps (only Settings' hints, the no-phone text, lyric lines): the role's line box, no multiplier. */
    fun wrapping(role: Type.Role, lines: Int = 0, moving: Boolean = false) {
        textDirection = TEXT_DIRECTION_LOCALE
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, role.sp)
        movingRole = if (moving) role else null
        typeface = Fonts.of(role, moving)
        if (lines > 0) {
            maxLines = lines
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        if (Build.VERSION.SDK_INT >= 28) lineHeight = role.line
    }

    /** Letter-spacing in px, only ever on digits (section 3: clock -1, speed -0.5, a three-digit limit -0.8). */
    fun digitSpacing(px: Float) {
        letterSpacing = px / textSize
    }

    /**
     * A TextView clips its text to its frame, and with no font padding the frame ends at the font's descent: the dots of a final ي reach below that (Plex Arabic: -447 against -415, its lowest
     * marks -601), and "عمري" read "عمرى". So the frame goes on a little lower than the parent gave. The parent's layout does not change, and the text's place comes from the measured height,
     * which does not either. Plain labels only: a background would grow with it, and a subclass may read its height (the word pill of a lyric line ends there).
     */
    override fun layout(l: Int, t: Int, r: Int, b: Int) {
        super.layout(l, t, r, if (background == null && javaClass === AText::class.java) b + kotlin.math.ceil(textSize * BELOW_DESCENT).toInt() else b)
    }

    override fun setGravity(gravity: Int) {
        super.setGravity(gravity)
        applyAlign()
    }

    private fun applyAlign() {
        // (also called from TextView's constructor, before this class's fields are set: alignStart is false then)
        if (!alignStart) return
        val h = gravity and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK
        textAlignment = if (h == 0 || h == Gravity.START) TEXT_ALIGNMENT_VIEW_START else TEXT_ALIGNMENT_GRAVITY
    }

    private companion object {
        /** The font's lowest marks below its descent, in em. */
        const val BELOW_DESCENT = 0.19f
    }
}
