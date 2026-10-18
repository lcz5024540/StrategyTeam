"""Limit Arrow/OMP threads and release pools so process exit code is reliable."""

from __future__ import annotations

import atexit
import os

_configured = False


def configure_arrow() -> None:
    global _configured
    if _configured:
        return
    _configured = True
    os.environ.setdefault("ARROW_NUM_THREADS", "1")
    os.environ.setdefault("OMP_NUM_THREADS", "1")
    os.environ.setdefault("OPENBLAS_NUM_THREADS", "1")
    try:
        import pyarrow as pa
    except Exception:
        return
    try:
        pa.set_cpu_count(1)
        setter = getattr(pa, "set_io_thread_count", None)
        if callable(setter):
            setter(1)
    except Exception:
        pass
    atexit.register(_cleanup_arrow)


def _cleanup_arrow() -> None:
    try:
        import pyarrow as pa
    except Exception:
        return
    try:
        pa.set_cpu_count(1)
        setter = getattr(pa, "set_io_thread_count", None)
        if callable(setter):
            setter(1)
        pool = pa.default_memory_pool()
        release = getattr(pool, "release_unused", None)
        if callable(release):
            release()
    except Exception:
        return
