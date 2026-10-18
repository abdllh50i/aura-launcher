"""The PC's graphics card, when it has one: where the speech, voice and alignment models run.

The owner's PC has an RTX 3090, but other programs use most of its memory (about 14.5 of 24 GB): the server keeps to a
few GB (about 2.2 with the three models loaded: whisper with int8 weights, the voice separator, the aligner in
float16; a little more while working), loads each model once, at its first use, and keeps it. When the card has no
memory left for a job, that one job is done on the processor (slower, never a crash); the next job tries the card
again. Without a usable card (no NVIDIA driver, torch without CUDA, or AMRI_LYRICS_CPU=1) everything runs on the
processor. (On Windows the card's memory is charged to the process as well: the server takes about 5 GB of the PC's
memory in all.)

faster-whisper (CTranslate2) on CUDA needs cuBLAS and cuDNN 9: torch's CUDA wheels carry them (torch/lib), so torch is
imported before CTranslate2 is and its folder is added to the DLL search path.
"""
import os
import sys
import threading

_lock = threading.Lock()
_state = {}


def torch():
    """The torch module, or None when it is not installed (the server then times lines only, no words)."""
    with _lock:
        if "torch" not in _state:
            try:
                import torch as t
                if sys.platform == "win32":
                    lib = os.path.join(os.path.dirname(t.__file__), "lib")
                    if os.path.isdir(lib):
                        try:
                            os.add_dll_directory(lib)
                        except OSError:
                            pass
                _state["torch"] = t
            except Exception as e:  # (an install without torch, or a broken one: say so once)
                print("  torch is not usable (%s): no vocal separation, no word times" % e, flush=True)
                _state["torch"] = None
        return _state["torch"]


def cuda():
    """A CUDA card is there and may be used."""
    if "cuda" not in _state:
        t = torch()
        ok = False
        if t is not None and os.environ.get("AMRI_LYRICS_CPU", "") not in ("1", "true", "yes"):
            try:
                ok = bool(t.cuda.is_available())
            except Exception:
                ok = False
        _state["cuda"] = ok
    return _state["cuda"]


def device():
    return "cuda" if cuda() else "cpu"


def is_oom(e):
    """[e] says the card ran out of memory (torch's own error, or CTranslate2's / cuBLAS's / cuDNN's words for it)."""
    t = _state.get("torch")
    if t is not None and isinstance(e, getattr(t.cuda, "OutOfMemoryError", ())):
        return True
    msg = str(e).lower()
    return "out of memory" in msg or "alloc_failed" in msg or "cudaerrormemoryallocation" in msg


def release():
    """What torch keeps cached on the card is given back (other programs share it)."""
    t = _state.get("torch")
    if t is not None and _state.get("cuda"):
        try:
            t.cuda.empty_cache()
        except Exception:
            pass


def free_gb():
    """Free memory on the card (GB), or None."""
    t = _state.get("torch")
    if t is None or not _state.get("cuda"):
        return None
    try:
        return t.cuda.mem_get_info()[0] / 1e9
    except Exception:
        return None


def name():
    t = _state.get("torch")
    if t is None or not cuda():
        return "CPU"
    try:
        return t.cuda.get_device_name(0)
    except Exception:
        return "CUDA"
