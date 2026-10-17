#!/usr/bin/env python3
"""AMRI's ring for an incoming call: a bright marimba figure in A major at a quick walking tempo, the kind of ring a
phone has (the owner asked for one like the iPhone's), then a short breath before it comes round again. Composed and
made here, note by note: the melody, the rhythm and the sound are AMRI's own, nothing is taken from another ringtone.

    python make_ring.py            -> app/src/main/res/raw/aura_ring.mp3   (needs numpy, and ffmpeg on PATH)

An MP3 because the firmware's own ring player is what plays it: the Bluetooth service loops "ring.mp3" from the
internal storage when one is there, in place of /system/ring.mp3 (see phone/Ringtone.kt). It ends on a rest that
fades to nothing, so the join of the loop cannot be heard.
"""
import os
import subprocess
import sys

import numpy as np

RATE = 44100
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.normpath(os.path.join(HERE, "..", "..", "app", "src", "main", "res", "raw", "aura_ring.mp3"))

A4 = 440.0
NAMES = {"C": -9, "C#": -8, "D": -7, "D#": -6, "E": -5, "F": -4, "F#": -3, "G": -2, "G#": -1, "A": 0, "A#": 1, "B": 2}


def freq(name):
    """'C#6' -> Hz (equal temperament, A4 = 440)."""
    pitch, octave = name[:-1], int(name[-1])
    return A4 * 2 ** ((NAMES[pitch] + 12 * (octave - 4)) / 12)


def marimba(f, seconds, level=1.0):
    """One struck bar: a marimba's tuned modes (the fundamental, the double octave and the one about ten times up,
    each dying faster than the one below), the resonator keeping the fundamental a little longer, and a short soft
    mallet tick on the attack. Higher bars ring shorter, as real ones do."""
    n = int(seconds * RATE)
    t = np.arange(n) / RATE
    ring = 0.55 * (440.0 / f) ** 0.5  # seconds to fall by e: ~0.55 s at A4, 0.39 s at A5, shorter up high
    x = np.sin(2 * np.pi * f * t) * np.exp(-t / ring)
    x += 0.30 * np.sin(2 * np.pi * f * 3.98 * t) * np.exp(-t / (ring * 0.22))
    x += 0.07 * np.sin(2 * np.pi * f * 9.86 * t) * np.exp(-t / (ring * 0.07))
    # a glassy shimmer an octave up on the attack: what makes a phone's marimba sound bright through a car speaker
    x += 0.12 * np.sin(2 * np.pi * f * 2.0 * t) * np.exp(-t / (ring * 0.30))
    # the mallet: a few milliseconds of soft noise, low-passed by a running mean
    rng = np.random.default_rng(int(f * 10))
    tick = rng.standard_normal(int(0.004 * RATE))
    tick = np.convolve(tick, np.ones(6) / 6, mode="same") * np.linspace(1.0, 0.0, tick.size) ** 2
    x[:tick.size] += 0.18 * tick
    attack = int(0.0025 * RATE)
    x[:attack] *= np.linspace(0.0, 1.0, attack)
    return level * x


def main():
    tempo = 116.0
    step = 60.0 / tempo / 4  # a sixteenth
    # (sixteenth, note, how loud): a figure that rises, answers itself a step higher, lifts to the top and comes home
    melody = [
        (0, "A5", 0.92), (2, "E6", 0.80), (3, "C#6", 0.70), (4, "E6", 0.84), (6, "B5", 0.70), (7, "C#6", 0.74),
        (8, "A5", 0.90), (10, "E6", 0.80), (11, "C#6", 0.70), (12, "F#6", 0.88), (14, "E6", 0.76), (15, "C#6", 0.72),
        (16, "D6", 0.92), (18, "F#6", 0.82), (19, "D6", 0.70), (20, "A6", 0.86), (22, "F#6", 0.76), (23, "E6", 0.74),
        (24, "C#6", 0.86), (26, "E6", 0.80), (27, "B5", 0.72), (28, "A5", 1.00), (28, "E5", 0.50),
    ]
    # the low bars under it: the chords' roots, softly (A, F#m, D, E, home to A)
    bass = [(0, "A3", 0.38), (8, "F#3", 0.34), (16, "D3", 0.38), (24, "E3", 0.34), (28, "A3", 0.42)]
    bars = 36  # 32 sixteenths of music and four of rest
    total = bars * step
    out = np.zeros(int(total * RATE) + 1)
    rng = np.random.default_rng(7)
    for at, name, level in melody + bass:
        f = freq(name)
        note = marimba(f, 1.8 if f < 300 else 1.2, level)
        # played by hand: a few milliseconds early or late
        i = max(0, int((at * step + rng.uniform(-0.004, 0.004)) * RATE))
        m = min(len(note), len(out) - i)
        out[i:i + m] += note[:m]
    # a small room around it: two quiet echoes
    for delay, gain in ((0.19, 0.09), (0.38, 0.035)):
        d = int(delay * RATE)
        out[d:] += gain * out[:-d]
    # the rest at the end goes to nothing, so the loop's join is silent
    fade = int(3 * step * RATE)
    out[-fade:] *= np.linspace(1.0, 0.0, fade) ** 2
    # a little above the stock ring's level (/system/ring.mp3 peaks at -9.6 dBFS): the firmware plays both at the same
    # volume, the phone-call one, and a ring far louder than the one it replaces would startle
    out *= 0.5 / np.max(np.abs(out))  # -6 dBFS

    pcm = (out * 32767).astype("<i2")
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-f", "s16le", "-ar", str(RATE), "-ac", "1", "-i", "-",
                    "-c:a", "libmp3lame", "-b:a", "128k", "-map_metadata", "-1", "-write_xing", "0", OUT],
                   input=pcm.tobytes(), check=True)
    print(OUT, os.path.getsize(OUT), "bytes,", "%.2f s" % total)


if __name__ == "__main__":
    sys.exit(main())
