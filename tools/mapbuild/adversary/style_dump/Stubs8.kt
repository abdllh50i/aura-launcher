@file:Suppress("unused")

package com.mapbox.mapboxsdk.maps

class Source { fun setVolatile(v: Boolean) { } }

/** MapStyle.afterLoad's only use of MapLibre. */
class Style { fun getSource(id: String): Source? = null }
