package com.abdllh.aura.nav

import com.abdllh.aura.ui.Palette

/**
 * The Aura map style (MapLibre style JSON) in the colours of the current theme: OpenStreetMap vector tiles from
 * OpenFreeMap (free, no key), a calm palette in the spirit of EV navigation screens, 3D buildings when close,
 * labels in the UI language, and three empty data sources the app fills: the route, the destination and the car.
 */
object MapStyle {
    const val SRC_ROUTE = "aura-route"

    private const val TILES = "https://tiles.openfreemap.org/planet"
    private const val GLYPHS = "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf"
    const val ATTRIBUTION = "© OpenStreetMap · OpenFreeMap"

    private class C(
        val land: String, val park: String, val wood: String, val water: String, val waterway: String,
        val building: String, val building3d: String, val casing: String, val minor: String, val service: String,
        val secondary: String, val primary: String, val motorway: String, val rail: String, val boundary: String,
        val roadLabel: String, val roadHalo: String, val place: String, val placeHalo: String, val minorPlace: String,
        val poi: String, val waterLabel: String, val routeCasing: String
    )

    private fun hex(c: Int) = String.format(java.util.Locale.ROOT, "#%06X", c and 0xFFFFFF)

    private fun colors(): C = if (Palette.dark) C(
        land = "#1B1D21", park = "#1D2A22", wood = "#1C271F", water = "#0E1925", waterway = "#122233",
        building = "#24272C", building3d = "#2A2E35", casing = "#121417", minor = "#2B2F36", service = "#25282E",
        secondary = "#343941", primary = "#3E444E", motorway = "#4A5361", rail = "#30343B", boundary = "#454B55",
        roadLabel = "#8E949D", roadHalo = "#1B1D21", place = "#C9CDD3", placeHalo = "#101214", minorPlace = "#8B919A",
        poi = "#7C828B", waterLabel = "#4F6B88", routeCasing = hex(Palette.mix(Palette.accent, 0xFF000000.toInt(), 0.45f))
    ) else C(
        land = "#EEF0F3", park = "#D6EAD0", wood = "#CFE4C8", water = "#B4D3EE", waterway = "#A9CBEA",
        building = "#E1E4E8", building3d = "#DDE0E5", casing = "#C9CED6", minor = "#FFFFFF", service = "#F7F8FA",
        secondary = "#FFFFFF", primary = "#FFFFFF", motorway = "#FFFFFF", rail = "#C3C8CF", boundary = "#A9AFB8",
        roadLabel = "#5E6570", roadHalo = "#FFFFFF", place = "#2E333A", placeHalo = "#FFFFFF", minorPlace = "#6B717B",
        poi = "#868D97", waterLabel = "#4C79A8", routeCasing = hex(Palette.mix(Palette.accent, 0xFF000000.toInt(), 0.30f))
    )

    /** 3D buildings when zoomed in (fill-extrusion). */
    @JvmField var buildings3d = true

    /** Style JSON for the current theme and accent; [arabic] picks Arabic names (falling back to the local name). */
    fun json(arabic: Boolean): String {
        val c = colors()
        val accent = hex(Palette.accent)
        val name = if (arabic) """["coalesce",["get","name:ar"],["get","name"]]"""
        else """["coalesce",["get","name:en"],["get","name:latin"],["get","name"]]"""
        val regular = """["Noto Sans Regular"]"""
        val bold = """["Noto Sans Bold"]"""

        fun width(vararg zw: Pair<Number, Number>) =
            """["interpolate",["exponential",1.5],["zoom"],${zw.joinToString(",") { "${it.first},${it.second}" }}]"""

        fun road(id: String, filter: String, color: String, w: String, minzoom: Int = 0) =
            """{"id":"$id","type":"line","source":"omt","source-layer":"transportation","minzoom":$minzoom,"filter":$filter,
               "layout":{"line-cap":"round","line-join":"round"},
               "paint":{"line-color":"$color","line-width":$w}}"""

        val notTunnel = """["!=",["get","brunnel"],"tunnel"]"""
        fun cls(vararg names: String) = """["match",["get","class"],[${names.joinToString(",") { "\"$it\"" }}],true,false]"""
        fun all(vararg f: String) = """["all",${f.joinToString(",")}]"""

        val wMotor = width(5 to 1.0, 10 to 2.6, 14 to 6.0, 18 to 26.0)
        val wPrimary = width(7 to 0.7, 10 to 1.6, 14 to 4.8, 18 to 22.0)
        val wSecondary = width(9 to 0.6, 12 to 1.4, 14 to 3.4, 18 to 18.0)
        val wMinor = width(12 to 0.5, 14 to 1.8, 18 to 13.0)
        val wService = width(14 to 0.5, 16 to 1.4, 18 to 7.0)
        fun plus(w: String, extra: Double) = w.replace(Regex("""(\d+),(\d+(\.\d+)?)""")) { m ->
            val z = m.groupValues[1].toInt()
            if (z <= 4) m.value else "${m.groupValues[1]},${"%.1f".format(java.util.Locale.ROOT, m.groupValues[2].toDouble() + extra)}"
        }

        val layers = listOf(
            """{"id":"background","type":"background","paint":{"background-color":"${c.land}"}}""",
            """{"id":"landcover-wood","type":"fill","source":"omt","source-layer":"landcover","filter":${cls("wood", "forest")},
               "paint":{"fill-color":"${c.wood}","fill-opacity":0.7}}""",
            """{"id":"landcover-grass","type":"fill","source":"omt","source-layer":"landcover","filter":${cls("grass", "farmland", "wetland")},
               "paint":{"fill-color":"${c.park}","fill-opacity":0.45}}""",
            """{"id":"park","type":"fill","source":"omt","source-layer":"park","paint":{"fill-color":"${c.park}","fill-opacity":0.85}}""",
            """{"id":"landuse-green","type":"fill","source":"omt","source-layer":"landuse","filter":${cls("cemetery", "stadium", "pitch", "playground")},
               "paint":{"fill-color":"${c.park}","fill-opacity":0.6}}""",
            """{"id":"waterway","type":"line","source":"omt","source-layer":"waterway","minzoom":8,
               "paint":{"line-color":"${c.waterway}","line-width":${width(8 to 0.5, 14 to 2.0, 18 to 6.0)}}}""",
            """{"id":"water","type":"fill","source":"omt","source-layer":"water","paint":{"fill-color":"${c.water}"}}""",
            """{"id":"aeroway","type":"line","source":"omt","source-layer":"aeroway","minzoom":11,"filter":${cls("runway", "taxiway")},
               "paint":{"line-color":"${c.minor}","line-width":${width(11 to 1.0, 15 to 12.0, 18 to 40.0)}}}""",
            if (buildings3d) """{"id":"building","type":"fill","source":"omt","source-layer":"building","minzoom":13,"maxzoom":15.5,
               "paint":{"fill-color":"${c.building}","fill-opacity":["interpolate",["linear"],["zoom"],13,0,14,1]}}"""
            else """{"id":"building","type":"fill","source":"omt","source-layer":"building","minzoom":13,
               "paint":{"fill-color":"${c.building}","fill-opacity":["interpolate",["linear"],["zoom"],13,0,14,1]}}""",
            if (buildings3d) """{"id":"building-3d","type":"fill-extrusion","source":"omt","source-layer":"building","minzoom":15,
               "paint":{"fill-extrusion-color":"${c.building3d}","fill-extrusion-height":["coalesce",["get","render_height"],8],
               "fill-extrusion-base":["coalesce",["get","render_min_height"],0],
               "fill-extrusion-opacity":["interpolate",["linear"],["zoom"],15,0,16,0.85]}}"""
            else """{"id":"building-outline","type":"line","source":"omt","source-layer":"building","minzoom":15,
               "paint":{"line-color":"${c.building3d}","line-width":1}}""",
            """{"id":"boundary","type":"line","source":"omt","source-layer":"boundary","filter":["<=",["get","admin_level"],4],
               "paint":{"line-color":"${c.boundary}","line-width":1,"line-dasharray":[3,2]}}""",
            // tunnels: faint
            """{"id":"tunnel","type":"line","source":"omt","source-layer":"transportation","minzoom":12,
               "filter":${all("""["==",["get","brunnel"],"tunnel"]""", cls("motorway", "trunk", "primary", "secondary", "tertiary", "minor"))},
               "layout":{"line-join":"round"},"paint":{"line-color":"${c.minor}","line-opacity":0.45,"line-width":$wMinor,"line-dasharray":[1,1]}}""",
            // casings
            road("road-minor-casing", all(notTunnel, cls("minor", "tertiary")), c.casing, plus(wMinor, 1.6), 13),
            road("road-secondary-casing", all(notTunnel, cls("secondary")), c.casing, plus(wSecondary, 1.6), 10),
            road("road-primary-casing", all(notTunnel, cls("primary", "trunk")), c.casing, plus(wPrimary, 1.8), 8),
            road("road-motorway-casing", all(notTunnel, cls("motorway")), c.casing, plus(wMotor, 2.0), 5),
            // fills
            road("road-service", all(notTunnel, cls("service", "track")), c.service, wService, 14),
            road("road-minor", all(notTunnel, cls("minor", "tertiary")), c.minor, wMinor, 12),
            road("road-secondary", all(notTunnel, cls("secondary")), c.secondary, wSecondary, 9),
            road("road-primary", all(notTunnel, cls("primary", "trunk")), c.primary, wPrimary, 7),
            road("road-motorway", all(notTunnel, cls("motorway")), c.motorway, wMotor, 5),
            """{"id":"rail","type":"line","source":"omt","source-layer":"transportation","minzoom":12,"filter":${cls("rail", "transit")},
               "paint":{"line-color":"${c.rail}","line-width":${width(12 to 0.6, 18 to 2.5)},"line-dasharray":[4,3]}}""",
            // our route, under the labels
            """{"id":"route-casing","type":"line","source":"$SRC_ROUTE","layout":{"line-cap":"round","line-join":"round"},
               "paint":{"line-color":"${c.routeCasing}","line-width":${width(8 to 6.0, 14 to 11.0, 18 to 30.0)}}}""",
            """{"id":"route","type":"line","source":"$SRC_ROUTE","layout":{"line-cap":"round","line-join":"round"},
               "paint":{"line-color":"$accent","line-width":${width(8 to 4.0, 14 to 7.5, 18 to 22.0)}}}""",
            // labels
            """{"id":"water-name","type":"symbol","source":"omt","source-layer":"water_name","minzoom":10,
               "layout":{"text-field":$name,"text-font":$regular,"text-size":13,"text-max-width":8},
               "paint":{"text-color":"${c.waterLabel}","text-halo-color":"${c.land}","text-halo-width":1}}""",
            """{"id":"road-name","type":"symbol","source":"omt","source-layer":"transportation_name","minzoom":13,
               "filter":${cls("motorway", "trunk", "primary", "secondary", "tertiary", "minor")},
               "layout":{"symbol-placement":"line","text-field":$name,"text-font":$regular,
               "text-size":["interpolate",["linear"],["zoom"],13,11.5,18,15],"text-max-angle":30,"text-padding":6,"text-rotation-alignment":"map"},
               "paint":{"text-color":"${c.roadLabel}","text-halo-color":"${c.roadHalo}","text-halo-width":1.6}}""",
            """{"id":"poi","type":"symbol","source":"omt","source-layer":"poi","minzoom":16,"filter":["<=",["get","rank"],14],
               "layout":{"text-field":$name,"text-font":$regular,"text-size":12,"text-max-width":8,"text-padding":8},
               "paint":{"text-color":"${c.poi}","text-halo-color":"${c.land}","text-halo-width":1.4}}""",
            """{"id":"housenumber","type":"symbol","source":"omt","source-layer":"housenumber","minzoom":17.5,
               "layout":{"text-field":["get","housenumber"],"text-font":$regular,"text-size":11},
               "paint":{"text-color":"${c.poi}","text-halo-color":"${c.land}","text-halo-width":1}}""",
            """{"id":"place-minor","type":"symbol","source":"omt","source-layer":"place","minzoom":11,
               "filter":${cls("suburb", "quarter", "neighbourhood", "hamlet", "isolated_dwelling")},
               "layout":{"text-field":$name,"text-font":$regular,"text-size":["interpolate",["linear"],["zoom"],11,11,16,14],"text-max-width":8},
               "paint":{"text-color":"${c.minorPlace}","text-halo-color":"${c.placeHalo}","text-halo-width":1.4}}""",
            """{"id":"place-village","type":"symbol","source":"omt","source-layer":"place","minzoom":9,"filter":${cls("village")},
               "layout":{"text-field":$name,"text-font":$regular,"text-size":["interpolate",["linear"],["zoom"],9,12,15,16],"text-max-width":8},
               "paint":{"text-color":"${c.place}","text-halo-color":"${c.placeHalo}","text-halo-width":1.4}}""",
            """{"id":"place-town","type":"symbol","source":"omt","source-layer":"place","minzoom":6,"filter":${cls("town")},
               "layout":{"text-field":$name,"text-font":$bold,"text-size":["interpolate",["linear"],["zoom"],6,12,14,18],"text-max-width":8},
               "paint":{"text-color":"${c.place}","text-halo-color":"${c.placeHalo}","text-halo-width":1.6}}""",
            """{"id":"place-city","type":"symbol","source":"omt","source-layer":"place","minzoom":3,"maxzoom":15,"filter":${cls("city")},
               "layout":{"text-field":$name,"text-font":$bold,"text-size":["interpolate",["linear"],["zoom"],4,13,12,22],"text-max-width":8},
               "paint":{"text-color":"${c.place}","text-halo-color":"${c.placeHalo}","text-halo-width":1.8}}""",
            """{"id":"place-country","type":"symbol","source":"omt","source-layer":"place","maxzoom":7,"filter":${cls("country", "state")},
               "layout":{"text-field":$name,"text-font":$bold,"text-size":["interpolate",["linear"],["zoom"],2,12,6,17],"text-max-width":7},
               "paint":{"text-color":"${c.minorPlace}","text-halo-color":"${c.placeHalo}","text-halo-width":1.6}}"""
        )
        val empty ="""{"type":"geojson","data":{"type":"FeatureCollection","features":[]}}"""
        return """{"version":8,"name":"Aura","glyphs":"$GLYPHS",
          "sources":{"omt":{"type":"vector","url":"$TILES","attribution":"$ATTRIBUTION"},
            "$SRC_ROUTE":$empty},
          "layers":[${layers.joinToString(",")}]}"""
    }
}
