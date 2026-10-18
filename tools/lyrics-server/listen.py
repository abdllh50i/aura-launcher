"""What is done with each piece of sound the car sends (1.16), and the recent sound of each song being listened to.

A piece is 12 s (2 s overlapping the one before) of the car's digital copy of the music it plays (Android's playback
capture of the Bluetooth music), or of its microphone; or a song's whole file. For each:
  1. its levels, for the diagnostics (the sound itself is never kept on disk);
  2. the singer's voice separated from the band (vocals.py);
  3. the recogniser writes down the words it hears in the voice: kept per song as before, they time the lines when
     the lyrics have no times ("untimed") and back up the word placing;
  4. the voice joins the session's recent sound (in memory only, the last 45 s; dropped when the song has been quiet
     for a few minutes), so a line sung across two pieces is still heard whole;
  5. every line whose time has been heard whole and that was not placed in this session yet gets its words placed
     (wordtimes.py), and they are kept in the song's record when they are surer than what it had.

"timed" sessions (the app gave each line's time, from its lyrics source) keep those times and only look for words,
around each line's own time. The app's song clock can be off by about a second: windows are wide at first, and the
session's offset (where the words were really sung against the given times, the median over the lines placed) moves
them and narrows them; the words are kept on the song's clock (the given times'), so the next play, with another
clock error, still shows them in step with its lines.
"""
import math
import threading
import time

import numpy as np

import align
import textnorm as tn
import vocals
import wordtimes as wt
from rapidfuzz import fuzz

RATE = 16000
KEEP_S = 45.0          # s of recent sound kept per session
QUIET_S = 180.0        # a session not heard from for this long is forgotten (its sound with it)
JUMP_S = 0.04          # two pieces whose overlap disagrees by more than this: the app's clock jumped
MIN_Q_SHORT = 0.6      # a short line (under SHORT letters) needs a surer placing: a few letters are found anywhere
SHORT = 10
MIN_Q_HEARD = 0.1      # ...or this much, when the recogniser heard enough of its words where they were placed
SURE_Q = 0.5           # lines placed at least this surely tell the session's clock offset
OFFSET_MIN = 3         # ...when there are this many of them
NARROW = 0.8           # s: the windows' slack once the offset is known
OFFSET_FROM = 0.15     # s: a smaller offset is not taken off the words kept, a larger one more and more,
OFFSET_FULL = 0.35     #   wholly from here (see kept_offset)


def kept_offset(off):
    """How much of the session's clock offset is taken off the words kept. The offset is measured on the lines'
    first words against the given times, so it also holds the source's own error (a few tenths, often one way for a
    whole song) and, in a noisy car, a first word heard a little late after the silence before it (0.1-0.15 s at 0 dB
    on the test songs, while the other words were on time): a small offset is not told from those and is left alone
    (the words stay where they were heard); an app clock that is clearly off (up to about a second) is taken off
    wholly, so the next play, with another clock error, shows the words in step with its lines. Between the two it
    grows evenly (a changing estimate never makes the words jump)."""
    if off is None:
        return None
    a = abs(off)
    if a <= OFFSET_FROM:
        return 0.0
    share = min(1.0, (a - OFFSET_FROM) / (OFFSET_FULL - OFFSET_FROM))
    return round(off * share, 3)


class Session:
    """One play of a song: its recent sound (the separated voice and the piece as sent), what was placed in it, and
    its clock's offset."""

    def __init__(self, sid, n):
        self.id = sid
        self.n = n
        self.voice = None
        self.mix = None
        self.t0 = 0.0
        self.last = time.time()
        self.tried = {}        # line -> the line time its window was made from
        self.placed = {}       # line -> its last word's start (song s, the audio's clock), for the lines placed
        self.deltas = {}       # line -> (first word's start - given time) for surely placed lines
        self.heard = []        # the recogniser's words in this session (song s, the app's clock)
        self.jumps = 0

    @property
    def t1(self):
        return self.t0 + (0 if self.voice is None else len(self.voice) / RATE)

    def offset(self):
        """The app's clock against the given times (s; None until enough lines were placed surely)."""
        if len(self.deltas) < OFFSET_MIN:
            return None
        d = sorted(self.deltas.values())
        med = d[len(d) // 2] if len(d) % 2 else (d[len(d) // 2 - 1] + d[len(d) // 2]) / 2
        return round(med, 3) if abs(med) >= 0.03 else 0.0

    def add(self, voice, mix, start):
        """The piece's voice and sound at song time [start] joined to the recent sound. Returns how: "new", "joined",
        "gap" (sound missing between them: started over), "jump" (the overlap disagreed: started over)."""
        self.last = time.time()
        how = "joined"
        if self.voice is None:
            how = "new"
        elif start > self.t1 + 0.3 or start < self.t0 or start + len(voice) / RATE <= self.t1:
            how = "gap"
        else:
            at = int(round((start - self.t0) * RATE))
            ov = len(self.voice) - at
            if ov > RATE // 4:
                lag = _lag(self.mix[at:], mix[:ov])
                if lag is not None and abs(lag) > JUMP_S:
                    how = "jump"
        if how in ("new", "gap", "jump"):
            if how == "jump":
                self.jumps += 1
            self.voice = voice.astype(np.float32)
            self.mix = mix.astype(np.float32)
            self.t0 = start
        else:
            at = int(round((start - self.t0) * RATE))
            ov = len(self.voice) - at
            ov = max(0, min(ov, len(voice)))
            if ov:
                # (the overlap heard twice: the end of one piece and the start of the next are both a separator's
                # edge, so the two are faded into each other)
                w = np.linspace(0.0, 1.0, ov, dtype=np.float32)
                self.voice[at:at + ov] = self.voice[at:at + ov] * (1 - w) + voice[:ov] * w
                self.mix[at:at + ov] = self.mix[at:at + ov] * (1 - w) + mix[:ov] * w
            self.voice = np.concatenate([self.voice, voice[ov:].astype(np.float32)])
            self.mix = np.concatenate([self.mix, mix[ov:].astype(np.float32)])
        drop = len(self.voice) - int(KEEP_S * RATE)
        if drop > 0:
            self.voice = self.voice[drop:]
            self.mix = self.mix[drop:]
            self.t0 += drop / RATE
        return how


def _lag(a, b, max_s=0.5):
    """How far [b] is shifted against [a] (s), the same stretch of sound sent twice (the pieces' overlap); 0 when it
    matches unshifted (as good as it matches anywhere: a steady beat or tone matches at other shifts too), None when
    it is too quiet to tell."""
    n = min(len(a), len(b))
    if n < RATE // 4:
        return None
    a = np.asarray(a[:n], dtype=np.float64)
    b = np.asarray(b[:n], dtype=np.float64)
    if np.sqrt(np.mean(a * a)) < 1e-3 or np.sqrt(np.mean(b * b)) < 1e-3:
        return None
    # (at 4 kHz: plenty for tens of milliseconds)
    k = 4
    m = (n // k) * k
    a = a[:m].reshape(-1, k).mean(1)
    b = b[:m].reshape(-1, k).mean(1)
    size = 1 << int(math.ceil(math.log2(2 * len(a))))
    c = np.fft.irfft(np.fft.rfft(a, size) * np.conj(np.fft.rfft(b, size)), size)
    lim = int(max_s * RATE / k)
    c = np.concatenate([c[-lim:], c[:lim + 1]])
    best = int(np.argmax(c))
    if c[lim] >= 0.9 * c[best]:
        return 0.0
    return (best - lim) * k / RATE


def corroborated(line, r, heard, slack=0.7):
    """How many of [line]'s words (3 letters or more) the recogniser heard near where they were placed ([r])."""
    toks = (line or "").split()
    n = hits = 0
    for j, tok in enumerate(toks):
        w = tn.word_norm(tok)
        if len(w) < 3 or r["s"][j] is None:
            continue
        n += 1
        t = r["s"][j]
        for (text, a, b, _) in heard:
            if a > t + slack:
                break
            if b < t - slack:
                continue
            h = tn.word_norm(text)
            if h and (h == w or (min(len(h), len(w)) >= 3 and fuzz.ratio(h, w) >= 80)):
                hits += 1
                break
    return hits, n


def believed(line, r, heard):
    """Whether a placing ([r], from wordtimes) is believed: sure enough for its length, or less sure but backed by
    what the recogniser heard there."""
    if r is None:
        return False
    toks, letters, kinds = wt.plan(line)
    size = sum(len(l) for l, k in zip(letters, kinds) if k == "word")
    if r["q"] >= (wt.MIN_Q if size >= SHORT else MIN_Q_SHORT):
        return True
    if r["q"] >= MIN_Q_HEARD and heard:
        hits, n = corroborated(line, r, heard)
        return n > 0 and hits >= max(1, math.ceil(0.5 * n))
    return False


class Listener:
    """The audio work: one piece at a time (the server's audio thread), the models loaded once and kept."""

    def __init__(self, songs, speech, log=print):
        self.songs = songs
        self.speech = speech
        self.log = log
        self.sep = vocals.Separator(log)
        self.aligner = wt.Aligner(log)
        self.sessions = {}
        self.lock = threading.Lock()
        self.broken = None     # why the voice / word models cannot run (no torch), once said

    # ------------------------------------------------------------------------------------------ sessions
    def started(self, s):
        """A new play of the song [s] (sync/start): its recent sound and what was placed in it start afresh."""
        with self.lock:
            self.sessions[s["id"]] = Session(s["id"], s.get("session", 0))

    def session(self, s):
        with self.lock:
            x = self.sessions.get(s["id"])
            if x is None or x.n != s.get("session", 0):
                x = Session(s["id"], s.get("session", 0))
                self.sessions[s["id"]] = x
            return x

    def tidy(self):
        """Sessions not heard from for QUIET_S are forgotten, their sound with them."""
        now = time.time()
        with self.lock:
            for k in [k for k, x in self.sessions.items() if now - x.last > QUIET_S]:
                del self.sessions[k]

    # ------------------------------------------------------------------------------------------ a piece
    def voice_of(self, audio):
        """The separated voice, or None (no torch: the recogniser then hears the piece as sent)."""
        if self.broken:
            return None
        try:
            return self.sep.vocals(audio)
        except Exception as e:
            if "torch" in str(e):
                self.broken = str(e)
            self.log("  voice separation failed: %s" % e)
            return None

    def chunk(self, i, audio, start, src=None, hurry=False):
        """A piece the car heard from song time [start] (16 kHz float32). Returns the record and its diagnostics.
        [hurry]: the processor is behind (no graphics card, pieces waiting): the voice is not separated (most of a
        piece's work there, about 10 s of a 12 s piece); the words are placed in the piece as sent (their starts as
        well as in the voice on the test songs, their ends and pauses less well)."""
        t_start = time.time()
        s = self.songs.load(i)
        if s is None:
            return None, None
        lines = s.get("lines") or []
        end = start + len(audio) / RATE
        diag = {"rms": vocals.dbfs(audio), "peak": vocals.peak_dbfs(audio), "vocal": None, "asr": 0, "aligned": 0, "src": src}
        voice = None if hurry else self.voice_of(audio)
        if voice is not None:
            diag["vocal"] = vocals.dbfs(voice)
        elif hurry and not self.broken:
            diag["hurry"] = True
            voice = audio
        heard = self.speech.heard(voice if voice is not None else audio, align.language_of(lines), start, lines)
        diag["asr"] = len(heard)
        s = self.songs.add_words(i, heard, start, end, edges=True, kind="chunk", diag=diag)
        if s is None:
            return None, diag
        sess = self.session(s)
        sess.heard = align.merge(sess.heard, heard, start, end, edges=True)
        if voice is not None:
            how = sess.add(voice, audio, start)
            if how in ("gap", "jump"):
                diag["join"] = how
            results = self.place(s, sess)
            off = sess.offset() if s.get("given") else 0.0
            s, n = self.songs.put_words(i, s.get("text"), str(sess.n), results, kept_offset(off))
            diag["aligned"] = n
            if off:
                diag["offset"] = int(round(off * 1000))
        diag["ms"] = int((time.time() - t_start) * 1000)
        if s is not None:
            s = self.songs.note_diag(i, diag) or s
        return s, diag

    def file(self, i, audio, src="file"):
        """A song's whole file, decoded (16 kHz float32, from 0 s)."""
        t_start = time.time()
        s = self.songs.load(i)
        if s is None:
            return None, None
        lines = s.get("lines") or []
        dur = len(audio) / RATE
        diag = {"rms": vocals.dbfs(audio), "peak": vocals.peak_dbfs(audio), "vocal": None, "asr": 0, "aligned": 0, "src": src}
        voice = self.voice_of(audio)
        if voice is not None:
            diag["vocal"] = vocals.dbfs(voice)
        heard = self.speech.heard(voice if voice is not None else audio, align.language_of(lines), 0.0, lines)
        diag["asr"] = len(heard)
        s = self.songs.add_words(i, heard, 0.0, dur, edges=False, kind="file", diag=diag)
        if s is None:
            return None, diag
        if voice is not None:
            # the whole song at once: a session of its own, its clock the file's
            sess = Session(i, s.get("session", 0))
            sess.voice, sess.mix, sess.t0, sess.heard = voice, audio, 0.0, heard
            results = self.place(s, sess)
            off = sess.offset() if s.get("given") else 0.0
            if off and abs(off) > 0.25:
                # (the file is another edit of the song than the given times': once more with the windows moved)
                sess.tried = {}
                again = self.place(s, sess)
                for k, r in again.items():
                    if k not in results or r["q"] > results[k]["q"]:
                        results[k] = r
            s, n = self.songs.put_words(i, s.get("text"), "f%d" % sess.n, results, kept_offset(off))
            diag["aligned"] = n
            if off:
                diag["offset"] = int(round(off * 1000))
        diag["ms"] = int((time.time() - t_start) * 1000)
        if s is not None:
            s = self.songs.note_diag(i, diag) or s
        return s, diag

    # ------------------------------------------------------------------------------------------ the words
    def place(self, s, sess):
        """The words of every line whose window the session's recent sound covers and that is not placed yet in it.
        Returns {line: placing} of the believed ones (times on the audio's clock)."""
        lines = s.get("lines") or []
        # (a song whose lines' times were given once is placed against them in any later play too)
        timed = bool(s.get("given")) and len(s["given"]) == len(lines)
        if timed:
            times = s["given"]
            ok = [t is not None for t in times]
        else:
            times = s.get("times") or [None] * len(lines)
            kinds = s.get("kinds") or [None] * len(lines)
            ok = [times[k] is not None and kinds[k] in ("anchor", "between") for k in range(len(lines))]
        if sess.voice is None or not any(ok):
            return {}
        off = (sess.offset() or 0.0) if timed else 0.0
        # (untimed: the lines' times come from the words heard, which may be another play's, on another clock)
        slack = NARROW if timed and sess.offset() is not None else wt.PRE
        out = {}
        a0, a1 = sess.t0, sess.t1
        heard_at = None if timed else s.get("first_heard")
        for k, line in enumerate(lines):
            if not ok[k] or not line.split():
                continue
            tried = sess.tried.get(k)
            if tried is not None and abs(tried - (times[k] + off)) < 0.5:
                continue
            earliest = heard_at[k] if heard_at and k < len(heard_at) else None
            a, b, need_a, need_b, by = wt.window(times, k, off, s.get("duration") or 0.0, slack, earliest)
            if need_a < a0 - 0.01 or need_b > a1 + 0.01:
                continue
            a, b = max(a, a0), min(b, a1)
            # in order: a line starts after the line before it (when that one was placed): the same text sung twice
            # in a row ("Pain!", "هلا هلا") would else be found where the first was sung
            before = max((x for x in sess.placed if x < k), default=None)
            if before is not None and sess.placed[before] + 0.05 > a:
                a = sess.placed[before] + 0.05
                if b - a < 1.0 or a >= by:
                    continue
            sess.tried[k] = times[k] + off
            r = self.aligner.line(sess.voice, sess.t0, line, a, b, by)
            if not believed(line, r, sess.heard):
                # the piece as sent, not the separated voice (a microphone's piece is sometimes heard better so);
                # the words' ends still from the voice
                r2 = self.aligner.line(sess.mix, sess.t0, line, a, b, by, voice=sess.voice)
                if r2 is not None and (r is None or r2["q"] > r["q"]):
                    r = r2
                    r["on"] = "mix"
                if not believed(line, r, sess.heard):
                    continue
            out[k] = r
            sess.placed[k] = max(x for x in r["s"] if x is not None)
            if timed and r["q"] >= SURE_Q:
                first = next((x for x in r["s"] if x is not None), None)
                if first is not None:
                    sess.deltas[k] = first - times[k]
        return out
