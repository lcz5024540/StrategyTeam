"""Deterministic hashes for smoke replay: drop identifiers, stable order."""

from __future__ import annotations

import hashlib
from typing import Iterable

import pandas as pd

IDENTIFIER_COLUMNS = frozenset({"run_id"})
EXECUTION_TIME_COLUMNS = frozenset(
    {"generated_at", "created_at", "recorded_at", "first_seen_at"}
)
DEFAULT_DROP = IDENTIFIER_COLUMNS | EXECUTION_TIME_COLUMNS
SORT_PREFERENCE = ("symbol", "bar_time", "time", "side", "action", "is_entry")


def stable_frame_hash(
    df: pd.DataFrame,
    drop_columns: Iterable[str] | None = None,
) -> str:
    if df is None or df.empty:
        return hashlib.sha256(b"empty").hexdigest()
    drop = set(DEFAULT_DROP if drop_columns is None else drop_columns)
    work = df.drop(columns=[col for col in drop if col in df.columns], errors="ignore")
    work = work.reindex(sorted(work.columns), axis=1)
    sort_cols = [col for col in SORT_PREFERENCE if col in work.columns]
    if sort_cols:
        work = work.sort_values(sort_cols, kind="mergesort").reset_index(drop=True)
    else:
        work = work.reset_index(drop=True)
    payload = work.to_json(orient="records", date_format="iso")
    return hashlib.sha256(payload.encode()).hexdigest()


def hash_parquet(path, drop_columns: Iterable[str] | None = None) -> str:
    from pathlib import Path

    file_path = Path(path)
    if not file_path.exists():
        return "missing"
    return stable_frame_hash(pd.read_parquet(file_path), drop_columns=drop_columns)
