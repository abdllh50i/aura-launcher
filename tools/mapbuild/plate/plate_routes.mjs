#!/usr/bin/env node
// Renders route plates (route.html) with the design kit's headless Chrome. Needs serve_tiles.py running with --root on this folder and base=out\base.mbtiles.
//   node plate_routes.mjs --kit D:\k2501-work\tmp\v2\design\_kit --port 8799 --jobs jobs.json --out <folder> [--w 1100] [--h 760]
// jobs.json is a list of { name, title, lines, points, box | center+zoom } (tests\make_plates.py writes it).
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

const argv = process.argv.slice(2);
const flag = n => { const i = argv.indexOf('--' + n); return i >= 0 ? argv[i + 1] : undefined; };
const kit = flag('kit'), port = flag('port') || '8799', outDir = flag('out');
const w = +(flag('w') || 1100), h = +(flag('h') || 760);
const jobs = JSON.parse(readFileSync(flag('jobs'), 'utf8'));
const { launch, renderOne } = await import(pathToFileURL(join(kit, 'render.mjs')).href);
const browser = await launch();
try {
  for (const job of jobs) {
    job.tiles = `http://127.0.0.1:${port}/base/{z}/{x}/{y}.pbf`;
    const out = join(outDir, job.name + '.png');
    const res = await renderOne(browser, {
      html: `http://127.0.0.1:${port}/route.html`, out, w, h, ready: 'window.__ready === true', wait: 200,
      init: 'window.__job = ' + JSON.stringify(job) + ';', eval: 'JSON.stringify(window.__info)',
    });
    const bad = res.problems.filter(p => !/favicon/.test(p));
    for (const p of bad.slice(0, 3)) console.log('  ! ' + p);
    console.log(job.name, res.extra);
  }
} finally { await browser.close(); }
