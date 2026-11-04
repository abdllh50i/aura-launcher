#!/usr/bin/env node
// Runs tile_time.html (see there), then checks every character of the tiles' labels against the glyphs: tile_stats.py's characters, Arabic letters through their presentation forms.
//   node tile_time.mjs --kit <design kit> --port 8801 --style <folder> --stats tile-stats.json --out result.json
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
const argv = process.argv.slice(2);
const flag = n => { const i = argv.indexOf('--' + n); return i >= 0 ? argv[i + 1] : undefined; };
const kit = flag('kit'), port = flag('port') || '8801';
const { launch, renderOne } = await import(pathToFileURL(join(kit, 'render.mjs')).href);
const style = JSON.parse(readFileSync(join(flag('style'), 'day-ar.json'), 'utf8'));
const centre = (z, x, y) => ({ lon: (x + .5) / 2 ** z * 360 - 180, lat: Math.atan(Math.sinh(Math.PI * (1 - 2 * (y + .5) / 2 ** z))) * 180 / Math.PI, z });
const spots = [
  { name: 'biggest gz 14/10316/7031 Riyadh', ...centre(14, 10316, 7031) },
  { name: 'biggest raw 14/10004/7193 Mecca', ...centre(14, 10004, 7193) },
  { name: 'most features 14/9971/7149', ...centre(14, 9971, 7149) },
  { name: 'heavy z12 2579/1758 Riyadh', ...centre(12, 2579, 1758) },
  { name: 'typical Khobar 14/10474/6951', ...centre(14, 10474, 6951) },
];
const job = { style, tiles: `http://127.0.0.1:${port}/base`, glyphs: 'https://tiles.openfreemap.org/fonts', spots, runs: 4 };
const browser = await launch();
let info;
try {
  const res = await renderOne(browser, { html: `http://127.0.0.1:${port}/tile_time.html`, out: flag('out') + '.png', w: 512, h: 512, ready: 'window.__ready === true', readyTimeout: 240000, wait: 50,
    init: 'window.__job = ' + JSON.stringify(job) + ';', eval: 'JSON.stringify(window.__info)' });
  info = JSON.parse(res.extra);
  console.log(res.problems.filter(p => !p.includes('favicon')).join(' | '));
} finally { await browser.close(); }
if (info.error) { console.log(info.error); process.exit(1); }
const summary = {};
for (const [k, runs] of Object.entries(info.times)) {
  const byTile = {};
  runs.slice(1).forEach(r => r.forEach(t => (byTile[t.tile] = byTile[t.tile] || []).push(t.ms)));
  summary[k] = Object.fromEntries(Object.entries(byTile).map(([t, v]) => [t, v.sort((a, b) => a - b)[Math.floor(v.length / 2)]]));
  console.log(k, JSON.stringify(summary[k]));
}
const out = { times: summary, glyph_counts: Object.fromEntries(Object.entries(info.glyphs).map(([k, v]) => [k, v.length])) };
const stats = JSON.parse(readFileSync(flag('stats'), 'utf8'));
for (const stack of Object.keys(info.glyphs)) {
  const have = new Set(info.glyphs[stack]);
  const missing = {};
  for (const [key, chars] of Object.entries(stats.chars)) {
    if (key === 'name:latin' && false) continue;
    for (const [hex, n] of Object.entries(chars)) {
      const c = parseInt(hex, 16);
      if (!have.has(c)) { const m = (missing[hex] = missing[hex] || { n: 0, keys: {} }); m.n += n; m.keys[key] = (m.keys[key] || 0) + n; }
    }
  }
  out['missing ' + stack] = missing;
  // Presentation forms a shaper would ask for, from Unicode's own tables: letters in names whose joined forms the font lacks
  const forms = {};
  for (let c = 0xFB50; c <= 0xFEFC; c++) {
    const nf = String.fromCodePoint(c).normalize('NFKC');
    if (nf.length === 1 && nf !== String.fromCodePoint(c)) (forms[nf.codePointAt(0)] = forms[nf.codePointAt(0)] || []).push(c);
  }
  const lackForms = {};
  for (const key of ['name', 'name:ar']) for (const hex of Object.keys(stats.chars[key] || {})) {
    const c = parseInt(hex, 16);
    for (const f of forms[c] || []) if (!have.has(f)) (lackForms[hex] = lackForms[hex] || new Set()).add(f.toString(16));
  }
  out['letters lacking a joined form ' + stack] = Object.fromEntries(Object.entries(lackForms).map(([k, v]) => [k, [...v]]));
  console.log(stack, 'glyphs', have.size, 'label code points with no glyph', Object.keys(missing).length, 'letters lacking a joined form', Object.keys(lackForms).length);
}
writeFileSync(flag('out'), JSON.stringify(out, null, 1));
