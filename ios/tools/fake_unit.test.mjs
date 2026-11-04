// Holds the stand-in to the protocol, so the app's tests against it mean something.  node --test tools/fake_unit.test.mjs
import assert from 'node:assert/strict';
import net from 'node:net';
import { after, before, beforeEach, test } from 'node:test';
import { createFakeUnit } from './fake_unit.mjs';

let unit;
let base;
let adbPorts;

before(async () => {
  unit = createFakeUnit({ port: 0, routeDelayMs: 40, progressMs: 60, heartbeatMs: 80, tripSeconds: 120, adb: [{ port: 0, model: 'K2501' }, { port: 0, model: 'Pixel 7' }] });
  const started = await unit.start();
  base = `http://127.0.0.1:${started.port}`;
  adbPorts = started.adbPorts;
});

after(async () => {
  await unit.stop();
});

beforeEach(async () => {
  await fetch(`${base}/__fake/reset`, { method: 'POST' });
});

const json = (body) => ({ 'Content-Type': 'application/json', body: JSON.stringify(body) });

async function post(path, body, token, extra = {}) {
  const headers = { 'Content-Type': 'application/json', ...extra };
  if (token) headers.Authorization = `Bearer ${token}`;
  return fetch(base + path, { method: 'POST', headers, body: JSON.stringify(body) });
}

async function pair() {
  const res = await post('/v1/pair', { code: '246810', device: 'test' });
  assert.equal(res.status, 200);
  return (await res.json()).token;
}

const place = { name: 'الراشد مول', detail: 'الخبر', lat: 26.290457, lon: 50.184555, src: 'mapkit' };

/** Reads SSE events until `until` says stop. */
async function readEvents(token, until, timeoutMs = 2000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  const res = await fetch(`${base}/v1/events`, { headers: { Authorization: `Bearer ${token}` }, signal: controller.signal });
  assert.equal(res.headers.get('content-type'), 'text/event-stream');
  const events = [];
  let text = '';
  const decoder = new TextDecoder();
  try {
    for await (const chunk of res.body) {
      text += decoder.decode(chunk, { stream: true });
      let end;
      while ((end = text.indexOf('\n\n')) >= 0) {
        const frame = text.slice(0, end);
        text = text.slice(end + 2);
        if (frame.startsWith(':')) {
          events.push({ comment: frame });
          continue;
        }
        if (frame.startsWith('retry:')) continue;
        const name = frame.match(/^event: (.*)$/m)?.[1];
        const data = frame.match(/^data: (.*)$/m)?.[1];
        events.push({ event: name, data: JSON.parse(data) });
      }
      if (until(events)) break;
    }
  } finally {
    clearTimeout(timer);
    controller.abort();
  }
  return events;
}

test('ping says who it is, with no secrets and no token needed', async () => {
  const res = await fetch(`${base}/v1/ping`);
  assert.equal(res.status, 200);
  assert.deepEqual(await res.json(), { app: 'AMRI', unit: 'k2501-test', name: 'K2501', version: '2.0.0', proto: 2 });
});

test('a pairing code works once', async () => {
  const bad = await post('/v1/pair', { code: '000000', device: 'test' });
  assert.equal(bad.status, 403);
  const token = await pair();
  assert.ok(token.length >= 32);
  const again = await post('/v1/pair', { code: '246810', device: 'test' });
  assert.equal(again.status, 403);
  assert.ok(!JSON.stringify(unit.state.tokens).includes(token), 'only a hash is kept');
});

test('a paired go is accepted, then routing, then started with real numbers, then progress', async () => {
  const token = await pair();
  const reply = await post('/v1/nav/go', { id: 'A1', place, mode: 'start' }, token);
  assert.equal(reply.status, 202);
  assert.deepEqual(await reply.json(), { id: 'A1', state: 'accepted' });
  const events = await readEvents(token, (list) => list.some((e) => e.data?.state === 'progress'));
  const navStates = events.filter((e) => e.event === 'nav').map((e) => e.data.state);
  assert.ok(navStates.includes('started'));
  const started = events.find((e) => e.data?.state === 'started').data;
  assert.equal(started.id, 'A1');
  assert.equal(started.durationS, 120);
  assert.match(started.etaLocal, /^\d{1,2}:\d{2}$/);
  const progress = events.find((e) => e.data?.state === 'progress').data;
  assert.ok(progress.timeLeftS < started.durationS);
  assert.ok(progress.nextText);
});

test('the same id never starts a second trip, the last 64 are remembered', async () => {
  const token = await pair();
  assert.equal((await (await post('/v1/nav/go', { id: 'R1', place }, token)).json()).state, 'accepted');
  assert.equal((await (await post('/v1/nav/go', { id: 'R1', place }, token)).json()).state, 'duplicate');
  assert.equal(unit.state.starts.length, 1);
  for (let i = 0; i < 64; i += 1) await post('/v1/nav/go', { id: `F${i}`, place }, token);
  assert.equal((await (await post('/v1/nav/go', { id: 'R1', place }, token)).json()).state, 'accepted', 'older than 64 is forgotten');
});

test('a frozen hotspot: the first go is never answered and not seen, the retry goes through once', async () => {
  const token = await pair();
  await fetch(`${base}/__fake/config`, { method: 'POST', ...{ headers: { 'Content-Type': 'application/json' } }, body: JSON.stringify({ dropGo: 1 }) });
  const controller = new AbortController();
  setTimeout(() => controller.abort(), 60);
  await assert.rejects(fetch(`${base}/v1/nav/go`, { method: 'POST', signal: controller.signal, headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` }, body: JSON.stringify({ id: 'D1', place }) }));
  assert.equal(unit.state.starts.length, 0);
  const reply = await post('/v1/nav/go', { id: 'D1', place }, token);
  assert.equal((await reply.json()).state, 'accepted');
  assert.equal(unit.state.starts.length, 1);
});

test('a lost ack: the unit took it, the retry is a duplicate, and one trip started', async () => {
  const token = await pair();
  await fetch(`${base}/__fake/config`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ dropAck: 1 }) });
  const controller = new AbortController();
  setTimeout(() => controller.abort(), 60);
  await assert.rejects(fetch(`${base}/v1/nav/go`, { method: 'POST', signal: controller.signal, headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` }, body: JSON.stringify({ id: 'L1', place }) }));
  const reply = await post('/v1/nav/go', { id: 'L1', place }, token);
  assert.equal((await reply.json()).state, 'duplicate');
  assert.equal(unit.state.starts.length, 1);
});

test('no token from the gateway makes a card and starts nothing until it is tapped', async () => {
  const reply = await post('/v1/nav/go', { id: 'C1', place });
  assert.equal(reply.status, 202);
  assert.equal(unit.state.starts.length, 0);
  assert.equal(unit.state.card.id, 'C1');
  await post('/__fake/card', { accept: true });
  assert.equal(unit.state.starts.length, 1);
});

test('no token from any other peer is refused, apart from ping and pairing', async () => {
  const other = createFakeUnit({ port: 0, gateway: '10.9.9.9', code: '111111' });
  const { port } = await other.start();
  const url = `http://127.0.0.1:${port}`;
  try {
    const go = await fetch(`${url}/v1/nav/go`, { method: 'POST', ...json({ id: 'X', place }), headers: { 'Content-Type': 'application/json' } });
    assert.equal(go.status, 403);
    assert.equal((await fetch(`${url}/v1/ping`)).status, 200);
    const paired = await fetch(`${url}/v1/pair`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ code: '111111', device: 't' }) });
    assert.equal(paired.status, 200);
  } finally {
    await other.stop();
  }
});

test('what needs a token says so without one, also from the gateway', async () => {
  assert.equal((await fetch(`${base}/v1/state`)).status, 401);
  assert.equal((await fetch(`${base}/v1/events`)).status, 401);
  assert.equal((await fetch(`${base}/v1/places`)).status, 401);
  assert.equal((await post('/v1/remote', { cmd: 'music.toggle' })).status, 401);
  assert.equal((await post('/v1/nav/stop', {})).status, 401);
  assert.equal((await post('/v1/nav/saved', { which: 'home' })).status, 401);
});

test('the web rules: JSON only, a foreign Origin is refused, no CORS, preflight fails, odd Host is refused', async () => {
  const token = await pair();
  const plain = await fetch(`${base}/v1/nav/go`, { method: 'POST', headers: { 'Content-Type': 'text/plain', Authorization: `Bearer ${token}` }, body: JSON.stringify({ id: 'W1', place }) });
  assert.equal(plain.status, 415);
  const foreign = await post('/v1/nav/go', { id: 'W2', place }, token, { Origin: 'https://evil.example' });
  assert.equal(foreign.status, 403);
  const preflight = await fetch(`${base}/v1/nav/go`, { method: 'OPTIONS' });
  assert.equal(preflight.status, 405);
  assert.equal(preflight.headers.get('access-control-allow-origin'), null);
  const rebinding = await new Promise((resolve) => {
    const url = new URL(base);
    const socket = net.connect(Number(url.port), '127.0.0.1', () => {
      socket.write('GET /v1/ping HTTP/1.1\r\nHost: evil.example\r\nConnection: close\r\n\r\n');
    });
    let text = '';
    socket.on('data', (d) => { text += d.toString(); });
    socket.on('end', () => resolve(text));
  });
  assert.match(rebinding, /^HTTP\/1\.1 403/);
});

test('a conflict answers 409 with the reason, a bad body 400', async () => {
  const token = await pair();
  await post('/__fake/config', { conflict: 'الشاشة في مكالمة' });
  const reply = await post('/v1/nav/go', { id: 'Z1', place }, token);
  assert.equal(reply.status, 409);
  assert.equal((await reply.json()).reason, 'الشاشة في مكالمة');
  assert.equal((await post('/v1/nav/go', { id: 'Z2', place: { name: 'x' } }, token)).status, 400);
});

test('a failed route reports failed, with its reason, and never started', async () => {
  const token = await pair();
  await post('/__fake/config', { failRoute: 'ما فيه طريق' });
  await post('/v1/nav/go', { id: 'F1', place }, token);
  const events = await readEvents(token, (list) => list.some((e) => e.data?.state === 'failed'));
  assert.ok(!events.some((e) => e.data?.state === 'started'));
  assert.equal(events.find((e) => e.data?.state === 'failed').data.reason, 'ما فيه طريق');
});

test('already navigating: a new place makes a card, the route does not change silently', async () => {
  const token = await pair();
  await post('/v1/nav/go', { id: 'N1', place }, token);
  const second = await post('/v1/nav/go', { id: 'N2', place: { ...place, name: 'ثاني' } }, token);
  assert.equal(second.status, 202);
  assert.equal(unit.state.starts.length, 1);
  assert.equal(unit.state.card.reason, 'navigating');
});

test('stop ends the trip, the events have a snapshot on connect and a heartbeat', async () => {
  const token = await pair();
  await post('/v1/nav/go', { id: 'S1', place }, token);
  const events = await readEvents(token, (list) => list.filter((e) => e.comment === ': alive').length >= 1);
  assert.deepEqual(events.filter((e) => e.event).slice(0, 3).map((e) => e.event), ['nav', 'music', 'car']);
  const stop = await post('/v1/nav/stop', {}, token);
  assert.equal(stop.status, 200);
  assert.equal(unit.state.nav.state, 'idle');
  assert.equal(unit.state.trip, null);
});

test('places: the unit is the source of truth, saved home starts a trip, a missing one says so', async () => {
  const token = await pair();
  const missing = await post('/v1/nav/saved', { which: 'home' }, token);
  assert.equal(missing.status, 409);
  const doc = { home: { name: 'البيت', lat: 26.4, lon: 50.1 }, work: null, favourites: [place], recents: [] };
  const put = await fetch(`${base}/v1/places`, { method: 'PUT', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` }, body: JSON.stringify(doc) });
  assert.equal(put.status, 200);
  const got = await (await fetch(`${base}/v1/places`, { headers: { Authorization: `Bearer ${token}` } })).json();
  assert.equal(got.home.name, 'البيت');
  assert.equal(got.favourites.length, 1);
  const saved = await post('/v1/nav/saved', { which: 'home' }, token);
  assert.equal(saved.status, 202);
  assert.equal(unit.state.starts.length, 1);
  assert.equal(unit.state.starts[0].place.name, 'البيت');
});

test('remote: music and the volume step change, an unknown command is refused', async () => {
  const token = await pair();
  await post('/v1/remote', { cmd: 'music.next' }, token);
  assert.equal(unit.state.music.title, 'على البال');
  await post('/v1/remote', { cmd: 'volume.up' }, token);
  assert.equal(unit.state.music.volume, 13);
  await post('/v1/remote', { cmd: 'music.toggle' }, token);
  assert.equal(unit.state.music.playing, false);
  assert.equal((await post('/v1/remote', { cmd: 'format.disk' }, token)).status, 400);
});

test('share: raw text from the gateway makes a card', async () => {
  const reply = await post('/v1/share', { text: 'الموقع 26.290457, 50.184555' });
  assert.equal(reply.status, 202);
  assert.equal(unit.state.card.place.lat, 26.290457);
});

test('diag and the web pages answer', async () => {
  const token = await pair();
  const diag = await fetch(`${base}/v1/diag`, { headers: { Authorization: `Bearer ${token}` } });
  assert.match(await diag.text(), /AMRI 2\.0\.0/);
  assert.equal((await fetch(`${base}/`)).status, 200);
  await post('/__fake/code', { code: '135790' });
  const page = await fetch(`${base}/p/135790`);
  assert.match(await page.text(), /amri:\/\/pair\?host=127\.0\.0\.1&port=\d+&code=135790/);
  assert.equal((await fetch(`${base}/p/000000`)).status, 404);
});

function adbExchange(port, packets) {
  return new Promise((resolve, reject) => {
    const socket = net.connect(port, '127.0.0.1', () => {
      for (const packet of packets) socket.write(packet);
    });
    const chunks = [];
    socket.on('data', (d) => chunks.push(d));
    socket.on('error', reject);
    setTimeout(() => {
      socket.destroy();
      resolve(Buffer.concat(chunks));
    }, 80);
  });
}

function adbPacket(command, arg0, arg1, payload = Buffer.alloc(0)) {
  const header = Buffer.alloc(24);
  header.writeUInt32LE(command, 0);
  header.writeUInt32LE(arg0, 4);
  header.writeUInt32LE(arg1, 8);
  header.writeUInt32LE(payload.length, 12);
  header.writeUInt32LE(payload.reduce((a, b) => a + b, 0), 16);
  header.writeUInt32LE((~command) >>> 0, 20);
  return Buffer.concat([header, payload]);
}

test('fake adbd: the banner names the model, and each OPEN is recorded', async () => {
  const cnxn = adbPacket(0x4e584e43, 0x01000000, 262144, Buffer.from('host::\0'));
  const reply = await adbExchange(adbPorts[0], [cnxn]);
  assert.match(reply.toString(), /ro\.product\.model=K2501/);
  const other = await adbExchange(adbPorts[1], [cnxn]);
  assert.match(other.toString(), /ro\.product\.model=Pixel 7/);
  assert.equal(unit.state.adbOpens.length, 0);
  const open = adbPacket(0x4e45504f, 1, 0, Buffer.from('shell:sh /data/local/tmp/amri-rollback/rollback.sh\0'));
  const ran = await adbExchange(adbPorts[0], [cnxn, open]);
  assert.match(ran.toString(), /EXIT=0/);
  assert.equal(unit.state.adbOpens.length, 1);
  assert.equal(unit.state.adbOpens[0].model, 'K2501');
});
