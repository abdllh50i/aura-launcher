"""The singer's voice alone, for the recogniser and the word aligner (1.16).

htdemucs (Demucs v4, the hybrid transformer one) splits a song into drums, bass, other and vocals; only the vocals are
kept. The car sends 16 kHz mono and htdemucs works at 44.1 kHz stereo: the piece is resampled up, given as both
channels, and its vocals are resampled back to 16 kHz mono (a piece without the band's highs is separated well enough:
it is how a song sounds through a phone).

On the graphics card a 12 s piece takes about 0.3 s (under 1 GB); a whole song file a few seconds. The model (80 MB)
is fetched once into TORCH_HOME (beside the repository, see server.py).
"""
import threading
import time

import numpy as np

import gpu

RATE = 16000


def dbfs(x):
    """Loudness of [x] (float, full scale 1.0): RMS in dB, -120 for silence."""
    if x is None or len(x) == 0:
        return -120.0
    r = float(np.sqrt(np.mean(np.square(x, dtype=np.float64))))
    return round(max(-120.0, 20.0 * np.log10(r + 1e-12)), 1)


def peak_dbfs(x):
    if x is None or len(x) == 0:
        return -120.0
    p = float(np.max(np.abs(x)))
    return round(max(-120.0, 20.0 * np.log10(p + 1e-12)), 1)


class Separator:
    """htdemucs, loaded at its first use and kept (one copy per device: the processor's only when the card was full)."""

    def __init__(self, log=print):
        self.models = {}
        self.lock = threading.Lock()
        self.log = log

    def model(self, dev):
        with self.lock:
            m = self.models.get(dev)
            if m is None:
                from demucs.pretrained import get_model
                t0 = time.time()
                m = get_model("htdemucs")
                m.eval()
                m.to(dev)
                self.models[dev] = m
                self.log("  voice separator htdemucs ready on %s in %.1f s" % (dev, time.time() - t0))
            return m

    def vocals(self, audio):
        """The vocals of [audio] (16 kHz mono float32), as long as it."""
        if gpu.torch() is None:
            raise RuntimeError("torch is not installed")
        dev = gpu.device()
        try:
            return self._run(audio, dev)
        except Exception as e:
            if dev != "cuda" or not gpu.is_oom(e):
                raise
            gpu.release()
            self.log("  the graphics card is full: this piece's voice is separated on the processor")
            return self._run(audio, "cpu")

    def _run(self, audio, dev):
        torch = gpu.torch()
        import torchaudio
        from demucs.apply import apply_model
        m = self.model(dev)
        n = len(audio)
        with torch.inference_mode():
            x = torch.from_numpy(np.ascontiguousarray(audio, dtype=np.float32)).to(dev)
            up = torchaudio.functional.resample(x, RATE, m.samplerate)
            sd = up.std() + 1e-8
            # (as demucs's own separate.py: the mix brought to unit variance, the sources scaled back)
            wav = (up / sd)[None, None].expand(1, 2, -1).contiguous()
            out = apply_model(m, wav, device=dev, shifts=0, split=True, overlap=0.25, progress=False)[0]
            v = out[m.sources.index("vocals")].mean(0) * sd
            v16 = torchaudio.functional.resample(v, m.samplerate, RATE)
            y = v16.float().cpu().numpy()
        del x, up, wav, out, v, v16
        if len(y) < n:
            y = np.concatenate([y, np.zeros(n - len(y), dtype=np.float32)])
        return y[:n].astype(np.float32)
