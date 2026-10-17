package com.abdllh.aura.nav

import android.graphics.Bitmap
import android.graphics.Color
import android.util.DisplayMetrics
import com.abdllh.aura.ui.Palette
import kotlin.math.hypot
import kotlin.math.pow

/**
 * The Aura map style (MapLibre style JSON) in the colours of the current theme: OpenStreetMap vector tiles from
 * OpenFreeMap (free, no key), 3D buildings when close, labels in the UI language, and the data sources the app fills
 * (the route, the arrow of the next turn, the destination).
 *
 * A road map first (1.8): the kinds of road are told apart at a glance — motorways and trunk roads in a warm colour,
 * main roads wider and lighter than streets, every road on a casing — built-up land, parks, sand and water each have
 * their tone, and the places people drive to are on it: shops, restaurants, fuel, mosques, hospitals... as a dot in
 * their kind's colour with their name ([Poi]), more of them the closer the map is.
 *
 * 1.12: the route is the brightest line on the map at night too, roads step down calmly below it, flyovers are drawn
 * over the roads they cross and buildings stand over the streets, and a place's dot and its name are one symbol, so
 * a dot never stands without its name.
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
    /**
     * The destination, a point. Drawn by the map itself rather than as a view laid over it: a view is moved after the
     * map has drawn and trailed it by a frame during every flight and pan.
     */
    const val SRC_DEST = "aura-dest"

    /** The layers a tap looks in for a place ([AuraMap.onPoiTap]). */
    val POI_LAYERS = arrayOf("poi-a", "poi-b", "poi-c", "poi-d")

    /** The route's line layers (faded while a new route is being found). */
    val ROUTE_LAYERS = arrayOf("route-casing", "route-near-casing", "route", "route-near")

    /**
     * Hidden while navigating: the minor places and the house numbers. A driver needs the road, the route and the next
     * turn; dozens of shop names compete with them, and on the turning heading-up map they are placed again all the
     * time (labels flicker, and the weak CPU pays for it). The most important places (band a) stay.
     */
    val NAV_HIDDEN = arrayOf("poi-b", "poi-c", "poi-d", "housenumber")

    /** The image of a place's dot ([poiDot]), drawn in each kind's colour. */
    const val POI_DOT = "poi-dot"

    private const val TILES = "https://tiles.openfreemap.org/planet"
    private const val GLYPHS = "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf"
    const val ATTRIBUTION = "© OpenStreetMap · OpenFreeMap"

    private class C(
        val land: String, val built: String, val commercial: String, val industrial: String, val care: String, val sand: String,
        val park: String, val wood: String, val water: String, val waterway: String,
        val building: String, val buildingLine: String, val building3d: String,
        val casing: String, val service: String, val minor: String, val tertiary: String, val secondary: String, val primary: String,
        val primaryCasing: String, val motorway: String, val motorwayCasing: String, val tunnel: String, val rail: String, val boundary: String,
        val roadLabel: String, val roadHalo: String, val motorLabel: String, val place: String, val placeHalo: String,
        val minorPlace: String, val waterLabel: String, val poiStroke: String
    )

    private fun hex(c: Int) = String.format(java.util.Locale.ROOT, "#%06X", c and 0xFFFFFF)

    // At night the roads step down calmly (each class a little brighter than the one below it, the smallest still
    // apart from the blocks) and stay below the route in brightness: until 1.11 the main roads and the motorway were as
    // bright as the route, and service roads vanished into the built-up land.
    private fun colors(): C = if (Palette.dark) C(
        land = hex(Palette.mapLand), built = "#22272E", commercial = "#262A31", industrial = "#24272C", care = "#2A2629", sand = "#25241F",
        park = "#1E3629", wood = "#1C3025", water = "#12243B", waterway = "#16304D",
        building = "#2B3038", buildingLine = "#394049", building3d = "#313741",
        casing = "#121519", service = "#30363F", minor = "#3A414B", tertiary = "#424A56", secondary = "#4A5361", primary = "#566173",
        primaryCasing = "#0F1114", motorway = "#6B5A38", motorwayCasing = "#2B2418", tunnel = "#2A2F37", rail = "#3A404A", boundary = "#525A66",
        roadLabel = "#B7BFCA", roadHalo = "#14171B", motorLabel = "#F0D6A4", place = "#ECEEF1", placeHalo = "#111317",
        minorPlace = "#9CA5B1", waterLabel = "#6C95C6", poiStroke = "#14171B"
    ) else C(
        land = hex(Palette.mapLand), built = "#F7F5F1", commercial = "#FBF3E4", industrial = "#EDEBEF", care = "#FBEBEC", sand = "#F3EBD5",
        park = "#CBE7BD", wood = "#C2DFB4", water = "#A8D2F2", waterway = "#9CC9EE",
        building = "#E6E2D9", buildingLine = "#D5D0C5", building3d = "#E2DED5",
        casing = "#CDD1D7", service = "#FFFFFF", minor = "#FFFFFF", tertiary = "#FFFFFF", secondary = "#FFFFFF", primary = "#FFFFFF",
        primaryCasing = "#B9BEC6", motorway = "#FCD572", motorwayCasing = "#DDA42B", tunnel = "#CDD1D7", rail = "#BFC4CC", boundary = "#A3A9B3",
        roadLabel = "#555C66", roadHalo = "#FFFFFF", motorLabel = "#6B4E0E", place = "#262B31", placeHalo = "#FFFFFF",
        minorPlace = "#666D77", waterLabel = "#3C76B3", poiStroke = "#FFFFFF"
    )

    /** The style's own tones as colours, for what stands in for the map before it has drawn ([com.abdllh.aura.ui.MapBackdropView]). */
    class Tones(val land: Int, val block: Int, val park: Int, val water: Int, val road: Int, val major: Int, val casing: Int)

    fun tones(): Tones {
        val c = colors()
        return Tones(Color.parseColor(c.land), Color.parseColor(c.building), Color.parseColor(c.park), Color.parseColor(c.water),
            Color.parseColor(c.minor), Color.parseColor(c.primary), Color.parseColor(c.casing))
    }

    /**
     * The colour of the route (and of the car on it): the owner's accent when it reads as a route on this map — blue,
     * cyan or violet — else the default blue. Amber is the motorways' colour, red the cameras', green the parks' and
     * graphite the roads' own: a route in those would sink into the map. The rest of the UI keeps the accent.
     */
    fun routeBase(): Int {
        val a = Palette.accent
        return if (a == Palette.accents[0] || a == Palette.accents[1] || a == Palette.accents[5]) a else Palette.DEFAULT_ACCENT
    }

    /** The route as drawn: the base colour, lighter at night so it is the brightest line among the dark roads. */
    fun routeColor(): Int = if (Palette.dark) Palette.mix(routeBase(), 0xFFFFFFFF.toInt(), 0.32f) else routeBase()

    /**
     * A white disc as a signed distance field (the alpha falls from 0.75 at the edge to 0 eight pixels out, as MapLibre
     * reads SDF images): one image the map tints per kind of place and rings with a halo, at any size.
     */
    fun poiDot(): Bitmap {
        val r = 6f
        val buffer = 8f
        val size = ((r + buffer) * 2).toInt()
        val px = IntArray(size * size)
        val mid = size / 2f
        for (y in 0 until size) for (x in 0 until size) {
            val d = hypot(x + 0.5f - mid, y + 0.5f - mid) - r // signed: negative inside
            val a = (1f - (d / buffer + 0.25f)).coerceIn(0f, 1f)
            px[y * size + x] = ((a * 255).toInt() shl 24) or 0xFFFFFF
        }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888).apply { density = DisplayMetrics.DENSITY_DEFAULT }
    }

    /** 3D buildings when zoomed in (fill-extrusion). */
    @JvmField var buildings3d = true

    // A road width: interpolated exponentially between stops (zoom, width), as MapLibre does, so a road drawn by other
    // layers (a bridge, a tunnel) can be given exactly the width of its class at any zoom.
    private class W(vararg val stops: Pair<Double, Double>) {
        fun at(z: Double): Double {
            if (z <= stops.first().first) return stops.first().second
            if (z >= stops.last().first) return stops.last().second
            val i = stops.indexOfLast { it.first <= z }
            val (z0, w0) = stops[i]
            val (z1, w1) = stops[i + 1]
            val t = (1.5.pow(z - z0) - 1) / (1.5.pow(z1 - z0) - 1)
            return w0 + (w1 - w0) * t
        }
    }

    private fun num(v: Double) = String.format(java.util.Locale.ROOT, "%.2f", v).trimEnd('0').trimEnd('.')

    /** Style JSON for the current theme and accent; [arabic] picks Arabic names (falling back to the local name). */
    fun json(arabic: Boolean): String {
        val c = colors()
        val dark = Palette.dark
        val base = routeBase()
        val route = hex(routeColor())
        val routeCasing = hex(Palette.mix(base, 0xFF000000.toInt(), if (dark) 0.62f else 0.38f))
        val turnCasing = hex(Palette.mix(base, 0xFF000000.toInt(), 0.62f))
        val name = if (arabic) """["coalesce",["get","name:ar"],["get","name"]]"""
        else """["coalesce",["get","name:en"],["get","name:latin"],["get","name"]]"""
        // (a motorway often has only its number)
        val roadName = if (arabic) """["coalesce",["get","name:ar"],["get","name"],["get","ref"]]"""
        else """["coalesce",["get","name:en"],["get","name:latin"],["get","name"],["get","ref"]]"""
        val regular = """["Noto Sans Regular"]"""
        val bold = """["Noto Sans Bold"]"""
        // Arabic letters read about a size smaller than Latin ones at the same pixel size: every label is a little
        // larger in Arabic, so both read alike
        fun ts(v: Double) = num(if (arabic) v * 1.12 else v)
        fun size(vararg zs: Pair<Number, Double>) =
            """["interpolate",["linear"],["zoom"],${zs.joinToString(",") { "${it.first},${ts(it.second)}" }}]"""

        fun width(vararg zw: Pair<Number, Number>) =
            """["interpolate",["exponential",1.5],["zoom"],${zw.joinToString(",") { "${it.first},${it.second}" }}]"""
        fun width(w: W, extra: Double = 0.0) = """["interpolate",["exponential",1.5],["zoom"],${w.stops.joinToString(",") { "${num(it.first)},${num(it.second + extra)}" }}]"""

        // (a colour, or an expression that picks one: [byClass])
        fun road(id: String, filter: String, color: String, w: String, minzoom: Int = 0, cap: String = "round") =
            """{"id":"$id","type":"line","source":"omt","source-layer":"transportation","minzoom":$minzoom,"filter":$filter,
               "layout":{"line-cap":"$cap","line-join":"round"},
               "paint":{"line-color":${if (color.startsWith("[")) color else "\"$color\""},"line-width":$w}}"""

        // on the ground: neither a bridge (drawn over the roads it crosses) nor a tunnel (faint, under them)
        val ground = """["!",["match",["get","brunnel"],["bridge","tunnel"],true,false]]"""
        fun cls(vararg names: String) = """["match",["get","class"],[${names.joinToString(",") { "\"$it\"" }}],true,false]"""
        fun all(vararg f: String) = """["all",${f.joinToString(",")}]"""
        fun fill(id: String, layer: String, filter: String, color: String, opacity: Double = 1.0) =
            """{"id":"$id","type":"fill","source":"omt","source-layer":"$layer","filter":$filter,
               "paint":{"fill-color":"$color","fill-opacity":$opacity}}"""

        // wide enough to read while driving: this screen has about one pixel per dp
        val wMotor = W(5.0 to 1.2, 10.0 to 3.0, 14.0 to 7.5, 16.0 to 16.0, 18.0 to 34.0)
        val wPrimary = W(7.0 to 0.8, 10.0 to 2.0, 14.0 to 6.0, 16.0 to 13.0, 18.0 to 28.0)
        val wSecondary = W(9.0 to 0.7, 12.0 to 1.8, 14.0 to 4.4, 16.0 to 10.0, 18.0 to 23.0)
        val wTertiary = W(11.0 to 0.6, 14.0 to 3.2, 16.0 to 8.0, 18.0 to 19.0)
        val wMinor = W(12.0 to 0.5, 14.0 to 2.2, 16.0 to 6.0, 18.0 to 15.0)
        val wService = W(14.0 to 0.6, 16.0 to 2.4, 18.0 to 8.0)
        // A slip road is a single lane: drawn at 60 % of its motorway, not as a second motorway beside it.
        fun motorWidth(extra: Double) = """["interpolate",["exponential",1.5],["zoom"],${wMotor.stops.joinToString(",") {
            """${num(it.first)},["+",["*",["case",["==",["get","ramp"],1],0.6,1],${num(it.second)}],${num(extra)}]""" }}]"""
        // A road drawn by its class in one layer (bridges, main tunnels): each class at the width of its own layer.
        fun classWidth(extra: Double) = """["interpolate",["exponential",1.5],["zoom"],${listOf(10.0, 12.0, 14.0, 16.0, 18.0).joinToString(",") { z ->
            """${num(z)},["match",["get","class"],["motorway","trunk"],${num(wMotor.at(z) + extra)},"primary",${num(wPrimary.at(z) + extra)},""" +
            """"secondary",${num(wSecondary.at(z) + extra)},"tertiary",${num(wTertiary.at(z) + extra)},${num(wMinor.at(z) + extra)}]""" }}]"""
        fun byClass(motorway: String, primary: String, secondary: String, tertiary: String, other: String) =
            """["match",["get","class"],["motorway","trunk"],"$motorway","primary","$primary","secondary","$secondary","tertiary","$tertiary","$other"]"""

        // The route is wider than the road it is on at driving zoom (a stripe down the middle of a wider road read as
        // a detail, not as "your road"), and stays a clear line when the map is far out.
        val wRoute = W(8.0 to 5.0, 12.0 to 7.0, 14.0 to 10.0, 16.0 to 15.0, 17.0 to 18.0, 18.0 to 22.0)
        val wTurn = W(8.0 to 3.0, 14.0 to 5.4, 16.0 to 8.0, 17.0 to 9.5, 18.0 to 11.5)

        // ---- places: a dot and its name as one symbol per band of importance (the tiles rank them), more bands the
        // closer the map. One symbol, so the two collide together: until 1.11 the dots were circle layers, which never
        // give way, and every name that did left an unnamed dot behind.
        val poiDot = Poi.match { if (dark) it.night else it.day }
        val poiText = Poi.match { if (dark) it.nightText else it.dayText }
        fun poi(band: String, minzoom: Double, rank: String): String {
            val filter = all("""["has","name"]""", rank)
            return """{"id":"poi-$band","type":"symbol","source":"omt","source-layer":"poi","minzoom":$minzoom,"filter":$filter,
                   "layout":{"icon-image":"$POI_DOT","icon-size":["interpolate",["linear"],["zoom"],14,0.6,17,1.05],
                   "icon-pitch-alignment":"viewport","text-field":$name,"text-font":$regular,"text-size":${size(14 to 12.5, 17 to 15.0)},
                   "text-anchor":"top","text-offset":[0,0.7],"text-max-width":8,"text-padding":6},
                   "paint":{"icon-color":$poiDot,"icon-halo-color":"${c.poiStroke}","icon-halo-width":1.6,
                   "icon-opacity":["interpolate",["linear"],["zoom"],$minzoom,0,${minzoom + 0.4},1],
                   "text-color":$poiText,"text-halo-color":"${c.roadHalo}","text-halo-width":1.5,
                   "text-opacity":["interpolate",["linear"],["zoom"],$minzoom,0,${minzoom + 0.4},1]}}"""
        }
        fun rank(above: Int, upTo: Int) = when {
            above <= 0 -> """["<=",["get","rank"],$upTo]"""
            upTo <= 0 -> """[">",["get","rank"],$above]"""
            else -> all("""[">",["get","rank"],$above]""", """["<=",["get","rank"],$upTo]""")
        }
        // (the least important band first: a layer further down the list places its names first)
        val pois = listOf(poi("d", 17.0, rank(40, 0)), poi("c", 16.0, rank(12, 40)), poi("b", 15.0, rank(3, 12)), poi("a", 13.6, rank(0, 3)))

        // Buildings: flat footprints at every zoom from 13 (cheap, and under the 3D ones when those show), 3D only
        // from zoom 17 — closer in than guidance drives (15.2 to 17), so a tilted map while driving never pays for
        // fill-extrusions, the most expensive layer this GPU draws. Drawn after the roads: a street in front of a
        // building passes behind its walls instead of being painted across them.
        val building = """{"id":"building","type":"fill","source":"omt","source-layer":"building","minzoom":13,
               "paint":{"fill-color":"${c.building}","fill-outline-color":"${c.buildingLine}","fill-opacity":["interpolate",["linear"],["zoom"],13,0,14,1]}}"""
        val building3d = if (buildings3d) """{"id":"building-3d","type":"fill-extrusion","source":"omt","source-layer":"building","minzoom":17,
               "paint":{"fill-extrusion-color":"${c.building3d}","fill-extrusion-height":["coalesce",["get","render_height"],8],
               "fill-extrusion-base":["coalesce",["get","render_min_height"],0],
               "fill-extrusion-opacity":["interpolate",["linear"],["zoom"],17,0,17.6,0.85]}}"""
        else """{"id":"building-outline","type":"line","source":"omt","source-layer":"building","minzoom":15,
               "paint":{"line-color":"${c.buildingLine}","line-width":1}}"""

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
            building,
            """{"id":"boundary","type":"line","source":"omt","source-layer":"boundary","filter":["<=",["get","admin_level"],4],
               "paint":{"line-color":"${c.boundary}","line-width":1,"line-dasharray":[3,2]}}""",
            // tunnels, under everything on the ground: the main roads as a quiet band of their own width (a main road
            // seemed to break at every underpass as a thin dashed line), the small ones dashed
            """{"id":"tunnel-major","type":"line","source":"omt","source-layer":"transportation","minzoom":10,
               "filter":${all("""["==",["get","brunnel"],"tunnel"]""", cls("motorway", "trunk", "primary", "secondary"))},
               "layout":{"line-join":"round"},"paint":{"line-color":"${c.tunnel}","line-opacity":0.55,"line-width":${classWidth(0.0)}}}""",
            """{"id":"tunnel","type":"line","source":"omt","source-layer":"transportation","minzoom":12,
               "filter":${all("""["==",["get","brunnel"],"tunnel"]""", cls("tertiary", "minor"))},
               "layout":{"line-join":"round"},"paint":{"line-color":"${c.minor}","line-opacity":0.45,"line-width":${width(wMinor)},"line-dasharray":[1,1]}}""",
            // casings (butt ends: round ones would show as beads where a road is cut into pieces)
            road("road-service-casing", all(ground, cls("service", "track")), c.casing, width(wService, 1.4), 15, "butt"),
            road("road-minor-casing", all(ground, cls("minor")), c.casing, width(wMinor, 1.8), 13, "butt"),
            road("road-tertiary-casing", all(ground, cls("tertiary")), c.casing, width(wTertiary, 1.8), 12, "butt"),
            road("road-secondary-casing", all(ground, cls("secondary")), c.casing, width(wSecondary, 2.0), 10, "butt"),
            road("road-primary-casing", all(ground, cls("primary")), c.primaryCasing, width(wPrimary, 2.2), 8, "butt"),
            road("road-motorway-casing", all(ground, cls("motorway", "trunk")), c.motorwayCasing, motorWidth(2.4), 5, "butt"),
            // fills, the bigger roads over the smaller ones
            road("road-service", all(ground, cls("service", "track")), c.service, width(wService), 14),
            road("road-minor", all(ground, cls("minor")), c.minor, width(wMinor), 12),
            road("road-tertiary", all(ground, cls("tertiary")), c.tertiary, width(wTertiary), 11),
            road("road-secondary", all(ground, cls("secondary")), c.secondary, width(wSecondary), 9),
            road("road-primary", all(ground, cls("primary")), c.primary, width(wPrimary), 7),
            road("road-motorway", all(ground, cls("motorway", "trunk")), c.motorway, motorWidth(0.0), 5),
            // bridges over all of them: a flyover crosses the motorway under it instead of being cut by it
            road("bridge-casing", all("""["==",["get","brunnel"],"bridge"]""", cls("motorway", "trunk", "primary", "secondary", "tertiary", "minor")),
                byClass(c.motorwayCasing, c.primaryCasing, c.casing, c.casing, c.casing), classWidth(2.4), 12, "butt"),
            road("bridge", all("""["==",["get","brunnel"],"bridge"]""", cls("motorway", "trunk", "primary", "secondary", "tertiary", "minor")),
                byClass(c.motorway, c.primary, c.secondary, c.tertiary, c.minor), classWidth(0.0), 12),
            """{"id":"rail","type":"line","source":"omt","source-layer":"transportation","minzoom":12,"filter":${cls("rail", "transit")},
               "paint":{"line-color":"${c.rail}","line-width":${width(12 to 0.6, 18 to 2.5)},"line-dasharray":[4,3]}}""",
            building3d,
            // our route, under the labels (the arrow of the next turn is over them, further down the list)
            // (both casings under both lines: the two parts join without a seam)
            """{"id":"route-casing","type":"line","source":"$SRC_ROUTE","layout":{"line-cap":"round","line-join":"round"},
               "paint":{"line-color":"$routeCasing","line-width":${width(wRoute, 4.5)}}}""",
            """{"id":"route-near-casing","type":"line","source":"$SRC_ROUTE_NEAR","layout":{"line-cap":"round","line-join":"round"},
               "paint":{"line-color":"$routeCasing","line-width":${width(wRoute, 4.5)}}}""",
            """{"id":"route","type":"line","source":"$SRC_ROUTE","layout":{"line-cap":"round","line-join":"round"},
               "paint":{"line-color":"$route","line-width":${width(wRoute)}}}""",
            """{"id":"route-near","type":"line","source":"$SRC_ROUTE_NEAR","layout":{"line-cap":"round","line-join":"round"},
               "paint":{"line-color":"$route","line-width":${width(wRoute)}}}"""
        ) + pois + extras(dark, c.roadHalo, bold) + listOf(
            // labels
            """{"id":"water-name","type":"symbol","source":"omt","source-layer":"water_name","minzoom":10,
               "layout":{"text-field":$name,"text-font":$regular,"text-size":${ts(13.0)},"text-max-width":8},
               "paint":{"text-color":"${c.waterLabel}","text-halo-color":"${c.land}","text-halo-width":1}}""",
            """{"id":"housenumber","type":"symbol","source":"omt","source-layer":"housenumber","minzoom":17.5,
               "layout":{"text-field":["get","housenumber"],"text-font":$regular,"text-size":${ts(11.0)}},
               "paint":{"text-color":"${c.minorPlace}","text-halo-color":"${c.land}","text-halo-width":1}}""",
            // Street names stand up to the viewer on the tilted map (lying flat they were squashed to about 60 % of
            // their height while driving, the very moment they are read).
            """{"id":"road-name","type":"symbol","source":"omt","source-layer":"transportation_name","minzoom":13,
               "filter":${cls("primary", "secondary", "tertiary", "minor", "service")},
               "layout":{"symbol-placement":"line","text-field":$name,"text-font":$regular,"symbol-spacing":320,
               "text-size":${size(13 to 12.5, 16 to 14.5, 18 to 17.0)},"text-max-angle":30,"text-padding":5,
               "text-rotation-alignment":"map","text-pitch-alignment":"viewport"},
               "paint":{"text-color":"${c.roadLabel}","text-halo-color":"${c.roadHalo}","text-halo-width":1.8}}""",
            """{"id":"road-name-major","type":"symbol","source":"omt","source-layer":"transportation_name","minzoom":10,
               "filter":${cls("motorway", "trunk")},
               "layout":{"symbol-placement":"line","text-field":$roadName,"text-font":$bold,"symbol-spacing":380,
               "text-size":${size(10 to 12.0, 14 to 14.0, 18 to 17.5)},"text-max-angle":30,"text-padding":5,
               "text-rotation-alignment":"map","text-pitch-alignment":"viewport"},
               "paint":{"text-color":"${c.motorLabel}","text-halo-color":"${c.roadHalo}","text-halo-width":1.8}}""",
            """{"id":"airport","type":"symbol","source":"omt","source-layer":"aerodrome_label","minzoom":9,
               "layout":{"text-field":$name,"text-font":$regular,"text-size":${ts(13.0)},"text-max-width":9},
               "paint":{"text-color":"${c.minorPlace}","text-halo-color":"${c.placeHalo}","text-halo-width":1.4}}""",
            """{"id":"place-minor","type":"symbol","source":"omt","source-layer":"place","minzoom":11,
               "filter":${cls("suburb", "quarter", "neighbourhood", "hamlet", "isolated_dwelling")},
               "layout":{"text-field":$name,"text-font":$regular,"text-size":${size(11 to 12.0, 16 to 15.5)},"text-max-width":8,
               "text-letter-spacing":0.02},
               "paint":{"text-color":"${c.minorPlace}","text-halo-color":"${c.placeHalo}","text-halo-width":1.5}}""",
            """{"id":"place-village","type":"symbol","source":"omt","source-layer":"place","minzoom":9,"filter":${cls("village")},
               "layout":{"text-field":$name,"text-font":$regular,"text-size":${size(9 to 12.0, 15 to 16.0)},"text-max-width":8},
               "paint":{"text-color":"${c.place}","text-halo-color":"${c.placeHalo}","text-halo-width":1.5}}""",
            """{"id":"place-town","type":"symbol","source":"omt","source-layer":"place","minzoom":6,"filter":${cls("town")},
               "layout":{"text-field":$name,"text-font":$bold,"text-size":${size(6 to 12.0, 14 to 18.0)},"text-max-width":8},
               "paint":{"text-color":"${c.place}","text-halo-color":"${c.placeHalo}","text-halo-width":1.7}}""",
            """{"id":"place-city","type":"symbol","source":"omt","source-layer":"place","minzoom":3,"maxzoom":15,"filter":${cls("city")},
               "layout":{"text-field":$name,"text-font":$bold,"text-size":${size(4 to 13.0, 12 to 22.0)},"text-max-width":8},
               "paint":{"text-color":"${c.place}","text-halo-color":"${c.placeHalo}","text-halo-width":1.9}}""",
            """{"id":"place-country","type":"symbol","source":"omt","source-layer":"place","maxzoom":7,"filter":${cls("country", "state")},
               "layout":{"text-field":$name,"text-font":$bold,"text-size":${size(2 to 12.0, 6 to 17.0)},"text-max-width":7},
               "paint":{"text-color":"${c.minorPlace}","text-halo-color":"${c.placeHalo}","text-halo-width":1.6}}""",
            // The arrow of the next turn over every label: a name never collides with a line, and a place's name
            // sitting on the turn hid its head (the one thing on the map the driver must see).
            """{"id":"turn-casing","type":"line","source":"$SRC_TURN","filter":["==","${'$'}type","LineString"],
               "layout":{"line-cap":"round","line-join":"round"},
               "paint":{"line-color":"$turnCasing","line-width":${width(wTurn, 3.4)}}}""",
            """{"id":"turn-head-casing","type":"line","source":"$SRC_TURN","filter":["==","${'$'}type","Polygon"],
               "layout":{"line-join":"round"},"paint":{"line-color":"$turnCasing","line-width":3.4}}""",
            """{"id":"turn","type":"line","source":"$SRC_TURN","filter":["==","${'$'}type","LineString"],
               "layout":{"line-cap":"round","line-join":"round"},
               "paint":{"line-color":"#FFFFFF","line-width":${width(wTurn)}}}""",
            """{"id":"turn-head","type":"fill","source":"$SRC_TURN","filter":["==","${'$'}type","Polygon"],
               "paint":{"fill-color":"#FFFFFF"}}"""
        ) + dest(route, dark)
        val empty ="""{"type":"geojson","data":{"type":"FeatureCollection","features":[]}}"""
        // tiles and glyphs through the unit's own tile server (the offline map, else OpenFreeMap), OpenFreeMap directly
        // only if that server could not start
        val local = com.abdllh.aura.nav.offline.TileServer.running
        val source = if (local) """"tiles":["${com.abdllh.aura.nav.offline.TileServer.tilesUrl}"],"minzoom":0,"maxzoom":14"""
            else """"url":"$TILES""""
        val glyphs = if (local) com.abdllh.aura.nav.offline.TileServer.glyphsUrl else GLYPHS
        // a softer light at night: lit roofs no brighter than the roads around them
        return """{"version":8,"name":"Aura","glyphs":"$glyphs",
          "light":{"anchor":"viewport","intensity":${if (dark) 0.3 else 0.4}},
          "sources":{"omt":{"type":"vector",$source,"attribution":"$ATTRIBUTION"},
            "$SRC_ROUTE":$empty,"$SRC_ROUTE_NEAR":$empty,"$SRC_TURN":$empty,"$SRC_EXTRAS":$empty,"$SRC_DEST":$empty},
          "layers":[${layers.joinToString(",")}]}"""
    }

    /**
     * The destination over everything else: a ringed dot in the route's colour with a soft halo, lying on the ground
     * like the route it ends (an ellipse on the tilted map), so it reads as a place, not as a button.
     */
    private fun dest(route: String, dark: Boolean): List<String> {
        fun circle(id: String, color: String, r: String, opacity: Double = 1.0) =
            """{"id":"$id","type":"circle","source":"$SRC_DEST",
               "paint":{"circle-color":"$color","circle-radius":$r,"circle-opacity":$opacity,"circle-pitch-alignment":"map"}}"""
        return listOf(
            circle("dest-halo", route, """["interpolate",["linear"],["zoom"],10,11,16,18]""", 0.22),
            circle("dest-ring", if (dark) "#14171B" else "#FFFFFF", """["interpolate",["linear"],["zoom"],10,8,16,12]"""),
            circle("dest-core", route, """["interpolate",["linear"],["zoom"],10,5.5,16,8.5]""")
        )
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
