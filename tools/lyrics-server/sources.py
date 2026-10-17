"""Where lyrics come from: YouTube Music's own (LyricFind / Musixmatch, mostly timed: the owner's catalogue), the timed
(LRC) ones of the sources the open-source `syncedlyrics` package knows (Musixmatch, LRCLIB, Megalobiz, NetEase), then
plain text from Genius as the last resort.

`syncedlyrics.search()` only returns the text of whatever it took for the best hit, without saying which song that was;
here every source's candidates are compared with the song asked for (its name, its artist, in Arabic or Latin letters,
and its length when it is known), and only one that really is that song is taken. That matters: Musixmatch now hands an
anonymous client a placeholder token and made-up lyrics ("Wob gopini den…") for whatever is asked, and Megalobiz
answers an Arabic title it does not have with some other song by the same singer.
"""
import concurrent.futures
import hashlib
import html
import json
import os
import re
import threading
import time

import requests
from bs4 import BeautifulSoup

import textnorm as tn

APP_UA = "AMRI-lyrics-server/1.0 (+https://github.com/abdllh50i/aura-launcher)"
BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
TIMEOUT = (4, 12)
DURATION_SLACK = 4  # seconds: another recording of the song beyond this (a live one, a video with an intro)

KEEP_SYNCED = 60 * 86400   # a timed source answer is kept two months
KEEP_PLAIN = 3 * 86400     # plain only: the timed sources are asked again after three days (someone may add it)
KEEP_MISS = 2 * 86400      # nobody had it: asked again after two days
KEEP_MISS_ERR = 1800       # nobody had it, but a source did not answer: half an hour

_TIME_TAG = re.compile(r"^\s*\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]")


class SourceError(Exception):
    """A source could not be asked (network, busy, changed): not the same as "it does not have the song"."""


class SourceOff(SourceError):
    """A source that does not serve this server at all for now (Musixmatch's placeholder token): left out, and its
    silence does not stop a "nobody has it" from being kept."""


class Song:
    def __init__(self, title, artist="", album="", duration=0):
        self.title = (title or "").strip()
        self.artist = (artist or "").strip()
        self.album = (album or "").strip()
        self.duration = int(duration or 0)

    def term(self):
        return ("%s %s" % (tn.clean(self.title), self.artist)).strip()


class Hit:
    def __init__(self, source, title, artist, duration, synced=None, plain=None, url=None, instrumental=False):
        self.source = source
        self.title = title or ""
        self.artist = artist or ""
        self.duration = float(duration or 0)
        self.synced = synced or None
        self.plain = plain or None
        self.url = url
        self.instrumental = instrumental


# ---------------------------------------------------------------------------------------------- matching
def matches(song, title, artist, duration):
    """The candidate is the song asked for: its name (decorations aside), its artist when both are known, and its
    length within a few seconds when both are known."""
    t = tn.clean(title)
    q = tn.clean(song.title)
    ok_title = tn.same(q, t, words=0.6)
    if not ok_title and " - " in song.title:
        # a video's name as the title ("Artist - Song"), the artist field often the album or a channel
        left, right = [x.strip() for x in song.title.split(" - ", 1)]
        ok_title = tn.same(tn.clean(right), t, words=0.6) or tn.same(tn.clean(left), t, words=0.6)
    if not ok_title:
        return False
    if song.artist and not (artist or "").strip():
        # a sheet with no artist ("Warm Up Official Audio" on Megalobiz) is some song of that name: only when its own
        # title names the artist asked for
        if not tn.same(song.artist, title or "", words=0.5) and tn.norm(song.artist) not in tn.norm(title or ""):
            return False
    elif song.artist and artist and not tn.same(song.artist, artist, words=0.34):
        # (the phone's "artist" may be the album or "Artist - Topic"; the title then has to carry the artist)
        if not (" - " in song.title and tn.same(song.title.split(" - ", 1)[0], artist, words=0.34)):
            return False
    if song.duration and duration and abs(float(duration) - song.duration) > DURATION_SLACK:
        return False
    return True


def rank(song, h):
    """The better the higher: the name, the artist, the length's closeness."""
    s = tn.similarity(tn.clean(song.title), tn.clean(h.title)) * 0.6
    s += (tn.similarity(song.artist, h.artist) if song.artist and h.artist else 0.5) * 0.3
    if song.duration and h.duration:
        s += max(0.0, 1.0 - abs(h.duration - song.duration) / DURATION_SLACK) * 0.1
    return s


def lrc_ok(lrc):
    """Timed text worth showing: several timed lines with words (not a single "[00:00.00] instrumental")."""
    if not lrc:
        return False
    n = 0
    for line in lrc.splitlines():
        m = _TIME_TAG.match(line)
        if m and line[m.end():].strip():
            n += 1
    return n >= 3


def clean_plain(text):
    """Plain lyrics as the screen shows them: section headings ("[Chorus]", "[Verse 1: …]") out, at most one empty
    line between stanzas, no empty lines at the ends."""
    out = []
    for raw in (text or "").replace("\r", "").split("\n"):
        line = raw.strip()
        if re.fullmatch(r"\[[^\]]{1,60}\]", line) or re.fullmatch(r"\([^)]{0,3}\)", line):
            continue
        if not line and (not out or not out[-1]):
            continue
        out.append(line)
    while out and not out[-1]:
        out.pop()
    return "\n".join(out) if any(out) else None


# ---------------------------------------------------------------------------------------------- the sources
class Source:
    name = "?"
    synced = True

    def __init__(self):
        self.session = requests.Session()
        self.session.headers["User-Agent"] = APP_UA

    def get(self, url, **kw):
        """A GET; a busy answer (LRCLIB often says 503) is asked once more 1.5 s later, as the app does."""
        kw.setdefault("timeout", TIMEOUT)
        for attempt in (0, 1):
            try:
                r = self.session.get(url, **kw)
            except requests.RequestException as e:
                raise SourceError("%s: %s" % (self.name, e.__class__.__name__))
            if r.status_code in (429, 503) and attempt == 0:
                time.sleep(1.5)
                continue
            if r.status_code in (403, 429, 500, 502, 503, 504):
                raise SourceError("%s: HTTP %d" % (self.name, r.status_code))
            return r

    def find(self, song):
        """The matching hits (best first); [] when the source has nothing for the song."""
        raise NotImplementedError


class Lrclib(Source):
    """lrclib.net, through syncedlyrics' endpoints: by field first, then free text."""
    name = "lrclib"

    def __init__(self):
        super().__init__()
        from syncedlyrics.providers import Lrclib as P
        self.p = P()
        self.p.session.headers["User-Agent"] = APP_UA
        self.session = self.p.session

    def find(self, song):
        seen = {}
        queries = []
        if song.artist:
            queries.append({"track_name": tn.clean(song.title), "artist_name": song.artist})
        queries.append({"q": song.term()})
        for q in queries:
            r = self.get(self.p.SEARCH_ENDPOINT, params=q)
            if r.status_code != 200:
                continue
            for o in r.json() or []:
                if o.get("id") in seen:
                    continue
                if not matches(song, o.get("trackName"), o.get("artistName"), o.get("duration")):
                    continue
                seen[o.get("id")] = Hit(self.name, o.get("trackName"), o.get("artistName"), o.get("duration"),
                                        synced=o.get("syncedLyrics"), plain=o.get("plainLyrics"),
                                        url="https://lrclib.net/api/get/%s" % o.get("id"), instrumental=bool(o.get("instrumental")))
            if any(h.synced for h in seen.values()):
                break
        hits = list(seen.values())
        if not song.duration:
            # no length to go by: the recording most timed entries agree on (as the app does), not a video's
            lens = [h.duration for h in hits if h.synced and h.duration]
            if lens:
                agreed = max(lens, key=lambda d: sum(1 for x in lens if abs(x - d) <= 3))
                for h in hits:
                    h.agreed = abs(h.duration - agreed) <= 3 if h.duration else False
        return sorted(hits, key=lambda h: (not lrc_ok(h.synced), not getattr(h, "agreed", True), -rank(song, h)))


class Musixmatch(Source):
    """Musixmatch's desktop API through syncedlyrics' provider (token, endpoints). Anonymous clients are now often given
    a placeholder token (all zeros) whose answers are invented: the source then says it is unavailable."""
    name = "musixmatch"
    _lock = threading.Lock()
    _token = None
    _token_until = 0
    _refused_until = 0

    def __init__(self):
        super().__init__()
        from syncedlyrics.providers import Musixmatch as P
        self.p = P()
        self.session = self.p.session

    def _token_now(self):
        with Musixmatch._lock:
            now = time.time()
            if now < Musixmatch._refused_until:
                raise SourceOff("musixmatch: placeholder token (anonymous access refused)")
            if Musixmatch._token and now < Musixmatch._token_until:
                return Musixmatch._token
            for _ in range(2):  # (syncedlyrics retries a 401 forever, 10 s apart: twice here)
                try:
                    d = self.p._get("token.get", [("user_language", "en")]).json()
                except (requests.RequestException, ValueError) as e:
                    raise SourceError("musixmatch: %s" % e.__class__.__name__)
                hdr = d.get("message", {}).get("header", {})
                if hdr.get("status_code") == 200:
                    tok = d["message"]["body"]["user_token"]
                    if not tok or set(tok) <= {"0"}:
                        Musixmatch._refused_until = now + 3600
                        raise SourceOff("musixmatch: placeholder token (anonymous access refused)")
                    Musixmatch._token = tok
                    Musixmatch._token_until = now + 540
                    return tok
                time.sleep(2)
            raise SourceError("musixmatch: no token")

    def _api(self, action, query):
        self.p.token = self._token_now()
        try:
            d = self.p._get(action, list(query)).json()
        except (requests.RequestException, ValueError) as e:
            raise SourceError("musixmatch: %s" % e.__class__.__name__)
        return d.get("message", {})

    def find(self, song):
        q = [("q_track", tn.clean(song.title)), ("page_size", "8"), ("page", "1"), ("s_track_rating", "desc")]
        if song.artist:
            q.append(("q_artist", song.artist))
        m = self._api("track.search", q)
        body = m.get("body")
        tracks = body.get("track_list", []) if isinstance(body, dict) else []
        hits = []
        for t in (x.get("track", {}) for x in tracks):
            if t.get("instrumental"):
                continue
            if not matches(song, t.get("track_name"), t.get("artist_name"), t.get("track_length")):
                continue
            h = Hit(self.name, t.get("track_name"), t.get("artist_name"), t.get("track_length"))
            h.id = t.get("track_id")
            h.has_sub = bool(t.get("has_subtitles"))
            h.has_lyrics = bool(t.get("has_lyrics"))
            hits.append(h)
        hits.sort(key=lambda h: (not h.has_sub, -rank(song, h)))
        for h in hits[:2]:
            if h.has_sub:
                lrc = self.p.get_lrc_by_id(str(h.id))
                h.synced = lrc.synced if lrc else None
            if not h.synced and h.has_lyrics:
                lb = self._api("track.lyrics.get", [("track_id", str(h.id))]).get("body")
                if isinstance(lb, dict):
                    text = (lb.get("lyrics") or {}).get("lyrics_body") or ""
                    # (a sample of the lyrics ends with this line; not the whole text)
                    h.plain = None if "*******" in text else text
        return [h for h in hits if h.synced or h.plain]


class Megalobiz(Source):
    """Megalobiz: its public API (the search page syncedlyrics 1.0.1 reads was removed; it now points there). No key:
    30 questions a minute from one address, which a car's songs never reach."""
    name = "megalobiz"
    API = "https://api.megalobiz.com/api/public/v1"

    def find(self, song):
        p = {"title": tn.clean(song.title), "limit": 10}
        if song.artist:
            p["artist"] = song.artist
        if song.duration:
            p["duration"] = song.duration
        r = self.get(self.API + "/search", params=p)
        if r.status_code != 200:
            return []
        hits = []
        for o in (r.json() or {}).get("results", []):
            if not matches(song, o.get("title"), o.get("artist"), o.get("duration")):
                continue
            h = Hit(self.name, o.get("title"), o.get("artist"), o.get("duration"), url=o.get("url"))
            h.id = o.get("id")
            h.manual = o.get("sync") == "manual"
            hits.append(h)
        hits.sort(key=lambda h: (not h.manual, -rank(song, h)))
        for h in hits[:2]:
            lr = self.get("%s/lrc/%s" % (self.API, h.id), params={"format": "lrc"})
            if lr.status_code == 200 and lrc_ok(lr.text):
                h.synced = lr.text
                return [h]
        return []


class NetEase(Source):
    """NetEase Cloud Music, through syncedlyrics' provider (its session and cookies)."""
    name = "netease"

    def __init__(self):
        super().__init__()
        from syncedlyrics.providers import NetEase as P
        self.p = P()
        self.session = self.p.session

    def find(self, song):
        r = self.get(self.p.API_ENDPOINT_METADATA, params={"limit": 10, "type": 1, "offset": 0, "s": song.term()})
        try:
            songs = (r.json().get("result") or {}).get("songs") or []
        except ValueError:
            raise SourceError("netease: not JSON")
        hits = []
        for s in songs:
            artist = ", ".join(a.get("name", "") for a in s.get("artists") or [])
            if not matches(song, s.get("name"), artist, (s.get("duration") or 0) / 1000.0):
                continue
            h = Hit(self.name, s.get("name"), artist, (s.get("duration") or 0) / 1000.0)
            h.id = s.get("id")
            hits.append(h)
        hits.sort(key=lambda h: -rank(song, h))
        for h in hits[:2]:
            lr = self.get(self.p.API_ENDPOINT_LYRICS, params={"id": h.id, "lv": 1})
            try:
                text = (lr.json().get("lrc") or {}).get("lyric")
            except ValueError:
                continue
            if lrc_ok(text):
                h.synced = text
                return [h]
        return []


class Genius(Source):
    """Genius: plain text only, the last resort. Its search API (the one its own pages use, no token) and the song's
    page; Genius refuses the requests library's own User-Agent, so this one says it is a browser."""
    name = "genius"
    synced = False

    def __init__(self):
        super().__init__()
        self.session.headers["User-Agent"] = BROWSER_UA
        self.session.headers["Accept-Language"] = "en-US,en;q=0.9,ar;q=0.8"

    def find(self, song):
        r = self.get("https://genius.com/api/search/multi", params={"q": song.term(), "per_page": 5})
        if r.status_code != 200:
            return []
        hits = []
        for sec in r.json().get("response", {}).get("sections", []):
            for hit in sec.get("hits", []):
                if hit.get("type") != "song":
                    continue
                o = hit.get("result", {})
                if o.get("instrumental"):
                    continue
                title = o.get("title") or ""
                artist = o.get("artist_names") or (o.get("primary_artist") or {}).get("name") or ""
                # (Genius has translations as songs of their own: "Hello (Traducción al Español)")
                if re.search(r"(?i)(translation|traducci|traduction|übersetzung|tradução|ترجمة|romanized)", title + " " + artist):
                    continue
                if not matches(song, title, artist, 0):
                    continue
                if any(h.url == o.get("url") for h in hits):
                    continue
                hits.append(Hit(self.name, title, artist, 0, url=o.get("url")))
        hits.sort(key=lambda h: -rank(song, h))
        for h in hits[:2]:
            page = self.get(h.url)
            if page.status_code != 200:
                continue
            text = self.lyrics(page.text)
            if text:
                h.plain = text
                return [h]
        return []

    @staticmethod
    def lyrics(page):
        soup = BeautifulSoup(page, "html.parser")
        boxes = soup.find_all("div", attrs={"data-lyrics-container": True})
        if not boxes:
            return None
        parts = []
        for box in boxes:
            # the page's own furniture inside the lyrics' boxes: contributors, translations, the song's blurb
            for x in box.find_all(attrs={"data-exclude-from-selection": True}):
                x.decompose()
            for br in box.find_all("br"):
                br.replace_with("\n")
            parts.append(html.unescape(box.get_text("")))
        return clean_plain("\n".join(parts))


class YouTubeMusic(Source):
    """YouTube Music's own lyrics: the catalogue the owner plays from, licensed from LyricFind or Musixmatch ("find lyrics"
    in his words was LyricFind, the "Source:" under YouTube Music's lyrics), timed for most songs — including Arabic ones
    no other source had (2026-10-18: all six of the owner's latest songs, four of which had come back plain from Genius
    or not at all). Through the open-source ytmusicapi package, no account: the song is found by its name and artist,
    its watch page names the lyrics' browse id, and the lyrics come with their times from the Android client the package
    asks for them. (YTMusic() as it is: given language="ar" with location="SA" every search came back empty.)"""
    name = "youtube"
    _yt = None
    _lock = threading.Lock()

    @classmethod
    def client(cls):
        with cls._lock:
            if cls._yt is None:
                from ytmusicapi import YTMusic
                cls._yt = YTMusic()
            return cls._yt

    @staticmethod
    def same_song(song, title, artist, dur):
        """The very title and an artist only spelt in the other script: YouTube Music names Arabic singers in Latin
        letters ("حودة" is "Houda", 0.63 alike, under the general rule's bar). A different singer altogether (another
        song of that name) stays out."""
        if tn.norm(tn.clean(title)) != tn.norm(tn.clean(song.title)):
            return False
        if song.artist and tn.similarity(song.artist, artist) < 0.5:
            return False
        return not (song.duration and dur and abs(float(dur) - song.duration) > DURATION_SLACK)

    def find(self, song):
        try:
            yt = self.client()
            results = yt.search(song.term(), filter="songs", limit=10) or []
        except Exception as e:
            raise SourceError("youtube: %s" % e.__class__.__name__)
        hits = []
        for o in results:
            vid = o.get("videoId")
            title = o.get("title") or ""
            artist = ", ".join(a.get("name", "") for a in o.get("artists") or [])
            dur = o.get("duration_seconds") or 0
            if not vid:
                continue
            if not matches(song, title, artist, dur) and not self.same_song(song, title, artist, dur):
                continue
            h = Hit(self.name, title, artist, dur, url="https://music.youtube.com/watch?v=%s" % vid)
            h.vid = vid
            hits.append(h)
        hits.sort(key=lambda h: -rank(song, h))
        for h in hits[:2]:
            try:
                bid = (yt.get_watch_playlist(h.vid, limit=1) or {}).get("lyrics")
                if not bid:
                    continue
                ly = yt.get_lyrics(bid, timestamps=True) or {}
            except Exception:
                continue
            lines = ly.get("lyrics")
            h.provider = (ly.get("source") or "").replace("Source:", "").strip() or None
            if ly.get("hasTimestamps") and isinstance(lines, list):
                # ("♪" marks music without words: an empty timed line, as LRC has it)
                lrc = "\n".join("[%02d:%05.2f]%s" % (l.start_time // 60000, (l.start_time % 60000) / 1000.0,
                                                     "" if l.text.strip() == "♪" else l.text) for l in lines)
                if lrc_ok(lrc):
                    h.synced = lrc
                    return [h]
            elif isinstance(lines, str) and lines.strip():
                h.plain = clean_plain(lines)
                if h.plain:
                    return [h]
        return []


# YouTube Music first: the owner's own catalogue, the same recording his phone plays
SYNCED_ORDER = [YouTubeMusic, Musixmatch, Lrclib, Megalobiz, NetEase]


# ---------------------------------------------------------------------------------------------- the search, kept
class Search:
    """Looks a song up (timed sources at once, then Genius) and keeps the answer on disk ([home]/lyrics)."""

    def __init__(self, home):
        self.dir = os.path.join(home, "lyrics")
        os.makedirs(self.dir, exist_ok=True)
        self.pool = concurrent.futures.ThreadPoolExecutor(max_workers=6, thread_name_prefix="source")
        self.lock = threading.Lock()

    @staticmethod
    def key(song):
        s = "%s|%s|%d" % (tn.norm(tn.clean(song.title)), tn.norm(song.artist), round(song.duration / 3) if song.duration else 0)
        return hashlib.sha1(s.encode("utf-8")).hexdigest()

    def _path(self, song):
        return os.path.join(self.dir, self.key(song) + ".json")

    def cached(self, song):
        path = self._path(song)
        try:
            with open(path, encoding="utf-8") as f:
                kept = json.load(f)
        except (OSError, ValueError):
            return None
        age = time.time() - kept.get("at", 0)
        r = kept.get("result")
        if r and r.get("synced"):
            limit = KEEP_SYNCED
        elif kept.get("errors"):
            # (a timed source was busy: LRCLIB's 503 once turned a song it has timed into Genius's plain text)
            limit = KEEP_MISS_ERR
        else:
            limit = KEEP_PLAIN if r else KEEP_MISS
        if age > limit:
            return None
        return kept

    def keep(self, song, result, tried):
        errors = [t["source"] for t in tried if t["result"] == "error"]
        kept = {"at": time.time(), "song": vars(song), "result": result, "tried": tried, "errors": errors}
        path = self._path(song)
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(kept, f, ensure_ascii=False)
        os.replace(tmp, path)
        return kept

    def find(self, song, fresh=False):
        """{"result": {synced, plain, source, title, artist, duration, url} or None, "tried": [...], "cached": bool}"""
        if not fresh:
            kept = self.cached(song)
            if kept is not None:
                return {"result": kept["result"], "tried": kept.get("tried", []), "cached": True}
        tried = []
        best_plain = None

        def run(cls):
            t0 = time.time()
            src = cls()
            try:
                hits = src.find(song)
                return cls.name, hits, None, time.time() - t0
            except SourceOff as e:
                return cls.name, [], "off: " + str(e), time.time() - t0
            except SourceError as e:
                return cls.name, [], str(e), time.time() - t0
            except Exception as e:  # a source that changed its pages: reported, never fatal
                return cls.name, [], "%s: %s" % (cls.name, e.__class__.__name__), time.time() - t0

        def outcome(err, synced, plain):
            if err:
                return "unavailable" if err.startswith("off: ") else "error"
            return "synced" if synced else ("plain" if plain else "miss")

        futures = [self.pool.submit(run, cls) for cls in SYNCED_ORDER]
        done, _ = concurrent.futures.wait(futures, timeout=40)
        answers = {}
        for f in done:
            name, hits, err, sec = f.result()
            answers[name] = (hits, err, sec)
        chosen = None
        for cls in SYNCED_ORDER:  # the first source in this order with a timed match wins
            hits, err, sec = answers.get(cls.name, ([], "no answer", 0))
            synced = next((h for h in hits if lrc_ok(h.synced)), None)
            plain = next((h for h in hits if h.plain), None)
            tried.append({"source": cls.name, "ms": int(sec * 1000), "result": outcome(err, synced, plain),
                          **({"error": err[5:] if err.startswith("off: ") else err} if err else {})})
            if synced and chosen is None:
                chosen = synced
            if plain and best_plain is None:
                best_plain = plain
        if chosen is None and best_plain is None:
            # Genius: the last resort, plain text only
            name, hits, err, sec = run(Genius)
            tried.append({"source": "genius", "ms": int(sec * 1000), "result": outcome(err, None, hits),
                          **({"error": err} if err else {})})
            if hits:
                best_plain = hits[0]
        else:
            tried.append({"source": "genius", "ms": 0, "result": "skipped"})
        result = None
        pick = chosen or best_plain
        if pick is not None:
            plain = pick.plain or (chosen and lrc_to_plain(chosen.synced)) or (best_plain and best_plain.plain)
            result = {"synced": pick.synced if chosen else None, "plain": clean_plain(plain) if plain else None,
                      "source": pick.source, "title": pick.title, "artist": pick.artist, "duration": pick.duration,
                      "url": pick.url}
            if getattr(pick, "provider", None):
                result["provider"] = pick.provider  # (YouTube Music's own source: LyricFind, Musixmatch)
        self.keep(song, result, tried)
        return {"result": result, "tried": tried, "cached": False}


def lrc_to_plain(lrc):
    out = []
    for line in (lrc or "").splitlines():
        m = _TIME_TAG.match(line)
        if not m:
            continue
        rest = line[m.end():]
        while True:  # several times on one line
            m2 = _TIME_TAG.match(rest)
            if not m2:
                break
            rest = rest[m2.end():]
        out.append(rest.strip())
    return "\n".join(out)
