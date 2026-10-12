package com.abdllh.aura.nav

import com.abdllh.aura.ui.Palette

/**
 * The Aura map style (MapLibre style JSON) in the colours of the current theme: OpenStreetMap vector tiles from
 * OpenFreeMap (free, no key), 3D buildings when close, labels in the UI language, and the data sources the app fills
 * (the route and the arrow of the next turn).
 *
 * A road map first (1.8): the kinds of road are told apart at a glance — motorways and trunk roads in a warm colour,
 * main roads wider and lighter than streets, every road on a casing — built-up land, parks, sand and water each have
 * their tone, and the places people drive to are on it: shops, restaurants, fuel, mosques, hospitals... as a dot in
 * their kind's colour with their name ([Poi]), more of them the closer the map is. Dots are circle layers and names
 * symbol layers without icons: icon symbols drew black on a tilted map on the emulator's GPU.
 */
object MapStyle {
    const val SRC_ROUTE = "aura-route"
    /**
     * While driving, the stretch of the route right ahead of the car: a short line of its own, cut again at the car
     * several times a second (the long rest, in [SRC_ROUTE], only every kilometre or so), so the line always starts
     * under the arrow instead of trailing behind it until the whole route is rebuilt.
     */
    const val SRC_ROUTE_NEAR = "aura-route-near"
    /** The next turn: a short line along the route through it and its arrow head (a polygon). */
    const val SRC_TURN = "aura-turn"
    /** What the owner added with the map editor ([MapExtras]): cameras, signals, bumps, streets with their limit. */
    const val SRC_EXTRAS = "aura-extras"

    /** The layers a tap looks in for a place ([AuraMap.onPoiTap]). */
    val POI_LAYERS = arrayOf("poi-dot-a", "poi-dot-b", "poi-dot-c", "poi-dot-d", "poi-name-a", "poi-name-b", "poi-name-c", "poi-name-d")

    private const val TILES = "https://tiles.openfreemap.org/planet"
    private const val GLYPHS = "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf"
    const val ATTRIBUTION = "© OpenStreetMap · OpenFreeMap"

    private class C(
        val land: String, val built: String, val commercial: String, val industrial: String, val care: String, val sand: String,
        val park: String, val wood: String, val water: String, val waterway: String,
        val building: String, val buildingLine: String, val building3d: String,
        val casing: String, val service: String, val minor: String, val tertiary: String, val secondary: String, val primary: String,
        val primaryCasing: String, val motorway: String, val motorwayCasing: String, val rail: String, val boundary: String,
        val roadLabel: String, val roadHalo: String, val motorLabel: String, val place: String, val placeHalo: String,
        val minorPlace: String, val waterLabel: String, val poiStroke: String
    )

    private fun hex(c: Int) = String.format(java.util.Locale.ROOT, "#%06X", c and 0xFFFFFF)

    private fun colors(): C = if (Palette.dark) C(
        land = hex(Palette.mapLand), built = "#22272E", commercial = "#262A31", industrial = "#24272C", care = "#2A2629", sand = "#25241F",
        park = "#1E3629", wood = "#1C3025", water = "#12243B", waterway = "#16304D",
        building = "#2B3038", buildingLine = "#394049", building3d = "#353C46",
        casing = "#13161A", service = "#2D323A", minor = "#3C434E", tertiary = "#47515E", secondary = "#515C6B", primary = "#606C7E",
        primaryCasing = "#15181D", motorway = "#8E7848", motorwayCasing = "#3B3220", rail = "#3A404A", boundary = "#525A66",
        roadLabel = "#B7BFCA", roadHalo = "#14171B", motorLabel = "#F0D6A4", place = "#ECEEF1", placeHalo = "#111317",
        minorPlace = "#9CA5B1", waterLabel = "#6C95C6", poiStroke = "#14171B"
    ) else C(
        land = hex(Palette.mapLand), built = "#F7F5F1", commercial = "#FBF3E4", industrial = "#EDEBEF", care = "#FBEBEC", sand = "#F3EBD5",
        park = "#CBE7BD", wood = "#C2DFB4", water = "#A8D2F2", waterway = "#9CC9EE",
        building = "#E6E2D9", buildingLine = "#D5D0C5", building3d = "#E2DED5",
        casing = "#CDD1D7", service = "#FFFFFF", minor = "#FFFFFF", tertiary = "#FFFFFF", secondary = "#FFFFFF", primary = "#FFFFFF",
        primaryCasing = "#B9BEC6", motorway = "#FCD572", motorwayCasing = "#DDA42B", rail = "#BFC4CC", boundary = "#A3A9B3",
        roadLabel = "#555C66", roadHalo = "#FFFFFF", motorLabel = "#6B4E0E", place = "#262B31", placeHalo = "#FFFFFF",
        minorPlace = "#666D77", waterLabel = "#3C76B3", poiStroke = "#FFFFFF"
    )

    /** 3D buildings when zoomed in (fill-extrusion). */
    @JvmField var buildings3d = true

    /** Style JSON for the current theme and accent; [arabic] picks Arabic names (falling back to the local name). */
    fun json(arabic: Boolean): String {
        val c = colors()
        val dark = Palette.dark
        // the route: the accent, a little lighter at night so it stands off the dark roads
        val route = hex(if (dark) Palette.mix(Palette.accent, 0xFFFFFFFF.toInt(), 0.14f) else Palette.accent)
        val routeCasing = hex(Palette.mix(Palette.accent, 0xFF000000.toInt(), if (dark) 0.50f else 0.32f))
        val turnCasing = hex(Palette.mix(Palette.accent, 0xFF000000.toInt(), 0.62f))
        val name = if (arabic) """["coalesce",["get","name:ar"],["get","name"]]"""
        else """["coalesce",["get","name:en"],["get","name:latin"],["get","name"]]"""
        // (a motorway often has only its number)
        val roadName = if (arabic) """["coalesce",["get","name:ar"],["get","name"],["get","ref"]]"""
        else """["coalesce",["get","name:en"],["get","name:latin"],["get","name"],["get","ref"]]"""
        val regular = """["Noto Sans Regular"]"""
        val bold = """["Noto Sans Bold"]"""

        fun width(vararg zw: Pair<Number, Number>) =
            """["interpolate",["exponential",1.5],["zoom"],${zw.joinToString(",") { "${it.first},${it.second}" }}]"""

        fun road(id: String, filter: String, color: String, w: String, minzoom: Int = 0, cap: String = "round") =
            """{"id":"$id","type":"line","source":"omt","source-layer":"transportation","minzoom":$minzoom,"filter":$filter,
               "layout":{"line-cap":"$cap","line-join":"round"},
               "paint":{"line-color":"$color","line-width":$w}}"""

        val notTunnel = """["!=",["get","brunnel"],"tunnel"]"""
        fun cls(vararg names: String) = """["match",["get","class"],[${names.joinToString(",") { "\"$it\"" }}],true,false]"""
        fun all(vararg f: String) = """["all",${f.joinToString(",")}]"""
        fun fill(id: String, layer: String, filter: String, color: String, opacity: Double = 1.0) =
            """{"id":"$id","type":"fill","source":"omt","source-layer":"$layer","filter":$filter,
               "paint":{"fill-color":"$color","fill-opacity":$opacity}}"""

        // wide enough to read while driving: this screen has about one pixel per dp
        val wMotor = width(5 to 1.2, 10 to 3.0, 14 to 7.5, 16 to 16.0, 18 to 34.0)
        val wPrimary = width(7 to 0.8, 10 to 2.0, 14 to 6.0, 16 to 13.0, 18 to 28.0)
        val wSecondary = width(9 to 0.7, 12 to 1.8, 14 to 4.4, 16 to 10.0, 18 to 23.0)
        val wTertiary = width(11 to 0.6, 14 to 3.2, 16 to 8.0, 18 to 19.0)
        val wMinor = width(12 to 0.5, 14 to 2.2, 16 to 6.0, 18 to 15.0)
        val wService = width(14 to 0.6, 16 to 2.4, 18 to 8.0)
        fun plus(w: String, extra: Double) = w.replace(Regex("""(\d+),(\d+(\.\d+)?)""")) { m ->
            val z = m.groupValues[1].toInt()
            if (z <= 4) m.value else "${m.groupValues[1]},${"%.1f".format(java.util.Locale.ROOT, m.groupValues[2].toDouble() + extra)}"
        }
        // (a line of nearly one width at every zoom, not a band as wide as the road when close)
        val wRoute = width(8 to 5.0, 14 to 8.5, 16 to 12.0, 18 to 16.0)

        // ---- places: a dot and a name per band of importance (the tiles rank them), more bands the closer the map
        val poiDot = Poi.match { if (dark) it.night else it.day }
        val poiText = Poi.match { if (dark) it.nightText else it.dayText }
        fun poi(band: String, minzoom: Double, rank: String): List<String> {
            val filter = all("""["has","name"]""", rank)
            return listOf(
                """{"id":"poi-dot-$band","type":"circle","source":"omt","source-layer":"poi","minzoom":$minzoom,"filter":$filter,
                   "paint":{"circle-color":$poiDot,"circle-radius":["interpolate",["linear"],["zoom"],14,3.6,17,6.2],
                   "circle-stroke-color":"${c.poiStroke}","circle-stroke-width":1.6,
                   "circle-opacity":["interpolate",["linear"],["zoom"],$minzoom,0,${minzoom + 0.4},1],
                   "circle-stroke-opacity":["interpolate",["linear"],["zoom"],$minzoom,0,${minzoom + 0.4},1]}}""",
                """{"id":"poi-name-$band","type":"symbol","source":"omt","source-layer":"poi","minzoom":$minzoom,"filter":$filter,
                   "layout":{"text-field":$name,"text-font":$regular,"text-size":["interpolate",["linear"],["zoom"],14,12,17,14.5],
                   "text-anchor":"top","text-offset":[0,0.75],"text-max-width":8,"text-padding":6,"text-optional":true},
                   "paint":{"text-color":$poiText,"text-halo-color":"${c.roadHalo}","text-halo-width":1.5}}"""
            )
        }
        fun rank(above: Int, upTo: Int) = when {
            above <= 0 -> """["<=",["get","rank"],$upTo]"""
            upTo <= 0 -> """[">",["get","rank"],$above]"""
            else -> all("""[">",["get","rank"],$above]""", """["<=",["get","rank"],$upTo]""")
        }
        // (the least important band first: a layer further down the list places its names first)
        val pois = poi("d", 17.0, rank(40, 0)) + poi("c", 16.0, rank(12, 40)) + poi("b", 15.0, rank(3, 12)) + poi("a", 13.6, rank(0, 3))

        val layers = listOf(
            """{"id":"background","type":"background","paint":{"background-color":"${c.land}"}}""",
            fill("landcover-sand", "landcover", cls("sand"), c.sand),
            fill("landuse-built", "landuse", cls("residential", "suburb", "neighbourhood"), c.built),
            fill("landuse-commercial", "landuse", cls("commercial", "retail"), c.commercial),
            fill("landuse-industrial", "landuse", cls("industrial", "railway", "garages", "quarry"), c.industrial),
            fill("landuse-care", "landuse", cls("hospital", "school", "university", "college", "kindergarten"), c.care),
            fill("landcover-wood", "landcover", cls("wood", "forest"), c.wood, 0.8),
            fill("landcover-grass", "landcover", cls("grass", "farmland", "wetland"), c.park, 0.5),
            """{"id":"park","type":"fill","source":"omt","source-layer":"park","paint":{"fill-color":"${c.park}","fill-opacity":0.9}}""",
            fill("landuse-green", "landuse", cls("cemetery", "stadium", "pitch", "playground", "track"), c.park, 0.7),
            """{"id":"waterway","type":"line","source":"omt","source-layer":"waterway","minzoom":8,
               "paint":{"line-color":"${c.waterway}","line-width":${width(8 to 0.5, 14 to 2.0, 18 to 6.0)}}}""",
            """{"id":"water","type":"fill","source":"omt","source-layer":"water","paint":{"fill-color":"${c.water}"}}""",
            """{"id":"aeroway","type":"line","source":"omt","source-layer":"aeroway","minzoom":11,"filter":${cls("runway", "taxiway")},
               "paint":{"line-color":"${c.minor}","line-width":${width(11 to 1.0, 15 to 12.0, 18 to 40.0)}}}""",
            if (buildings3d) """{"id":"building","type":"fill","source":"omt","source-layer":"building","minzoom":13,"maxzoom":15.5,
               "paint":{"fill-color":"${c.building}","fill-outline-color":"${c.buildingLine}","fill-opacity":["interpolate",["linear"],["zoom"],13,0,14,1]}}"""
            else """{"id":"building","type":"fill","source":"omt","source-layer":"building","minzoom":13,
               "paint":{"fill-color":"${c.building}","fill-outline-color":"${c.buildingLine}","fill-opacity":["interpolate",["linear"],["zoom"],13,0,14,1]}}""",
            if (buildings3d) """{"id":"building-3d","type":"fill-extrusion","source":"omt","source-layer":"building","minzoom":15,
               "paint":{"fill-extrusion-color":"${c.building3d}","fill-extrusion-height":["coalesce",["get","render_height"],8],
               "fill-extrusion-base":["coalesce",["get","render_min_height"],0],
               "fill-extrusion-opacity":["interpolate",["linear"],["zoom"],15,0,16,0.85]}}"""
            else """{"id":"building-outline","type":"line","source":"omt","source-layer":"building","minzoom":15,
               "paint":{"line-color":"${c.buildingLine}","line-width":1}}""",
            """{"id":"boundary","type":"line","source":"omt","source-layer":"boundary","filter":["<=",["get","admin_level"],4],
               "paint":{"line-color":"${c.boundary}","line-width":1,"line-dasharray":[3,2]}}""",
            // tunnels: faint
            """{"id":"tunnel","type":"line","source":"omt","source-layer":"transportation","minzoom":12,
               "filter":${all("""["==",["get","brunnel"],"tunnel"]""", cls("motorway", "trunk", "primary", "secondary", "tertiary", "minor"))},
               "layout":{"line-join":"round"},"paint":{"line-color":"${c.minor}","line-opacity":0.45,"line-width":$wMinor,"line-dasharray":[1,1]}}""",
            // casings (butt ends: round ones would show as beads where a road is cut into pieces)
            road("road-service-casing", all(notTunnel, cls("service", "track")), c.casing, plus(wService, 1.4), 15, "butt"),
            road("road-minor-casing", all(notTunnel, cls("minor")), c.casing, plus(wMinor, 1.8), 13, "butt"),
            road("road-tertiary-casing", all(notTunnel, cls("tertiary")), c.casing, plus(wTertiary, 1.8), 12, "butt"),
            road("road-secondary-casing", all(notTunnel, cls("secondary")), c.casing, plus(wSecondary, 2.0), 10, "butt"),
            road("road-primary-casing", all(notTunnel, cls("primary")), c.primaryCasing, plus(wPrimary, 2.2), 8, "butt"),
            road("road-motorway-casing", all(notTunnel, cls("motorway", "trunk")), c.motorwayCasing, plus(wMotor, 2.4), 5, "butt"),
            // fills, the bigger roads over the smaller ones
            road("road-service", all(notTunnel, cls("service", "track")), c.service, wService, 14),
            road("road-minor", all(notTunnel, cls("minor")), c.minor, wMinor, 12),
            road("road-tertiary", all(notTunnel, cls("tertiary")), c.tertiary, wTertiary, 11),
            road("road-secondary", all(notTunnel, cls("secondary")), c.secondary, wSecondary, 9),
            road("road-primary", all(notTunnel, cls("primary")), c.primary, wPrimary, 7),
            road("road-motorway", all(notTunnel, cls("motorway", "trunk")), c.motorway, wMotor, 5),
            """{"id":"rail","type":"line","source":"omt","source-layer":"transportation","minzoom":12,"filter":${cls("rail", "transit")},
               "paint":{"line-color":"${c.rail}","line-width":${width(12 to 0.6, 18 to 2.5)},"line-dasharray":[4,3]}}""",
            // our route, under the labels; then the arrow of the next turn on it
            // (both casings under both lines: the two parts join without a seam)
            """{"id":"route-casing","type":"line","source":"$SRC_ROUTE","layout":{"line-cap":"round","line-join":"round"},
               "paint":{"line-color":"$routeCasing","line-width":${plus(wRoute, 4.5)}}}""",
            """{"id":"route-near-casing","type":"line","source":"$SRC_ROUTE_NEAR","layout":{"line-cap":"round","line-join":"round"},
               "paint":{"line-color":"$routeCasing","line-width":${plus(wRoute, 4.5)}}}""",
            """{"id":"route","type":"line","source":"$SRC_ROUTE","layout":{"line-cap":"round","line-join":"round"},
               "paint":{"line-color":"$route","line-width":$wRoute}}""",
            """{"id":"route-near","type":"line","source":"$SRC_ROUTE_NEAR","layout":{"line-cap":"round","line-join":"round"},
               "paint":{"line-color":"$route","line-width":$wRoute}}""",
            """{"id":"turn-casing","type":"line","source":"$SRC_TURN","filter":["==","${'$'}type","LineString"],
               "layout":{"line-cap":"round","line-join":"round"},
               "paint":{"line-color":"$turnCasing","line-width":${plus(width(8 to 2.8, 14 to 4.8, 16 to 6.8, 18 to 9.5), 3.4)}}}""",
            """{"id":"turn-head-casing","type":"line","source":"$SRC_TURN","filter":["==","${'$'}type","Polygon"],
               "layout":{"line-join":"round"},"paint":{"line-color":"$turnCasing","line-width":3.4}}""",
            """{"id":"turn","type":"line","source":"$SRC_TURN","filter":["==","${'$'}type","LineString"],
               "layout":{"line-cap":"round","line-join":"round"},
               "paint":{"line-color":"#FFFFFF","line-width":${width(8 to 2.8, 14 to 4.8, 16 to 6.8, 18 to 9.5)}}}""",
            """{"id":"turn-head","type":"fill","source":"$SRC_TURN","filter":["==","${'$'}type","Polygon"],
               "paint":{"fill-color":"#FFFFFF"}}"""
        ) + pois + extras(dark, c.roadHalo, bold) + listOf(
            // labels
            """{"id":"water-name","type":"symbol","source":"omt","source-layer":"water_name","minzoom":10,
               "layout":{"text-field":$name,"text-font":$regular,"text-size":13,"text-max-width":8},
               "paint":{"text-color":"${c.waterLabel}","text-halo-color":"${c.land}","text-halo-width":1}}""",
            """{"id":"housenumber","type":"symbol","source":"omt","source-layer":"housenumber","minzoom":17.5,
               "layout":{"text-field":["get","housenumber"],"text-font":$regular,"text-size":11},
               "paint":{"text-color":"${c.minorPlace}","text-halo-color":"${c.land}","text-halo-width":1}}""",
            """{"id":"road-name","type":"symbol","source":"omt","source-layer":"transportation_name","minzoom":13,
               "filter":${cls("primary", "secondary", "tertiary", "minor", "service")},
               "layout":{"symbol-placement":"line","text-field":$name,"text-font":$regular,"symbol-spacing":320,
               "text-size":["interpolate",["linear"],["zoom"],13,12,16,14,18,16.5],"text-max-angle":30,"text-padding":5,"text-rotation-alignment":"map"},
               "paint":{"text-color":"${c.roadLabel}","text-halo-color":"${c.roadHalo}","text-halo-width":1.8}}""",
            """{"id":"road-name-major","type":"symbol","source":"omt","source-layer":"transportation_name","minzoom":10,
               "filter":${cls("motorway", "trunk")},
               "layout":{"symbol-placement":"line","text-field":$roadName,"text-font":$bold,"symbol-spacing":380,
               "text-size":["interpolate",["linear"],["zoom"],10,11.5,14,13.5,18,17],"text-max-angle":30,"text-padding":5,"text-rotation-alignment":"map"},
               "paint":{"text-color":"${c.motorLabel}","text-halo-color":"${c.roadHalo}","text-halo-width":1.8}}""",
            """{"id":"airport","type":"symbol","source":"omt","source-layer":"aerodrome_label","minzoom":9,
               "layout":{"text-field":$name,"text-font":$regular,"text-size":13,"text-max-width":9},
               "paint":{"text-color":"${c.minorPlace}","text-halo-color":"${c.placeHalo}","text-halo-width":1.4}}""",
            """{"id":"place-minor","type":"symbol","source":"omt","source-layer":"place","minzoom":11,
               "filter":${cls("suburb", "quarter", "neighbourhood", "hamlet", "isolated_dwelling")},
               "layout":{"text-field":$name,"text-font":$regular,"text-size":["interpolate",["linear"],["zoom"],11,11.5,16,15],"text-max-width":8,
               "text-letter-spacing":0.02},
               "paint":{"text-color":"${c.minorPlace}","text-halo-color":"${c.placeHalo}","text-halo-width":1.5}}""",
            """{"id":"place-village","type":"symbol","source":"omt","source-layer":"place","minzoom":9,"filter":${cls("village")},
               "layout":{"text-field":$name,"text-font":$regular,"text-size":["interpolate",["linear"],["zoom"],9,12,15,16],"text-max-width":8},
               "paint":{"text-color":"${c.place}","text-halo-color":"${c.placeHalo}","text-halo-width":1.5}}""",
            """{"id":"place-town","type":"symbol","source":"omt","source-layer":"place","minzoom":6,"filter":${cls("town")},
               "layout":{"text-field":$name,"text-font":$bold,"text-size":["interpolate",["linear"],["zoom"],6,12,14,18],"text-max-width":8},
               "paint":{"text-color":"${c.place}","text-halo-color":"${c.placeHalo}","text-halo-width":1.7}}""",
            """{"id":"place-city","type":"symbol","source":"omt","source-layer":"place","minzoom":3,"maxzoom":15,"filter":${cls("city")},
               "layout":{"text-field":$name,"text-font":$bold,"text-size":["interpolate",["linear"],["zoom"],4,13,12,22],"text-max-width":8},
               "paint":{"text-color":"${c.place}","text-halo-color":"${c.placeHalo}","text-halo-width":1.9}}""",
            """{"id":"place-country","type":"symbol","source":"omt","source-layer":"place","maxzoom":7,"filter":${cls("country", "state")},
               "layout":{"text-field":$name,"text-font":$bold,"text-size":["interpolate",["linear"],["zoom"],2,12,6,17],"text-max-width":7},
               "paint":{"text-color":"${c.minorPlace}","text-halo-color":"${c.placeHalo}","text-halo-width":1.6}}"""
        )
        val empty ="""{"type":"geojson","data":{"type":"FeatureCollection","features":[]}}"""
        // tiles and glyphs through the unit's own tile server (the offline map, else OpenFreeMap), OpenFreeMap directly
        // only if that server could not start
        val local = com.abdllh.aura.nav.offline.TileServer.running
        val source = if (local) """"tiles":["${com.abdllh.aura.nav.offline.TileServer.tilesUrl}"],"minzoom":0,"maxzoom":14"""
            else """"url":"$TILES""""
        val glyphs = if (local) com.abdllh.aura.nav.offline.TileServer.glyphsUrl else GLYPHS
        return """{"version":8,"name":"Aura","glyphs":"$glyphs",
          "sources":{"omt":{"type":"vector",$source,"attribution":"$ATTRIBUTION"},
            "$SRC_ROUTE":$empty,"$SRC_ROUTE_NEAR":$empty,"$SRC_TURN":$empty,"$SRC_EXTRAS":$empty},
          "layers":[${layers.joinToString(",")}]}"""
    }

    /**
     * The owner's additions, drawn without icons like the places: a camera is a red disc with a white eye and its
     * word under it, a bump an amber dot with its word, a traffic signal its three lamps (three dots that keep their
     * order whichever way the map is turned), and a street's limit is written along it.
     */
    private fun extras(dark: Boolean, halo: String, bold: String): List<String> {
        fun kind(k: String) = """["==",["get","kind"],"$k"]"""
        val ring = if (dark) "#14171B" else "#FFFFFF"
        fun lamp(id: String, color: String, dy: Int) =
            """{"id":"extras-signal-$id","type":"circle","source":"$SRC_EXTRAS","minzoom":14,"filter":${kind("signal")},
               "paint":{"circle-color":"$color","circle-radius":3.6,"circle-stroke-color":"#20242B","circle-stroke-width":1.6,
               "circle-translate":[0,$dy],"circle-translate-anchor":"viewport"}}"""
        fun word(id: String, k: String, color: String, minzoom: Double, size: Double, dy: Double) =
            """{"id":"extras-$id-name","type":"symbol","source":"$SRC_EXTRAS","minzoom":$minzoom,"filter":${kind(k)},
               "layout":{"text-field":["get","label"],"text-font":$bold,"text-size":$size,"text-anchor":"top","text-offset":[0,$dy],
               "text-allow-overlap":true,"text-ignore-placement":true},
               "paint":{"text-color":"$color","text-halo-color":"$halo","text-halo-width":1.8}}"""
        return listOf(
            """{"id":"extras-speed","type":"symbol","source":"$SRC_EXTRAS","minzoom":13,"filter":${kind("speed")},
               "layout":{"symbol-placement":"line","symbol-spacing":230,"text-field":["get","label"],"text-font":$bold,
               "text-size":["interpolate",["linear"],["zoom"],13,12,17,16],"text-rotation-alignment":"viewport","text-keep-upright":true,
               "text-padding":2},
               "paint":{"text-color":"${if (dark) "#FF8A8E" else "#C9272D"}","text-halo-color":"$halo","text-halo-width":2.2}}""",
            lamp("red", "#E5484D", -8), lamp("amber", "#F5A524", 0), lamp("green", "#2FD07A", 8),
            """{"id":"extras-bump","type":"circle","source":"$SRC_EXTRAS","minzoom":13.5,"filter":${kind("bump")},
               "paint":{"circle-color":"#F5A524","circle-radius":["interpolate",["linear"],["zoom"],13.5,4,17,7.5],
               "circle-stroke-color":"$ring","circle-stroke-width":2}}""",
            """{"id":"extras-camera","type":"circle","source":"$SRC_EXTRAS","minzoom":10,"filter":${kind("camera")},
               "paint":{"circle-color":"#E5484D","circle-radius":["interpolate",["linear"],["zoom"],10,4,14,8,17,10.5],
               "circle-stroke-color":"$ring","circle-stroke-width":2.2}}""",
            """{"id":"extras-camera-eye","type":"circle","source":"$SRC_EXTRAS","minzoom":13,"filter":${kind("camera")},
               "paint":{"circle-color":"#FFFFFF","circle-radius":["interpolate",["linear"],["zoom"],13,2.2,17,3.8]}}""",
            word("bump", "bump", if (dark) "#F7BE5C" else "#9A6200", 15.0, 12.5, 0.75),
            word("camera", "camera", if (dark) "#FF8A8E" else "#C9272D", 12.5, 13.5, 0.9)
        )
    }
}
