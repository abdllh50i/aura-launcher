"""Plain lyrics made into timed ones by listening to the song.

The sound comes from the car, never from a streaming service: what the car's microphone hears while a Bluetooth song
plays (chunks of 15-30 s, each with the song's position at its start from the app's song clock), or a song file from the
unit's own storage. faster-whisper (CTranslate2, on this PC's CPU) writes down the words it hears with their times; the
audio is deleted as soon as it is decoded. The words are kept per song (not the sound), merged chunk by chunk, and
matched against the lyric lines with a tolerant alignment: Arabic in one spelling (alef forms, ة/ه, ى/ي, no marks, the
و ف ب ل ك ال prefixes that lyrics and a recogniser join or split differently), Latin without case or punctuation. A line
gets the time of its first matched words; lines between two timed ones are spread by their words; the rest are guessed
at the song's own pace. The result is kept on disk, so the next play is timed at once.
"""
import hashlib
import json
import os
import re
import statistics
import threading
import time

import numpy as np
from rapidfuzz import fuzz
from rapidfuzz.process import cdist

import textnorm as tn

RATE = 16000
EDGE = 1.0          # s: the ends of a chunk are heard worse (a word cut in two): a neighbour's middle is believed there
SHOW_AT = 0.7       # how much of a song must be timed before the app shows the timing
DONE_AT = 0.9       # ...and before it stops listening
DEFAULT_WPS = 0.42  # s a sung word takes, until the song itself tells


# ---------------------------------------------------------------------------------------------- the recogniser
class Transcriber:
    """faster-whisper, loaded at its first use (a few seconds) and kept."""

    def __init__(self, model, threads, beam=5):
        self.name = model
        self.threads = threads
        self.beam = beam
        self.model = None
        self.lock = threading.Lock()

    def load(self):
        with self.lock:
            if self.model is None:
                from faster_whisper import WhisperModel
                t0 = time.time()
                self.model = WhisperModel(self.name, device="cpu", compute_type="int8", cpu_threads=self.threads)
                print("  speech model %s ready in %.1f s" % (self.name, time.time() - t0), flush=True)
        return self.model

    def words(self, audio, lang=None, offset=0.0, hotwords=None):
        """[(text, start, end, probability)] of what is sung in [audio] (float32, 16 kHz), times from [offset]."""
        model = self.load()
        segs, info = model.transcribe(
            audio, language=lang, task="transcribe", beam_size=self.beam, word_timestamps=True,
            condition_on_previous_text=False, hotwords=hotwords, vad_filter=True,
            vad_parameters={"threshold": 0.3, "min_silence_duration_ms": 700, "speech_pad_ms": 300},
            hallucination_silence_threshold=2.0, no_speech_threshold=0.6, temperature=0.0)
        out = []
        for s in segs:
            for w in s.words or []:
                text = w.word.strip()
                if text:
                    out.append((text, round(offset + w.start, 3), round(offset + w.end, 3), round(float(w.probability), 3)))
        return out, info

    def heard(self, audio, lang, offset, lines):
        """[words] with the recogniser's known failures dealt with. A piece of singing sometimes comes back as one of
        the phrases it learnt from video subtitles ("اشتركوا في القناة", "Thanks for watching") and nothing else: when
        what it wrote is such a phrase, or far too little for the speech it found, it is asked once more with the
        lyrics' own words as hot words (a light bias: the whole lyrics as a prompt made it write lines it had not
        heard, at made-up times)."""
        words, info = self.words(audio, lang, offset)
        words = clean_words(words)
        speech = float(getattr(info, "duration_after_vad", 0) or 0)
        if lines and (hallucinated(words) or (speech >= 6.0 and len(words) < speech * 0.35)):
            again, _ = self.words(audio, lang, offset, hotwords=hot_words(lines))
            again = clean_words(again)
            if len(again) > len(words):
                words = again
        return [w for w in words if not _is_junk(w[0])]


# ---------------------------------------------------------------------------------------------- what is not singing
_HALLUCINATIONS = [tn.words(p) for p in (
    "اشتركوا في القناة", "اشترك في القناة", "لا تنسوا الاشتراك في القناة", "شكرا للمشاهدة", "شكرا على المشاهدة",
    "ترجمة نانسي قنقر", "نانسي قنقر", "thanks for watching", "thank you for watching",
    "please subscribe", "subscribe to my channel", "like and subscribe", "subtitles by the amara org community",
    "amara org", "transcribed by")]


def _is_junk(text):
    return not tn.word_norm(text) or text.strip() in ("♪", "♫", "...", "…")


def hallucinated(words):
    """The words heard are (mostly) one of the recogniser's stock phrases."""
    got = [tn.word_norm(w[0]) for w in words if tn.word_norm(w[0])]
    if not got:
        return False
    for p in _HALLUCINATIONS:
        if len(p) >= 2 and len(got) <= len(p) + 2 and " ".join(p) in " ".join(got):
            return True
    return False


def clean_words(words):
    """Without the stock phrases, and without words crammed into no time at all (text the recogniser wrote without
    hearing it: three or more in a row, each shorter than 60 ms)."""
    out = list(words)
    norm = [tn.word_norm(w[0]) for w in out]
    drop = set()
    for p in _HALLUCINATIONS:
        if len(p) < 2:
            continue
        for i in range(len(norm) - len(p) + 1):
            if norm[i:i + len(p)] == p:
                drop.update(range(i, i + len(p)))
    run = []
    for i, w in enumerate(out + [("", 1e9, 1e9 + 1, 0)]):
        if i < len(out) and w[2] - w[1] < 0.06:
            run.append(i)
            continue
        if len(run) >= 3:
            drop.update(run)
        run = []
    return [w for i, w in enumerate(out) if i not in drop]


def hot_words(lines, limit=120):
    """The lyrics' own words for the recogniser to expect: the distinctive ones first (longest, most sung)."""
    count = {}
    first = {}
    for line in lines:
        for raw in line.split():
            w = raw.strip(".,!?؟،؛:\"'()[]")
            n = tn.word_norm(w)
            if len(n) < 3:
                continue
            count[n] = count.get(n, 0) + 1
            first.setdefault(n, w)
    ranked = sorted(count, key=lambda n: (-count[n] * len(n), n))
    out = []
    size = 0
    for n in ranked:
        w = first[n]
        if size + len(w) + 1 > limit:
            break
        out.append(w)
        size += len(w) + 1
    return " ".join(out) or None


def language_of(lines):
    """The lyrics' language for the recogniser: Arabic or English by their letters, else left to it."""
    text = " ".join(lines)
    letters = [c for c in text if c.isalpha()]
    if not letters:
        return None
    ar = sum(1 for c in letters if "؀" <= c <= "ۿ")
    if ar >= 0.3 * len(letters):
        return "ar"
    if all(c.isascii() for c in letters):
        return "en"
    return None


# ---------------------------------------------------------------------------------------------- words merged
def merge(old, new, t0, t1, edges=True):
    """The words heard in [t0, t1] (a new chunk) merged with those kept: the new chunk is believed in its middle,
    its first and last second only where nothing was heard before (another chunk's middle covers them)."""
    m0 = EDGE if (edges and t0 > 0.5) else 0.0
    m1 = EDGE if edges else 0.0
    a, b = t0 + m0, t1 - m1

    def mid(w):
        return (w[1] + w[2]) / 2

    kept = [w for w in old if not (a <= mid(w) <= b)]
    left = any(t0 <= mid(w) < a for w in kept)
    right = any(b < mid(w) <= t1 for w in kept)
    for w in new:
        m = mid(w)
        if a <= m <= b or (m < a and not left) or (m > b and not right):
            kept.append(w)
    kept.sort(key=lambda w: w[1])
    return kept


# ---------------------------------------------------------------------------------------------- the alignment
def _sim_matrix(rec, lyr):
    """Similarity of every heard word with every lyric word, 0..1 (by the whole word or without its prefixes)."""
    rn = [w[0] for w in rec]
    ln = [w[0] for w in lyr]
    a = cdist(rn, ln, scorer=fuzz.ratio, dtype=np.float32) / 100.0
    rs = [tn.word_stem(w) for w in rn]
    ls = [tn.word_stem(w) for w in ln]
    b = cdist(rs, ls, scorer=fuzz.ratio, dtype=np.float32) / 100.0
    s = np.maximum(a, b * 0.97)
    # very short words count only when they are the same word (and then little: "يا", "la", "I" are everywhere)
    rl = np.array([len(w) for w in rn])[:, None]
    ll = np.array([len(w) for w in ln])[None, :]
    short = np.minimum(rl, ll) <= 2
    s[short & (a < 1.0)] = 0.0
    return s


def align(lines, heard, duration=0.0):
    """Times for [lines] from the words [heard] ([(text, start, end, prob)], song time in s).

    Returns (times, kinds, covered): a time (s) for every line, how each was found ("anchor": its own words heard,
    "between": spread between two anchored lines, "guess": before the first / after the last at the song's pace;
    None for all when nothing could be matched), and the share of the sung lines that are anchored or between."""
    lyr = []  # (word, line, position in line)
    for k, line in enumerate(lines):
        for p, w in enumerate(tn.words(line)):
            lyr.append((w, k, p))
    rec = [(tn.word_norm(t), s, e, pr) for (t, s, e, pr) in heard if tn.word_norm(t)]
    n_lines = len(lines)
    sung = [k for k, line in enumerate(lines) if tn.words(line)]
    if not lyr or not rec or not sung:
        return [None] * n_lines, [None] * n_lines, 0.0
    m, n = len(rec), len(lyr)
    sim = _sim_matrix(rec, lyr)

    # scores: a strong match of a long word counts most; a position far from where the song's time says it should be
    # costs a little (it breaks ties between two choruses, nothing more)
    lens = np.array([len(w[0]) for w in lyr], dtype=np.float32)
    weight = np.clip(0.5 + (lens - 2) * 0.25, 0.5, 1.5)[None, :]
    score = np.where(sim >= 0.8, weight * (1.0 + 2.5 * (sim - 0.8)), np.where(sim >= 0.6, 0.25, -0.5)).astype(np.float32)
    if duration and duration > 0:
        # (mild: songs have intros and outros of their own lengths, and a length may be another recording's)
        tf = np.array([min(1.0, w[1] / duration) for w in rec], dtype=np.float32)[:, None]
        lf = (np.arange(n, dtype=np.float32) / max(1, n - 1))[None, :]
        off = np.abs(tf - lf)
        score = score - np.minimum(0.8, np.maximum(0.0, off - 0.25) * 3.0) * (sim >= 0.6)
    ins = np.array([-0.4 * max(0.3, w[3]) for w in rec], dtype=np.float32)  # a heard word left out (unsure ones cheaply)
    # a lyric word left out: cheap where the singing paused for long (an instrumental part, or a part not listened to),
    # free before the first and after the last heard word
    dele = np.full(m + 1, -0.35, dtype=np.float32)
    dele[0] = 0.0
    dele[m] = 0.0
    for i in range(1, m):
        if rec[i][1] - rec[i - 1][2] > 3.0:
            dele[i] = -0.03

    S = np.zeros((m + 1, n + 1), dtype=np.float32)
    B = np.zeros((m + 1, n + 1), dtype=np.int8)  # 0 diagonal, 1 heard word left out, 2 lyric word left out
    for i in range(1, m + 1):
        S[i, 0] = S[i - 1, 0] + ins[i - 1]
        B[i, 0] = 1
    B[0, 1:] = 2
    for i in range(1, m + 1):
        prev = S[i - 1]
        row = S[i]
        brow = B[i]
        sc = score[i - 1]
        d = float(dele[i])
        up = prev + ins[i - 1]
        diag = prev[:-1] + sc
        best = np.maximum(diag, up[1:])
        choice = np.where(diag >= up[1:], 0, 1).astype(np.int8)
        # (the left moves depend on the row itself: a short Python loop over the row)
        r0 = row[0]
        bl = best.tolist()
        cl = choice.tolist()
        out = [0.0] * n
        outb = [0] * n
        left = r0
        for j in range(n):
            v = bl[j]
            c = cl[j]
            lv = left + d
            if lv > v:
                v = lv
                c = 2
            out[j] = v
            outb[j] = c
            left = v
        row[1:] = out
        brow[1:] = outb
    # the end: every heard word used, any lyric word after the last left out for free
    j = int(np.argmax(S[m]))
    i = m
    pairs = []
    while i > 0 and j > 0:
        c = B[i, j]
        if c == 0:
            pairs.append((i - 1, j - 1, float(sim[i - 1, j - 1])))
            i -= 1
            j -= 1
        elif c == 1:
            i -= 1
        else:
            j -= 1
    pairs.reverse()
    return _time_lines(lines, lyr, rec, pairs, duration)


def _time_lines(lines, lyr, rec, pairs, duration):
    n_lines = len(lines)
    nwords = [len(tn.words(l)) for l in lines]
    strong = [(lyr[j][1], lyr[j][2], rec[i][1], len(lyr[j][0])) for i, j, s in pairs if s >= 0.8 and len(lyr[j][0]) >= 2]
    if not strong:
        return [None] * n_lines, [None] * n_lines, 0.0
    per_line = {}
    for k, p, t, ln in strong:
        per_line.setdefault(k, []).append((p, t, ln))
    # the song's pace: seconds between two matched words of one line, per word
    steps = []
    for k, ws in per_line.items():
        ws = sorted(ws)
        for (p1, t1, _), (p2, t2, _) in zip(ws, ws[1:]):
            if p2 > p1 and t2 > t1:
                steps.append((t2 - t1) / (p2 - p1))
    wps = min(0.9, max(0.2, statistics.median(steps))) if len(steps) >= 3 else DEFAULT_WPS
    anchors = {}
    for k, ws in per_line.items():
        ws = sorted(ws)
        long_words = [w for w in ws if w[2] >= 4]
        enough = len(ws) >= 2 or (long_words and long_words[0][0] <= 2) or (nwords[k] <= 2 and ws[0][2] >= 3)
        if not enough:
            continue
        # The middle of what the heard words say, each moved back by the song's pace. Not the first word's own start
        # even when it was heard: the recogniser stretches a line's first word back over the pause before it (tried:
        # the mean error grew from 0.15-0.5 s to 0.3-0.85 s on the test songs).
        starts = sorted(t - p * wps for p, t, _ in ws)
        anchors[k] = (max(0.0, starts[len(starts) // 2] if len(starts) > 2 else starts[0]), len(ws))
    # in order: a line sung later than the next one's start is wrong somewhere; keep the heaviest increasing run
    ks = sorted(anchors)
    best = []  # longest (by weight) increasing chain, O(k²) over a song's few dozen lines
    score = []
    prev = []
    for a, k in enumerate(ks):
        t, w = anchors[k]
        score.append(w)
        prev.append(-1)
        for b in range(a):
            kb = ks[b]
            if anchors[kb][0] + 0.3 <= t and score[b] + w > score[a]:
                score[a] = score[b] + w
                prev[a] = b
    if score:
        a = max(range(len(ks)), key=lambda x: score[x])
        while a >= 0:
            best.append(ks[a])
            a = prev[a]
        best.reverse()
    # A line sung twice in a row is often written down once (the recogniser drops a repeat), and the alignment, free
    # to leave lyrics out before the first word heard, then gives the one heard to the second of the two: the first
    # line's own time went to the second and the first was guessed seconds early. The one heard is the first of them.
    texts = [" ".join(tn.words(l)) for l in lines]
    for idx, k in enumerate(best):
        p = k - 1
        while p >= 0 and nwords[p] == 0:
            p -= 1
        floor = best[idx - 1] if idx > 0 else -1
        first = k
        while p > floor and p not in anchors and texts[p] and _alike(texts[p], texts[first]):
            first = p
            p -= 1
            while p >= 0 and nwords[p] == 0:
                p -= 1
        if first != k:
            anchors[first] = anchors.pop(k)
            best[idx] = first
    times = [None] * n_lines
    kinds = [None] * n_lines
    for k in best:
        times[k] = anchors[k][0]
        kinds[k] = "anchor"
    sung = [k for k in range(n_lines) if nwords[k] > 0]
    # between two anchored lines: spread by their words (a stanza break takes the slack when there is more time than
    # the words need)
    for a, b in zip(best, best[1:]):
        inner = [k for k in range(a + 1, b) if nwords[k] > 0]
        if not inner:
            continue
        span = times[b] - times[a]
        need = sum(nwords[k] for k in range(a, b)) * wps
        blank = next((k for k in range(a + 1, b) if nwords[k] == 0), None)
        if need < span * 0.7 and blank is not None:
            t = times[a]
            for k in range(a + 1, blank):
                t += nwords[k - 1] * wps
                if nwords[k]:
                    times[k] = t; kinds[k] = "between"
            t = times[b]
            for k in range(b - 1, blank, -1):
                t -= nwords[k] * wps
                if nwords[k]:
                    times[k] = t; kinds[k] = "between"
        else:
            total = sum(nwords[k] for k in range(a, b))
            acc = 0
            for k in range(a + 1, b):
                acc += nwords[k - 1]
                if nwords[k]:
                    times[k] = times[a] + span * acc / total
                    kinds[k] = "between"
    # before the first and after the last anchored line: at the pace from one timed line to the next (pauses between
    # lines included; a word's own length alone made the guesses run twice too fast)
    gaps = []
    for a, b in zip(best, best[1:]):
        between = [k for k in range(a + 1, b) if nwords[k]]
        if not between and nwords[a] and times[b] > times[a]:
            gaps.append((times[b] - times[a]) / nwords[a])
    line_pace = min(1.5, max(0.25, statistics.median(gaps))) if len(gaps) >= 2 else wps * 1.6
    first, last = best[0], best[-1]
    t = times[first]
    for k in range(first - 1, -1, -1):
        if nwords[k]:
            t -= nwords[k] * line_pace
            times[k] = max(0.0, t)
            kinds[k] = "guess"
    t = times[last]
    for k in range(last + 1, n_lines):
        t += nwords[k - 1] * line_pace
        if nwords[k]:
            times[k] = min(t, duration) if duration else t
            kinds[k] = "guess"
    # empty lines (stanza breaks): when the line before has been sung
    for k in range(n_lines):
        if nwords[k] == 0:
            before = next((x for x in range(k - 1, -1, -1) if times[x] is not None and nwords[x]), None)
            after = next((x for x in range(k + 1, n_lines) if times[x] is not None), None)
            if before is None:
                times[k] = 0.0
            else:
                t = times[before] + nwords[before] * wps + 0.4
                if after is not None:
                    t = min(t, times[after] - 0.2)
                times[k] = max(times[before], t)
            kinds[k] = kinds[before] if before is not None else "guess"
    # never backwards
    last_t = 0.0
    for k in range(n_lines):
        times[k] = max(last_t, times[k])
        last_t = times[k]
    covered = sum(1 for k in sung if kinds[k] in ("anchor", "between")) / len(sung)
    return times, kinds, covered


def _alike(a, b):
    return a == b or fuzz.ratio(a, b) >= 85


def lrc(lines, times):
    out = []
    for line, t in zip(lines, times):
        t = max(0.0, t or 0.0)
        out.append("[%02d:%05.2f]%s" % (int(t // 60), t - 60 * int(t // 60), line))
    return "\n".join(out)


# ---------------------------------------------------------------------------------------------- per song, on disk
_ID = re.compile(r"^[0-9a-f]{16}$")


def song_id(title, artist):
    s = "%s|%s" % (tn.norm(tn.clean(title)), tn.norm(artist))
    return hashlib.sha1(s.encode("utf-8")).hexdigest()[:16]


class Songs:
    """The words heard of every song (never its sound), and the timing made of them ([home]/songs/<id>.json)."""

    def __init__(self, home):
        self.dir = os.path.join(home, "songs")
        os.makedirs(self.dir, exist_ok=True)
        self.lock = threading.RLock()

    @staticmethod
    def valid(i):
        return bool(i and _ID.match(i))

    def path(self, i):
        return os.path.join(self.dir, i + ".json")

    def load(self, i):
        if not self.valid(i):
            return None
        try:
            with open(self.path(i), encoding="utf-8") as f:
                return json.load(f)
        except (OSError, ValueError):
            return None

    def save(self, s):
        s["updated"] = time.time()
        p = self.path(s["id"])
        tmp = p + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(s, f, ensure_ascii=False)
        os.replace(tmp, p)

    def start(self, title, artist, duration, lines):
        """The song's record with these lyric lines (a new text is timed again from the words already heard)."""
        i = song_id(title, artist)
        with self.lock:
            s = self.load(i) or {"id": i, "title": title, "artist": artist, "words": [], "chunks": [], "times": None,
                                 "kinds": None, "covered": 0.0, "lines": []}
            if duration:
                s["duration"] = duration
            text_hash = hashlib.sha1("\n".join(lines).encode("utf-8")).hexdigest()
            if lines and s.get("text") != text_hash:
                s["lines"] = lines
                s["text"] = text_hash
                self.realign(s)
            self.save(s)
            return s

    def realign(self, s):
        times, kinds, covered = align(s.get("lines") or [], s.get("words") or [], s.get("duration") or 0)
        s["times"] = [None if t is None else round(t, 2) for t in times]
        s["kinds"] = kinds
        s["covered"] = round(covered, 3)

    def add_words(self, i, words, t0, t1, edges, kind):
        with self.lock:
            s = self.load(i)
            if s is None:
                return None
            s["words"] = merge(s.get("words") or [], words, t0, t1, edges)
            s["chunks"] = (s.get("chunks") or [])[-200:] + [{"t0": round(t0, 2), "t1": round(t1, 2), "kind": kind, "at": int(time.time()), "heard": len(words)}]
            self.realign(s)
            self.save(s)
            return s

    @staticmethod
    def state(s, pending=0):
        """What the app is told: the timed lines (when there are any), how much of the song they cover, and whether
        listening can stop: nearly everything timed, or most of it with no line still to come only guessed (the
        guesses after the last line heard were seconds off: listening on to the song's end times them)."""
        lines = s.get("lines") or []
        times = s.get("times")
        kinds = s.get("kinds") or []
        covered = s.get("covered", 0.0)
        has = bool(times) and any(t is not None for t in times)
        last = max((k for k, kd in enumerate(kinds) if kd in ("anchor", "between")), default=-1)
        ahead = sum(1 for k in range(last + 1, len(kinds)) if kinds[k] == "guess" and tn.words(lines[k]))
        done = covered >= 0.97 or (covered >= DONE_AT and ahead == 0)
        return {"ok": True, "id": s["id"], "lines": len(lines), "covered": covered,
                "show": covered >= SHOW_AT, "done": done, "ahead": ahead,
                "times": [None if t is None else int(t * 1000) for t in times] if has else None,
                "lrc": lrc(lines, times) if has else None,
                "heard": len(s.get("words") or []), "chunks": len(s.get("chunks") or []), "pending": pending}
