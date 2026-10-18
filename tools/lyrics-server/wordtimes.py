"""Each word's own time inside its line (1.16): the line's known text placed onto the singing heard around the line's
time, so the app lights a word only while it is really sung, and stops where the singer pauses.

How:
  * the voice alone (vocals.py), 16 kHz;
  * the MMS forced-alignment model (torchaudio's MMS_FA: wav2vec2 trained on 1,100+ languages written in Latin letters
    by uroman) says, for every 20 ms of the voice, how likely each letter is;
  * the line's words are written in those letters (uroman: Arabic letters into the Latin ones the model knows) and put
    in order onto the frames (the most likely path, "CTC forced alignment"); a wildcard ("star") before and after the
    line takes whatever is sung or played there, so the line's words are not stretched over the neighbours' singing;
  * a word starts at its first letter's frame and ends where its voice dies away: CTC puts a letter at the start of
    its sound, so a held note would else end where it began, and a pause would show too early;
  * a line whose letters are unlikely where they had to go (another text, no singing, the wrong place) gets nothing.

Times are song seconds. The tokens are the line's whitespace tokens (line.split()), as the app and words.tagged count
them; a token without letters (a dash, "♪") borrows a neighbour's time, a sung token the model cannot spell (a number
in an Arabic line) is the wildcard's.
"""
import re
import threading
import time
import unicodedata

import numpy as np

import gpu
import textnorm as tn

RATE = 16000
HOP = 320                # samples per frame of the model (20 ms)
LABELS = ("-", "a", "i", "e", "n", "o", "u", "t", "s", "r", "m", "k", "l", "d", "g", "h", "y", "b", "p", "w", "c",
          "v", "j", "z", "f", "'", "q", "x", "*")
INDEX = {c: i for i, c in enumerate(LABELS)}
BLANK = 0
STAR = INDEX["*"]

PRE = 1.2        # s before a line's time where its first word may be (the app's song clock is off by up to a second,
POST = 1.2       #   a source's line times by a few tenths) ... and after the next line's time for its last word
NEED = 0.4       # s of that margin that must have been heard before a line is aligned
MAX_LINE = 15.0  # s a line's window reaches at most (a line before a long instrumental part)
CONTEXT = 1.0    # s of sound on each side of a window given to the model (it hears a word better in its context)
MIN_Q = 0.30     # a line is believed when its letters' probability where they were placed is at least this (median)
END_DROP = 20.0  # dB under the word's loudest frame where its voice has died away (10-15 cut held notes short)
SILENT_RUN = 0.25  # s of silence inside a word's letters that means the singer stopped (a consonant is shorter)
PAUSE_S = 0.25   # (as words.PAUSE_S: a longer gap between two words is shown as a pause)


# ---------------------------------------------------------------------------------------------- letters
_DIGITS = str.maketrans("٠١٢٣٤٥٦٧٨٩۰۱۲۳۴۵۶۷۸۹", "01234567890123456789")
_ONES = ("zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen "
         "seventeen eighteen nineteen").split()
_TENS = ("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
_APOS = re.compile("[’‘`´ʼ]")
_RUNS = re.compile(r"(.)\1{2,}")
_NOT_LETTER = re.compile("[^a-z']")
_uroman = None
_uroman_lock = threading.Lock()


def spell_en(n):
    """A number in English words, joined ("24" -> "twentyfour"); the aligner hears "twenty four" either way."""
    if n < 20:
        return _ONES[n]
    if n < 100:
        return _TENS[n // 10] + (_ONES[n % 10] if n % 10 else "")
    if n < 1000:
        return _ONES[n // 100] + "hundred" + (spell_en(n % 100) if n % 100 else "")
    if n < 10000 and n % 100 and 1100 <= n < 2100:
        return spell_en(n // 100) + spell_en(n % 100)   # 1999: nineteen ninety-nine
    if n < 1000000:
        return spell_en(n // 1000) + "thousand" + (spell_en(n % 1000) if n % 1000 else "")
    return ""


def _romanizer():
    global _uroman
    with _uroman_lock:
        if _uroman is None:
            import uroman
            _uroman = uroman.Uroman()
        return _uroman


def roman(token, latin_line=True):
    """[token]'s letters as the model knows them ('a'-'z' and the apostrophe), '' when it has none. Numbers are spelt
    in English in a Latin line; Arabic (and any other script) goes through uroman as written: its own spelling is
    nearer the sound than a normalised one (ة and ى are sung "a", إ is "i")."""
    t = (token or "").translate(_DIGITS).replace("ـ", "")
    if latin_line:
        t = re.sub(r"\d+", lambda m: " " + spell_en(int(m.group(0)[:7])) + " ", t)
    latin = not any(ord(c) > 0x24F and c.isalpha() for c in t)
    if not latin:
        try:
            t = _romanizer().romanize_string(t)
        except Exception:
            return ""
    t = unicodedata.normalize("NFKD", _APOS.sub("'", t).lower())
    t = "".join(c for c in t if not unicodedata.combining(c))
    parts = [_NOT_LETTER.sub("", p) for p in re.split(r"[-–—/]", t)]
    if latin:
        # a sung "oh", "ooh", "yeah": the h is not heard, and a letter placed where it is not heard spoils the line
        parts = [p[:-1] if len(p) >= 2 and p[-1] == "h" and p[-2] in "aeiou" else p for p in parts]
    t = "".join(parts)
    t = _RUNS.sub(r"\1\1", t)      # "waaaaah", "oooh": a long sound, not seven letters
    return t.strip("'")


def plan(line):
    """The line's whitespace tokens, the letters looked for in each, and each one's kind: "word" (letters to place),
    "wild" (sung, but not spellable: the wildcard takes its time), "mark" (no letters: borrows a neighbour's time)."""
    toks = (line or "").split()
    latin = not any(ord(c) > 0x24F and c.isalpha() for c in line or "")
    letters, kinds = [], []
    for tok in toks:
        r = roman(tok, latin)
        if r:
            letters.append(r)
            kinds.append("word")
        else:
            letters.append("")
            kinds.append("wild" if any(c.isalnum() for c in tok) else "mark")
    return toks, letters, kinds


def targets(letters, kinds):
    """The model's labels to place for a line, with the token each comes from (-1: the wildcards at both ends)."""
    ids, owner = [STAR], [-1]
    for j, (l, k) in enumerate(zip(letters, kinds)):
        if k == "word":
            for c in l:
                ids.append(INDEX[c])
                owner.append(j)
        elif k == "wild":
            ids.append(STAR)
            owner.append(j)
    ids.append(STAR)
    owner.append(-1)
    return ids, owner


# ---------------------------------------------------------------------------------------------- the path
def viterbi(lp, ids, first_by=None):
    """CTC forced alignment: the most likely way the labels [ids] follow each other over the frames of [lp] (frames x
    labels, log probabilities; the wildcard's column is 0: it costs nothing). With [first_by] (a frame), the leading
    wildcard ([ids][0]) must have given way by then: the line's first word starts no later. Returns (per frame the
    index into [ids] or -1 for a blank frame, the path's log probability), or None when there are too few frames."""
    T = lp.shape[0]
    L = len(ids)
    need = L + sum(1 for a, b in zip(ids, ids[1:]) if a == b)
    if T < need or L == 0:
        return None
    S = 2 * L + 1
    ext = np.zeros(S, dtype=np.int64)
    ext[1::2] = ids
    em = lp[:, ext].astype(np.float64)
    if first_by is not None and L >= 2:
        # (the states before the first real label: the blank, the wildcard and the blank after it)
        cut = max(1, min(T, int(first_by) + 1))
        if T - cut < need - 1:
            return None
        em[cut:, 0:3] = -1e18
    skip = np.zeros(S, dtype=bool)
    skip[3::2] = ext[3::2] != ext[1:-2:2]
    neg = -1e18
    alpha = np.full(S, neg)
    alpha[0] = em[0, 0]
    alpha[1] = em[0, 1]
    back = np.zeros((T, S), dtype=np.int8)
    a1 = np.empty(S)
    a2 = np.empty(S)
    for t in range(1, T):
        a1[0] = neg
        a1[1:] = alpha[:-1]
        a2[:2] = neg
        a2[2:] = alpha[:-2]
        a2[~skip] = neg
        best = alpha.copy()
        b = np.zeros(S, dtype=np.int8)
        m = a1 > best
        best[m] = a1[m]
        b[m] = 1
        m = a2 > best
        best[m] = a2[m]
        b[m] = 2
        alpha = best + em[t]
        back[t] = b
    s = S - 1 if alpha[S - 1] >= alpha[S - 2] else S - 2
    total = float(alpha[s])
    path = np.empty(T, dtype=np.int64)
    for t in range(T - 1, -1, -1):
        path[t] = s
        s -= back[t, s]
    return np.where(path % 2 == 1, (path - 1) // 2, -1), total


def label_spans(tgt, n):
    """Per label index 0..n-1: (first frame, last frame + 1), or (-1, -1) when no frame holds it."""
    first = np.full(n, -1, dtype=np.int64)
    last = np.full(n, -1, dtype=np.int64)
    idx = np.flatnonzero(tgt >= 0)
    if len(idx):
        lab = tgt[idx]
        u, i0 = np.unique(lab, return_index=True)
        first[u] = idx[i0]
        u2, i1 = np.unique(lab[::-1], return_index=True)
        last[u2] = idx[len(idx) - 1 - i1] + 1
    return first, last


def frame_db(audio, frames):
    """The voice's loudness per model frame (dB, RMS of the frame's 20 ms)."""
    n = frames * HOP
    x = audio[:n]
    if len(x) < n:
        x = np.concatenate([x, np.zeros(n - len(x), dtype=np.float32)])
    r = np.sqrt(np.mean(np.square(x.reshape(frames, HOP), dtype=np.float64), axis=1))
    return 20.0 * np.log10(r + 1e-9)


# ---------------------------------------------------------------------------------------------- one line
def place(lp, t0, frame_s, line, db=None, first_by=None, plan_=None):
    """The words of [line] on the frames [lp] (log probabilities, the first frame at song time [t0], [frame_s] s
    each), with the voice's loudness per frame [db] for the words' ends; the first word starting by song time
    [first_by]. Returns {"s", "e", "q", ...} — per token start and end (song s) and how sure the placing is — or None
    when the line has nothing to place or no room."""
    toks, letters, kinds = plan_ or plan(line)
    if not toks or "word" not in kinds:
        return None
    ids, owner = targets(letters, kinds)
    by = None if first_by is None else int((first_by - t0) / frame_s)
    got = viterbi(lp, ids, by)
    if got is None:
        return None
    tgt, _ = got
    first, last = label_spans(tgt, len(ids))
    if (first[1:-1] < 0).any():
        return None
    T = lp.shape[0]
    owner = np.array(owner)
    # how sure: each letter's best probability over the frames it was given (a letter sung there is likely somewhere
    # in its frames; one forced onto music or onto another word is not)
    probs = []
    word_q = {}
    for i, j in enumerate(owner):
        if j < 0 or kinds[j] != "word":
            continue
        p = float(np.exp(lp[first[i]:last[i], ids[i]].max()))
        probs.append(p)
        word_q.setdefault(int(j), []).append(p)
    q = float(np.median(probs)) if probs else 0.0
    sf = {}
    ef = {}
    for j in range(len(toks)):
        mine = np.flatnonzero(owner == j)
        if len(mine):
            sf[j] = int(first[mine[0]])
            ef[j] = int(last[mine[-1]])
    order = sorted(sf)
    # the words' ends: on through the frames where the voice still sounds (a held note), up to the next word
    line_end = int(first[-1]) if first[-1] >= 0 else T   # (where the trailing wildcard took over)
    if db is not None and len(db) >= T:
        floor = float(np.percentile(db[:T], 10))
        gap = max(3, int(round(SILENT_RUN / frame_s)))
        for j in order:
            # A word's letters across a silence (the singer stopped): CTC may put a word's last letter after a pause,
            # just before the next word (or its first one before it), and the word would seem to last through the
            # pause. Of the pieces the silence cuts it into, the one with most of its letters is the word.
            s, e = sf[j], ef[j]
            if e - s <= gap:
                continue
            top = float(db[s:e].max())
            silent = db[s:e] < max(floor + 10.0, top - 35.0)
            runs = []
            i = 0
            while i < len(silent):
                if silent[i]:
                    i2 = i
                    while i2 < len(silent) and silent[i2]:
                        i2 += 1
                    if i2 - i >= gap:
                        runs.append((s + i, s + i2))
                    i = i2
                else:
                    i += 1
            if not runs:
                continue
            mine = [int(first[i]) for i in np.flatnonzero(owner == j)]
            edges = [s] + [x for r in runs for x in r] + [e]
            pieces = [(edges[i], edges[i + 1]) for i in range(0, len(edges), 2)]
            best = max(pieces, key=lambda p: sum(1 for f in mine if p[0] <= f < p[1]))
            inside = [f for f in mine if best[0] <= f < best[1]]
            if inside:
                sf[j] = min(inside)
                ef[j] = max(min(best[1], e), sf[j] + 1)
        for n_, j in enumerate(order):
            s, e = sf[j], ef[j]
            nxt = sf[order[n_ + 1]] if n_ + 1 < len(order) else min(T, line_end + int(round(4.0 / frame_s)))
            top = float(db[s:max(e, s + 1)].max())
            thr = max(top - END_DROP, floor + 3.0)
            f = e
            quiet = 0
            while f < nxt:
                if db[f] < thr:
                    quiet += 1
                    if quiet >= 3:
                        f -= 2
                        break
                else:
                    quiet = 0
                f += 1
            ef[j] = max(e, min(f, nxt))
        # a wildcard's word starts where its voice starts (the wildcard also took the pause before it)
        for j in order:
            if kinds[j] == "wild":
                s, e = sf[j], ef[j]
                if e > s:
                    top = float(db[s:e].max())
                    loud = np.flatnonzero(db[s:e] >= top - 12.0)
                    if len(loud):
                        sf[j] = s + int(loud[0])
    starts = [None] * len(toks)
    ends = [None] * len(toks)
    for j in order:
        starts[j] = round(t0 + sf[j] * frame_s, 3)
        ends[j] = round(t0 + ef[j] * frame_s, 3)
    # marks borrow a neighbour's time: the end of the word before, else the start of the word after
    for j, k in enumerate(kinds):
        if starts[j] is not None:
            continue
        prev = next((x for x in range(j - 1, -1, -1) if ends[x] is not None and kinds[x] != "mark"), None)
        if prev is not None:
            starts[j] = ends[j] = ends[prev]
        else:
            nxt = next((x for x in range(j + 1, len(toks)) if starts[x] is not None), None)
            starts[j] = ends[j] = starts[nxt] if nxt is not None else round(t0, 3)
    # never backwards (a mark after a held word, or two words sharing a frame)
    for j in range(1, len(starts)):
        if starts[j] < starts[j - 1]:
            starts[j] = starts[j - 1]
        if ends[j - 1] > starts[j] and kinds[j - 1] != "mark":
            ends[j - 1] = max(starts[j - 1], starts[j])
    wq = [round(float(np.median(word_q[j])), 3) if j in word_q else None for j in range(len(toks))]
    voiced = None
    if db is not None and len(db) >= T and order:
        a, b = sf[order[0]], max(ef[order[-1]], sf[order[0]] + 1)
        voiced = round(float(np.percentile(db[a:b], 75) - np.percentile(db[:T], 10)), 1)
    return {"s": starts, "e": ends, "q": round(q, 3), "wq": wq, "voiced": voiced}


def window(times, k, offset=0.0, duration=0.0, slack=PRE, earliest=None):
    """Where line [k]'s words may be (song s, on the audio's clock): from [slack] before its time (or before
    [earliest], where its words were first heard, when that is earlier) to [slack] after the next line's, at most
    MAX_LINE long. Returns (from, to, the part of it that must have been heard before the line is aligned (from, to),
    the time its first word must have started by)."""
    t = times[k]
    nxt = next((times[x] for x in range(k + 1, len(times)) if times[x] is not None and times[x] > t), None)
    end = min(nxt, t + MAX_LINE) if nxt is not None else t + MAX_LINE * 0.6
    if duration:
        end = min(end, duration + 1.0)
    t0 = t if earliest is None else min(t, max(earliest, t - MAX_LINE))
    a = t0 + offset - slack
    b = end + offset + slack
    return max(0.0, a), b, max(0.0, t0 + offset - NEED), end + offset + NEED, t + offset + slack


# ---------------------------------------------------------------------------------------------- repeats
REPEAT_SPAN = 0.10  # a repeated line's own span (to the next line) within this share of the heard line's: sung alike


def repeats(lines, times, wtl):
    """[wtl] (per line None or {"s", "e", "from", ...}) with the lines not heard themselves given the words of a line
    of the same text that was heard (a chorus sung again), moved to their own time, "from": "repeat" — when their own
    span (to the next line's time) is within REPEAT_SPAN of that line's: the same singing, so the first play of a song
    has word times for its later choruses. A line's own hearing replaces its repeat (the repeats are made afresh from
    the heard lines each time)."""
    if not wtl:
        return wtl
    n = len(lines)
    out = [None if (e and e.get("from") == "repeat") else e for e in list(wtl)[:n]]
    out += [None] * (n - len(out))
    times = list(times or []) + [None] * max(0, n - len(times or []))
    norm = [" ".join(tn.words(l)) for l in lines]
    span = []
    for k in range(n):
        t = times[k]
        nxt = next((times[x] for x in range(k + 1, n) if times[x] is not None and times[x] > (t or 0)), None)
        span.append(None if t is None or nxt is None else nxt - t)
    heard = {}
    for k in range(n):
        e = out[k]
        if e and e.get("from") == "heard" and times[k] is not None and span[k] and norm[k]:
            heard.setdefault(norm[k], []).append(k)
    for k in range(n):
        if out[k] or not norm[k] or times[k] is None or not span[k]:
            continue
        toks = len(lines[k].split())
        cands = [h for h in heard.get(norm[k], []) if len(lines[h].split()) == toks
                 and abs(span[k] - span[h]) <= REPEAT_SPAN * span[h]]
        if not cands:
            continue
        h = max(cands, key=lambda x: (out[x].get("q") or 0.0, -abs(x - k)))
        d = times[k] - times[h]
        e = out[h]
        out[k] = {"s": [round(x + d, 2) for x in e["s"]], "e": [x if x < 0 else round(x + d, 2) for x in e["e"]],
                  "from": "repeat", "of": h}
    return out


# ---------------------------------------------------------------------------------------------- the model
class Aligner:
    """The MMS forced-alignment model (wav2vec2, 300M), loaded at its first use and kept: in float16 on the card
    (about 1.5 GB while working), float32 on the processor."""

    def __init__(self, log=print):
        self.models = {}
        self.lock = threading.Lock()
        self.log = log

    def model(self, dev):
        with self.lock:
            m = self.models.get(dev)
            if m is None:
                t0 = time.time()
                m = self._load()
                if dev == "cuda":
                    m.to(dev)
                else:
                    m.float()
                m.eval()
                self.models[dev] = m
                self.log("  word aligner MMS_FA ready on %s in %.1f s" % (dev, time.time() - t0))
            return m

    @staticmethod
    def _load():
        """The model (the inner wav2vec2: its logits, the softmax is taken here in float32) with float16 weights read
        straight from a file into a model built without memory of its own. torchaudio's own way (get_model) holds the
        1.2 GB download, the float32 model and a float16 copy at once: 3.8 GB of the PC's memory on the way, which the
        workshop PC did not have to spare (on Windows the card's memory is charged to the process as well). The
        float16 file (0.6 GB) is made once from the download, beside it in TORCH_HOME."""
        torch = gpu.torch()
        import os
        import torchaudio
        from torchaudio.pipelines._wav2vec2 import utils as w2v
        bundle = torchaudio.pipelines.MMS_FA
        if tuple(bundle.get_labels()) != LABELS:
            raise RuntimeError("MMS_FA labels changed: %s" % (bundle.get_labels(),))
        hub = os.path.join(torch.hub.get_dir(), "checkpoints")
        half = os.path.join(hub, "mms_fa_uroman_fp16.pt")
        if not os.path.isfile(half):
            full = os.path.join(hub, os.path.basename(bundle._path))
            if os.path.isfile(full):
                sd = torch.load(full, map_location="cpu", mmap=True, weights_only=True)
                if bundle._remove_aux_axis:
                    w2v._remove_aux_axes(sd, bundle._remove_aux_axis)
            else:
                sd = w2v._get_state_dict(bundle._path, None, bundle._remove_aux_axis)   # (downloads it, 1.2 GB)
            sd = {k: (v.half() if v.is_floating_point() else v) for k, v in sd.items()}
            os.makedirs(hub, exist_ok=True)
            torch.save(sd, half + ".tmp")
            os.replace(half + ".tmp", half)
            del sd
        sd = torch.load(half, map_location="cpu", mmap=True, weights_only=True)
        with torch.device("meta"):
            m = w2v._get_model(bundle._model_type, bundle._params)
        m.load_state_dict(sd, assign=True)
        return m

    def emissions(self, audio):
        """Per 20 ms frame of [audio] (16 kHz mono float32), the log probability of every label (the wildcard's 0)."""
        if gpu.torch() is None:
            raise RuntimeError("torch is not installed")
        dev = gpu.device()
        try:
            return self._run(audio, dev)
        except Exception as e:
            if dev != "cuda" or not gpu.is_oom(e):
                raise
            gpu.release()
            self.log("  the graphics card is full: these words are placed on the processor")
            return self._run(audio, "cpu")

    def _run(self, audio, dev):
        torch = gpu.torch()
        m = self.model(dev)
        with torch.inference_mode():
            x = torch.from_numpy(np.ascontiguousarray(audio, dtype=np.float32)).to(dev)[None]
            x = torch.nn.functional.layer_norm(x, x.shape)       # (as the bundle's own wrapper)
            x = x.to(next(m.parameters()).dtype)
            logits, _ = m(x)
            lp = torch.log_softmax(logits[0].float(), dim=-1)
            out = lp.cpu().numpy()
        star = np.zeros((out.shape[0], 1), dtype=np.float32)
        return np.concatenate([out, star], axis=1)

    def line(self, sound, v0, line, a, b, first_by=None, voice=None):
        """Line [line]'s words in [sound] (16 kHz, its first sample at song time [v0]: the separated voice, or the
        piece as sent) between song times [a] and [b], the first word started by [first_by]; the words' ends from the
        loudness of [voice] (the separated voice, as long as [sound]; [sound] itself when not given). The model hears
        CONTEXT s more on each side; the path only sees [a, b]."""
        n = len(sound)
        ca = max(0, int((a - CONTEXT - v0) * RATE))
        cb = min(n, int((b + CONTEXT - v0) * RATE))
        if cb - ca < RATE:
            return None
        piece = sound[ca:cb]
        lp = self.emissions(piece)
        frame_s = len(piece) / RATE / lp.shape[0]
        f0 = max(0, int(round((a - v0 - ca / RATE) / frame_s)))
        f1 = min(lp.shape[0], int(round((b - v0 - ca / RATE) / frame_s)))
        if f1 - f0 < 10:
            return None
        db = frame_db(piece if voice is None else voice[ca:cb], lp.shape[0])
        return place(lp[f0:f1], v0 + ca / RATE + f0 * frame_s, frame_s, line, db[f0:f1], first_by)
