# The adversary's checks of the pack

Each script says how to run it. Data stays outside the repo.

| What | Script |
|---|---|
| The app's real style as JSON: compiles `nav/MapStyle.kt` and `nav/Poi.kt` with small stubs | `dump_style.ps1`, `style_dump/` |
| That style on `base.mbtiles` in MapLibre GL JS: 30 cameras, day and night, Arabic and English; sheets and a table | `plate_style.mjs`, `plate_style.html`, `plates_sheet.py`, `edge_cameras.json` |
| The same in the real renderer: the debug app's MapLibre Native 10.3.7 on an x86_64 emulator, tile template pointed at the PC, restored after | `native_shots.ps1`, `native_setpref.sh`, `native_stress.ps1`, `serve_logged.py` |
| The pack's sea and fonts in the real renderer, the app's style asking for its usual font names: `glyphs.db` under those names in a small map.db, restored after | `native_pack.ps1` |
| Largest tiles, every label character against the app's glyph ranges | `tile_stats.py`, `tile_time.mjs`, `tile_time.html`, `tile_decode.c` |
| Which sea cells have no row; water names, green areas, POI bands, boundaries at sea | `sea_rows.py` |
| Contiguous address space in a 32-bit ART process on a 64-bit kernel | `Mmap32.java` |
| The ARM32 library under qemu-arm with the firmware's own bionic, against the PC service's answers | `arm32_driver.c`, `arm32_run.sh`, `arm32_requests.py`, `arm32_compare.py` |
| Whether that qemu faults on unaligned accesses as the unit's CPU does | `arm32_align.c` |
| A dry run of the chunk plan (`maps.md` section 15); the real thing is `make_pack.py` | `pack_plan.py` |
