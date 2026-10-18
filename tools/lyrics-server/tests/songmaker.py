"""Test songs this project may use, with every word's true time: a song's lines spoken by text-to-speech (edge-tts, which
also says where each word starts and ends), made a little more like singing (a line's last word held two or three times
as long, half lines with a pause between them, a room's echo), laid at known times over a made-up band (bass, chords,
drums and a wordless "ooh" lead), and the times a lyrics source would give (each line's start, a little off).

    from songmaker import make_song
    song = make_song(lines, "en", seed=1)       # song.audio (16 kHz float32), song.truth, song.given, ...

Nothing here is a recording of a song: the voice is synthetic and the band is generated. The spoken pieces are kept
in --work (lyrics-cache/test/words, outside the repository) so a second run does not ask for them again.
"""
import asyncio
import hashlib
import json
import os
import random
import re
import time

import numpy as np

RATE = 16000
HERE = os.path.dirname(os.path.abspath(__file__))
WORK = os.path.join(os.environ.get("AMRI_LYRICS_CACHE") or os.path.join(HERE, "..", "..", "..", "..", "lyrics-cache"), "test", "words")
VOICES = {"ar": ["ar-KW-FahedNeural", "ar-SA-HamedNeural"], "en": ["en-US-GuyNeural", "en-US-JennyNeural"]}


class Song:
    def __init__(self, audio, lines, truth, starts, given, pauses, voice):
        self.audio = audio      # the mix, 16 kHz mono float32
        self.lines = lines
        self.truth = truth      # per line: None (empty) or [(start, end) or None per whitespace token]
        self.starts = starts    # per line: the first word's true start (None for an empty line)
        self.given = given      # per line: the time a lyrics source would give (s; an empty line: the previous line's end)
        self.pauses = pauses    # [(line, token before the pause, pause start, pause end)]
        self.voice = voice      # the voice alone, as mixed (for checks)
        self.shift = 0.0

    def calibrate(self):
        """The voice's word times are a little early against its sound (the MP3's delay, where the voice puts a word's
        start): the truth is moved by the median difference between the sound's onset and the given start of the
        words that follow a silence (a line's first word, the word after a pause), where an onset is unmistakable.
        Returns (shift, how many words told it, their 10th and 90th percentile)."""
        d = []
        for k, tr in enumerate(self.truth):
            if not tr:
                continue
            first = next((i for i, y in enumerate(tr) if y), None)
            for j, x in enumerate(tr):
                if x is None:
                    continue
                if j == first or any(p[0] == k and p[1] + 1 == j for p in self.pauses):
                    on = onset_after_silence(self.voice, x[0])
                    if on is not None:
                        d.append(on - x[0])
        if not d:
            return 0.0, 0, 0.0, 0.0
        s = float(np.median(d))
        self.truth = [None if tr is None else [None if x is None else (x[0] + s, x[1] + s) for x in tr] for tr in self.truth]
        self.starts = [None if x is None else x + s for x in self.starts]
        self.given = [x + s for x in self.given]
        self.pauses = [(k, j, a + s, b + s) for (k, j, a, b) in self.pauses]
        self.shift = s
        return s, len(d), float(np.percentile(d, 10)), float(np.percentile(d, 90))


def onset_after_silence(voice, t, look=0.25):
    """Where the voice starts sounding near [t] after a silence: the first 5 ms above (its peak - 35 dB) after at least
    80 ms under (its peak - 50 dB); None when there is no such silence there."""
    a = int(max(0, t - look) * RATE)
    x = voice[a:int((t + look) * RATE)]
    fr = len(x) // 80
    if fr == 0:
        return None
    e = 20 * np.log10(np.sqrt((x[:fr * 80].reshape(fr, 80) ** 2).mean(1)) + 1e-9)
    pk = 20 * np.log10(np.abs(voice).max() + 1e-9)
    run = 0
    for i, v in enumerate(e):
        if v < pk - 50:
            run += 1
        elif v > pk - 35 and run >= 16:
            return (a + i * 80) / RATE
        elif v > pk - 35:
            run = 0
    return None


# ---------------------------------------------------------------------------------------------- the voice
async def _tts(text, voice, rate, pitch, path):
    import edge_tts
    c = edge_tts.Communicate(text, voice, rate=rate, pitch=pitch, boundary="WordBoundary")
    words = []
    with open(path, "wb") as f:
        async for chunk in c.stream():
            if chunk["type"] == "audio":
                f.write(chunk["data"])
            elif chunk["type"] == "WordBoundary":
                words.append((chunk["text"], chunk["offset"] / 1e7, (chunk["offset"] + chunk["duration"]) / 1e7))
    return words


def spoken(text, voice, rate="+0%", pitch="+0Hz", work=WORK):
    """[text] spoken: (audio at 16 kHz, [(word, start, end)] as the voice says it)."""
    from faster_whisper import decode_audio
    os.makedirs(work, exist_ok=True)
    key = hashlib.sha1(("%s|%s|%s|%s" % (voice, rate, pitch, text)).encode("utf-8")).hexdigest()[:16]
    mp3 = os.path.join(work, key + ".mp3")
    meta = os.path.join(work, key + ".json")
    if not (os.path.isfile(mp3) and os.path.isfile(meta)):
        for attempt in range(4):
            try:
                words = asyncio.run(_tts(text, voice, rate, pitch, mp3))
                break
            except Exception:
                if attempt == 3:
                    raise
                time.sleep(2 + 2 * attempt)
        with open(meta, "w", encoding="utf-8") as f:
            json.dump({"text": text, "voice": voice, "words": words}, f, ensure_ascii=False)
    with open(meta, encoding="utf-8") as f:
        words = [tuple(w) for w in json.load(f)["words"]]
    return decode_audio(mp3, sampling_rate=RATE), words


def stretch(x, factor):
    """[x] played [factor] times as long at the same pitch (a phase vocoder): a held, sung-like note."""
    import torch
    import torchaudio
    if factor <= 1.01 or len(x) < 2048:
        return x
    n_fft, hop = 1024, 256
    t = torch.from_numpy(np.ascontiguousarray(x, dtype=np.float32))
    win = torch.hann_window(n_fft)
    spec = torch.stft(t, n_fft, hop, window=win, return_complex=True)
    phase_adv = torch.linspace(0, np.pi * hop, spec.shape[0])[..., None]
    out = torchaudio.functional.phase_vocoder(spec, 1.0 / factor, phase_adv)
    y = torch.istft(out, n_fft, hop, window=win, length=int(len(x) * factor))
    return y.numpy().astype(np.float32)


def vibrato(x, rate_hz=5.5, depth=0.004):
    """A singer's wobble of pitch: [x] read at a speed that swings around 1."""
    n = len(x)
    t = np.arange(n) / RATE
    pos = np.arange(n) + depth * RATE / (2 * np.pi * rate_hz) * np.sin(2 * np.pi * rate_hz * t)
    return np.interp(np.clip(pos, 0, n - 1), np.arange(n), x).astype(np.float32)


def _norm_word(w):
    return re.sub(r"[^\w]", "", (w or "").lower())


def _token_times(tokens, words, offset):
    """The voice's words given to the line's whitespace tokens in order (by their letters): [(start, end) or None]."""
    out = [None] * len(tokens)
    j = 0
    for i, tok in enumerate(tokens):
        nt = _norm_word(tok)
        if not nt:
            continue
        # a token may be several of the voice's words ("oh-ooh") or one word may be split around an apostrophe
        k = j
        while k < len(words) and not _norm_word(words[k][0]):
            k += 1
        if k >= len(words):
            break
        first = k
        acc = _norm_word(words[k][0])
        while len(acc) < len(nt) and k + 1 < len(words) and nt.startswith(acc):
            k += 1
            acc += _norm_word(words[k][0])
        out[i] = (offset + words[first][1], offset + words[k][2])
        j = k + 1
    return out


# ---------------------------------------------------------------------------------------------- the band
def _saw(f, t):
    return 2.0 * ((f * t) % 1.0) - 1.0


def band(seconds, seed, lead=True):
    """A pop-ish backing: bass, chords, kick, snare, hats and (lead) a wordless sung-like "ooh" with vibrato that comes
    and goes (a backing singer or a synth lead: what a vocal separator also calls a voice)."""
    import torch
    import torchaudio
    rng = np.random.default_rng(seed)
    n = int(seconds * RATE)
    t = np.arange(n) / RATE
    y = np.zeros(n, dtype=np.float32)
    bpm = rng.uniform(85, 125)
    beat = 60.0 / bpm
    root = rng.uniform(98, 140)
    prog = [0, 5, 7, 3]
    bar = beat * 4
    for i in range(int(seconds / bar) + 1):
        s = int(i * bar * RATE)
        L = min(int(bar * RATE), n - s)
        if L <= 0:
            break
        f = root * 2 ** (prog[i % 4] / 12)
        tt = t[:L]
        y[s:s + L] += 0.30 * _saw(f / 2, tt) * np.exp(-tt * 0.6)
        y[s:s + L] += sum(0.07 * _saw(f * 2 ** (iv / 12) * 2, tt) for iv in (0, 4, 7))
    y = torchaudio.functional.lowpass_biquad(torch.from_numpy(y), RATE, 2500.0).numpy()
    for k in range(int(seconds / beat)):
        s = int(k * beat * RATE)
        if s >= n:
            break
        L = min(int(0.25 * RATE), n - s)
        kk = np.arange(L) / RATE
        y[s:s + L] += 0.9 * np.sin(2 * np.pi * (55 + 60 * np.exp(-kk * 30)) * kk) * np.exp(-kk * 18)
        if k % 2 == 1:
            L2 = min(int(0.15 * RATE), n - s)
            y[s:s + L2] += 0.35 * rng.standard_normal(L2) * np.exp(-np.arange(L2) / RATE * 30)
        h = s + int(beat / 2 * RATE)
        if h + 600 < n:
            y[h:h + 600] += 0.12 * rng.standard_normal(600) * np.exp(-np.arange(600) / 120)
    if lead:
        ld = np.zeros(n, dtype=np.float32)
        st = rng.uniform(2, 6)
        while st < seconds - 2:
            d = rng.uniform(1.0, 3.0)
            s = int(st * RATE)
            L = min(int(d * RATE), n - s)
            u = np.arange(L) / RATE
            f = root * 2 * 2 ** (rng.integers(0, 8) / 12)
            fv = f * (1 + 0.012 * np.sin(2 * np.pi * 5.5 * u))
            ph = 2 * np.pi * np.cumsum(fv) / RATE
            v = sum(np.sin(h * ph) / h ** 1.2 for h in range(1, 14)).astype(np.float32)
            vt = torch.from_numpy(v)
            v = (torchaudio.functional.bandpass_biquad(vt, RATE, 600.0, 2.0) + 0.6 * torchaudio.functional.bandpass_biquad(vt, RATE, 1100.0, 3.0)).numpy()
            env = np.minimum(1, u / 0.08) * np.minimum(1, (d - u) / 0.2)
            ld[s:s + L] += v * env
            st += d + rng.uniform(2.0, 8.0)
        y += ld / (np.std(ld) + 1e-9) * np.std(y) * 0.35
    return (y / (np.sqrt(np.mean(y ** 2)) + 1e-9)).astype(np.float32)


def room(x, seed, tail=0.35, wet=0.25):
    """A vocal's reverb: decaying noise as the room's answer."""
    rng = np.random.default_rng(seed)
    L = int(tail * RATE)
    ir = rng.standard_normal(L).astype(np.float32) * np.exp(-np.arange(L) / (tail * RATE / 5))
    ir = ir / np.sqrt(np.sum(ir ** 2)) * wet
    ir[0] += 1.0
    import torch
    import torchaudio
    y = torchaudio.functional.fftconvolve(torch.from_numpy(x), torch.from_numpy(ir)).numpy()[: len(x)]
    return y.astype(np.float32)


# ---------------------------------------------------------------------------------------------- the song
def _sing(toks, voice, rnd, hold, pauses, work):
    """One line sung: ([(at, audio)] pieces from the line's start, per token (start, end) or None, the first word's
    start, [(token before a pause, pause start, pause end)], its length, the gap after it) — times from the line's
    start."""
    cut = None
    if len(toks) >= 4 and rnd.random() < pauses:
        cut = rnd.randint(2, len(toks) - 2)   # (a pause between two whole tokens)
    parts = [(0, len(toks))] if cut is None else [(0, cut), (cut, len(toks))]
    rate = "%+d%%" % rnd.randint(-20, 5)
    pitch = "%+dHz" % rnd.randint(-8, 8)
    out, gaps = [], []
    line_truth = [None] * len(toks)
    line_start = None
    t = 0.0
    for pi, (a, b) in enumerate(parts):
        x, words = spoken(" ".join(toks[a:b]), voice, rate, pitch, work)
        tt = _token_times(toks[a:b], words, 0.0)
        # the part's own silence at its start and end cut away, so a pause is what we put in
        known = [w for w in tt if w]
        if not known:
            continue
        head = max(0.0, known[0][0] - 0.05)
        x = x[int(head * RATE):]
        tt = [None if w is None else (w[0] - head, w[1] - head) for w in tt]
        last_i = max(i for i, w in enumerate(tt) if w)
        x = x[: int((tt[last_i][1] + 0.08) * RATE)]
        if hold and rnd.random() < 0.7:
            # the piece's last word held as a singer holds a note: its first consonant as spoken, the rest (the vowel
            # on) stretched, with a wobble
            f = rnd.uniform(2.0, 3.2)
            w0, w1 = tt[last_i]
            s0 = int((w0 + 0.3 * (w1 - w0)) * RATE)
            held = vibrato(stretch(x[s0:], f))
            x = np.concatenate([x[:s0], held])
            tt[last_i] = (w0, s0 / RATE + (w1 - s0 / RATE) * f)
        fade = min(len(x), int(0.03 * RATE))
        x[-fade:] *= np.linspace(1, 0, fade)
        out.append((t, x))
        for i, w in enumerate(tt):
            if w:
                line_truth[a + i] = (t + w[0], t + w[1])
        if line_start is None:
            line_start = t + next(w for w in tt if w)[0]
        t += len(x) / RATE
        if pi + 1 < len(parts):
            gap = rnd.uniform(0.6, 2.0)
            prev = max(i for i in range(a, b) if line_truth[i])
            gaps.append((prev, t, t + gap))
            t += gap
    return out, line_truth, line_start or 0.0, gaps, t, rnd.uniform(0.3, 1.4)


def make_song(lines, lang, seed=1, voice=None, vocal_db=2.0, lead=True, source_error=0.25, hold=True, pauses=0.35,
              intro=8.0, work=WORK):
    """The test song of [lines] ([lang] "en" or "ar"), the voice [vocal_db] dB over the band. Each sung line: spoken
    at a pace of its own, a pause in the middle of some ([pauses] of them, 0.6-2 s), the last word of each piece held
    2-3 times as long ([hold]). [given]: each line's true start moved by up to [source_error] s (a source's error)."""
    rnd = random.Random(seed)
    voice = voice or VOICES[lang][seed % len(VOICES[lang])]
    pieces = []          # (at, audio)
    truth, starts, given, gaps = [], [], [], []
    sung = {}            # a line's text -> how it was sung (a chorus is sung again the same way)
    t = intro
    for k, line in enumerate(lines):
        toks = line.split()
        if not toks:
            truth.append(None)
            starts.append(None)
            given.append(round(t, 2))
            t += rnd.uniform(3.0, 6.0)
            continue
        if line not in sung:
            sung[line] = _sing(toks, voice, rnd, hold, pauses, work)
        own, line_truth_rel, line_start_rel, gaps_rel, length, after = sung[line]
        for at, x in own:
            pieces.append((t + at, x))
        line_truth = [None if w is None else (round(t + w[0], 3), round(t + w[1], 3)) for w in line_truth_rel]
        for (prev, a, b) in gaps_rel:
            gaps.append((k, prev, round(t + a, 3), round(t + b, 3)))
        truth.append(line_truth)
        starts.append(round(t + line_start_rel, 3))
        given.append(round(t + line_start_rel + rnd.uniform(-source_error, source_error), 2))
        t += length + after
    total = t + 4.0
    vox = np.zeros(int(total * RATE) + RATE, dtype=np.float32)
    for at, x in pieces:
        s = int(at * RATE)
        vox[s:s + len(x)] += x
    vox = room(vox, seed)
    v_rms = np.sqrt(np.mean(vox[np.abs(vox) > 1e-3] ** 2)) + 1e-9
    bd = band(len(vox) / RATE + 0.01, seed, lead=lead)[: len(vox)]
    mix = vox + bd * v_rms / (10 ** (vocal_db / 20.0))
    peak = np.abs(mix).max() + 1e-9
    g = 0.89 / peak
    return Song((mix * g).astype(np.float32), list(lines), truth, starts, given, gaps, (vox * g).astype(np.float32))


# ---------------------------------------------------------------------------------------------- the car's microphone
def car_mic(x, noise_path, snr_db, level_dbfs=-30.0, seed=1):
    """[x] as a car's microphone hears it: road noise at [snr_db] under the song, the highs lost (the cabin and a
    small microphone), the level of a quiet capture."""
    import torch
    import torchaudio
    from faster_whisper import decode_audio
    noise = decode_audio(noise_path, sampling_rate=RATE)
    rng = np.random.default_rng(seed)
    if len(noise) < len(x):
        noise = np.tile(noise, len(x) // len(noise) + 1)
    s = int(rng.integers(0, len(noise) - len(x) + 1))
    noise = noise[s:s + len(x)]
    xs = np.sqrt(np.mean(x ** 2)) + 1e-9
    ns = np.sqrt(np.mean(noise ** 2)) + 1e-9
    y = x + noise * xs / ns / (10 ** (snr_db / 20.0))
    y = torchaudio.functional.lowpass_biquad(torch.from_numpy(y.astype(np.float32)), RATE, 3800.0).numpy()
    y = torchaudio.functional.highpass_biquad(torch.from_numpy(y), RATE, 120.0).numpy()
    y = y / (np.sqrt(np.mean(y ** 2)) + 1e-9) * 10 ** (level_dbfs / 20.0)
    return np.clip(y, -1, 1).astype(np.float32)
