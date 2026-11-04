package com.abdllh.aura.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.abdllh.aura.R

/**
 * Recolouring in place (SPEC.md section 8): a theme change assigns the other [ThemeColors] and walks what shows, once; nothing
 * is built again, no bounds change, so no layout runs. What a view does then is its own:
 *  - a component of ui/ is [Recolours], and re-reads its tokens and bitmaps in [Recolours.onLook];
 *  - any other view says what depends on the theme with [look], `label.look { setTextColor(Palette.ink2) }`, or the short
 *    forms [ink] and [glyph];
 *  - a plate is a [Tint].
 * Everything reads [Palette] at the moment it is asked, so the same code runs at the build and at every change.
 */
interface Recolours {
    fun onLook()
}

object Look {
    private val windows = ArrayList<View>()

    /** The window's content, or a panel's: every view under [root], in the tree or not on screen just now. */
    fun apply(root: View) {
        root.setTag(R.id.aura_look_stale, null)
        (root as? Recolours)?.onLook()
        (root.getTag(R.id.aura_look) as? Runnable)?.run()
        if (root is ViewGroup) for (i in 0 until root.childCount) apply(root.getChildAt(i))
    }

    /** A hidden panel: it recolours when it is next shown ([catchUp], from its onShow), so no frame walks hundreds of views. */
    fun markStale(root: View) = root.setTag(R.id.aura_look_stale, true)

    fun catchUp(root: View) {
        if (root.getTag(R.id.aura_look_stale) == true) apply(root)
    }

    /** A window of its own that is open now, a [Modal], a [Menu]: it is told too. Taken off again by [closed]. */
    fun opened(root: View) { if (root !in windows) windows.add(root) }

    fun closed(root: View) { windows.remove(root) }

    /** Every open window of its own. */
    fun applyToWindows() { for (w in windows.toList()) apply(w) }
}

/** [f] now, and at every theme change. More than one on a view all run, in order. */
fun <T : View> T.look(f: T.() -> Unit): T {
    f()
    val before = getTag(R.id.aura_look) as? Runnable
    setTag(R.id.aura_look, Runnable { before?.run(); f() })
    return this
}

/** A text in one colour token: `label.ink { Palette.ink2 }`. */
fun <T : TextView> T.ink(token: () -> Int): T = look { setTextColor(token()) }

/** A glyph in one colour token: `icon.glyph { Palette.icon }`. */
fun <T : ImageView> T.glyph(token: () -> Int): T = look { setColorFilter(token()) }

/**
 * A drawable in one colour token, recoloured in place: one of the white plates ([Plates]) or an icon. Each has its own mutated
 * state, since a tint on a shared 9-patch's state tints them all; the bitmap under it stays shared. [recolour] changes no bounds
 * and asks for no layout. With no [token] it is left as it is (a bitmap with its own colours, swapped by whoever holds it).
 */
class Tint(ctx: Context, res: Int, private val token: () -> Int) {
    val drawable: Drawable = ctx.getDrawable(res)!!.mutate()

    init { recolour() }

    fun recolour() = drawable.setTint(token())
}
