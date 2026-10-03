#!/usr/bin/env python3
"""Read-only, resumable collector for immutable HTX public kline research datasets.

The collector stores each untouched page under ``raw/`` and produces one sorted,
deduplicated envelope per contract/timeframe for the existing manifest tool.  It
never reads account configuration or sends a trading request.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import json
import re
import threading
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable, Iterable
from urllib.error import URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from zoneinfo import ZoneInfo

from htx_trend_dataset_manifest import build_manifest, canonical_universe, universe_sha256

TZ = ZoneInfo("Asia/Shanghai")
PERIOD_SECONDS = {"15min": 900, "30min": 1800, "60min": 3600, "4hour": 14400, "1day": 86400}
DEFAULT_PERIODS = tuple(PERIOD_SECONDS)
DEFAULT_ENDPOINTS = ("https://api.hbdm.vn", "https://api.hbdm.com")
STATE_SCHEMA = "htx-trend-research-collector-state-v1"


@dataclass(frozen=True)
class CollectionTarget:
    symbol: str
    period: str


class CollectionError(RuntimeError):
    def __init__(self, message: str, payload: dict | None = None):
        super().__init__(message)
        self.payload = payload


def epoch(value: str) -> int:
    """Parse an Asia/Shanghai ISO-8601 timestamp into a Unix timestamp."""
    parsed = datetime.fromisoformat(value)
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=TZ)
    return int(parsed.timestamp())


def format_epoch(value: int) -> str:
    return datetime.fromtimestamp(value, TZ).strftime("%Y%m%dT%H%M%S")


def load_default_symbols(application_yml: Path) -> list[str]:
    text = application_yml.read_text(encoding="utf-8")
    match = re.search(r"contract-codes:\s*\$\{HTX_TREND_CONTRACTS:([^}]*)}", text)
    if not match:
        raise CollectionError(f"HTX trend contract whitelist was not found in {application_yml}")
    symbols = [part.strip() for part in match.group(1).split(",") if part.strip()]
    if len(symbols) < 50:
        raise CollectionError(f"research universe unexpectedly contains only {len(symbols)} contracts")
    return symbols


def atomic_json_write(path: Path, payload: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    tmp.replace(path)


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def normalize_bars(payload: dict, lower: int, upper: int) -> list[dict]:
    if payload.get("status") != "ok":
        raise CollectionError(f"HTX returned non-ok response: {payload.get('err_code')} {payload.get('err_msg')}")
    data = payload.get("data")
    if not isinstance(data, list):
        raise CollectionError("HTX response does not contain a data array")
    by_id: dict[int, dict] = {}
    for row in data:
        try:
            ts = int(row["id"])
            float(row["open"]); float(row["high"]); float(row["low"]); float(row["close"])
        except (KeyError, TypeError, ValueError):
            continue
        if lower <= ts < upper:
            by_id[ts] = row
    return [by_id[key] for key in sorted(by_id)]


def merge_raw_pages(raw_dir: Path, lower: int, upper: int) -> list[dict]:
    bars: dict[int, dict] = {}
    for path in sorted(raw_dir.glob("*.json")):
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
            for row in normalize_bars(payload, lower, upper):
                bars[int(row["id"])] = row
        except (OSError, json.JSONDecodeError, CollectionError):
            continue
    return [bars[key] for key in sorted(bars)]


def missing_intervals(bars: Iterable[dict], start: int, end: int, period_seconds: int) -> list[tuple[int, int]]:
    expected = set(range(start, end, period_seconds))
    actual = {int(row["id"]) for row in bars if start <= int(row["id"]) < end}
    missing = sorted(expected - actual)
    result: list[tuple[int, int]] = []
    for stamp in missing:
        if result and stamp == result[-1][1] + period_seconds:
            result[-1] = (result[-1][0], stamp)
        else:
            result.append((stamp, stamp))
    return result


class HtxPublicKlineClient:
    """Public-only HTTP client. The requested time range is explicit on every page."""

    def __init__(self, endpoints: tuple[str, ...] = DEFAULT_ENDPOINTS, timeout_seconds: int = 45):
        self.endpoints = endpoints
        self.timeout_seconds = timeout_seconds

    def fetch(self, symbol: str, period: str, start: int, end: int, size: int | None = None) -> tuple[dict, str, str]:
        # HTX documents that size and from/to are mutually exclusive.  Supplying all
        # three silently ignores the range and returns the latest bars, which would
        # contaminate an immutable historical dataset.
        # The caller controls page size through the requested time window.  Keep
        # ``size`` only for API compatibility with test doubles; never serialize it.
        _ = size
        params = {"contract_code": symbol, "period": period, "from": start, "to": end}
        query = urlencode(params)
        errors = []
        last_error_payload = None
        for endpoint in self.endpoints:
            url = endpoint.rstrip("/") + "/linear-swap-ex/market/history/kline?" + query
            try:
                request = Request(url, headers={"Accept": "application/json", "User-Agent": "CoinStrategyResearchCollector/1.0"})
                with urlopen(request, timeout=self.timeout_seconds) as response:
                    body = response.read()
                payload = json.loads(body.decode("utf-8"))
                if payload.get("status") != "ok":
                    code = payload.get("err_code") or payload.get("err-code") or "unknown-error"
                    message = payload.get("err_msg") or payload.get("err-msg") or json.dumps(payload, ensure_ascii=False)
                    last_error_payload = payload
                    raise CollectionError(f"{code} {message}", payload)
                return payload, endpoint, url
            except (URLError, TimeoutError, OSError, UnicodeDecodeError, json.JSONDecodeError, CollectionError) as exc:
                errors.append(f"{endpoint}: {exc}")
        raise CollectionError("; ".join(errors), last_error_payload)


def load_state(path: Path, start: int, end: int, symbols: list[str], periods: list[str], research_universe: dict | None = None) -> dict:
    if path.exists():
        value = json.loads(path.read_text(encoding="utf-8"))
        if value.get("schema") != STATE_SCHEMA:
            raise CollectionError(f"unsupported collector state schema in {path}")
        if value.get("window") != {"start": start, "end": end}:
            raise CollectionError("collector state window differs from requested window; use a new data directory")
        if value.get("symbols") != symbols or value.get("periods") != periods:
            raise CollectionError("collector state universe differs from request; use a new data directory")
        recorded_universe = value.get("researchUniverse")
        expected_universe = ({**research_universe, "sha256": universe_sha256(research_universe)}
                             if research_universe is not None else None)
        if recorded_universe != expected_universe:
            raise CollectionError("collector state research universe differs from request; use a new data directory")
        return value
    return {"schema": STATE_SCHEMA, "window": {"start": start, "end": end}, "symbols": symbols, "periods": periods,
            "researchUniverse": ({**research_universe, "sha256": universe_sha256(research_universe)}
                                 if research_universe is not None else None),
            "targets": {}, "createdAt": datetime.now(timezone.utc).isoformat(), "updatedAt": None}


def save_state(path: Path, state: dict, lock: threading.Lock) -> None:
    with lock:
        state["updatedAt"] = datetime.now(timezone.utc).isoformat()
        atomic_json_write(path, state)


def raw_page_path(data_dir: Path, target: CollectionTarget, page_start: int, page_end: int) -> Path:
    return data_dir / "raw" / target.symbol / target.period / f"{page_start}-{page_end}.json"


def collect_target(target: CollectionTarget, data_dir: Path, start: int, end: int, page_bars: int,
                   retries: int, client: HtxPublicKlineClient, state: dict, state_path: Path, state_lock: threading.Lock) -> dict:
    seconds = PERIOD_SECONDS[target.period]
    page_seconds = seconds * page_bars
    key = f"{target.symbol}:{target.period}"
    raw_dir = data_dir / "raw" / target.symbol / target.period
    raw_dir.mkdir(parents=True, exist_ok=True)
    target_state = state["targets"].setdefault(key, {"status": "PENDING", "pages": {}, "failures": []})
    # HTX treats ``to`` as inclusive.  A 2,000-bar request therefore spans
    # 1,999 intervals; global ``end`` stays exclusive to preserve the manifest
    # contract and allow adjacent pages to join without overlap.
    for page_start in range(start, end, page_seconds):
        page_end = min(page_start + page_seconds, end)  # exclusive local boundary
        request_to = page_end - seconds
        page_key = f"{page_start}-{page_end}"
        path = raw_page_path(data_dir, target, page_start, page_end)
        if path.exists():
            try:
                payload = json.loads(path.read_text(encoding="utf-8"))
                rows = normalize_bars(payload, page_start, page_end)
                if rows:
                    target_state["pages"][page_key] = {"status": "REUSED", "file": str(path.relative_to(data_dir)),
                                                        "sha256": file_sha256(path), "barCount": len(rows)}
                    continue
            except (OSError, json.JSONDecodeError, CollectionError):
                pass
        target_state["status"] = "FETCHING"
        save_state(state_path, state, state_lock)
        failure = None
        for attempt in range(1, retries + 1):
            try:
                payload, endpoint, request_url = client.fetch(target.symbol, target.period, page_start, request_to, page_bars)
                rows = normalize_bars(payload, page_start, page_end)
                if not rows:
                    raise CollectionError("response contains no bars in requested page range")
                atomic_json_write(path, payload)
                target_state["pages"][page_key] = {"status": "SAVED", "file": str(path.relative_to(data_dir)),
                    "sha256": file_sha256(path), "barCount": len(rows), "firstBar": int(rows[0]["id"]),
                    "lastBar": int(rows[-1]["id"]), "endpoint": endpoint, "request": request_url, "attempt": attempt}
                failure = None
                break
            except CollectionError as exc:
                failure = str(exc)
                if exc.payload is not None:
                    error_path = path.with_suffix(".error.json")
                    atomic_json_write(error_path, exc.payload)
                    target_state["pages"][page_key] = {"status": "REJECTED", "file": str(error_path.relative_to(data_dir)),
                        "sha256": file_sha256(error_path), "message": failure}
                if attempt < retries:
                    time.sleep(min(8, attempt * 2))
        if failure:
            target_state["failures"].append({"page": page_key, "message": failure, "at": datetime.now(timezone.utc).isoformat()})
            target_state["status"] = "FAILED"
            save_state(state_path, state, state_lock)
            return {"target": key, "status": "FAILED", "reason": failure}
        save_state(state_path, state, state_lock)

    bars = merge_raw_pages(raw_dir, start, end)
    missing = missing_intervals(bars, start, end, seconds)
    merged_path = data_dir / f"{target.symbol}_{target.period}.json"
    atomic_json_write(merged_path, {"status": "ok", "ch": f"research.{target.symbol}.kline.{target.period}",
                                    "ts": int(time.time() * 1000), "data": bars})
    target_state["status"] = "COMPLETE" if not missing else "PARTIAL"
    target_state["mergedFile"] = merged_path.name
    target_state["mergedSha256"] = file_sha256(merged_path)
    target_state["mergedBarCount"] = len(bars)
    target_state["missingIntervals"] = [{"start": first, "end": last} for first, last in missing]
    save_state(state_path, state, state_lock)
    return {"target": key, "status": target_state["status"], "bars": len(bars), "missingIntervals": len(missing)}


def collect_in_rounds(targets: list[CollectionTarget], data_dir: Path, start: int, end: int, page_bars: int,
                      retries: int, client: HtxPublicKlineClient, state: dict, state_path: Path,
                      state_lock: threading.Lock, concurrency: int, retry_rounds: int,
                      round_backoff_seconds: int) -> tuple[list[dict], int]:
    """Collect every target, then retry only incomplete targets for bounded rounds.

    Public market endpoints occasionally reset TLS connections.  A target remains
    auditable after one failed page, but should not force an operator to manually
    restart a multi-hour capture.  This function retries only FAILED/PARTIAL
    targets, preserves all prior raw pages, and never reclassifies an incomplete
    target as COMPLETE.
    """
    pending = list(targets)
    final: dict[str, dict] = {}
    for round_index in range(1, retry_rounds + 1):
        if not pending:
            break
        with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as executor:
            futures = [executor.submit(collect_target, target, data_dir, start, end, page_bars, retries,
                                       client, state, state_path, state_lock) for target in pending]
            round_results = [future.result() for future in concurrent.futures.as_completed(futures)]
        for result in round_results:
            final[result["target"]] = result
            state["targets"][result["target"]]["lastRound"] = round_index
            print(json.dumps({"round": round_index, **result}, ensure_ascii=False), flush=True)
        pending = [target for target in targets if final.get(f"{target.symbol}:{target.period}", {}).get("status") != "COMPLETE"]
        if pending and round_index < retry_rounds and round_backoff_seconds:
            time.sleep(round_backoff_seconds * round_index)
    return [final.get(f"{target.symbol}:{target.period}", {"target": f"{target.symbol}:{target.period}", "status": "FAILED",
                                                        "reason": "not-attempted"}) for target in targets], round_index


def probe(client: HtxPublicKlineClient, symbol: str, period: str, anchor: int) -> dict:
    """Validate exact range semantics before an expensive collection run."""
    seconds = PERIOD_SECONDS[period]
    start, end = anchor, anchor + seconds * 3  # end is exclusive in our data contract
    payload, endpoint, url = client.fetch(symbol, period, start, end - seconds, 10)
    rows = normalize_bars(payload, start, end)
    return {"symbol": symbol, "period": period, "requestedStart": start, "requestedEnd": end,
            "returnedBars": len(rows), "returnedIds": [int(row["id"]) for row in rows], "endpoint": endpoint, "request": url,
            "rangeHonored": bool(rows) and all(start <= int(row["id"]) < end for row in rows)}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-dir", type=Path, required=True)
    parser.add_argument("--start", required=True, help="Asia/Shanghai ISO timestamp")
    parser.add_argument("--end", required=True, help="exclusive Asia/Shanghai ISO timestamp")
    parser.add_argument("--symbols", help="comma-separated contracts; defaults to HTX trend whitelist")
    parser.add_argument("--universe-file", type=Path, help="immutable research universe JSON; cannot be combined with --symbols")
    parser.add_argument("--periods", default=",".join(DEFAULT_PERIODS))
    parser.add_argument("--concurrency", type=int, default=5, choices=range(1, 11))
    parser.add_argument("--page-bars", type=int, default=2000, choices=range(10, 2001))
    parser.add_argument("--retries", type=int, default=3, choices=range(1, 6))
    parser.add_argument("--retry-rounds", type=int, default=4, choices=range(1, 11),
                        help="bounded whole-target retry rounds for transient public API failures")
    parser.add_argument("--round-backoff-seconds", type=int, default=20, choices=range(0, 301))
    parser.add_argument("--timeout-seconds", type=int, default=45, choices=range(5, 121))
    parser.add_argument("--endpoints", default=",".join(DEFAULT_ENDPOINTS))
    parser.add_argument("--manifest-out", type=Path, help="defaults to <data-dir>/dataset-manifest.json")
    parser.add_argument("--probe-only", action="store_true")
    args = parser.parse_args()
    start, end = epoch(args.start), epoch(args.end)
    if start >= end:
        raise SystemExit("--start must precede --end")
    periods = [value.strip() for value in args.periods.split(",") if value.strip()]
    unknown = sorted(set(periods) - PERIOD_SECONDS.keys())
    if unknown:
        raise SystemExit(f"unsupported periods: {','.join(unknown)}")
    if args.symbols and args.universe_file:
        raise SystemExit("--symbols and --universe-file cannot be combined")
    research_universe = None
    if args.universe_file:
        raw_universe = json.loads(args.universe_file.read_text(encoding="utf-8"))
        contracts = raw_universe.get("contracts", []) if isinstance(raw_universe, dict) else []
        research_universe = canonical_universe(raw_universe, contracts)
        symbols = research_universe["contracts"]
    else:
        symbols = [value.strip() for value in args.symbols.split(",") if value.strip()] if args.symbols else load_default_symbols(
            Path("coin-strategy-htx/src/main/resources/application.yml"))
    client = HtxPublicKlineClient(tuple(value.rstrip("/") for value in args.endpoints.split(",") if value.strip()), args.timeout_seconds)
    if args.probe_only:
        result = probe(client, symbols[0], periods[0], start)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        if not result["rangeHonored"]:
            raise SystemExit("HTX did not honor the requested historical range")
        return

    args.data_dir.mkdir(parents=True, exist_ok=True)
    state_path = args.data_dir / "collector-state.json"
    state = load_state(state_path, start, end, symbols, periods, research_universe)
    lock = threading.Lock()
    targets = [CollectionTarget(symbol, period) for symbol in symbols for period in periods]
    results, rounds_used = collect_in_rounds(targets, args.data_dir, start, end, args.page_bars, args.retries,
                                              client, state, state_path, lock, args.concurrency, args.retry_rounds,
                                              args.round_backoff_seconds)
    summary = {"schema": "htx-trend-research-collector-summary-v1", "window": {"start": start, "end": end},
               "targets": len(results), "roundsUsed": rounds_used,
               "complete": sum(item["status"] == "COMPLETE" for item in results),
               "partial": sum(item["status"] == "PARTIAL" for item in results),
               "failed": sum(item["status"] == "FAILED" for item in results), "results": sorted(results, key=lambda item: item["target"])}
    atomic_json_write(args.data_dir / "collector-summary.json", summary)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    if summary["complete"] != summary["targets"]:
        raise SystemExit("dataset is not complete after bounded retry rounds; inspect collector-state.json and rerun to resume")
    manifest = build_manifest(args.data_dir, symbols, periods, args.start, args.end, research_universe)
    manifest_path = args.manifest_out or args.data_dir / "dataset-manifest.json"
    atomic_json_write(manifest_path, manifest)
    print(json.dumps({"manifest": str(manifest_path), "integrity": manifest["inputIntegrity"], "datasetKey": manifest["datasetKey"]}, ensure_ascii=False))
    if manifest["inputIntegrity"] != "COMPLETE":
        raise SystemExit("collector pages completed but manifest found a coverage gap")


if __name__ == "__main__":
    main()
