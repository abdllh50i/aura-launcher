package com.abdllh.aura.nav

import android.content.Context
import com.abdllh.aura.R

/**
 * The kinds of place the map shows: each kind has a colour for its dot and its name on the map ([MapPalette]), and a word
 * for the card of a place that was tapped ("restaurant", "mosque"...). A place comes from one of two sources: the pack's
 * places.mbtiles, whose field `kind` is the index of its kind ([MapPalette.KINDS], table cat's icon), or the online tiles'
 * `poi` layer (OpenMapTiles classes, outside the Kingdom and on a unit with no pack).
 */
object Poi {
    class Kind(val classes: List<String>, val label: Int)

    // in the order of MapPalette.KINDS after "other": index + 1 is the kind's number
    private val KINDS = listOf(
        Kind(listOf("restaurant", "fast_food", "food_court", "bakery", "butcher", "ice_cream"), R.string.poi_food),
        Kind(listOf("cafe", "bar", "beer", "pub", "biergarten"), R.string.poi_cafe),
        Kind(listOf("shop", "grocery", "clothing_store", "alcohol_shop", "laundry", "hairdresser", "music", "post"), R.string.poi_shop),
        Kind(listOf("fuel", "charging_station"), R.string.poi_fuel),
        Kind(listOf("car", "parking", "bus", "bus_station", "railway", "harbor", "aerialway"), R.string.poi_transport),
        Kind(listOf("hospital", "pharmacy", "doctors", "dentist", "veterinary"), R.string.poi_health),
        Kind(listOf("place_of_worship"), R.string.poi_worship),
        Kind(listOf("lodging"), R.string.poi_lodging),
        Kind(listOf("bank", "atm"), R.string.poi_bank),
        Kind(listOf("school", "college", "library", "kindergarten"), R.string.poi_school),
        Kind(listOf("park", "garden", "playground", "pitch", "golf", "swimming", "stadium", "sports_centre", "campsite", "zoo"), R.string.poi_park),
        Kind(listOf("attraction", "museum", "art_gallery", "castle", "cinema", "theatre", "monument", "viewpoint"), R.string.poi_sight),
        Kind(listOf("town_hall", "police", "fire_station", "embassy"), R.string.poi_public)
    )

    /** A style expression giving each place of the online tiles its colour from [colors] (by kind, as in [MapColors.dots]). */
    fun matchClass(colors: Array<String>): String = buildString {
        append("""["match",["get","class"]""")
        KINDS.forEachIndexed { i, k -> append(",[").append(k.classes.joinToString(",") { "\"$it\"" }).append("],\"").append(colors[i + 1]).append('"') }
        append(",\"").append(colors[0]).append("\"]")
    }

    /** The same for the pack's places. */
    fun matchKind(colors: Array<String>): String = buildString {
        append("""["match",["get","kind"]""")
        for (i in 1..KINDS.size) append(',').append(i).append(",\"").append(colors[i]).append('"')
        append(",\"").append(colors[0]).append("\"]")
    }

    /** What a place of the pack is called instead of a class in [label]. */
    fun packKind(kind: Int) = "kind:$kind"

    /** The word for a place of the tiles' class [cls], or of a [packKind] ("" for a kind without one of its own). */
    fun label(ctx: Context, cls: String?): String {
        val k = if (cls != null && cls.startsWith("kind:")) KINDS.getOrNull((cls.substring(5).toIntOrNull() ?: 0) - 1)
            else KINDS.firstOrNull { cls != null && cls in it.classes }
        return if (k == null) "" else ctx.getString(k.label)
    }
}
