package com.abdllh.aura.home

/** What the home cards need from their hosting activity. */
interface HomeHost {
    fun openDrawer()
    fun openSettings(page: Int = 0)
    fun toast(msg: String)
}
