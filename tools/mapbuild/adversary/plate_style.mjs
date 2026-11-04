#!/usr/bin/env node
// The app's real style (dump_style.ps1) on the pack's base.mbtiles, 30 fixed cameras over the Kingdom, day and night, Arabic and English,
// in MapLibre GL JS with the design kit's headless Chrome. Needs serve_tiles.py running with --root on this folder and base=<base.mbtiles>.
//   node plate_style.mjs --kit <design kit folder, for render.mjs> --port 8801 --style <folder> --out <folder> [--only name,name] [--w 1024 --h 600] [--variants day-ar,...] [--cameras file.json]
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

const argv = process.argv.slice(2);
const flag = n => { const i = argv.indexOf('--' + n); return i >= 0 ? argv[i + 1] : undefined; };
const kit = flag('kit'), port = flag('port') || '8801', styleDir = flag('style'), outDir = flag('out'), only = flag('only');
const w = +(flag('w') || 1024), h = +(flag('h') || 600);
const { launch, renderOne } = await import(pathToFileURL(join(kit, 'render.mjs')).href);

// name, lon, lat, zoom, pitch, bearing
export const CAMERAS = [
  ['01-ksa-z4', 45, 24, 4, 0, 0],
  ['02-gulf-z7', 50.3, 26.5, 7, 0, 0],
  ['03-causeway-z11', 50.35, 26.19, 11, 0, 0],
  ['04-causeway-z14', 50.33, 26.185, 14, 0, 0],
  ['05-khobar-nav-z15', 50.2158, 26.2945, 15.5, 58, 30],
  ['06-khobar-z13', 50.2, 26.29, 13, 0, 0],
  ['07-dammam-z12', 50.1, 26.42, 12, 0, 0],
  ['08-dammam-junction-z16', 50.1, 26.39, 16.5, 60, 120],
  ['09-dhahran-airport-z12', 49.79, 26.47, 12, 0, 0],
  ['10-jubail-z14', 49.66, 27.01, 14, 0, 0],
  ['11-hwy613-z10', 49.95, 26.72, 10, 0, 0],
  ['12-hofuf-z13', 49.59, 25.38, 13, 0, 0],
  ['13-salwa-z12', 50.77, 24.74, 12, 0, 0],
  ['14-khafji-z12', 48.5, 28.43, 12, 0, 0],
  ['15-hwy40-z12', 48.1, 25.6, 12, 0, 0],
  ['16-riyadh-z10', 46.7, 24.7, 10, 0, 0],
  ['17-riyadh-z14', 46.685, 24.713, 14, 0, 0],
  ['18-riyadh-3d-z17', 46.6745, 24.7113, 17.5, 60, 200],
  ['19-jeddah-z12', 39.18, 21.54, 12, 0, 0],
  ['20-jeddah-corniche-z15', 39.11, 21.6, 15, 45, 300],
  ['21-mecca-z14', 39.826, 21.4225, 14, 0, 0],
  ['22-medina-z13', 39.61, 24.468, 13, 0, 0],
  ['23-abha-z13', 42.505, 18.216, 13, 0, 0],
  ['24-jazan-z11', 42.56, 16.89, 11, 0, 0],
  ['25-farasan-z10', 41.9, 16.7, 10, 0, 0],
  ['26-tabuk-z12', 36.57, 28.38, 12, 0, 0],
  ['27-haql-z11', 34.95, 29.3, 11, 0, 0],
  ['28-rub-z9', 50.5, 20.5, 9, 0, 0],
  ['29-rub-z14', 50.5, 20.5, 14, 0, 0],
  ['30-manama-z13', 50.58, 26.22, 13, 0, 0],
];

const variants = (flag('variants') || 'day-ar,night-ar,day-en,night-en').split(',');
if (flag('cameras')) { CAMERAS.length = 0; CAMERAS.push(...JSON.parse(readFileSync(flag('cameras'), 'utf8'))); }
const browser = await launch();
const all = {};
try {
  for (const v of variants) {
    const style = JSON.parse(readFileSync(join(styleDir, v + '.json'), 'utf8'));
    mkdirSync(join(outDir, v), { recursive: true });
    for (const [name, lon, lat, zoom, pitch, bearing] of CAMERAS) {
      if (only && !only.split(',').includes(name)) continue;
      const job = { style, lang: v.endsWith('ar') ? 'ar' : 'en', tiles: `http://127.0.0.1:${port}/base`, glyphs: flag('glyphs') || 'https://tiles.openfreemap.org/fonts', center: [lon, lat], zoom, pitch, bearing };
      const out = join(outDir, v, name + '.png');
      const t0 = Date.now();
      const res = await renderOne(browser, {
        html: `http://127.0.0.1:${port}/plate_style.html`, out, w, h, ready: 'window.__ready === true', wait: 100,
        init: 'window.__job = ' + JSON.stringify(job) + ';', eval: 'JSON.stringify(window.__info)',
      });
      const info = JSON.parse(res.extra || '{}');
      info.ms = Date.now() - t0;
      info.problems = res.problems;
      all[v + '/' + name] = info;
      writeFileSync(join(outDir, v, name + '.json'), JSON.stringify(info));
      console.log(v, name, info.ms + 'ms', JSON.stringify(info.symbols), JSON.stringify(info.tiles), 'err=' + (info.errors || []).length + ' miss=' + (info.missingImages || []).length, res.problems.join(' | '));
    }
  }
} finally { await browser.close(); }
writeFileSync(join(outDir, 'all.json'), JSON.stringify(all));
