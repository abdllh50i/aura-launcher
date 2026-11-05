package com.abdllh.aura.settings

import android.content.Context
import android.view.View

/**
 * One page of the Settings panel, or a sub-page of one (SPEC.md section 6.6). The panel holds the one card, the title and the back button; a page is what lies in the card.
 */
abstract class Page(val titleRes: Int, val iconRes: Int = 0) {
    abstract fun build(ctx: Context): View
    /** It is on screen: what it shows is read again, what it hears goes on. Safe twice. */
    open fun onShow() {}
    open fun onHide() {}

    /**
     * Whether the panel keeps the page once built. False for a page of lines read when it is built that change meanwhile (the gear's source, the notes from the unit): it is
     * built again at every visit.
     */
    open val keep: Boolean get() = true

    /** It scrolls inside the card, rows snapping, with the baked fades at its ends. False: its rows fill the card, as the seven pages' do. */
    open val scrolls: Boolean get() = true
}
