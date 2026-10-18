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
