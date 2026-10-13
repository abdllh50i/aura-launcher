#!/usr/bin/env python3
"""AMRI's ring for an incoming call: a calm phrase of struck notes (a marimba with a little bell on top) in E major,
then a breath before it comes round again. Made here, note by note, so that nothing is borrowed.

    python make_ring.py            -> app/src/main/res/raw/aura_ring.mp3   (needs numpy, and ffmpeg on PATH)

An MP3 because the firmware's own ring player is what plays it: the Bluetooth service loops "ring.mp3" from the
internal storage when one is there, in place of /system/ring.mp3 (see phone/Ringtone.kt). Its end is quiet, so the
join of the loop cannot be heard.
"""
import os
import subprocess
import sys

import numpy as np

RATE = 44100
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.normpath(os.path.join(HERE, "..", "..", "app", "src", "main", "res", "raw", "aura_ring.mp3"))

NOTE = {"B4": 493.88, "E5": 659.26, "F#5": 739.99, "G#5": 830.61, "B5": 987.77, "C#6": 1108.73, "E6": 1318.51}


def struck(freq, seconds, level=1.0):
    """One note: a soft mallet on wood. The fundamental rings, the fourth partial (a marimba's colour) and a bell-like
    upper one die quickly; a few milliseconds of attack keep the start from clicking."""
    n = int(seconds * RATE)
    t = np.arange(n) / RATE
    body = np.sin(2 * np.pi * freq * t) * np.exp(-t / 0.42)
    wood = 0.30 * np.sin(2 * np.pi * freq * 4.0 * t) * np.exp(-t / 0.07)
    bell = 0.10 * np.sin(2 * np.pi * freq * 2.76 * t) * np.exp(-t / 0.18)
    warm = 0.22 * np.sin(2 * np.pi * freq * 2.0 * t) * np.exp(-t / 0.22)
    x = body + wood + bell + warm
    attack = int(0.004 * RATE)
    x[:attack] *= np.linspace(0.0, 1.0, attack) ** 2
    return level * x


def main():
    step = 0.17  # a sixteenth
    # (when in sixteenths, which note, how loud): up through the chord, a turn at the top, and down to rest
    phrase = [(0, "E5", 0.80), (2, "G#5", 0.78), (4, "B5", 0.84), (6, "E6", 1.00),
              (9, "C#6", 0.74), (11, "B5", 0.80), (13, "G#5", 0.70), (15, "B5", 0.92),
              (15, "E5", 0.45)]
    total = 4.6  # the phrase (about 2.7 s), its ring, and the breath
    out = np.zeros(int(total * RATE))
    for at, name, level in phrase:
        note = struck(NOTE[name], 1.9, level)
        i = int(at * step * RATE)
        m = min(len(note), len(out) - i)
        out[i:i + m] += note[:m]
    # a little room around it: two quiet echoes
    for delay, gain in ((0.23, 0.16), (0.46, 0.07)):
        d = int(delay * RATE)
        out[d:] += gain * out[:-d]
    # the end goes to nothing, so the join of the loop cannot be heard
    fade = int(0.5 * RATE)
    out[-fade:] *= np.linspace(1.0, 0.0, fade) ** 2
    # a little above the stock ring's level (/system/ring.mp3 peaks at -9.6 dBFS): the firmware plays both at the same
    # volume, the phone-call one, and a ring far louder than the one it replaces would startle
    out *= 0.5 / np.max(np.abs(out))  # -6 dBFS

    pcm = (out * 32767).astype("<i2")
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-f", "s16le", "-ar", str(RATE), "-ac", "1", "-i", "-",
                    "-c:a", "libmp3lame", "-b:a", "112k", "-map_metadata", "-1", "-write_xing", "0", OUT],
                   input=pcm.tobytes(), check=True)
    print(OUT, os.path.getsize(OUT), "bytes,", "%.1f s" % total)


if __name__ == "__main__":
    sys.exit(main())
