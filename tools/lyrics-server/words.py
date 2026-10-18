"""Word times inside timed lines, for the app's word-by-word lyrics (1.15): an "enhanced" LRC (the A2 extension), each
word preceded by its start, `[00:23.83]<00:23.83>مافيش <00:24.40>حاجة <00:24.95>تستاهل <00:26.10>` (the last tag: where
the line's singing ends). The words are the line's whitespace-separated tokens, so removing the tags and joining them by
single spaces gives the line's text back.

Two kinds:
  * "heard": a timing made by listening (align.py) knows when each matched word was sung; the words between are spread by
    their lengths;
  * "estimated": for lyrics timed line by line (YouTube Music, LRCLIB, Megalobiz, NetEase), the line's words are spread
    over the time it is sung: letters weigh (a long word takes longer), and a line sung quickly before a pause does not
    stretch over the pause. The app estimates with the very same rule when the server is not asked (LRCLIB's timed
    lyrics fetched by the unit itself): keep them in step (music/LyricsWords in the app).
"""
import re

import textnorm as tn

_LETTER = re.compile(r"[^\W_]", re.UNICODE)
_TIME_TAG = re.compile(r"\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]")
LAST_LINE_S = 6.0      # the last line: sung over at most this long
PER_WEIGHT_S = 0.16    # seconds a unit of weight takes to sing (a five-letter word: about 0.6 s)
MIN_LINE_S = 0.5
GAP_S = 0.15           # the singing of a line ends this much before the next line at the latest


def weight(token):
    """How long a word takes to sing, in units: its letters (and digits), a little more than linearly short ones."""
    return max(1, len(_LETTER.findall(token))) ** 0.8 + 0.6


def parse_lrc(text):
    """[(seconds, line)] of an LRC (metadata lines skipped, a line with several times kept at each, in time order)."""
    out = []
    for raw in (text or "").splitlines():
        times = []
        rest = raw
        while True:
            m = _TIME_TAG.match(rest.lstrip())
            if not m:
                break
            frac = m.group(3) or "0"
            times.append(int(m.group(1)) * 60 + int(m.group(2)) + int(frac) / (10 ** len(frac)))
            rest = rest.lstrip()[m.end():]
        if not times:
            continue
        line = re.sub(r"<\d{1,3}:\d{2}(?:[.:]\d{1,3})?>", "", rest).strip()  # (word tags of an enhanced source)
        line = " ".join(line.split())
        for t in times:
            out.append((t, line))
    out.sort(key=lambda x: x[0])
    return out


def _next_start(times, k):
    t = times[k]
    for j in range(k + 1, len(times)):
        if times[j] is not None and times[j] > t:
            return times[j]
    return t + LAST_LINE_S


def estimate(lines, times):
    """Per line (tokens, word starts, end) — or None for an untimed or empty line — spread by the words' weights."""
    out = []
    for k, line in enumerate(lines):
        toks = (line or "").split()
        t = times[k]
        if t is None or not toks:
            out.append(None)
            continue
        g = [weight(x) for x in toks]
        total = sum(g)
        room = _next_start(times, k) - t - GAP_S
        sing = max(0.3, min(max(MIN_LINE_S, PER_WEIGHT_S * total), room))
        starts, acc = [], 0.0
        for x in g:
            starts.append(t + sing * acc / total)
            acc += x
        out.append((toks, starts, t + sing))
    return out


def from_heard(lines, times, word_at):
    """Like [estimate], with the words the listening matched at their heard times: [word_at] is
    [[line, word position (as textnorm.words counts them), start, end], ...]. The tokens between two heard ones are
    spread between them by weight; the line's own time stays the first word's start bound."""
    est = estimate(lines, times)
    heard = {}
    for k, p, s, e in word_at or []:
        heard.setdefault(int(k), {})[int(p)] = (float(s), float(e))
    out = []
    for k, w in enumerate(est):
        if w is None or k not in heard:
            out.append(w)
            continue
        toks, starts, end = w
        # which textnorm word positions each display token holds
        known = {}
        pos = 0
        for j, tok in enumerate(toks):
            n = len(tn.words(tok))
            for p in range(pos, pos + n):
                if p in heard[k]:
                    known.setdefault(j, heard[k][p])
            pos += n
        if not known:
            out.append(w)
            continue
        t0 = times[k]
        limit = _next_start(times, k) - GAP_S
        s2 = list(starts)
        for j, (s, _) in known.items():
            s2[j] = min(max(s, t0), limit)
        # the end: the last heard word's end when the last token was heard, else the estimate (kept after the starts)
        last = max(known)
        end2 = max(end, known[last][1]) if last == len(toks) - 1 else end
        end2 = min(max(end2, s2[-1] + 0.15), limit + GAP_S)
        # The others: time is taken as running evenly over the words' weight between the known points (the line's
        # start, each heard word's start, the line's end): a word's start is read off that piecewise-linear line.
        g = [weight(x) for x in toks]
        cum = [0.0]
        for x in g:
            cum.append(cum[-1] + x)
        pts = {} if 0 in known else {0.0: t0}
        for j in known:
            pts[cum[j]] = s2[j]
        pts[cum[-1]] = max(end2, max(pts.values()) + 0.1)
        xs = sorted(pts)
        for j in range(len(toks)):
            if j in known:
                continue
            c = cum[j]
            lo = max((x for x in xs if x <= c), default=xs[0])
            hi = min((x for x in xs if x > c), default=xs[-1])
            s2[j] = pts[lo] if hi == lo else pts[lo] + (pts[hi] - pts[lo]) * (c - lo) / (hi - lo)
        # never backwards
        for j in range(1, len(s2)):
            s2[j] = max(s2[j], s2[j - 1] + 0.02)
        out.append((toks, s2, max(end2, s2[-1] + 0.1)))
    return out


def _tag(t, brackets="<>"):
    t = max(0.0, t)
    m = int(t // 60)
    return "%s%02d:%05.2f%s" % (brackets[0], m, t - 60 * m, brackets[1])


def enhanced(lines, times, words):
    """The enhanced LRC of [lines] at [times] with [words] (from [estimate] / [from_heard])."""
    out = []
    for line, t, w in zip(lines, times, words):
        if t is None:
            continue
        head = _tag(t, "[]")
        if not w:
            out.append(head + (line or ""))
            continue
        toks, starts, end = w
        out.append(head + " ".join(_tag(s) + x for s, x in zip(starts, toks)) + " " + _tag(end))
    return "\n".join(out)


def enhance_lrc(lrc):
    """An LRC timed line by line, with estimated word times."""
    pairs = parse_lrc(lrc)
    if not pairs:
        return None
    times = [t for t, _ in pairs]
    lines = [l for _, l in pairs]
    return enhanced(lines, times, estimate(lines, times))


# ---------------------------------------------------------------------------------------------- real word times (1.16)
PAUSE_S = 0.25  # a gap longer than this between a word's end and the next word's start is written as a pause


def real_count(wt):
    """How many lines of [wt] carry word times."""
    return sum(1 for w in wt or [] if w)


def coverage(lines, wt):
    """The share of [lines] with words that have word times in [wt]."""
    sung = [k for k, l in enumerate(lines) if tn.words(l)]
    if not sung:
        return 0.0
    return sum(1 for k in sung if wt and k < len(wt) and wt[k]) / len(sung)


def _norms(line):
    toks = (line or "").split()
    return toks, [tn.word_norm(t) for t in toks]


def _line_sim(a, b):
    from rapidfuzz import fuzz
    x = " ".join(w for w in a if w)
    y = " ".join(w for w in b if w)
    if not x or not y:
        return 0.0
    return fuzz.ratio(x, y) / 100.0


def _match_tokens(a, b):
    """Pairs (i, j) of tokens of [a] and [b] (normalised words) that are the same word, in order (a longest common
    subsequence with alike words counted as equal: one spelling of a word against another's, "gonna"/"gon'")."""
    from rapidfuzz import fuzz
    n, m = len(a), len(b)
    if not n or not m:
        return []
    eq = [[bool(a[i]) and bool(b[j]) and (a[i] == b[j] or fuzz.ratio(a[i], b[j]) >= 75) for j in range(m)] for i in range(n)]
    L = [[0] * (m + 1) for _ in range(n + 1)]
    for i in range(n - 1, -1, -1):
        for j in range(m - 1, -1, -1):
            L[i][j] = L[i + 1][j + 1] + 1 if eq[i][j] else max(L[i + 1][j], L[i][j + 1])
    out = []
    i = j = 0
    while i < n and j < m:
        if eq[i][j] and L[i][j] == L[i + 1][j + 1] + 1:
            out.append((i, j))
            i += 1
            j += 1
        elif L[i + 1][j] >= L[i][j + 1]:
            i += 1
        else:
            j += 1
    return out


SOURCE_TOLERANCE_S = 1.2   # a shown line and its word-timed twin (after the offset) start this close at most
MIN_TOKENS_MATCHED = 0.75  # of a line's words, or the line gets no word times from the source


def map_words(lines, times, src):
    """The real word times of a word-timed source ([src]: sources.word_lines: [(line start, [(token, start, end)])])
    put on the lines the app shows ([lines] at [times], s: usually YouTube Music's text and timing of the same
    recording). Returns (wt, covered, offset) — wt as in [tagged] — or (None, 0, 0) when the source is refused.

    The two timings may differ by a constant (another copy of the recording, a little more silence before it): that
    offset is the one most line pairs of the same words agree on (within 0.35 s); a source on which too few lines agree
    is another recording (a live one, a radio edit) and gives nothing. Then each shown line takes its twin's words
    (the same words in order, spelling aside): a line whose words are not mostly found there gets no word times."""
    tgt = []
    for k, (line, t) in enumerate(zip(lines, times)):
        toks, norms = _norms(line)
        if t is None or not any(norms):
            continue
        tgt.append((k, t, toks, norms))
    srcn = [(ws[0][1], ws, [tn.word_norm(w[0]) for w in ws]) for _, ws in src or [] if ws]
    if len(tgt) < 3 or len(srcn) < 3:
        return None, 0.0, 0.0
    # 1. the offset most clear pairs agree on
    cands = []  # (shown line, source line, shown time - source time)
    for k, t, _, norms in tgt:
        for m, (s0, _, sn) in enumerate(srcn):
            d = t - s0
            if abs(d) <= 30.0 and _line_sim(norms, sn) >= 0.85:
                cands.append((k, m, d))
    best = (0, 0.0)
    for _, _, d0 in cands:
        support = {k for k, _, d in cands if abs(d - d0) <= 0.35}
        if len(support) > best[0]:
            best = (len(support), d0)
    need = max(3, int(0.3 * len(tgt) + 0.999))
    if best[0] < need:
        return None, 0.0, 0.0
    near = sorted(d for _, _, d in cands if abs(d - best[1]) <= 0.35)
    offset = near[len(near) // 2]
    # 2. each shown line's words from its twin
    wt = [None] * len(lines)
    for k, t, toks, norms in tgt:
        twin = None
        for m, (s0, ws, sn) in enumerate(srcn):
            if abs(t - (s0 + offset)) > SOURCE_TOLERANCE_S:
                continue
            s = _line_sim(norms, sn)
            if s >= 0.6 and (twin is None or s > twin[0]):
                twin = (s, ws, sn)
        if twin is None:
            continue
        _, ws, sn = twin
        pairs = _match_tokens(norms, sn)
        real = [j for j, x in enumerate(norms) if x]
        if not real or len(pairs) < MIN_TOKENS_MATCHED * len(real):
            continue
        starts = [None] * len(toks)
        ends = [None] * len(toks)
        for i, j in pairs:
            starts[i] = ws[j][1] + offset
            ends[i] = ws[j][2] + offset
        _fill(starts, ends)
        wt[k] = {"s": [round(x, 3) for x in starts], "e": [round(x, 3) for x in ends], "from": "source"}
    covered = coverage(lines, wt)
    return (wt if any(wt) else None), covered, offset


def _fill(starts, ends):
    """Tokens no time was found for (punctuation, a word only one of the two texts has) take one between their found
    neighbours: from the word before's end to the word after's start, shared out; at a line's ends, next to the
    nearest found word. Never backwards."""
    n = len(starts)
    known = [i for i in range(n) if starts[i] is not None]
    if not known:
        return
    i = 0
    while i < n:
        if starts[i] is not None:
            i += 1
            continue
        j = i
        while j < n and starts[j] is None:
            j += 1
        before = i - 1 if i > 0 else None
        after = j if j < n else None
        if before is not None and after is not None:
            a, b = ends[before], starts[after]
            if b < a:
                a = b
        elif after is not None:
            b = starts[after]
            a = b - 0.25 * (j - i)
        else:
            a = ends[before]
            b = a + 0.25 * (j - i)
        step = (b - a) / (j - i) if j > i else 0.0
        for x in range(i, j):
            starts[x] = a + step * (x - i)
            ends[x] = a + step * (x - i + 1)
        i = j
    for x in range(1, n):
        if starts[x] < starts[x - 1]:
            starts[x] = starts[x - 1]
    for x in range(n):
        if ends[x] < starts[x]:
            ends[x] = starts[x]


def carry(src_lines, src_times, src_wt, lines, times, wt):
    """Word times heard for a song's record ([src_lines] at [src_times], s, with [src_wt]) given to the lines of an
    answer ([lines] at [times]) that still have none in [wt] (filled in place): the same words starting within a
    second. The word times are the song's own (heard on its clock): they are not moved with the line. Returns how many
    lines got some."""
    n = 0
    idx = {}
    for m, (l, t) in enumerate(zip(src_lines or [], src_times or [])):
        if t is None or m >= len(src_wt or []) or not src_wt[m]:
            continue
        idx.setdefault(" ".join(tn.words(l)), []).append((t, src_wt[m], l))
    for k, (l, t) in enumerate(zip(lines, times)):
        if t is None or wt[k]:
            continue
        for st, w, sl in idx.get(" ".join(tn.words(l)), []):
            if abs(st - t) <= 1.0 and len((sl or "").split()) == len((l or "").split()):
                wt[k] = w
                n += 1
                break
    return n


def tagged(lines, times, wt):
    """The enhanced LRC of 1.16: [lines] at [times] (s; None: left out), with word tags only on the lines whose real
    word times are in [wt] — per line None, or {"s": [start per token], "e": [end per token, -1 unknown]} with one
    entry per whitespace token of the line (the times in s, on the same clock as [times]). A word followed by a pause
    (its end known, the next word starting more than PAUSE_S later) gets an empty tag where its singing ended:
    `[00:23.44]<00:23.44>In <00:23.80>other <00:24.10>words, <00:24.90> <00:27.20>hold <00:27.60>my <00:27.90>hand <00:29.10>`
    (1.15 apps skip an empty tag in the middle of a line). Lines without word times carry their text only: the app
    lights them whole."""
    out = []
    for k, (line, t) in enumerate(zip(lines, times)):
        if t is None:
            continue
        head = _tag(t, "[]")
        w = wt[k] if wt and k < len(wt) else None
        toks = (line or "").split()
        if not w or not toks or len(w.get("s") or []) != len(toks):
            out.append(head + (line or ""))
            continue
        starts = [float(x) for x in w["s"]]
        ends = [float(x) for x in (w.get("e") or [-1] * len(toks))]
        if len(ends) != len(toks):
            ends = [-1.0] * len(toks)
        parts = []
        for j, tok in enumerate(toks):
            parts.append(_tag(starts[j]) + tok)
            if j + 1 < len(toks) and ends[j] > 0 and starts[j + 1] - ends[j] > PAUSE_S:
                parts.append(_tag(ends[j]))
        last_end = ends[-1] if ends[-1] > 0 else None
        out.append(head + " ".join(parts) + (" " + _tag(last_end) if last_end is not None else ""))
    return "\n".join(out)
