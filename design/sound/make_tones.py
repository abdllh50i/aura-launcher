"""Makes the three tones of SPEC.md section 15: res/raw/tone_tick.wav, tone_saher.wav, tone_over.wav. Mono, 16 bit, 22050 Hz.

    python -I design/sound/make_tones.py

The spec gives the tick's length (30 ms) and the Saher tone's character ("one calm tone, not a siren"), and no pitch or level for any of
them, so these numbers are this file's choice and one table: not yet heard on the car's speakers. Plain decaying sines, soft at both ends so
none of them clicks; a low partial gives the two warnings some body at the head unit's level.
"""
import math
import os
import struct
import wave

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "..", "app", "src", "main", "res", "raw")
RATE = 22050

# name: milliseconds, [(frequency, level, decay per second), ...]; for two notes, a second list after a gap
TONES = {
    # a dry knock under a finger, not a beep
    "tick": (30, [(1760, 0.34, 120), (880, 0.16, 90)]),
    # one soft bell, low and round
    "saher": (420, [(784, 0.42, 7), (1568, 0.10, 11)]),
    # two descending notes: the same voice as Saher, a step lower, so it is told apart without being louder
    "over": (360, [(659, 0.40, 8), (1318, 0.08, 12)]),
}


def tone(ms, partials):
    n = int(RATE * ms / 1000)
    out = []
    attack = int(RATE * 0.003)
    release = int(RATE * 0.012)
    for i in range(n):
        t = i / RATE
        v = sum(a * math.exp(-d * t) * math.sin(2 * math.pi * f * t) for f, a, d in partials)
        v *= min(1.0, i / attack) * min(1.0, (n - i) / release)
        out.append(max(-1.0, min(1.0, v)))
    return out


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, (ms, partials) in TONES.items():
        s = tone(ms, partials)
        path = os.path.join(OUT, "tone_%s.wav" % name)
        with wave.open(path, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(RATE)
            w.writeframes(b"".join(struct.pack("<h", int(x * 32767)) for x in s))
        print(name, ms, "ms", os.path.getsize(path), "bytes")


if __name__ == "__main__":
    main()
