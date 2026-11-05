// Made by scripts/gen_map_palette.py from the design's map-day.json and map-night.json. Do not edit: change those and run it again.
package com.abdllh.aura.nav

/** The colours of the map in one theme. [dots] and [texts]: a place's dot and name, by kind ([KINDS]: table cat's icon). */
internal class MapColors(
    val land: String, val built: String, val commercial: String, val industrial: String, val care: String, val sand: String, val park: String, val wood: String, val water: String, val waterway: String, val building: String, val buildingLine: String, val building3d: String, val casing: String, val service: String, val minor: String, val tertiary: String, val secondary: String, val primary: String, val primaryCasing: String, val motorway: String, val motorwayCasing: String, val tunnel: String, val rail: String, val boundary: String, val roadLabel: String, val roadHalo: String, val motorLabel: String, val place: String, val placeHalo: String, val minorPlace: String, val waterLabel: String, val poiStroke: String, val route: String, val routeCasing: String, val routeAlt: String, val routeAltCasing: String, val turn: String, val turnCasing: String, val dest: String, val destRing: String, val poi: String, val poiHalo: String,
    val dots: Array<String>, val texts: Array<String>, val light: Double
)

internal object MapPalette {
    /** The places' kinds in the order of [MapColors.dots]: the `kind` field of the POI tiles is an index into it. */
    val KINDS = arrayOf("other", "food", "cafe", "shop", "fuel", "transport", "health", "worship", "lodging", "bank", "school", "park", "sight", "public")

    val DAY = MapColors(
        land = "#E4E5E0", built = "#E1E2DD", commercial = "#E3E3DC", industrial = "#DEDFDD", care = "#E7E2DF", sand = "#E4E5E0",
        park = "#CBE8BF", wood = "#C2E2B6", water = "#A3D4F7", waterway = "#9ACEF3", building = "#D8DAD7", buildingLine = "#CBCDCC",
        building3d = "#DDDFDC", casing = "#AEB8C7", service = "#FFFFFF", minor = "#FFFFFF", tertiary = "#FFFFFF", secondary = "#FFFFFF",
        primary = "#FFFFFF", primaryCasing = "#A4AFBF", motorway = "#FDFDFE", motorwayCasing = "#94A1B4", tunnel = "#CDD2D9", rail = "#C4C8D0",
        boundary = "#B6BCC7", roadLabel = "#4A5260", roadHalo = "#FFFFFF", motorLabel = "#2C3749", place = "#1C2230", placeHalo = "#FFFFFF",
        minorPlace = "#5A6371", waterLabel = "#2A6BA2", poiStroke = "#FFFFFF", route = "#1F6BFF", routeCasing = "#0E47BE", routeAlt = "#93B4EE",
        routeAltCasing = "#6C8DC8", turn = "#FFFFFF", turnCasing = "#0E47BE", dest = "#1F6BFF", destRing = "#FFFFFF", poi = "#59626F",
        poiHalo = "#FFFFFF",
        dots = arrayOf("#78828F", "#EE8A2A", "#D9772B", "#4A8CF0", "#16A3B8", "#5B7FA6", "#E5484D", "#2F9E62", "#E35FA5", "#6C6FD6", "#8A7862", "#3FA34D", "#12A594", "#7C6BD9"),
        texts = arrayOf("#4F565E", "#9D5B1C", "#8F4E1C", "#315C9E", "#0F6B79", "#3C546E", "#972F33", "#1F6841", "#963F6D", "#47498D", "#5B4F41", "#2A6B33", "#0C6D62", "#52478F"),
        light = 0.4
    )

    val NIGHT = MapColors(
        land = "#1B2230", built = "#1E2534", commercial = "#202737", industrial = "#1D2432", care = "#222838", sand = "#1B2230",
        park = "#18392D", wood = "#163428", water = "#0F2D50", waterway = "#133660", building = "#242D3D", buildingLine = "#29334A",
        building3d = "#2C3648", casing = "#131925", service = "#2D3747", minor = "#374256", tertiary = "#404C62", secondary = "#4B5971",
        primary = "#5C6D8A", primaryCasing = "#131925", motorway = "#7387AB", motorwayCasing = "#161E2C", tunnel = "#2B3444", rail = "#354052",
        boundary = "#3C485D", roadLabel = "#B6C0CF", roadHalo = "#141A26", motorLabel = "#DCE4F0", place = "#E9EDF3", placeHalo = "#10151F",
        minorPlace = "#95A2B4", waterLabel = "#71A2D8", poiStroke = "#141A26", route = "#4F8BFF", routeCasing = "#1B3E9E", routeAlt = "#7D93BE",
        routeAltCasing = "#25324E", turn = "#FFFFFF", turnCasing = "#1B3E9E", dest = "#4F8BFF", destRing = "#141A26", poi = "#9BA8B9",
        poiHalo = "#141A26",
        dots = arrayOf("#8D96A1", "#F09C4F", "#DF8C4C", "#689EF3", "#3EB1C3", "#7492B3", "#E96569", "#52AE7C", "#E778B3", "#8285DD", "#9B8C79", "#5CB268", "#36B3A4", "#9081DF"),
        texts = arrayOf("#BFC4CB", "#F6C69A", "#EFBE97", "#A9C7F7", "#8CD4DF", "#AFC2D5", "#F2A5A8", "#9CD3B3", "#F0AFD1", "#B8BAEA", "#C7BFB2", "#A3D4A9", "#88D6CC", "#BFB5EC"),
        light = 0.3
    )
}
