#!/usr/bin/env node
// A stand-in for the unit's PhoneServer: AMRI Link v2 as section 5.4 of the plan writes it, for CI and the UI tests.
//
//   node tools/fake_unit.mjs [--port 47101] [--host 127.0.0.1] [--code 246810] [--route-delay 1500] [--progress-ms 10000]
//                            [--gateway 127.0.0.1] [--eta 9:41] [--adb 15555:K2501] [--adb "15556:Pixel 7"]
//
// It holds itself to what the real server must do:
//   - three levels of trust: a paired token acts at once; no token from the Wi-Fi gateway makes a card on the screen; no
//     token from any other peer is refused, apart from /v1/ping and pairing while the code is showing;
//   - the web rules: Content-Type must be JSON, no CORS allowance so preflight fails, a mismatched Origin is refused, and
//     Host must be an IP or the unit's name;
//   - an id is remembered (the last 64), so a retry never starts a trip twice;
//   - `started` comes only after routing, with real numbers.
//
// Everything under /__fake/ is the test's own hand on the unit (tap the card, freeze the hotspot, read the log). It is not
// part of the protocol. A test double for CI only: it holds no secrets and listens on loopback by default.
import crypto from 'node:crypto';
import http from 'node:http';
import net from 'node:net';
import { fileURLToPath } from 'node:url';

const MAX_BODY = 64 * 1024;
const REMOTE_COMMANDS = new Set(['music.toggle', 'music.next', 'music.prev', 'volume.up', 'volume.down', 'screen.off', 'voice.mute', 'open.maps', 'open.music']);
const PLAYLIST = [
  { title: 'يا طير', artist: 'فنان تجريبي', source: 'naghm', durationS: 214 },
  { title: 'على البال', artist: 'فنان تجريبي', source: 'naghm', durationS: 187 },
  { title: 'Fixture Three', artist: 'Test Artist', source: 'bt', durationS: 241 },
];

const sha256 = (text) => crypto.createHash('sha256').update(text).digest('hex');

function privatePeer(address) {
  const a = address.replace(/^::ffff:/, '');
  if (a === '::1' || a.startsWith('127.')) return true; // the real server allows loopback only under its debug property
  const parts = a.split('.').map(Number);
  if (parts.length !== 4) return false;
  return parts[0] === 10 || (parts[0] === 172 && parts[1] >= 16 && parts[1] <= 31) || (parts[0] === 192 && parts[1] === 168);
}

export function createFakeUnit(options = {}) {
  const base = {
    host: '127.0.0.1',
    port: 47101,
    unit: 'k2501-test',
    name: 'K2501',
    version: '2.0.0',
    proto: 2,
    code: '246810',
    gateway: '127.0.0.1',
    routeDelayMs: 1500,
    progressMs: 10000,
    heartbeatMs: 15000,
    tripSeconds: 1380,
    tripMeters: 18400,
    eta: null,
    adb: [],
    ...options,
  };

  let config;
  let state;
  const sseClients = new Set();
  const timers = new Set();

  function reset() {
    for (const timer of timers) clearTimeout(timer);
    timers.clear();
    config = {
      routeDelayMs: base.routeDelayMs,
      progressMs: base.progressMs,
      tripSeconds: base.tripSeconds,
      eta: base.eta,
      dropGo: 0, // the hotspot froze: the next N go requests get no answer and are not seen
      dropAck: 0, // the ack is lost: the next N go requests are taken, but no answer comes back
      failRoute: null, // the route fails, with this reason
      conflict: null, // 409 with this reason
      rejectTokens: false, // the unit forgot its tokens
      noRollbackScript: false, // a version with no way back prepared
      rollbackFails: false, // pm install fails
    };
    state = {
      code: base.code,
      codeExpires: Date.now() + 5 * 60 * 1000,
      tokens: new Set(),
      seenIds: [],
      nav: { state: 'idle' },
      trip: null,
      card: null,
      music: { ...PLAYLIST[0], playing: true, volume: 12, maxVolume: 40, muted: false },
      musicIndex: 0,
      navVoice: true,
      car: { gear: 'P', gearSure: true, reversing: false, inCall: false, fix: { lat: 26.29, lon: 50.18, ageS: 1, kmh: 0 } },
      places: { home: null, work: null, favourites: [], recents: [] },
      starts: [],
      log: [],
      adbOpens: [],
      lastState: {},
    };
    for (const client of sseClients) client.end();
    sseClients.clear();
  }
  reset();

  function later(ms, fn) {
    const timer = setTimeout(() => {
      timers.delete(timer);
      fn();
    }, ms);
    timers.add(timer);
    return timer;
  }

  function send(res, status, body, type = 'application/json; charset=utf-8') {
    const text = typeof body === 'string' ? body : JSON.stringify(body);
    res.writeHead(status, { 'Content-Type': type, 'Content-Length': Buffer.byteLength(text), 'Cache-Control': 'no-store' });
    res.end(text);
  }

  // Live events
  function sseFrame(event, data) {
    return `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
  }
  function broadcast(event, data) {
    const frame = sseFrame(event, data);
    for (const client of sseClients) client.write(frame);
  }
  function setNav(nav) {
    state.nav = nav;
    if (nav.id && state.seenIds.includes(nav.id)) state.lastState[nav.id] = nav.state;
    broadcast('nav', nav);
  }
  /** The trip as it is now: the shape of /v1/state's "nav" and of the snapshot on connect. It names no id, like the unit's. */
  function navJson() {
    const trip = state.trip;
    const card = state.card;
    const pending = card ? { pending: { id: card.id, stage: card.reason === 'navigating' ? 'replacing' : 'asking', place: card.place } } : {};
    if (!trip) return state.nav.state === 'arrived' ? { state: 'arrived', place: state.nav.place, ...pending } : { state: 'idle', ...pending };
    if (!trip.running) return { state: 'routing', place: trip.place, ...pending };
    return { state: 'navigating', place: trip.place, by: 'phone', remainingM: trip.leftM, timeLeftS: trip.leftS, etaLocal: etaLocal(trip.leftS), speedKmh: 0, rerouting: false, nextText: 'بعد ٨٠٠ متر انعطف يمين', ...pending };
  }
  function setMusic(patch) {
    state.music = { ...state.music, ...patch };
    broadcast('music', state.music);
  }
  function setCar(patch) {
    state.car = { ...state.car, ...patch };
    broadcast('car', state.car);
  }

  function etaLocal(seconds) {
    if (config.eta) return config.eta;
    const at = new Date(Date.now() + seconds * 1000);
    return `${at.getHours()}:${String(at.getMinutes()).padStart(2, '0')}`;
  }

  function startTrip(id, place) {
    endTripTimers();
    state.card = null;
    state.trip = { id, place, leftS: config.tripSeconds, leftM: base.tripMeters, running: false };
    state.starts.push({ id, place });
    setNav({ state: 'routing', id, place });
    state.trip.route = later(config.routeDelayMs, () => {
      if (config.failRoute) {
        const reason = config.failRoute;
        state.trip = null;
        setNav({ state: 'failed', id, reason });
        return;
      }
      state.trip.running = true;
      setNav({ state: 'started', id, distanceM: base.tripMeters, durationS: config.tripSeconds, etaLocal: etaLocal(config.tripSeconds) });
      broadcast('nav', navJson());
      scheduleProgress();
    });
  }

  function scheduleProgress() {
    if (!state.trip) return;
    state.trip.progress = later(config.progressMs, () => {
      const trip = state.trip;
      if (!trip) return;
      trip.leftS = Math.max(0, trip.leftS - 60);
      trip.leftM = Math.max(0, trip.leftM - 800);
      if (trip.leftS === 0) {
        state.trip = null;
        setNav({ state: 'arrived', place: trip.place });
        return;
      }
      setNav({ ...navJson(), state: 'progress' });
      scheduleProgress();
    });
  }

  function endTripTimers() {
    if (state.trip?.route) { clearTimeout(state.trip.route); timers.delete(state.trip.route); }
    if (state.trip?.progress) { clearTimeout(state.trip.progress); timers.delete(state.trip.progress); }
  }

  function remember(id) {
    state.seenIds.push(id);
    if (state.seenIds.length > 64) state.seenIds.shift();
  }

  function trustOf(req) {
    const peer = (req.socket.remoteAddress || '').replace(/^::ffff:/, '');
    const auth = req.headers.authorization || '';
    const token = auth.startsWith('Bearer ') ? auth.slice(7).trim() : '';
    if (token && !config.rejectTokens && state.tokens.has(sha256(token))) return 'token';
    if (peer === base.gateway) return 'gateway';
    return 'other';
  }

  function hostAllowed(req) {
    const host = (req.headers.host || '').replace(/:\d+$/, '');
    return /^\d{1,3}(\.\d{1,3}){3}$/.test(host) || host === base.unit || host === base.name;
  }

  function originAllowed(req) {
    const origin = req.headers.origin;
    if (!origin) return true;
    return origin === `http://${req.headers.host}`;
  }

  // Handlers
  const needsToken = (trust, res) => {
    if (trust === 'token') return true;
    send(res, trust === 'gateway' ? 401 : 403, { reason: trust === 'gateway' ? 'pair' : 'refused' });
    return false;
  };

  function handleGo(req, res, body, trust) {
    const place = body?.place;
    if (!body?.id || !place || typeof place.lat !== 'number' || typeof place.lon !== 'number' || !place.name) {
      return send(res, 400, { reason: 'bad_place' });
    }
    if (trust === 'other') return send(res, 403, { reason: 'refused' });
    if (config.dropGo > 0) {
      config.dropGo -= 1;
      state.log[state.log.length - 1].dropped = true;
      return; // never answered, and not seen: a frozen hotspot
    }
    if (state.seenIds.includes(body.id)) return send(res, 202, { id: body.id, state: 'duplicate', last: state.lastState[body.id] ?? 'accepted' });
    remember(body.id);
    if (config.conflict) return send(res, 409, { reason: config.conflict });

    if (trust === 'gateway' || state.trip) {
      // Unpaired from the gateway: always the card. Already navigating: a card too, never a silent change of route.
      state.card = { id: body.id, place, reason: trust === 'gateway' ? 'unpaired' : 'navigating' };
      broadcast('nav', { id: body.id, state: 'card', kind: trust === 'gateway' ? 'ask' : 'replace' });
    } else {
      startTrip(body.id, place);
    }
    if (config.dropAck > 0) {
      config.dropAck -= 1;
      state.log[state.log.length - 1].ackLost = true;
      return;
    }
    return send(res, 202, { id: body.id, state: 'accepted', ...(trust === 'gateway' ? { needs: 'tap' } : {}) });
  }

  function handleShare(req, res, body, trust) {
    if (trust === 'other') return send(res, 403, { reason: 'refused' });
    const text = `${body?.url ?? ''} ${body?.text ?? ''}`;
    if (!text.trim()) return send(res, 400, { reason: 'nothing_shared' });
    const id = body?.id ?? crypto.randomUUID();
    if (state.seenIds.includes(id)) return send(res, 202, { id, state: 'duplicate' });
    remember(id);
    const match = text.match(/(-?\d{1,2}\.\d{3,})\s*,\s*(-?\d{1,3}\.\d{3,})/);
    if (match) {
      const place = { name: 'موقع من الجوال', lat: Number(match[1]), lon: Number(match[2]) };
      if (trust === 'gateway' || state.trip) state.card = { id, place, reason: trust === 'gateway' ? 'unpaired' : 'navigating' };
      else startTrip(id, place);
    }
    return send(res, 202, { id, state: 'accepted' });
  }

  function handleEvents(req, res) {
    res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-store', Connection: 'keep-alive' });
    res.write(`retry: 3000\n\n${sseFrame('nav', navJson())}${sseFrame('music', state.music)}${sseFrame('car', state.car)}`);
    sseClients.add(res);
    const beat = setInterval(() => res.write(': alive\n\n'), base.heartbeatMs);
    res.on('close', () => {
      clearInterval(beat);
      sseClients.delete(res);
    });
  }

  function handleRemote(res, body) {
    const cmd = body?.cmd;
    if (!REMOTE_COMMANDS.has(cmd)) return send(res, 400, { reason: 'bad_cmd' });
    if (cmd.startsWith('open.') && state.car.reversing) return send(res, 409, { id: body?.id, cmd, state: 'refused', reason: 'reversing' });
    switch (cmd) {
      case 'music.toggle':
        setMusic({ playing: !state.music.playing });
        break;
      case 'music.next':
      case 'music.prev': {
        const step = cmd === 'music.next' ? 1 : PLAYLIST.length - 1;
        state.musicIndex = (state.musicIndex + step) % PLAYLIST.length;
        setMusic({ ...PLAYLIST[state.musicIndex], positionS: 0, playing: true });
        break;
      }
      case 'volume.up':
        setMusic({ muted: false, volume: Math.min(state.music.maxVolume, state.music.volume + 1) });
        break;
      case 'volume.down':
        setMusic({ volume: Math.max(0, state.music.volume - 1) });
        break;
      case 'voice.mute':
        state.navVoice = !state.navVoice;
        break;
      default:
        break;
    }
    return send(res, 200, { id: body?.id, cmd, state: 'ok', volume: state.music.volume, muted: state.music.muted, navVoice: state.navVoice });
  }

  function validPlace(p) {
    return p === null || p === undefined || (typeof p.name === 'string' && typeof p.lat === 'number' && typeof p.lon === 'number');
  }

  function handleFake(req, res, path, body) {
    switch (path) {
      case '/__fake/log':
        return send(res, 200, { requests: state.log, starts: state.starts, adbOpens: state.adbOpens });
      case '/__fake/state':
        return send(res, 200, { nav: navJson(), last: state.nav, music: state.music, car: state.car, places: state.places, code: state.code, card: state.card, tokens: state.tokens.size, seenIds: state.seenIds, config });
      case '/__fake/config':
        Object.assign(config, body ?? {});
        return send(res, 200, config);
      case '/__fake/reset':
        reset();
        return send(res, 200, { ok: true });
      case '/__fake/places':
        state.places = { home: null, work: null, favourites: [], recents: [], ...(body ?? {}) };
        return send(res, 200, state.places);
      case '/__fake/code':
        state.code = body?.code ?? base.code;
        state.codeExpires = Date.now() + (body?.ttlMs ?? 5 * 60 * 1000);
        return send(res, 200, { code: state.code });
      case '/__fake/card': {
        const card = state.card;
        if (!card) return send(res, 409, { reason: 'no card' });
        state.card = null;
        if (body?.expire) {
          broadcast('nav', { id: card.id, state: 'expired' });
          state.lastState[card.id] = 'expired';
        } else if (body?.accept === false) {
          broadcast('nav', { id: card.id, state: 'ignored', kept: true });
        } else {
          startTrip(card.id, card.place);
        }
        return send(res, 200, { ok: true });
      }
      case '/__fake/arrive': {
        const trip = state.trip;
        endTripTimers();
        state.trip = null;
        if (trip) setNav({ state: 'arrived', place: trip.place });
        return send(res, 200, { ok: true });
      }
      default:
        return send(res, 404, { reason: 'no such hook' });
    }
  }

  function pairPage(req, res, code) {
    if (code !== state.code || Date.now() > state.codeExpires) return send(res, 404, '<h1>الكود انتهى</h1>', 'text/html; charset=utf-8');
    const host = (req.headers.host || '').replace(/:\d+$/, '');
    const link = `amri://pair?host=${host}&port=${base.port}&code=${code}`;
    return send(res, 200, `<!doctype html><html dir="rtl" lang="ar"><meta charset="utf-8"><h1>${base.name}</h1><a href="${link}">افتح في عمري</a></html>`, 'text/html; charset=utf-8');
  }

  function route(req, res, path, body) {
    const trust = trustOf(req);
    const method = req.method;
    const key = `${method} ${path}`;

    if (key === 'GET /') {
      return send(res, 200, `<!doctype html><html dir="rtl" lang="ar"><meta charset="utf-8"><title>${base.name}</title><h1>الريموت (بديل للاختبار)</h1></html>`, 'text/html; charset=utf-8');
    }
    if (method === 'GET' && path.startsWith('/p/')) return pairPage(req, res, path.slice(3));
    if (key === 'GET /v1/ping') {
      return send(res, 200, { app: 'AMRI', unit: base.unit, name: base.name, version: base.version, proto: base.proto });
    }
    if (key === 'POST /v1/pair') {
      if (!state.code || Date.now() > state.codeExpires) return send(res, 403, { reason: 'no_code' });
      if (!body?.code || body.code !== state.code) return send(res, 403, { reason: 'bad_code', triesLeft: 4 });
      state.code = null; // single use
      const token = crypto.randomBytes(24).toString('hex');
      state.tokens.add(sha256(token)); // the unit stores a hash of the token
      return send(res, 200, { token, unit: base.unit, name: base.name });
    }
    if (key === 'POST /v1/nav/go') return handleGo(req, res, body, trust);
    if (key === 'POST /v1/share') return handleShare(req, res, body, trust);
    if (key === 'POST /v1/nav/saved') {
      if (!needsToken(trust, res)) return undefined;
      const which = body?.which;
      if (which !== 'home' && which !== 'work') return send(res, 400, { reason: 'bad_which' });
      const place = state.places[which];
      if (!place) return send(res, 409, { reason: 'not_set', which });
      const id = body?.id || crypto.randomUUID();
      if (state.seenIds.includes(id)) return send(res, 202, { id, state: 'duplicate', last: state.lastState[id] ?? 'accepted' });
      remember(id);
      if (state.trip) state.card = { id, place, reason: 'navigating' };
      else startTrip(id, place);
      return send(res, 202, { id, state: 'accepted' });
    }
    if (key === 'POST /v1/nav/stop') {
      if (!needsToken(trust, res)) return undefined;
      const trip = state.trip;
      endTripTimers();
      state.trip = null;
      if (trip) broadcast('nav', { id: trip.id, state: 'cancelled', by: 'phone' });
      setNav({ state: 'idle' });
      return send(res, 200, { id: body?.id, state: trip ? 'stopped' : 'idle' });
    }
    if (key === 'POST /v1/search') {
      if (!needsToken(trust, res)) return undefined;
      if (!body?.q || body.q.length < 2) return send(res, 400, { reason: 'bad_query' });
      if (body.on === 'phone') return send(res, 200, { state: 'ok', results: [] });
      return send(res, 202, { state: 'accepted' });
    }
    if (key === 'GET /v1/state') {
      if (!needsToken(trust, res)) return undefined;
      return send(res, 200, { nav: navJson(), music: state.music, car: state.car, link: { naghm: false, wifi: 'ok', address: req.socket.localAddress?.replace(/^::ffff:/, '') ?? null, listening: sseClients.size } });
    }
    if (key === 'GET /v1/events') {
      if (!needsToken(trust, res)) return undefined;
      return handleEvents(req, res);
    }
    if (key === 'GET /v1/places') {
      if (!needsToken(trust, res)) return undefined;
      return send(res, 200, state.places);
    }
    if (key === 'PUT /v1/places') {
      if (!needsToken(trust, res)) return undefined;
      // Only what the body names changes. Places from Apple's data are not kept here (its terms: section 2.6 of the plan).
      const doc = body ?? {};
      const apple = (p) => String(p?.src ?? '').toLowerCase().includes('apple');
      if (apple(doc.home) || apple(doc.work)) return send(res, 409, { reason: 'apple_not_kept' });
      const favourites = (doc.favourites ?? state.places.favourites).filter((p) => !apple(p));
      const recents = (doc.recents ?? state.places.recents).filter((p) => !apple(p));
      if (!validPlace(doc.home) || !validPlace(doc.work) || !favourites.every(validPlace) || !recents.every(validPlace)) {
        return send(res, 400, { reason: 'bad_place' });
      }
      state.places = { home: 'home' in doc ? doc.home : state.places.home, work: 'work' in doc ? doc.work : state.places.work, favourites, recents };
      return send(res, 200, state.places);
    }
    if (key === 'POST /v1/remote') {
      if (!needsToken(trust, res)) return undefined;
      return handleRemote(res, body);
    }
    if (key === 'GET /v1/diag') {
      if (!needsToken(trust, res)) return undefined;
      return send(res, 200, `AMRI ${base.version} (بديل للاختبار)\nWi-Fi: ok\nphoneLinkNote: -\nGPS: ${state.car.gpsFix ? 'fix' : 'no fix'}\nlast crash: none\n`, 'text/plain; charset=utf-8');
    }
    return send(res, 404, { reason: 'not found' });
  }

  const server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://fake');
    const path = url.pathname;
    const chunks = [];
    let size = 0;
    let tooBig = false;
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > MAX_BODY) tooBig = true;
      else chunks.push(chunk);
    });
    req.on('end', () => {
      const raw = Buffer.concat(chunks).toString('utf8');
      const isFake = path.startsWith('/__fake/');
      if (tooBig) return send(res, 413, { reason: 'too big' });

      if (!isFake) {
        state.log.push({ method: req.method, path, body: raw ? safeJSON(raw) : null, trust: trustOf(req), hadToken: Boolean(req.headers.authorization), at: Date.now() });
        const peer = req.socket.remoteAddress || '';
        if (!privatePeer(peer)) return send(res, 403, { reason: 'private networks only' });
        // Web safety. No CORS header anywhere, and OPTIONS is simply not allowed, so preflight fails.
        if (req.method === 'OPTIONS') return send(res, 405, { reason: 'no' });
        if (!hostAllowed(req)) return send(res, 403, { reason: 'bad host' });
        if (!originAllowed(req)) return send(res, 403, { reason: 'bad origin' });
        if ((req.method === 'POST' || req.method === 'PUT') && !(req.headers['content-type'] || '').startsWith('application/json')) {
          return send(res, 415, { reason: 'application/json only' });
        }
      }
      let body = null;
      if (raw) {
        body = safeJSON(raw);
        if (body === undefined) return send(res, 400, { reason: 'bad json' });
      }
      return isFake ? handleFake(req, res, path, body) : route(req, res, path, body);
    });
  });

  const adbServers = [];

  /** As assets/rollback.sh answers: it says "started" at once and works on by itself; its result file tells how that goes. */
  function adbAnswer(service) {
    if (service === 'shell:sh /data/local/tmp/amri-rollback/rollback.sh') {
      if (config.noRollbackScript) return "sh: can't open '/data/local/tmp/amri-rollback/rollback.sh': No such file or directory\r\n";
      state.rollbackReads = 0;
      return 'started\r\n';
    }
    if (service === 'shell:cat /data/local/tmp/amri-rollback/result') {
      state.rollbackReads = (state.rollbackReads ?? 0) + 1;
      const head = 'MODE=back\r\nFROM= versionCode=200 versionName=2.0.0 \r\nTO=1.19.0\r\n';
      if (state.rollbackReads < 2) return head;
      if (config.rollbackFails) return `${head}Failure [INSTALL_FAILED]\r\nEXIT=1\r\nHOME=com.android.launcher DISABLED=1\r\nDONE\r\n`;
      return `${head}Success\r\nEXIT=0\r\nNOW= versionCode=119 versionName=1.19.0 \r\nDONE\r\n`;
    }
    return 'not a stand-in command\r\n';
  }

  function startAdb({ port, model }) {
    const banner = `device::ro.product.name=fake;ro.product.model=${model};ro.product.device=fake-device;features=shell_v2,cmd\0`;
    const srv = net.createServer((socket) => {
      let buffer = Buffer.alloc(0);
      socket.on('error', () => {});
      socket.on('data', (data) => {
        buffer = Buffer.concat([buffer, data]);
        for (;;) {
          if (buffer.length < 24) return;
          const length = buffer.readUInt32LE(12);
          if (buffer.length < 24 + length) return;
          const command = buffer.readUInt32LE(0);
          const arg0 = buffer.readUInt32LE(4);
          const payload = buffer.subarray(24, 24 + length);
          buffer = buffer.subarray(24 + length);
          if (buffer.length === 0) buffer = Buffer.alloc(0);
          if (command === ADB.CNXN) {
            socket.write(adbPacket(ADB.CNXN, 0x01000001, 256 * 1024, Buffer.from(banner)));
          } else if (command === ADB.OPEN) {
            const service = payload.toString('utf8').replace(/\0$/, '');
            state.adbOpens.push({ port, model, service });
            const mine = 1000 + state.adbOpens.length;
            socket.write(adbPacket(ADB.OKAY, mine, arg0));
            socket.write(adbPacket(ADB.WRTE, mine, arg0, Buffer.from(adbAnswer(service))));
            socket.write(adbPacket(ADB.CLSE, mine, arg0));
          }
        }
      });
    });
    adbServers.push(srv);
    return new Promise((resolve) => srv.listen(port, base.host, () => resolve(srv.address().port)));
  }

  return {
    async start() {
      await new Promise((resolve) => server.listen(base.port, base.host, resolve));
      const adbPorts = [];
      for (const entry of base.adb) adbPorts.push(await startAdb(entry));
      return { port: server.address().port, adbPorts };
    },
    async stop() {
      for (const client of sseClients) client.end();
      sseClients.clear();
      for (const timer of timers) clearTimeout(timer);
      timers.clear();
      for (const srv of adbServers) srv.close();
      server.closeAllConnections();
      await new Promise((resolve) => server.close(resolve));
    },
    get state() {
      return state;
    },
    get config() {
      return config;
    },
  };
}

const ADB = { CNXN: 0x4e584e43, OPEN: 0x4e45504f, OKAY: 0x59414b4f, CLSE: 0x45534c43, WRTE: 0x45545257 };

function adbPacket(command, arg0, arg1, payload = Buffer.alloc(0)) {
  const header = Buffer.alloc(24);
  header.writeUInt32LE(command, 0);
  header.writeUInt32LE(arg0, 4);
  header.writeUInt32LE(arg1, 8);
  header.writeUInt32LE(payload.length, 12);
  let sum = 0;
  for (const byte of payload) sum = (sum + byte) >>> 0;
  header.writeUInt32LE(sum, 16);
  header.writeUInt32LE((~command) >>> 0, 20);
  return Buffer.concat([header, payload]);
}

function safeJSON(text) {
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

function parseArgs(argv) {
  const options = { adb: [] };
  const numbers = { '--port': 'port', '--route-delay': 'routeDelayMs', '--progress-ms': 'progressMs', '--trip-seconds': 'tripSeconds', '--heartbeat-ms': 'heartbeatMs' };
  const strings = { '--host': 'host', '--code': 'code', '--gateway': 'gateway', '--eta': 'eta', '--unit': 'unit', '--name': 'name' };
  for (let i = 0; i < argv.length; i += 2) {
    const flag = argv[i];
    const value = argv[i + 1];
    if (numbers[flag]) options[numbers[flag]] = Number(value);
    else if (strings[flag]) options[strings[flag]] = value;
    else if (flag === '--adb') {
      const at = value.indexOf(':');
      options.adb.push({ port: Number(value.slice(0, at)), model: value.slice(at + 1) });
    } else throw new Error(`unknown flag ${flag}`);
  }
  return options;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const unit = createFakeUnit(parseArgs(process.argv.slice(2)));
  unit.start().then(({ port, adbPorts }) => {
    console.log(`fake unit on port ${port}, adb on [${adbPorts.join(', ')}]`);
  });
}
