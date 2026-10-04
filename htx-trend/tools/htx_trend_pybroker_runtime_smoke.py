#!/usr/bin/env python3
"""Verify that an isolated PyBroker runtime can read a verified frozen CSV.

This is deliberately a runtime compatibility check, not a strategy backtest. It does not
create orders, calculate PnL, contact HTX, or assert that a model is profitable.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


REQUIRED_COLUMNS = ("date", "symbol", "open", "high", "low", "close")


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify_runtime(csv_path: Path, start_date: str, end_date: str, symbols: list[str]) -> dict:
    try:
        import pandas as pd
        from pybroker.data import DataSource
    except ImportError as exc:
        raise RuntimeError(
            "PyBroker runtime is unavailable; run this only in an isolated Python 3.11+ environment"
        ) from exc

    class FrozenCsvDataSource(DataSource):
        def _fetch_data(self, requested_symbols, start, end, _timeframe, _adjust):
            frame = pd.read_csv(csv_path)
            missing = [column for column in REQUIRED_COLUMNS if column not in frame.columns]
            if missing:
                raise ValueError(f"frozen CSV missing required columns: {','.join(missing)}")
            frame["date"] = pd.to_datetime(frame["date"], utc=True)
            start_utc = pd.Timestamp(start, tz="UTC") if getattr(start, "tzinfo", None) is None else pd.Timestamp(start)
            end_utc = pd.Timestamp(end, tz="UTC") if getattr(end, "tzinfo", None) is None else pd.Timestamp(end)
            return frame[(frame["symbol"].isin(requested_symbols))
                         & (frame["date"] >= start_utc)
                         & (frame["date"] <= end_utc)].copy()

    source = FrozenCsvDataSource()
    frame = source.query(symbols, start_date, end_date, timeframe="15min")
    if frame.empty:
        raise ValueError("PyBroker runtime check returned no frozen bars")
    return {
        "schema": "htx-trend-pybroker-runtime-smoke-v1",
        "executionBoundary": "READ_ONLY_INTERNAL_RESEARCH_ONLY",
        "result": "PASS",
        "csv": str(csv_path),
        "csvSha256": sha256(csv_path),
        "rowCount": len(frame),
        "symbols": sorted(frame["symbol"].unique().tolist()),
        "firstBar": frame["date"].min().isoformat(),
        "lastBar": frame["date"].max().isoformat(),
        "statement": "Runtime compatibility only; no orders, PnL, Walk-forward, Bootstrap, or model policy result.",
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--csv", type=Path, required=True)
    parser.add_argument("--start", required=True)
    parser.add_argument("--end", required=True)
    parser.add_argument("--symbols", required=True, help="comma-separated contract codes")
    parser.add_argument("--report-out", type=Path, required=True)
    args = parser.parse_args()
    report = verify_runtime(args.csv, args.start, args.end,
                            [symbol.strip() for symbol in args.symbols.split(",") if symbol.strip()])
    args.report_out.parent.mkdir(parents=True, exist_ok=True)
    args.report_out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
