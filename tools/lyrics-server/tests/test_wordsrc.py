#!/usr/bin/env python3
"""Word times from the word-timed sources (1.16), without the network: a NetEase yrc text parsed into words, their words put on another source's lines through the two timings' offset (and refused for another recording),
the enhanced LRC written with pauses, and heard words carried to an answer's lines.

    python tests/test_wordsrc.py
"""
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))

import sources  # noqa: E402
import words  # noqa: E402

YRC = "\n".join([
    '{"t":0,"c":[{"tx":"作词: "},{"tx":"Someone"}]}',
    "[9030,3750](9030,510,0)Call (9540,1020,0)me (10560,2220,0)irresponsible",
    "[12810,3420](12810,480,0)Call (13290,1260,0)me (14550,1680,0)unre(15400,830,0)liable",
    "[16300,4200](16300,500,0)Throw (16800,400,0)in (17200,1500,0)undependable (19900,600,0)too",
    "[21000,3000](21000,400,0)Do (21400,300,0)my (21700,700,0)foolish (22400,900,0)alibis",
])


class WordLines(unittest.TestCase):
    def test_yrc(self):
        w = sources.word_lines(YRC)
        self.assertEqual(len(w), 4)  # (the credits line left out)
        t, toks = w[1]
        self.assertAlmostEqual(t, 12.81)
        # "unre" + "liable" are one word, from the first piece's start to the last one's end
        self.assertEqual([x[0] for x in toks], ["Call", "me", "unreliable"])
        self.assertAlmostEqual(toks[2][1], 14.55)
        self.assertAlmostEqual(toks[2][2], 16.23)


class MapWords(unittest.TestCase):
    def lines(self, shift):
        # another source's lines (other spelling, punctuation) of the same recording, timed [shift] s later
        pairs = [(9.03, "Call me irresponsible"), (12.81, "Call me unreliable,"), (16.3, "Throw in undependable, too"),
                 (21.0, "Do my foolish alibis"), (25.0, "Bother you")]
        return [l for _, l in pairs], [t + shift for t, _ in pairs]

    def test_offset_and_tokens(self):
        lines, times = self.lines(0.4)
        wt, covered, offset = words.map_words(lines, times, sources.word_lines(YRC))
        self.assertAlmostEqual(offset, 0.4, places=2)
        self.assertAlmostEqual(covered, 0.8)  # "Bother you" is not in the source
        self.assertIsNone(wt[4])
        s = wt[2]["s"]
        self.assertEqual(len(s), 4)  # "undependable," and "too" matched despite the comma
        self.assertAlmostEqual(s[3], 19.9 + 0.4, places=2)
        self.assertAlmostEqual(wt[1]["e"][2], 16.23 + 0.4, places=2)

    def test_other_recording_refused(self):
        # lines that do not keep one offset with the source (another take): nothing
        lines, times = self.lines(0.0)
        times = [times[0], times[1] + 2.0, times[2] - 1.5, times[3] + 3.1, times[4]]
        wt, covered, _ = words.map_words(lines, times, sources.word_lines(YRC))
        self.assertIsNone(wt)
        self.assertEqual(covered, 0.0)


class Tagged(unittest.TestCase):
    def test_pause_and_plain_lines(self):
        lines = ["In other words, hold my hand", "Fly me to the moon"]
        times = [23.44, 31.0]
        wt = [{"s": [23.44, 23.8, 24.1, 27.2, 27.6, 27.9], "e": [23.8, 24.1, 24.9, 27.6, 27.9, 29.1]}, None]
        out = words.tagged(lines, times, wt).splitlines()
        self.assertEqual(out[0], "[00:23.44]<00:23.44>In <00:23.80>other <00:24.10>words, <00:24.90> "
                                 "<00:27.20>hold <00:27.60>my <00:27.90>hand <00:29.10>")
        self.assertEqual(out[1], "[00:31.00]Fly me to the moon")
        # read back: the words' text and the line's time
        back = words.parse_lrc("\n".join(out))
        self.assertEqual(back[0], (23.44, "In other words, hold my hand"))


class Carry(unittest.TestCase):
    def test_same_words_close_in_time(self):
        rec_lines = ["Call me irresponsible", "Call me unreliable"]
        rec_times = [9.0, 12.8]
        rec_wt = [{"s": [9.03, 9.54, 10.56], "e": [9.54, 10.56, 12.78]}, None]
        lines = ["Call me Irresponsible!", "Call me unreliable"]
        times = [9.5, 12.8]
        wt = [None, None]
        self.assertEqual(words.carry(rec_lines, rec_times, rec_wt, lines, times, wt), 1)
        self.assertEqual(wt[0]["s"][0], 9.03)  # the heard times as they are (not moved with the line)
        self.assertIsNone(wt[1])


if __name__ == "__main__":
    unittest.main()
