package com.abdllh.aura.home

/** What the home panels need from their hosting activity. */
interface HomeHost {
    fun openDrawer()
    fun openControls()
    fun openSettings(page: Int = 0)
    fun toast(msg: String)

    /** Re-applies the theme/accent after it was changed from the home screen itself. */
    fun restyle()
}
