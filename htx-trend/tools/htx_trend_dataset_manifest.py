#!/usr/bin/env python3
"""Create a content-addressed manifest for frozen HTX strategy research inputs."""
from __future__ import annotations

import argparse
import hashlib
import json
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

from htx_trend_backtest import load_bars
from htx_multitimeframe_profitability_backtest import derive_daily_from_4hour


TZ = ZoneInfo("Asia/Shanghai")
SECONDS = {"15min": 900, "30min": 1800, "60min": 3600, "4hour": 14400, "1day": 86400}


def epoch(value: str) -> int:
    return int(datetime.fromisoformat(value).replace(tzinfo=TZ).timestamp())


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def build_manifest(data_dir: Path, symbols: list[str], periods: list[str], start_at: str, end_at: str) -> dict:
    start, end = epoch(start_at), epoch(end_at)
    inputs, missing = [], []
    digest = hashlib.sha256()
    for symbol in sorted(symbols):
        for period in periods:
            path = data_dir / f"{symbol}_{period}.json"
            if not path.exists():
                if period != "1day":
                    missing.append(f"{symbol}:{period}:missing-file")
                    continue
                source_path = data_dir / f"{symbol}_4hour.json"
                if not source_path.exists():
                    missing.append(f"{symbol}:{period}:missing-file")
                    continue
                bars = derive_daily_from_4hour(load_bars(data_dir, symbol, "4hour"))
                source_kind, file_name, checksum = "DERIVED_4H", source_path.name, sha256(source_path)
            else:
                bars = load_bars(data_dir, symbol, period)
                source_kind, file_name, checksum = "RAW_PUBLIC", path.name, sha256(path)
            coverage = bool(bars) and bars[0].ts <= start and bars[-1].ts + SECONDS[period] >= end
            digest.update(f"{symbol}:{period}:{source_kind}:{checksum}\n".encode())
            inputs.append({"contract": symbol, "period": period, "file": file_name, "sourceKind": source_kind, "sha256": checksum,
                           "count": len(bars), "firstBarTime": bars[0].ts if bars else None,
                           "lastBarTime": bars[-1].ts if bars else None, "coverage": "COMPLETE" if coverage else "PARTIAL"})
            if not coverage:
                missing.append(f"{symbol}:{period}:insufficient-coverage")
    checksum = digest.hexdigest()
    return {"schema": "htx-trend-research-dataset-manifest-v1", "datasetKey": f"research:{checksum[:32]}",
            "sourceKind": "FROZEN_PUBLIC_KLINE", "timezone": "Asia/Shanghai", "inputStartAt": start_at,
            "inputEndAt": end_at, "contracts": sorted(symbols), "periods": periods,
            "inputChecksum": checksum, "inputIntegrity": "COMPLETE" if not missing else "PARTIAL",
            "inputs": inputs, "missingOrPartial": missing}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--data-dir", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--symbols", required=True)
    parser.add_argument("--periods", default="15min,30min,60min,4hour,1day")
    parser.add_argument("--start", required=True)
    parser.add_argument("--end", required=True)
    args = parser.parse_args()
    manifest = build_manifest(args.data_dir, [value.strip() for value in args.symbols.split(",") if value.strip()],
                              [value.strip() for value in args.periods.split(",") if value.strip()], args.start, args.end)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(args.out)


if __name__ == "__main__":
    main()
