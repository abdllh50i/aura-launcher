package com.abdllh.aura.nav

import android.content.Context
import com.abdllh.aura.R
import com.abdllh.aura.ui.Palette

/**
 * The kinds of place the map shows (the tiles' `poi` layer, OpenMapTiles classes): each kind has a colour for its dot
 * and its name on the map, and a word for the card of a place that was tapped ("restaurant", "mosque"...).
 */
object Poi {
    class Kind(val classes: List<String>, val label: Int, color: Int) {
        val day = hex(color)
        val night = hex(Palette.mix(color, 0xFFFFFFFF.toInt(), 0.16f))
        // names: darker than the dot on the light map, lighter on the dark one, so they read on the land colour
        val dayText = hex(Palette.mix(color, 0xFF000000.toInt(), 0.34f))
        val nightText = hex(Palette.mix(color, 0xFFFFFFFF.toInt(), 0.42f))
    }

    private val OTHER = Kind(emptyList(), 0, 0xFF78828F.toInt())

    private val KINDS = listOf(
        Kind(listOf("restaurant", "fast_food", "food_court", "bakery", "butcher", "ice_cream"), R.string.poi_food, 0xFFEE8A2A.toInt()),
        Kind(listOf("cafe", "bar", "beer", "pub", "biergarten"), R.string.poi_cafe, 0xFFD9772B.toInt()),
        Kind(listOf("shop", "grocery", "clothing_store", "alcohol_shop", "laundry", "hairdresser", "music", "post"), R.string.poi_shop, 0xFF4A8CF0.toInt()),
        Kind(listOf("fuel", "charging_station"), R.string.poi_fuel, 0xFF16A3B8.toInt()),
        Kind(listOf("car", "parking", "bus", "bus_station", "railway", "harbor", "aerialway"), R.string.poi_transport, 0xFF5B7FA6.toInt()),
        Kind(listOf("hospital", "pharmacy", "doctors", "dentist", "veterinary"), R.string.poi_health, 0xFFE5484D.toInt()),
        Kind(listOf("place_of_worship"), R.string.poi_worship, 0xFF2F9E62.toInt()),
        Kind(listOf("lodging"), R.string.poi_lodging, 0xFFE35FA5.toInt()),
        Kind(listOf("bank", "atm"), R.string.poi_bank, 0xFF6C6FD6.toInt()),
        Kind(listOf("school", "college", "library", "kindergarten"), R.string.poi_school, 0xFF8A7862.toInt()),
        Kind(listOf("park", "garden", "playground", "pitch", "golf", "swimming", "stadium", "sports_centre", "campsite", "zoo"), R.string.poi_park, 0xFF3FA34D.toInt()),
        Kind(listOf("attraction", "museum", "art_gallery", "castle", "cinema", "theatre", "monument", "viewpoint"), R.string.poi_sight, 0xFF12A594.toInt()),
        Kind(listOf("town_hall", "police", "fire_station", "embassy"), R.string.poi_public, 0xFF7C6BD9.toInt())
    )

    private fun hex(c: Int) = String.format(java.util.Locale.ROOT, "#%06X", c and 0xFFFFFF)

    /** A style expression giving each place the colour [pick] takes from its kind. */
    fun match(pick: (Kind) -> String): String = buildString {
        append("""["match",["get","class"]""")
        for (k in KINDS) append(",[").append(k.classes.joinToString(",") { "\"$it\"" }).append("],\"").append(pick(k)).append('"')
        append(",\"").append(pick(OTHER)).append("\"]")
    }

    /** The word for a place of the tiles' class [cls] ("" for a kind without one of its own). */
    fun label(ctx: Context, cls: String?): String {
        val k = KINDS.firstOrNull { cls != null && cls in it.classes } ?: return ""
        return ctx.getString(k.label)
    }
}
