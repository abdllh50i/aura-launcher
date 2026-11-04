# AMRI maps pack: notices

This file travels with the pack (make_pack.py refuses to build without it). The texts named here are in `licences/`.

## The data

**OpenStreetMap** (base tiles, routing tiles, roads, most signals and cameras, part of the places)
© OpenStreetMap contributors. Available under the Open Database License 1.0: https://opendatacommons.org/licenses/odbl/1-0/ . Contents under the Database Contents License: https://opendatacommons.org/licenses/dbcl/1-0/ . See https://www.openstreetmap.org/copyright .
This pack is a Derivative Database and is offered under the ODbL. The scripts that make it are public in `tools/mapbuild/` of the AMRI OS repository, so that anyone can make it again from the same data.
Changed from OpenStreetMap by this build: Arabic names added to roads that had only a Latin name, by a rule table and word pairs learnt from OpenStreetMap's own names (`arabic_names.py`, its rules and word list beside it). A few typos in Arabic names mended. Nothing is added to a road whose name has a word the table does not know.

**OpenMapTiles schema** (the layout of the base tiles)
© OpenMapTiles, https://openmaptiles.org . Schema under BSD and CC-BY; the tiles were built with Planetiler and planetiler-openmaptiles.

**Natural Earth** (the far seas and the lowest zooms)
Public domain, https://www.naturalearthdata.com .

**Overture Maps Places** (release in `places.db`, table meta, key overture)
Overture Maps Foundation, https://overturemaps.org . Community Data License Agreement Permissive 2.0 (`licences/CDLA-Permissive-2.0.txt`); data that came from Foursquare under Apache 2.0 (`licences/Apache-2.0.txt`).
Copyright 2024 Foursquare Labs, Inc. All rights reserved. Foursquare data was transformed to the Overture schema.

**Overture Maps Divisions** (towns, districts, areas)
© OpenStreetMap contributors, Overture Maps Foundation. ODbL; some sources CC BY 4.0.

**Owner's additions** (`spot` rows with src 2): signals drawn by the owner of the unit, in the repository's branch `mapdata`, under the same terms as the pack.

Conflating Overture rows with OpenStreetMap rows makes `places.db` a Derivative Database under the ODbL; the CDLA and Apache 2.0 allow that with these notices kept.

**On the map, as required:** `© OpenStreetMap contributors · © OpenMapTiles · Overture Maps`.

## The fonts

Glyphs in `glyphs.db` (stacks `AMRI Map Sans Regular`, `AMRI Map Sans SemiBold`) are signed-distance renderings of **IBM Plex Sans Arabic**: Copyright 2017 IBM Corp. with Reserved Font Name "Plex". SIL Open Font License 1.1 (`licences/OFL-1.1.txt`). A modified form for the map, so under a name without the reserved word: each glyph drawn as a distance field at 24 px, in the ranges MapLibre asks for; letters and marks the font lacks borrowed from similar ones. Made by `make_glyphs.py`.

## The routing library the pack is read with (in the app, not in the pack)

Valhalla 3.9.1 (MIT License, © Valhalla contributors, https://github.com/valhalla/valhalla) through valhalla-mobile 0.6.4 (MIT License, https://github.com/Rallista/valhalla-mobile). Statically linked into its library, each under its own licence: Protocol Buffers (BSD 3-Clause), Boost (Boost Software License 1.0), LZ4 (BSD 2-Clause), Abseil (Apache 2.0), robin-hood-hashing (MIT), RapidJSON (MIT), unordered_dense (MIT), date and tz data (MIT, public domain).

## Tools, which do not ship

Planetiler (Apache 2.0), planetiler-openmaptiles (BSD 3-Clause), Valhalla's build tools (MIT), pyosmium (BSD 2-Clause), fontTools (MIT).

## To check before a public release

Written on 2026-11-04 with no web access to these pages:
- Read https://docs.overturemaps.org/attribution/ once and compare with the Overture lines above: they come from the plan's notes (maps.md section 16).
- `licences/CDLA-Permissive-2.0.txt` was typed from memory, the short text is well known, but compare it with https://cdla.dev/permissive-2-0/ .
- Take the static libraries' copyright lines from their own LICENSE files into this file, as BSD and MIT ask. They are named above, not yet quoted.
