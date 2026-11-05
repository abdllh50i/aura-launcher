# A stand-in for the owner's lyrics server on the PC, for trying the music screen on the emulator: lyrics_standin.py <port>
# Never the real server, never its port 8790. In the app (debug build, test values): prefs "aura", lyricsServer = 10.0.2.2:<port>,
# lyricsServerKey = anything. Knows a few made-up songs (scripts/gen_test_songs.py has them): "Corniche Lights" and "Phone Song Two"
# timed with word times, "Exit 12" and "Phone Song" timed without (so they are listened to for their words), "Second Road" and "Plain
# Phone Song" untimed (timed by listening), "Morning Heat" a server error, anything else none. Every sync request goes to the log with
# its time, so what is listened to after the screen left shows.
import json
import sys
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(sys.argv[1])
if PORT == 8790:
    sys.exit("8790 is the owner's real server: another port")
jobs = {}
WORDS = ['First line of the song', 'Then the second one comes', 'A third with more words in it', 'Short fourth',
         'The fifth goes on and on', 'And a sixth for the road', 'Seven lights on the corniche', 'Eight more miles to go',
         'Nine is where it turns', 'Ten and the sea is near', 'Eleven nearly home', 'Twelve and the song is over']


def stamp(sec):
    return '%02d:%05.2f' % (int(sec // 60), sec % 60)


def synced(lines=WORDS, first=3.0, step=6.0):
    return '\n'.join(f'[{stamp(first + i * step)}]{t}' for i, t in enumerate(lines))


def enhanced(lines=WORDS, first=3.0, step=6.0, upto=None):
    out = []
    for i, t in enumerate(lines[:upto] if upto else lines):
        at = first + i * step
        ws = t.split()
        tags = ''.join(f'<{stamp(at + k * 0.6)}>{w} ' for k, w in enumerate(ws))
        out.append(f'[{stamp(at)}]{tags}<{stamp(at + len(ws) * 0.6)}>')
    return '\n'.join(out)


def plain(lines=WORDS):
    return '\n'.join(lines)


def answer(title):
    t = title.lower()
    if t == 'corniche lights' or t == 'phone song two':
        return {'synced': synced(), 'plain': plain(), 'source': 'standin', 'id': 'x', 'covered': 1.0, 'enhanced': enhanced(), 'words': 'heard', 'match': {'duration': 179.0}}
    if t == 'exit 12' or t == 'phone song':
        return {'synced': synced(), 'plain': plain(), 'source': 'standin', 'id': 'x', 'covered': 1.0, 'match': {'duration': 179.0}}
    if t == 'second road' or t == 'plain phone song':
        return {'synced': None, 'plain': plain(), 'source': 'standin', 'id': 'x', 'covered': 0.0}
    if t == 'morning heat':
        return None  # an error
    return {'synced': None, 'plain': None, 'source': 'none', 'id': ''}


class H(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, *a):
        pass

    def say(self, *a):
        print(time.strftime('%H:%M:%S'), *a, flush=True)

    def send(self, code, obj):
        b = json.dumps(obj, ensure_ascii=False).encode('utf-8')
        self.send_response(code)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(b)))
        self.send_header('Connection', 'close')
        self.end_headers()
        self.wfile.write(b)
        self.close_connection = True

    def state(self, j, words=True):
        o = {'id': j['id'], 'mode': j['mode'], 'covered': j['covered'], 'show': j['covered'] > 0, 'done': j['covered'] >= 1.0, 'pending': j['pending']}
        n = len(j['lines'])
        if j['mode'] == 'untimed' and j['covered'] > 0:
            o['times'] = [int((3.0 + i * 6.0) * 1000) if i < int(n * j['covered']) else None for i in range(n)]
        if j['mode'] == 'timed' and j['covered'] > 0 and words:
            o['enhanced'] = enhanced(j['lines'], upto=max(1, int(n * j['covered'])))
            o['words'] = 'heard'
            o['heard'] = 10
        return o

    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        q = urllib.parse.parse_qs(u.query)
        if u.path == '/health':
            return self.send(200, {'service': 'amri-lyrics'})
        if u.path == '/api/ping':
            return self.send(200, {'version': 'stand-in', 'model': 'none'})
        if u.path == '/api/lyrics':
            title = q.get('title', [''])[0]
            self.say('lyrics', title, q.get('duration', ['-'])[0])
            a = answer(title)
            if a is None:
                return self.send(500, {'error': 'stand-in'})
            return self.send(200, a)
        if u.path == '/api/sync':
            j = jobs.get(q.get('id', [''])[0])
            if not j:
                return self.send(404, {})
            if j['file']:
                j['polls'] += 1
                j['covered'] = 1.0 if j['polls'] >= 2 else 0.5
                j['pending'] = 0 if j['polls'] >= 2 else 1
            self.say('sync state', j['title'], 'covered', j['covered'])
            return self.send(200, self.state(j))
        self.send(404, {})

    def do_POST(self):
        u = urllib.parse.urlparse(self.path)
        q = urllib.parse.parse_qs(u.query)
        n = int(self.headers.get('Content-Length', '0'))
        left = n
        body = b''
        while left > 0:
            chunk = self.rfile.read(min(65536, left))
            if not chunk:
                break
            if u.path == '/api/sync/start':
                body += chunk
            left -= len(chunk)
        if u.path == '/api/sync/start':
            o = json.loads(body.decode('utf-8'))
            jid = 'job%d' % (len(jobs) + 1)
            jobs[jid] = {'id': jid, 'title': o.get('title'), 'lines': o.get('lines', []), 'mode': 'timed' if o.get('times') else 'untimed',
                         'covered': 0.0, 'pending': 0, 'chunks': 0, 'file': False, 'polls': 0}
            self.say('sync start', o.get('title'), jobs[jid]['mode'], len(o.get('lines', [])), 'lines')
            return self.send(200, self.state(jobs[jid]))
        if u.path == '/api/sync/chunk':
            j = jobs.get(q.get('id', [''])[0])
            if not j:
                return self.send(409, {})
            j['chunks'] += 1
            j['covered'] = min(0.8, 0.2 * j['chunks'])
            self.say('sync chunk', j['title'], 'pos', q.get('pos', ['?'])[0], 'bytes', n, 'src', q.get('src', ['?'])[0])
            return self.send(200, self.state(j))
        if u.path == '/api/sync/file':
            j = jobs.get(q.get('id', [''])[0])
            if not j:
                return self.send(409, {})
            j['file'] = True
            j['pending'] = 1
            self.say('sync file', j['title'], q.get('name', ['?'])[0], 'bytes', n)
            return self.send(200, self.state(j))
        self.send(404, {})


print('listening', PORT, flush=True)
ThreadingHTTPServer(('127.0.0.1', PORT), H).serve_forever()
