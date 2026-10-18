#!/usr/bin/env python3
"""The word-time parts that need no sound and no graphics card (1.16): how a line's tokens are spelt for the aligner,
the forced-alignment path, a line's words placed from made-up model output (pauses, held notes, marks), repeats, the
enhanced LRC, the song record of a timed session and what the app is told, the clock offset kept, and the session's
recent sound joined piece by piece.

    python tests/test_units.py            (or: python -m unittest tests.test_units)
"""
import os
import shutil
import sys
import tempfile
import unittest

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))

import align  # noqa: E402
import listen  # noqa: E402
import words  # noqa: E402
import wordtimes as wt  # noqa: E402

RATE = 16000


def emissions(frames, events, floor=-8.0):
    """Made-up model output: every frame blank (log p ~ 0) but at [events] {frame: letter}, where that letter is
    likely; the wildcard's column 0 as the model's own."""
    lp = np.full((frames, len(wt.LABELS)), floor, dtype=np.float32)
    lp[:, wt.BLANK] = -0.01
    for f, c in events.items():
        lp[f, :] = floor
        lp[f, wt.INDEX[c]] = -0.05
    lp[:, wt.STAR] = 0.0
    return lp


def spell(word, at, step=2):
    """A word's letters at frames from [at], [step] apart."""
    return {at + i * step: c for i, c in enumerate(word)}


class Letters(unittest.TestCase):
    def test_english(self):
        self.assertEqual(wt.roman("I'ma"), "i'ma")
        self.assertEqual(wt.roman("Don’t"), "don't")
        self.assertEqual(wt.roman("words,"), "words")
        self.assertEqual(wt.roman("café"), "cafe")

    def test_silent_h_and_hyphens(self):
        self.assertEqual(wt.roman("oh-ooh"), "ooo"[:2])     # "o" + "oo": a long o, not five letters
        self.assertEqual(wt.roman("Yeah!"), "yea")
        self.assertEqual(wt.roman("through"), "through")    # (an h after a consonant is kept)

    def test_long_sounds(self):
        self.assertEqual(wt.roman("Ooooooh"), "oo")
        self.assertEqual(wt.roman("واااااااه"), wt.roman("واااااااه")[:4])
        self.assertTrue(len(wt.roman("واااااااه")) <= 4)

    def test_numbers(self):
        self.assertEqual(wt.roman("12"), "twelve")
        self.assertEqual(wt.roman("1999"), "nineteenninetynine")
        self.assertEqual(wt.roman("24/7"), "twentyfourseven")
        self.assertEqual(wt.roman("٣", latin_line=False), "")   # a number in an Arabic line: not spelt

    def test_arabic(self):
        # as written, through uroman: ة and ى sound "a", إ "i"; marks and tatweel go
        self.assertEqual(wt.roman("مشتاقة"), "mshtaqa")
        self.assertEqual(wt.roman("أنسى"), "ansa")
        self.assertEqual(wt.roman("إحتريتك"), "ihtrytk")
        self.assertEqual(wt.roman("قلـــبي"), "qlby")
        self.assertEqual(wt.roman("بقوّه"), "bqwh")

    def test_plan(self):
        toks, letters, kinds = wt.plan("Pain! - you made me 12 ♪")
        self.assertEqual(toks, ["Pain!", "-", "you", "made", "me", "12", "♪"])
        self.assertEqual(kinds, ["word", "mark", "word", "word", "word", "word", "mark"])
        toks, letters, kinds = wt.plan("يا عيني ٣ مرات")
        self.assertEqual(kinds, ["word", "word", "wild", "word"])
        ids, owner = wt.targets(letters, kinds)
        self.assertEqual(ids[0], wt.STAR)
        self.assertEqual(ids[-1], wt.STAR)
        self.assertEqual(owner[0], -1)
        self.assertEqual(owner[-1], -1)
        self.assertIn(wt.STAR, ids[1:-1])          # the number's wildcard
        self.assertEqual(sorted(set(o for o in owner if o >= 0)), [0, 1, 2, 3])


class Path(unittest.TestCase):
    def test_viterbi_finds_the_letters(self):
        ev = spell("hi", 20)
        ev.update(spell("yo", 60))
        lp = emissions(100, ev)
        ids = [wt.STAR] + [wt.INDEX[c] for c in "hiyo"] + [wt.STAR]
        tgt, score = wt.viterbi(lp, ids)
        first, last = wt.label_spans(tgt, len(ids))
        self.assertEqual(list(first[1:5]), [20, 22, 60, 62])

    def test_too_few_frames(self):
        self.assertIsNone(wt.viterbi(emissions(3, {}), [wt.STAR, 1, 2, 3, wt.STAR]))

    def test_first_word_deadline(self):
        # "no" sung twice: at frame 20 and at frame 80; the line must start by frame 40
        ev = spell("no", 20)
        ev.update(spell("no", 80))
        lp = emissions(120, ev)
        lp[80, wt.INDEX["n"]] = 0.0  # (the later one is even likelier)
        ids = [wt.STAR, wt.INDEX["n"], wt.INDEX["o"], wt.STAR]
        tgt, _ = wt.viterbi(lp, ids)
        self.assertEqual(int(wt.label_spans(tgt, 4)[0][1]), 80)
        tgt, _ = wt.viterbi(lp, ids, first_by=40)
        self.assertEqual(int(wt.label_spans(tgt, 4)[0][1]), 20)


class Placing(unittest.TestCase):
    FRAME = 0.02

    def line_db(self, frames, voiced):
        """The voice's loudness per frame: -20 dB where [voiced] (start, end) frames sound, -80 elsewhere."""
        db = np.full(frames, -80.0)
        for a, b in voiced:
            db[a:b] = -20.0
        return db

    def test_words_and_a_pause(self):
        # "hold my hand": "hold" at 20 held to 60, "my" at 62, a pause, "hand" at 150
        ev = spell("hold", 20)
        ev.update(spell("my", 62))
        ev.update(spell("hand", 150))
        lp = emissions(200, ev)
        db = self.line_db(200, [(20, 60), (62, 75), (150, 170)])
        r = wt.place(lp, 10.0, self.FRAME, "hold my hand", db)
        self.assertAlmostEqual(r["s"][0], 10.0 + 20 * self.FRAME, places=2)
        self.assertAlmostEqual(r["s"][2], 10.0 + 150 * self.FRAME, places=2)
        # "hold" lasts while its voice does (a held note), not just to its last letter's frame
        self.assertGreater(r["e"][0], 10.0 + 55 * self.FRAME)
        # "my" ends where the voice stops: the pause shows
        self.assertLess(r["e"][1], 10.0 + 80 * self.FRAME)
        self.assertGreater(r["s"][2] - r["e"][1], wt.PAUSE_S)
        self.assertGreater(r["q"], 0.9)

    def test_a_letter_after_a_silence(self):
        # the word's last letter put after a long silence, just before the next word: the word is what is sung
        ev = spell("wakh", 20)
        ev[120] = "y"
        ev.update(spell("no", 124))
        lp = emissions(160, ev)
        db = self.line_db(160, [(20, 30), (118, 135)])
        r = wt.place(lp, 0.0, self.FRAME, "wakhy no", db)
        self.assertLess(r["e"][0], 40 * self.FRAME)

    def test_marks_borrow_a_neighbour(self):
        ev = spell("go", 20)
        ev.update(spell("on", 40))
        lp = emissions(80, ev)
        r = wt.place(lp, 0.0, self.FRAME, "- go - on", self.line_db(80, [(20, 24), (40, 44)]))
        self.assertEqual(len(r["s"]), 4)
        self.assertAlmostEqual(r["s"][0], r["s"][1])          # a leading mark: the next word's start
        self.assertAlmostEqual(r["s"][2], r["e"][1])          # a mark after a word: that word's end
        self.assertEqual(r["s"], sorted(r["s"]))

    def test_wrong_text_is_unsure(self):
        lp = emissions(120, spell("hello", 30))
        r = wt.place(lp, 0.0, self.FRAME, "goodbye", self.line_db(120, [(30, 40)]))
        self.assertLess(r["q"], 0.1)

    def test_window(self):
        times = [10.0, 14.0, None, 30.0]
        a, b, na, nb, by = wt.window(times, 1, 0.5, slack=1.2)
        self.assertAlmostEqual(a, 14.0 + 0.5 - 1.2)
        self.assertAlmostEqual(b, 14.0 + wt.MAX_LINE + 0.5 + 1.2)   # (the next line 16 s on: at most MAX_LINE)
        self.assertAlmostEqual(by, 14.0 + 0.5 + 1.2)
        a, b, na, nb, by = wt.window(times, 0, 0.0, slack=1.2, earliest=8.0)
        self.assertAlmostEqual(a, 8.0 - 1.2)
        self.assertAlmostEqual(b, 14.0 + 1.2)


class Repeats(unittest.TestCase):
    LINES = ["la la land", "oh my", "la la land", "oh my", "la la land", "end"]

    def test_a_chorus_sung_again(self):
        times = [10.0, 13.0, 20.0, 23.0, 30.0, 40.0]
        heard = {"s": [10.1, 10.5, 11.0], "e": [10.4, 10.9, 12.5], "from": "heard", "q": 0.9}
        out = wt.repeats(self.LINES, times, [heard, None, None, None, None, None])
        self.assertEqual(out[2]["from"], "repeat")
        self.assertEqual(out[2]["s"], [20.1, 20.5, 21.0])
        self.assertEqual(out[2]["e"], [20.4, 20.9, 22.5])
        # line 4's span (to "end", 10 s) is not line 0's (3 s): not the same singing
        self.assertIsNone(out[4])
        self.assertIsNone(out[1])

    def test_heard_replaces_repeat(self):
        times = [10.0, 13.0, 20.0, 23.0, 30.0, 40.0]
        a = {"s": [10.1, 10.5, 11.0], "e": [-1, -1, -1], "from": "heard", "q": 0.9}
        b = {"s": [20.3, 20.6, 21.2], "e": [-1, -1, -1], "from": "heard", "q": 0.5}
        out = wt.repeats(self.LINES, times, [a, None, {"s": [0, 0, 0], "e": [0, 0, 0], "from": "repeat"}, None, None, None])
        self.assertEqual(out[2]["from"], "repeat")
        out = wt.repeats(self.LINES, times, [a, None, b, None, None, None])
        self.assertEqual(out[2], b)

    def test_other_tokens_not_repeated(self):
        lines = ["la la land", "x", "la-la land", "y"]
        times = [10.0, 13.0, 20.0, 23.0]
        a = {"s": [10.1, 10.5, 11.0], "e": [-1, -1, -1], "from": "heard", "q": 0.9}
        out = wt.repeats(lines, times, [a, None, None, None])
        self.assertIsNone(out[2])   # (another token count: the words would not fit its tokens)


class Enhanced(unittest.TestCase):
    def test_tagged_with_a_pause(self):
        lines = ["In other words, hold my hand", "plain line"]
        w = [{"s": [23.44, 23.80, 24.10, 27.20, 27.60, 27.90], "e": [23.8, 24.1, 24.9, 27.6, 27.9, 29.1], "from": "heard"}, None]
        text = words.tagged(lines, [23.44, 30.0], w)
        first, second = text.split("\n")
        self.assertEqual(first, "[00:23.44]<00:23.44>In <00:23.80>other <00:24.10>words, <00:24.90> <00:27.20>hold "
                                "<00:27.60>my <00:27.90>hand <00:29.10>")
        self.assertEqual(second, "[00:30.00]plain line")


class Record(unittest.TestCase):
    def setUp(self):
        self.home = tempfile.mkdtemp(prefix="amri-units-")
        self.songs = align.Songs(self.home)
        self.lines = ["one two three", "", "four five", "one two three", "six"]
        self.given = [10000, 14000, 15000, 20000, 24000]

    def tearDown(self):
        shutil.rmtree(self.home, ignore_errors=True)

    def placing(self, starts, q=0.8):
        return {"s": list(starts), "e": [x + 0.3 for x in starts], "q": q}

    def test_timed_session(self):
        s = self.songs.start("Song", "Singer", 0, self.lines, self.given)
        st = align.Songs.state(s, 0, True)
        self.assertEqual(st["mode"], "timed")
        self.assertEqual(st["times"], self.given)
        self.assertEqual(st["covered"], 0.0)
        self.assertFalse(st["show"])
        self.assertNotIn("words", st)
        self.assertTrue(st["lrc"].startswith("[00:10.00]one two three"))
        self.assertEqual(s["times"], [10.0, 14.0, 15.0, 20.0, 24.0])   # (what the lyrics answer reads)
        s, n = self.songs.put_words(s["id"], s["text"], "1", {0: self.placing([10.1, 10.5, 10.9])}, 0.0)
        self.assertEqual(n, 1)
        st = align.Songs.state(s, 0, True)
        # line 0 heard; line 3 is the same text sung over as long (4 s to the next line's time, the empty line's
        # for line 0): its words are line 0's, moved to its own time
        self.assertEqual(s["wt"][0]["from"], "heard")
        self.assertEqual(s["wt"][3]["from"], "repeat")
        self.assertTrue(st["show"])
        self.assertEqual(st["words"], "heard")
        self.assertIn("[00:10.00]<00:10.10>one <00:10.50>two <00:10.90>three <00:11.20>", st["enhanced"])
        self.assertIn("[00:20.00]<00:20.10>one <00:20.50>two <00:20.90>three <00:21.20>", st["enhanced"])
        self.assertIn("[00:15.00]four five", st["enhanced"])
        self.assertAlmostEqual(st["covered"], 2 / 4)
        self.assertFalse(st["done"])

    def test_surer_words_replace(self):
        s = self.songs.start("Song", "Singer", 0, self.lines, self.given)
        s, n = self.songs.put_words(s["id"], s["text"], "1", {2: self.placing([15.1, 15.4], 0.9)}, 0.0)
        s, n = self.songs.put_words(s["id"], s["text"], "1", {2: self.placing([15.5, 15.8], 0.6)}, 0.0)
        self.assertEqual(n, 0)
        self.assertEqual(s["wt"][2]["s"], [15.1, 15.4])
        s, n = self.songs.put_words(s["id"], s["text"], "1", {2: self.placing([15.2, 15.5], 0.95)}, 0.0)
        self.assertEqual(n, 1)
        self.assertEqual(s["wt"][2]["s"], [15.2, 15.5])

    def test_offset_moves_the_sessions_words(self):
        s = self.songs.start("Song", "Singer", 0, self.lines, self.given)
        # placed on the app's clock, 0.8 s ahead; the offset known only after the first line
        s, _ = self.songs.put_words(s["id"], s["text"], "1", {0: self.placing([10.9, 11.3, 11.7])}, None)
        self.assertEqual(s["wt"][0]["s"], [10.9, 11.3, 11.7])
        s, _ = self.songs.put_words(s["id"], s["text"], "1", {2: self.placing([15.9, 16.2])}, 0.8)
        self.assertEqual(s["wt"][0]["s"], [10.1, 10.5, 10.9])
        self.assertEqual(s["wt"][2]["s"], [15.1, 15.4])
        # another session's words are its own
        s, _ = self.songs.put_words(s["id"], s["text"], "2", {4: self.placing([24.0])}, 0.3)
        self.assertEqual(s["wt"][0]["s"], [10.1, 10.5, 10.9])

    def test_other_text_drops_words(self):
        s = self.songs.start("Song", "Singer", 0, self.lines, self.given)
        s, _ = self.songs.put_words(s["id"], s["text"], "1", {0: self.placing([10.1, 10.5, 10.9])}, 0.0)
        s2 = self.songs.start("Song", "Singer", 0, ["other", "text", "here"], None)
        self.assertEqual(s2["wt"], [None, None, None])
        s3, n = self.songs.put_words(s["id"], s["text"], "1", {0: self.placing([10.1, 10.5, 10.9])}, 0.0)
        self.assertEqual(n, 0)   # (placed for the old lines: dropped)

    def test_untimed_state_as_before(self):
        s = self.songs.start("Song", "Singer", 0, self.lines, None)
        st = align.Songs.state(s, 2)
        for key in ("ok", "id", "lines", "covered", "show", "done", "ahead", "times", "lrc", "heard", "chunks", "pending"):
            self.assertIn(key, st)
        self.assertEqual(st["mode"], "untimed")
        self.assertEqual(st["pending"], 2)
        self.assertIsNone(st["times"])

    def test_untimed_lines_start_with_their_words(self):
        lines = ["a b c", "d e f", "g h i"]
        times, kinds, cov = align.Songs.word_starts(lines, [10.0, 15.0, 20.0], ["anchor", "between", "anchor"], 0.6,
                                                     [None, {"s": [13.0, 13.5, 16.5], "e": [-1] * 3, "from": "heard"}, None])
        self.assertEqual(times, [10.0, 13.0, 20.0])
        self.assertEqual(kinds[1], "anchor")


class Lines(unittest.TestCase):
    """Listener.place over a song's lines, the model replaced by a stand-in that 'hears' each line 0.3 s after its
    window's slack."""

    class Hearing:
        """Hears each line where [at] says it is sung."""
        def __init__(self, at):
            self.at = at
            self.asked = []

        def line(self, sound, v0, line, a, b, first_by=None, voice=None):
            self.asked.append((line, round(a, 2), round(b, 2), round(first_by, 2)))
            n = len(line.split())
            t = self.at[line]
            return {"s": [round(t + 0.3 * j, 2) for j in range(n)], "e": [round(t + 0.3 * j + 0.25, 2) for j in range(n)],
                    "q": 0.9, "wq": [0.9] * n}

    def listener(self, s, late=0.05):
        x = listen.Listener(None, None, log=lambda m: None)
        times = s.get("given") or s.get("times")
        x.aligner = self.Hearing({l: t + late for l, t in zip(s["lines"], times) if t is not None})
        return x

    def session(self, secs=40.0):
        x = listen.Session("x", 1)
        x.voice = np.zeros(int(secs * RATE), dtype=np.float32)
        x.mix = x.voice.copy()
        x.t0 = 0.0
        return x

    def test_timed(self):
        s = {"lines": ["one two", "", "three four five", "six", "seven eight"], "given": [5.0, 8.0, 10.0, 15.0, 30.0]}
        L, sess = self.listener(s), self.session(36.0)
        out = L.place(s, sess)
        # every line whose window the 36 s cover (the last one's, to 39.4 s, reaches past them: not yet)
        self.assertEqual(sorted(out), [0, 2, 3])
        self.assertEqual(len(out[2]["s"]), 3)
        self.assertEqual(sorted(sess.deltas), [0, 2, 3])
        self.assertAlmostEqual(sess.offset(), 0.05)
        # placed once per session: asked again, nothing new
        self.assertEqual(L.place(s, sess), {})
        sess.voice = np.zeros(60 * RATE, dtype=np.float32)
        sess.mix = sess.voice.copy()
        self.assertEqual(sorted(L.place(s, sess)), [4])
        # once the offset is known, the windows are narrower and moved by it
        self.assertAlmostEqual(L.aligner.asked[-1][3], 30.0 + 0.05 + listen.NARROW)

    def test_clock_offset_retries(self):
        # the app's clock 0.9 s ahead: the first lines are found late in their wide windows; once the offset is
        # known the lines are looked for again around their moved times (and the words kept are moved back)
        s = {"lines": ["a b", "c d", "e f", "g h"], "given": [5.0, 10.0, 15.0, 20.0]}
        L, sess = self.listener(s, late=0.9), self.session(19.0)
        self.assertEqual(sorted(L.place(s, sess)), [0, 1])
        sess.voice = np.zeros(40 * RATE, dtype=np.float32)
        sess.mix = sess.voice.copy()
        self.assertEqual(sorted(L.place(s, sess)), [2, 3])
        self.assertAlmostEqual(sess.offset(), 0.9)
        self.assertAlmostEqual(listen.kept_offset(sess.offset()), 0.9)

    def test_the_same_line_twice(self):
        # "Pain!" sung at 5.0 and again at 6.5: the second line's window reaches back over the first singing
        class Twice:
            def line(self, sound, v0, line, a, b, first_by=None, voice=None):
                for t in (5.0, 6.5):
                    if a <= t <= first_by:
                        return {"s": [t], "e": [t + 0.6], "q": 0.9, "wq": [0.9]}
                return None
        s = {"lines": ["Pain!", "Pain!", "and more words here"], "given": [5.0, 6.5, 9.0]}
        L, sess = self.listener(s), self.session(30.0)
        L.aligner = Twice()
        out = L.place(s, sess)
        self.assertEqual(out[0]["s"], [5.0])
        self.assertEqual(out[1]["s"], [6.5])

    def test_untimed(self):
        s = {"lines": ["one two", "three four", "five six"], "times": [5.0, 10.0, 15.0],
             "kinds": ["anchor", "between", "guess"], "first_heard": [4.0, None, None]}
        L, sess = self.listener(s), self.session()
        out = L.place(s, sess)
        self.assertEqual(sorted(out), [0, 1])          # (a guessed line is not looked for)
        self.assertEqual(L.aligner.asked[0][1], round(4.0 - wt.PRE, 2))   # (its window reaches back to its first word heard)
        self.assertEqual(sess.deltas, {})


class Offset(unittest.TestCase):
    def test_kept_offset(self):
        self.assertEqual(listen.kept_offset(None), None)
        self.assertEqual(listen.kept_offset(0.1), 0.0)
        self.assertEqual(listen.kept_offset(-0.15), 0.0)
        self.assertAlmostEqual(listen.kept_offset(0.25), 0.125)
        self.assertEqual(listen.kept_offset(0.8), 0.8)
        self.assertEqual(listen.kept_offset(-1.0), -1.0)

    def test_session_offset_needs_lines(self):
        x = listen.Session("x", 1)
        x.deltas = {0: 0.7, 1: 0.8}
        self.assertIsNone(x.offset())
        x.deltas[2] = 0.9
        self.assertAlmostEqual(x.offset(), 0.8)


class Joining(unittest.TestCase):
    def tone(self, start, secs, seed):
        """Sound to join: a steady tone (a beat or a held chord matches at many shifts) under noise (what tells)."""
        t = np.arange(int(secs * RATE)) / RATE + start
        rng = np.random.default_rng(seed)
        return (0.1 * np.sin(2 * np.pi * 220 * t) + 0.1 * rng.standard_normal(len(t))).astype(np.float32)

    def test_pieces_with_overlap(self):
        song = self.tone(0.0, 40.0, 1)
        x = listen.Session("x", 1)
        self.assertEqual(x.add(song[:12 * RATE], song[:12 * RATE], 0.0), "new")
        self.assertEqual(x.add(song[10 * RATE:22 * RATE], song[10 * RATE:22 * RATE], 10.0), "joined")
        self.assertAlmostEqual(x.t1, 22.0, places=3)
        self.assertTrue(np.allclose(x.mix, song[:22 * RATE], atol=1e-6))

    def test_gap_and_jump(self):
        song = self.tone(0.0, 60.0, 2)
        x = listen.Session("x", 1)
        x.add(song[:12 * RATE], song[:12 * RATE], 0.0)
        # the next piece 20 s on: a gap, the recent sound starts over
        self.assertEqual(x.add(song[30 * RATE:42 * RATE], song[30 * RATE:42 * RATE], 30.0), "gap")
        self.assertAlmostEqual(x.t0, 30.0)
        # the next piece's position 0.3 s off its sound (the app's clock jumped): started over
        piece = song[int(40.3 * RATE):int(52.3 * RATE)]
        self.assertEqual(x.add(piece, piece, 40.0), "jump")

    def test_keeps_only_recent_sound(self):
        song = self.tone(0.0, 80.0, 3)
        x = listen.Session("x", 1)
        for st in range(0, 70, 10):
            p = song[st * RATE:(st + 12) * RATE]
            x.add(p, p, float(st))
        self.assertLessEqual(x.t1 - x.t0, listen.KEEP_S + 0.01)


if __name__ == "__main__":
    unittest.main(verbosity=1)
