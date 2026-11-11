#!/usr/bin/env python3
"""Operator entrypoint for same-trial-id SBOX reseal.

Delegates to ``htx_trend.realtime.trial_reseal``. Does not SSH, does not edit
PROC_ENV, and does not mint a trial id. See release/RESEAL-SAME-TRIAL.md.
"""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from htx_trend.realtime.trial_reseal import main  # noqa: E402


if __name__ == "__main__":
    sys.exit(main())
