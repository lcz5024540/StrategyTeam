#!/usr/bin/env python3
"""Freeze completed runtime K-lines into a content-addressed MS-AR research dataset.

The tool reads only ``htx_trend_runtime_kline_candle`` through the local MySQL
client.  It picks the first observed content version for every
contract/period/bar, excludes an active bar at the supplied as-of time, and
writes the ordinary JSON envelope consumed by offline research tools.  It never
writes the database, calls an exchange, or imports the live strategy.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
from collections import defaultdict
from datetime import datetime
from pathlib import Path
from typing import Iterable
from zoneinfo import ZoneInfo


SCHEMA = "htx-trend-research-dataset-manifest-v1"
PERIOD_SECONDS = {"4hour": 14_400, "1day": 86_400}
TZ = ZoneInfo("Asia/Shanghai")


def parse_timestamp(value: str) -> int:
    parsed = datetime.fromisoformat(value)
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=TZ)
    return int(parsed.timestamp())


def completed_end(as_of: int, period: str) -> int:
    """Exclusive open-time boundary: every retained bar is complete at ``as_of``."""
    seconds = PERIOD_SECONDS[period]
    return as_of // seconds * seconds


def checksum(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def valid_contract(value: str) -> str:
    candidate = value.strip().upper()
    if not re.fullmatch(r"[A-Z0-9]+-USDT", candidate):
        raise ValueError(f"invalid contract code: {value!r}")
    return candidate


def _sql(contracts: list[str], ends: dict[str, int]) -> str:
    quoted_contracts = ", ".join("'" + contract + "'" for contract in contracts)
    bounds = " OR ".join(f"(period = '{period}' AND bar_time < {ends[period]})" for period in PERIOD_SECONDS)
    return f"""
SELECT contract_code, period, bar_time, open_price, high_price, low_price, close_price,
       amount, volume, turnover, trade_count, candle_hash, captured_at
FROM (
  SELECT contract_code, period, bar_time, open_price, high_price, low_price, close_price,
         amount, volume, turnover, trade_count, candle_hash, captured_at, id,
         ROW_NUMBER() OVER (PARTITION BY contract_code, period, bar_time ORDER BY captured_at ASC, id ASC) AS version_rank
  FROM htx_trend_runtime_kline_candle
  WHERE contract_code IN ({quoted_contracts}) AND ({bounds})
) chosen
WHERE version_rank = 1
ORDER BY contract_code, period, bar_time
""".strip()


def mysql_rows(contracts: list[str], ends: dict[str, int], database: str, environ: dict[str, str] | None = None) -> list[list[str]]:
    environment = dict(os.environ if environ is None else environ)
    required = ("HTX_DB_HOST", "HTX_DB_PORT", "HTX_DB_USERNAME", "HTX_DB_PASSWORD")
    missing = [name for name in required if not environment.get(name)]
    if missing:
        raise ValueError("missing database environment: " + ", ".join(missing))
    command = ["mysql", "--batch", "--skip-column-names", "--raw", "--host", environment["HTX_DB_HOST"],
               "--port", environment["HTX_DB_PORT"], "--user", environment["HTX_DB_USERNAME"], database, "--execute", _sql(contracts, ends)]
    child_environment = dict(environment)
    child_environment["MYSQL_PWD"] = environment["HTX_DB_PASSWORD"]
    result = subprocess.run(command, capture_output=True, check=False, text=True, env=child_environment)
    if result.returncode != 0:
        raise RuntimeError("runtime K-line export query failed: " + result.stderr.strip())
    rows = [line.split("\t") for line in result.stdout.splitlines() if line.strip()]
    if any(len(row) != 13 for row in rows):
        raise ValueError("runtime K-line export returned malformed rows")
    return rows


def _bar(row: list[str]) -> dict:
    return {"id": int(row[2]), "open": row[3], "high": row[4], "low": row[5], "close": row[6],
            "amount": None if row[7] == "NULL" else row[7], "vol": None if row[8] == "NULL" else row[8],
            "trade_turnover": None if row[9] == "NULL" else row[9], "count": None if row[10] == "NULL" else int(row[10])}


def write_dataset(rows: Iterable[list[str]], output_dir: Path, contracts: list[str], starts: dict[str, int], ends: dict[str, int],
                  as_of: str, exclusions: list[dict]) -> dict:
    grouped: dict[tuple[str, str], list[dict]] = defaultdict(list)
    for row in rows:
        grouped[(row[0], row[1])].append(_bar(row))
    inputs, failures = [], []
    for contract in contracts:
        for period, seconds in PERIOD_SECONDS.items():
            bars = sorted(grouped[(contract, period)], key=lambda item: item["id"])
            expected = list(range(starts[period], ends[period], seconds))
            actual = [bar["id"] for bar in bars]
            coverage = "COMPLETE" if actual == expected else "PARTIAL"
            path = output_dir / f"{contract}_{period}.json"
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps({"status": "ok", "ch": f"runtime.{contract}.kline.{period}", "data": bars},
                                       ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            inputs.append({"contract": contract, "period": period, "file": path.name, "sourceKind": "RUNTIME_FIRST_OBSERVED_CLOSED",
                           "sha256": checksum(path), "count": len(bars), "firstBarTime": actual[0] if actual else None,
                           "lastBarTime": actual[-1] if actual else None, "coverage": coverage,
                           "windowStart": starts[period], "windowEndExclusive": ends[period]})
            if coverage != "COMPLETE":
                failures.append(f"{contract}:{period}:expected={len(expected)} actual={len(actual)}")
    digest = hashlib.sha256()
    for item in sorted(inputs, key=lambda value: (value["contract"], value["period"])):
        digest.update(f"{item['contract']}:{item['period']}:{item['sha256']}\n".encode())
    digest.update(json.dumps(exclusions, sort_keys=True, separators=(",", ":")).encode())
    value = {"schema": SCHEMA, "datasetKey": f"research:{digest.hexdigest()[:32]}", "sourceKind": "FROZEN_RUNTIME_KLINE",
             "timezone": "Asia/Shanghai", "asOf": as_of, "runtimeVersionSelection": "FIRST_OBSERVED_CLOSED_VERSION",
             "inputStartAt": datetime.fromtimestamp(min(starts.values()), TZ).isoformat(),
             "inputEndAt": datetime.fromtimestamp(max(ends.values()), TZ).isoformat(), "periodWindows": {
                 period: {"start": starts[period], "endExclusive": ends[period]} for period in PERIOD_SECONDS},
             "contracts": contracts, "periods": list(PERIOD_SECONDS), "inputChecksum": digest.hexdigest(),
             "inputIntegrity": "COMPLETE" if not failures else "PARTIAL", "researchUniverse": {
                 "schema": "htx-trend-research-universe-v1", "contracts": contracts, "excludedContracts": exclusions},
             "inputs": inputs, "missingOrPartial": failures}
    manifest = output_dir / "dataset-manifest.json"
    manifest.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return value


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--as-of", required=True, help="Frozen Asia/Shanghai or offset timestamp")
    parser.add_argument("--contracts", required=True, help="Comma-separated research universe")
    parser.add_argument("--daily-start", required=True)
    parser.add_argument("--four-hour-start", required=True)
    parser.add_argument("--database", default="htx_trade_strategy")
    parser.add_argument("--excluded-contracts-json", type=Path, required=True)
    args = parser.parse_args()
    contracts = [valid_contract(item) for item in args.contracts.split(",") if item.strip()]
    if len(contracts) < 2 or len(set(contracts)) != len(contracts):
        raise ValueError("research universe must contain at least two unique contracts")
    exclusions = json.loads(args.excluded_contracts_json.read_text(encoding="utf-8"))
    if not isinstance(exclusions, list):
        raise ValueError("excluded contracts file must be a JSON list")
    starts = {"1day": parse_timestamp(args.daily_start), "4hour": parse_timestamp(args.four_hour_start)}
    as_of_epoch = parse_timestamp(args.as_of)
    ends = {period: completed_end(as_of_epoch, period) for period in PERIOD_SECONDS}
    if any(starts[period] >= ends[period] for period in PERIOD_SECONDS):
        raise ValueError("as-of time precedes a requested research window")
    manifest = write_dataset(mysql_rows(contracts, ends, args.database), args.out, contracts, starts, ends, args.as_of, exclusions)
    print(json.dumps({"datasetKey": manifest["datasetKey"], "inputIntegrity": manifest["inputIntegrity"],
                      "contracts": len(contracts), "periodWindows": manifest["periodWindows"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
