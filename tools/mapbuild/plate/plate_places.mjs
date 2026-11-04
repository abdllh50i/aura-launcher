#!/usr/bin/env node
// Renders plates of places.mbtiles in MapLibre GL JS with the design kit's headless Chrome. Needs serve_tiles.py running with --root on this folder.
//   node plate_places.mjs --kit D:\k2501-work\tmp\v2\design\_kit --port 8799 --out <folder> --icons icons.json [--base http://.../base/{z}/{x}/{y}.pbf]
// A plate per place and zoom; a .json beside each says how many features MapLibre itself read, with some of their fields.
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

const argv = process.argv.slice(2);
const flag = n => { const i = argv.indexOf('--' + n); return i >= 0 ? argv[i + 1] : undefined; };
const kit = flag('kit'), port = flag('port') || '8799', outDir = flag('out'), base = flag('base');
const iconOf = JSON.parse(readFileSync(flag('icons'), 'utf8'));
const { launch, renderOne } = await import(pathToFileURL(join(kit, 'render.mjs')).href);
const PLATES = [
  ['khobar-z14', [50.2085, 26.2935], 14], ['khobar-z13', [50.2085, 26.2935], 13], ['khobar-z12', [50.17, 26.32], 12],
  ['jubail-z14', [49.662, 27.012], 14], ['jubail-z13', [49.662, 27.012], 13],
  ['riyadh-z14', [46.685, 24.713], 14],
];
const browser = await launch();
try {
  for (const [name, center, zoom] of PLATES) {
    const job = { tiles: `http://127.0.0.1:${port}/places/{z}/{x}/{y}.pbf`, center, zoom, iconOf, base };
    const out = join(outDir, name + '.png');
    const res = await renderOne(browser, {
      html: `http://127.0.0.1:${port}/places.html`, out, w: 1024, h: 600, ready: 'window.__ready === true', wait: 200,
      init: 'window.__job = ' + JSON.stringify(job) + ';', eval: 'JSON.stringify(window.__info)',
    });
    for (const p of res.problems) console.log('  ! ' + p);
    writeFileSync(join(outDir, name + '.json'), res.extra || '{}');
    console.log(name, res.extra);
  }
} finally { await browser.close(); }
