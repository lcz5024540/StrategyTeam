#!/usr/bin/env python3
"""Post-freeze, pre-run quality-flag gate (operator, outside the package).

Product option A: collect raw files do not carry native flag columns.

Required (no defaults): --kline-gaps, --package-tar, --package-sha256,
per-symbol --grid-start / --expected-rows / --expected-external /
--expected-non-comparable / --expected-bad-ticks, plus raw-segment
expectations. Official R0001 values are in r0001_operator_runbook.md.

  * (symbol, bar_time) uniqueness on raw and frozen separately; any
    duplicate fails before set comparison
  * missing source column fails (does not invent htx/binance)
  * YAML via PyYAML (inline mappings parse); fail if PyYAML missing
  * --package-sha256 is checked by hashing --package-tar
  * symbols compared to hard-coded {BTC-USDT, ETH-USDT}, not to themselves
  * expected_rows == (cutoff - start) / 4h - registered_gaps
  * actual first bar == declared --grid-start

Do not copy this file into the package tree. Do not import htx_trend.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

HARD_CODED_SYMBOLS = ["BTC-USDT", "ETH-USDT"]
REQUIRED_COLUMNS = ("incomplete", "non_comparable", "bad_tick", "external")
BAR_HOURS = {"4hour": 4, "4h": 4, "1day": 24, "1d": 24}
EXTERNAL_SOURCES = frozenset({"binance", "binance_external", "backfill"})


def require_yaml():
    try:
        import yaml  # noqa: F401
    except ImportError as exc:  # pragma: no cover
        raise SystemExit(
            "PyYAML is required to parse stitch/bad_ticks/kline-gaps "
            "(inline mappings). Install it in the operator venv."
        ) from exc
    return yaml


def _utc_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _parse_utc(text: str) -> datetime:
    value = str(text).strip()
    if value.endswith("Z"):
        value = value[:-1] + "+00:00"
    parsed = datetime.fromisoformat(value)
    if parsed.tzinfo is None:
        return parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def pd_timestamp_utc(value):
    import pandas as pd

    ts = pd.Timestamp(value)
    if ts.tzinfo is None:
        return ts.tz_localize("UTC")
    return ts.tz_convert("UTC")


def iso_z(value) -> str:
    return pd_timestamp_utc(value).strftime("%Y-%m-%dT%H:%M:%SZ")


def split_csv(text: str) -> list[str]:
    return [item.strip() for item in text.split(",") if item.strip()]


def parse_symbol_items(items: list[str] | None, flag: str, *, kind: str) -> dict:
    parsed = {}
    for item in items or []:
        if "=" not in item:
            raise SystemExit(f"{flag} must be SYMBOL=VALUE, got {item!r}")
        symbol, raw = item.split("=", 1)
        symbol = symbol.strip()
        raw = raw.strip()
        if kind == "int":
            parsed[symbol] = int(raw)
        elif kind == "utc":
            parsed[symbol] = _parse_utc(raw)
        else:
            parsed[symbol] = raw
    return parsed


def binance_symbol(symbol: str) -> str:
    return symbol.replace("-", "")


def load_mapping_yaml(path: Path) -> dict:
    yaml = require_yaml()
    payload = yaml.safe_load(path.read_text()) or {}
    if not isinstance(payload, dict):
        raise ValueError(f"{path}: expected a mapping")
    raw = payload.get("points") or payload.get("stitch") or payload
    if not isinstance(raw, dict):
        raise ValueError(f"{path}: stitch file must be a mapping")
    out: dict[str, str | None] = {}
    for key, value in raw.items():
        if key in {"version", "points", "stitch"}:
            continue
        if value is None or str(value).strip().lower() in {"none", "null", "~"}:
            out[str(key)] = None
        else:
            out[str(key)] = str(value)
    return out


def load_bad_ticks_yaml(path: Path) -> list[dict]:
    yaml = require_yaml()
    payload = yaml.safe_load(path.read_text()) or {}
    if not isinstance(payload, dict):
        raise ValueError(f"{path}: expected a mapping")
    rows = payload.get("ticks")
    if rows is None:
        rows = payload.get("entries")
    if rows is None:
        raise ValueError(f"{path}: missing top-level ticks or entries list")
    if not isinstance(rows, list) or not rows:
        raise ValueError(f"{path}: ticks/entries must be a non-empty list")
    ticks: list[dict] = []
    for index, item in enumerate(rows):
        if not isinstance(item, dict):
            raise ValueError(f"{path}: ticks[{index}] is not a mapping: {item!r}")
        if "symbol" not in item or "bar_time" not in item:
            raise ValueError(
                f"{path}: ticks[{index}] missing symbol/bar_time: {item!r}"
            )
        tick = dict(item)
        if "confirmed" not in tick:
            tick["confirmed"] = True
        ticks.append(tick)
    return ticks


def load_kline_gaps_yaml(path: Path) -> dict[str, set[str]]:
    yaml = require_yaml()
    payload = yaml.safe_load(path.read_text()) or {}
    if not isinstance(payload, dict):
        raise ValueError(f"{path}: expected a mapping")
    rows = payload.get("gaps")
    if rows is None:
        rows = payload.get("entries")
    if rows is None:
        raise ValueError(f"{path}: missing top-level gaps list")
    if not isinstance(rows, list):
        raise ValueError(f"{path}: gaps must be a list")
    out: dict[str, set[str]] = {}
    for index, item in enumerate(rows):
        if not isinstance(item, dict):
            raise ValueError(f"{path}: gaps[{index}] is not a mapping: {item!r}")
        if "symbol" not in item or "bar_time" not in item:
            raise ValueError(
                f"{path}: gaps[{index}] missing symbol/bar_time: {item!r}"
            )
        symbol = str(item["symbol"])
        out.setdefault(symbol, set()).add(iso_z(item["bar_time"]))
    return out


def duplicate_bar_times(times) -> list[str]:
    import pandas as pd

    series = pd.to_datetime(pd.Series(list(times)), utc=True)
    if series.empty:
        return []
    stamped = series.map(iso_z)
    dup = stamped[stamped.duplicated(keep=False)]
    return sorted(set(dup.tolist()))


def require_source_column(frame, *, context: str) -> None:
    if "source" not in frame.columns:
        raise ValueError(f"{context}: missing required source column")
    if frame["source"].isna().any():
        raise ValueError(f"{context}: source contains null values")


def consumed_raw_paths(
    *,
    raw_dir: Path,
    symbols: list[str],
    timeframe: str,
    stitch_points: dict[str, str | None],
    backfill_binance: bool,
) -> tuple[list[Path], list[str], dict[str, list[str]]]:
    errors: list[str] = []
    paths: list[Path] = []
    roles: dict[str, list[str]] = {}
    seen: set[Path] = set()

    def add(path: Path, symbol: str, role: str) -> None:
        roles.setdefault(symbol, [])
        if path in seen:
            roles[symbol].append(f"{role}={path.name} (already listed)")
            return
        if not path.is_file():
            errors.append(f"{symbol}: required raw file missing: {path}")
            roles[symbol].append(f"{role} MISSING: {path.name}")
            return
        seen.add(path)
        paths.append(path)
        roles[symbol].append(f"{role}={path.name}")

    for symbol in symbols:
        stitch = stitch_points.get(symbol)
        if timeframe in {"4hour", "4h"}:
            add(raw_dir / f"{symbol}_4hour_htx.parquet", symbol, "htx_4hour")
            if stitch is not None and backfill_binance:
                add(
                    raw_dir / f"{binance_symbol(symbol)}_4h_binance.parquet",
                    symbol,
                    "binance_4h",
                )
        else:
            errors.append(f"unsupported timeframe {timeframe!r}")
    return paths, errors, roles


def inspect_raw_file(path: Path) -> dict:
    import pandas as pd

    frame = pd.read_parquet(path)
    if "bar_time" not in frame.columns:
        raise ValueError(f"{path}: raw parquet missing bar_time")
    times = pd.to_datetime(frame["bar_time"], utc=True)
    dups = duplicate_bar_times(times)
    return {
        "path": str(path),
        "rows": int(len(frame)),
        "sha256": file_sha256(path),
        "min_bar_time": iso_z(times.min()) if not times.empty else None,
        "max_bar_time": iso_z(times.max()) if not times.empty else None,
        "columns": [str(name) for name in frame.columns],
        "duplicate_bar_times": dups,
        "has_source": "source" in frame.columns,
    }


def grid_size(start: datetime, cutoff: datetime, timeframe: str) -> int:
    hours = BAR_HOURS[timeframe]
    delta = pd_timestamp_utc(cutoff) - pd_timestamp_utc(start)
    total_hours = delta.total_seconds() / 3600.0
    if total_hours <= 0:
        raise ValueError(f"cutoff {iso_z(cutoff)} is not after start {iso_z(start)}")
    if abs(total_hours / hours - round(total_hours / hours)) > 1e-9:
        raise ValueError(
            f"(cutoff-start) is not a whole number of {hours}h bars: "
            f"{iso_z(start)} -> {iso_z(cutoff)}"
        )
    return int(round(total_hours / hours))


def full_4h_grid(start: datetime, cutoff: datetime, timeframe: str):
    import pandas as pd

    hours = BAR_HOURS[timeframe]
    start_ts = pd_timestamp_utc(start)
    last_open = pd_timestamp_utc(cutoff) - pd.Timedelta(hours=hours)
    if start_ts.minute or start_ts.second or start_ts.microsecond:
        raise ValueError(f"grid start is not 4h-aligned: {iso_z(start_ts)}")
    if start_ts.hour % hours != 0:
        raise ValueError(f"grid start hour {start_ts.hour} is not on the {hours}h grid")
    return pd.date_range(start=start_ts, end=last_open, freq=f"{hours}h", tz="UTC")


def stitch_expected(
    *,
    raw_dir: Path,
    symbol: str,
    timeframe: str,
    stitch: datetime | None,
    backfill_binance: bool,
    cutoff: datetime,
):
    import pandas as pd

    htx_path = raw_dir / f"{symbol}_{timeframe}_htx.parquet"
    htx = pd.read_parquet(htx_path)
    if "bar_time" not in htx.columns:
        raise ValueError(f"{symbol}: HTX raw missing bar_time")
    require_source_column(htx, context=f"{symbol} HTX raw")
    htx = htx.copy()
    htx["bar_time"] = pd.to_datetime(htx["bar_time"], utc=True)
    htx_dups = duplicate_bar_times(htx["bar_time"])
    if htx_dups:
        raise ValueError(f"{symbol} HTX raw duplicate bar_time: {htx_dups[:8]}")

    if stitch is None:
        combined = htx[["bar_time", "source"]].copy()
    else:
        stitch_ts = pd_timestamp_utc(stitch)
        after = htx.loc[htx["bar_time"] >= stitch_ts, ["bar_time", "source"]].copy()
        if backfill_binance:
            bn_path = raw_dir / (
                f"{binance_symbol(symbol)}_{timeframe.replace('hour', 'h')}_binance.parquet"
            )
            bn = pd.read_parquet(bn_path)
            if "bar_time" not in bn.columns:
                raise ValueError(f"{symbol}: Binance raw missing bar_time")
            require_source_column(bn, context=f"{symbol} Binance raw")
            bn = bn.copy()
            bn["bar_time"] = pd.to_datetime(bn["bar_time"], utc=True)
            bn_dups = duplicate_bar_times(bn["bar_time"])
            if bn_dups:
                raise ValueError(
                    f"{symbol} Binance raw duplicate bar_time: {bn_dups[:8]}"
                )
            before = bn.loc[bn["bar_time"] < stitch_ts, ["bar_time", "source"]].copy()
            combined = pd.concat([before, after], ignore_index=True)
        else:
            combined = after
    if combined.empty:
        raise ValueError(f"{symbol}: stitched raw has no bars")
    stitched_dups = duplicate_bar_times(combined["bar_time"])
    if stitched_dups:
        raise ValueError(f"{symbol} stitched raw duplicate bar_time: {stitched_dups[:8]}")
    hours = BAR_HOURS[timeframe]
    closes = combined["bar_time"] + pd.Timedelta(hours=hours)
    closed = combined.loc[closes <= pd_timestamp_utc(cutoff)].copy()
    if closed.empty:
        raise ValueError(f"{symbol}: no closed bars at or before cutoff")
    return closed.reset_index(drop=True), combined.reset_index(drop=True)


def expected_non_comparable_set(times, stitch: datetime | None, window_bars: int) -> set[str]:
    import pandas as pd

    series = pd.Series(times)
    if stitch is None or series.empty:
        return set()
    ordered = series.sort_values().reset_index(drop=True)
    stitch_ts = pd_timestamp_utc(stitch)
    if stitch_ts > pd_timestamp_utc(ordered.max()):
        raise ValueError(
            f"stitch point {iso_z(stitch)} is after last expected bar {iso_z(ordered.max())}"
        )
    at_or_after = ordered.index[ordered >= stitch_ts]
    if len(at_or_after) == 0:
        return set()
    pos = int(at_or_after[0])
    start = max(0, pos - int(window_bars))
    end = min(len(ordered) - 1, pos + int(window_bars))
    return {iso_z(ordered.iloc[i]) for i in range(start, end + 1)}


def expected_external_set(closed, stitch: datetime | None) -> set[str]:
    found: set[str] = set()
    stitch_ts = None if stitch is None else pd_timestamp_utc(stitch)
    for ts, source in zip(closed["bar_time"], closed["source"]):
        text = str(source).strip().lower()
        if text in EXTERNAL_SOURCES:
            found.add(iso_z(ts))
        elif stitch_ts is not None and pd_timestamp_utc(ts) < stitch_ts:
            found.add(iso_z(ts))
    return found


def expected_bad_tick_set(times, symbol: str, ticks: list[dict]) -> set[str]:
    time_set = {iso_z(ts): pd_timestamp_utc(ts) for ts in times}
    found: set[str] = set()
    for tick in ticks:
        if str(tick.get("symbol")) != symbol:
            continue
        if tick.get("confirmed") is not True:
            continue
        start = pd_timestamp_utc(_parse_utc(str(tick["bar_time"])))
        if tick.get("end_time"):
            end = pd_timestamp_utc(_parse_utc(str(tick["end_time"])))
            for key, ts in time_set.items():
                if start <= ts <= end:
                    found.add(key)
        else:
            key = iso_z(start)
            if key not in time_set:
                raise ValueError(
                    f"{symbol}: confirmed bad_tick {key} does not hit any expected closed bar"
                )
            found.add(key)
    return found


def require_strict_bool(series, *, symbol: str, name: str) -> list[str]:
    errors: list[str] = []
    if series.isna().any():
        errors.append(f"frozen {symbol}: {name!r} has {int(series.isna().sum())} nulls")
    if series.dtype != bool and str(series.dtype) != "bool":
        errors.append(
            f"frozen {symbol}: {name!r} must be strict bool dtype "
            f"(numpy.bool_/bool), got dtype={series.dtype}"
        )
        return errors
    for value in series.tolist():
        if value is not True and value is not False and type(value).__name__ != "bool_":
            errors.append(
                f"frozen {symbol}: {name!r} value {value!r} type={type(value).__name__} "
                "is not bool/numpy.bool_"
            )
            break
    return errors


def true_bar_set(frame, name) -> set[str]:
    import pandas as pd

    times = pd.to_datetime(frame["bar_time"], utc=True)
    found: set[str] = set()
    for ts, flag in zip(times, frame[name]):
        if flag is True or (type(flag).__name__ == "bool_" and bool(flag)):
            found.add(iso_z(ts))
    return found


def inspect_symbol(
    symbol: str,
    frozen_path: Path,
    *,
    raw_dir: Path,
    stitch: datetime | None,
    window_bars: int,
    cutoff: datetime,
    timeframe: str,
    ticks: list[dict],
    backfill_binance: bool,
    declared_gaps: set[str],
    grid_start: datetime,
    expected_rows: int,
    expected_external: int,
    expected_non_comparable: int,
    expected_bad_count: int,
    expected_binance_rows: int,
    expected_htx_rows: int,
    expected_binance_start: datetime,
    expected_binance_end: datetime,
    expected_htx_start: datetime,
    expected_htx_end: datetime,
    expected_last_open: datetime,
) -> tuple[dict, list[str]]:
    import pandas as pd

    errors: list[str] = []
    closed, _stitched = stitch_expected(
        raw_dir=raw_dir,
        symbol=symbol,
        timeframe=timeframe,
        stitch=stitch,
        backfill_binance=backfill_binance,
        cutoff=cutoff,
    )

    htx = pd.read_parquet(raw_dir / f"{symbol}_{timeframe}_htx.parquet")
    htx["bar_time"] = pd.to_datetime(htx["bar_time"], utc=True)
    if int(len(htx)) != expected_htx_rows:
        errors.append(
            f"{symbol}: HTX raw rows {len(htx)} != expected {expected_htx_rows}"
        )
    if iso_z(htx["bar_time"].min()) != iso_z(expected_htx_start):
        errors.append(
            f"{symbol}: HTX raw start {iso_z(htx['bar_time'].min())} "
            f"!= expected {iso_z(expected_htx_start)}"
        )
    if iso_z(htx["bar_time"].max()) != iso_z(expected_htx_end):
        errors.append(
            f"{symbol}: HTX raw end {iso_z(htx['bar_time'].max())} "
            f"!= expected {iso_z(expected_htx_end)}"
        )

    if stitch is not None and backfill_binance:
        bn = pd.read_parquet(
            raw_dir
            / f"{binance_symbol(symbol)}_{timeframe.replace('hour', 'h')}_binance.parquet"
        )
        bn["bar_time"] = pd.to_datetime(bn["bar_time"], utc=True)
        if int(len(bn)) != expected_binance_rows:
            errors.append(
                f"{symbol}: Binance raw rows {len(bn)} != expected {expected_binance_rows}"
            )
        if iso_z(bn["bar_time"].min()) != iso_z(expected_binance_start):
            errors.append(
                f"{symbol}: Binance raw start {iso_z(bn['bar_time'].min())} "
                f"!= expected {iso_z(expected_binance_start)}"
            )
        if iso_z(bn["bar_time"].max()) != iso_z(expected_binance_end):
            errors.append(
                f"{symbol}: Binance raw end {iso_z(bn['bar_time'].max())} "
                f"!= expected {iso_z(expected_binance_end)}"
            )

    computed_rows = grid_size(grid_start, cutoff, timeframe) - len(declared_gaps)
    if expected_rows != computed_rows:
        errors.append(
            f"{symbol}: --expected-rows {expected_rows} != "
            f"(cutoff-start)/4h - gaps = {computed_rows}"
        )
    grid = full_4h_grid(grid_start, cutoff, timeframe)
    grid_set = {iso_z(ts) for ts in grid}
    expected_row_set = set(grid_set) - set(declared_gaps)
    raw_closed_set = {iso_z(ts) for ts in closed["bar_time"]}
    first_raw = iso_z(closed["bar_time"].min())
    if first_raw != iso_z(grid_start):
        errors.append(
            f"{symbol}: actual data first bar {first_raw} != declared start {iso_z(grid_start)}"
        )
    raw_holes = sorted(expected_row_set - raw_closed_set)
    extra_raw = sorted(raw_closed_set - expected_row_set)
    if raw_holes:
        errors.append(
            f"{symbol}: raw/grid hole(s) not in --kline-gaps: {raw_holes[:8]}"
            + (f" (+{len(raw_holes) - 8} more)" if len(raw_holes) > 8 else "")
        )
    if extra_raw:
        errors.append(f"{symbol}: raw closed bars outside declared grid: {extra_raw[:8]}")

    expected_times = pd.Series(pd.to_datetime(sorted(expected_row_set), utc=True))
    expected = {
        "incomplete": set(),
        "non_comparable": expected_non_comparable_set(expected_times, stitch, window_bars),
        "bad_tick": expected_bad_tick_set(expected_times, symbol, ticks),
        "external": expected_external_set(closed, stitch),
    }
    if len(expected["bad_tick"]) != expected_bad_count:
        errors.append(
            f"{symbol}: expected bad_tick True-set size {len(expected['bad_tick'])} "
            f"!= --expected-bad-ticks {expected_bad_count}"
        )
    if len(expected["non_comparable"]) != expected_non_comparable:
        errors.append(
            f"{symbol}: expected non_comparable size {len(expected['non_comparable'])} "
            f"!= --expected-non-comparable {expected_non_comparable}"
        )
    if len(expected["external"]) != expected_external:
        errors.append(
            f"{symbol}: expected external size {len(expected['external'])} "
            f"!= --expected-external {expected_external}"
        )

    frame = pd.read_parquet(frozen_path)
    if "bar_time" not in frame.columns:
        raise ValueError(f"{symbol}: frozen parquet missing bar_time")
    times = pd.to_datetime(frame["bar_time"], utc=True)
    frozen_dups = duplicate_bar_times(times)
    if frozen_dups:
        errors.append(f"frozen {symbol}: duplicate bar_time: {frozen_dups[:8]}")
    if times.empty:
        raise ValueError(f"{symbol}: frozen parquet has no bars")
    frozen_set = {iso_z(ts) for ts in times}
    hours = BAR_HOURS[timeframe]
    last_open = pd_timestamp_utc(times.max())
    last_close = last_open + timedelta(hours=hours)
    cutoff_ts = pd_timestamp_utc(cutoff)
    last_closed = last_close <= cutoff_ts
    if not last_closed:
        errors.append(
            f"frozen {symbol}: last bar not closed at/before cutoff "
            f"(open={iso_z(last_open)} close={iso_z(last_close)} cutoff={iso_z(cutoff_ts)})"
        )
    if iso_z(last_open) != iso_z(expected_last_open) and not frozen_dups:
        # Still report when the last open drifted (deleted tail / extra bar).
        if iso_z(times.max()) != iso_z(expected_last_open):
            errors.append(
                f"frozen {symbol}: last open {iso_z(last_open)} "
                f"!= expected {iso_z(expected_last_open)}"
            )

    missing_rows = sorted(expected_row_set - frozen_set)
    extra_rows = sorted(frozen_set - expected_row_set)
    undeclared_holes = sorted(grid_set - frozen_set - declared_gaps)
    if missing_rows or extra_rows:
        errors.append(
            f"frozen {symbol}: row-set mismatch vs 4h grid (minus kline-gaps) "
            f"missing={len(missing_rows)} extra={len(extra_rows)}; "
            f"missing_sample={missing_rows[:8]} extra_sample={extra_rows[:8]}"
        )
    if undeclared_holes:
        errors.append(
            f"frozen {symbol}: deleted/missing whole row(s) not in --kline-gaps: "
            f"{undeclared_holes[:8]}"
            + (f" (+{len(undeclared_holes) - 8} more)" if len(undeclared_holes) > 8 else "")
        )
    if int(len(frame)) != expected_rows and not frozen_dups:
        errors.append(
            f"frozen {symbol}: rows {len(frame)} != --expected-rows {expected_rows}"
        )

    actual: dict[str, list[str]] = {}
    diffs: dict[str, dict[str, list[str]]] = {}
    dtypes: dict[str, str] = {}
    row = {
        "path": str(frozen_path),
        "rows": int(len(frame)),
        "grid_start": iso_z(grid_start),
        "grid_bars": int(len(grid)),
        "expected_rows": expected_rows,
        "computed_rows": computed_rows,
        "declared_kline_gaps": sorted(declared_gaps),
        "raw_holes_undeclared": raw_holes,
        "frozen_holes_undeclared": undeclared_holes,
        "frozen_duplicate_bar_times": frozen_dups,
        "last_bar_open": iso_z(last_open),
        "last_bar_close": iso_z(last_close),
        "last_bar_closed_at_or_before_cutoff": last_closed,
        "expected_true": {name: sorted(values) for name, values in expected.items()},
        "actual_true": actual,
        "symmetric_diff": diffs,
        "flag_dtypes": dtypes,
        "ok": True,
    }
    for name in REQUIRED_COLUMNS:
        if name not in frame.columns:
            errors.append(f"frozen {symbol}: missing {name!r}")
            row["ok"] = False
            actual[name] = []
            continue
        series = frame[name]
        dtypes[name] = str(series.dtype)
        dtype_errors = require_strict_bool(series, symbol=symbol, name=name)
        errors.extend(dtype_errors)
        if dtype_errors:
            row["ok"] = False
            actual[name] = []
            continue
        got = true_bar_set(frame, name)
        actual[name] = sorted(got)
        want = expected[name]
        only_actual = sorted(got - want)
        only_expected = sorted(want - got)
        diffs[name] = {"only_actual": only_actual, "only_expected": only_expected}
        if only_actual or only_expected:
            errors.append(
                f"frozen {symbol}: {name} True-set mismatch "
                f"count actual={len(got)} expected={len(want)}; "
                f"only_actual={only_actual} only_expected={only_expected}"
            )
            row["ok"] = False
    if errors:
        row["ok"] = False
    return row, errors


def parse_stitch_point_items(items: list[str] | None) -> dict[str, str | None]:
    parsed: dict[str, str | None] = {}
    for item in items or []:
        if "=" not in item:
            raise SystemExit(f"--stitch-point must be SYMBOL=TIME, got {item!r}")
        symbol, stamp = item.split("=", 1)
        symbol = symbol.strip()
        stamp = stamp.strip()
        if stamp.lower() in {"none", "null", "~"}:
            parsed[symbol] = None
        else:
            _parse_utc(stamp)
            parsed[symbol] = stamp
    return parsed


def require_per_symbol(mapping: dict, symbols: list[str], flag: str, errors: list[str]) -> None:
    for symbol in symbols:
        if symbol not in mapping:
            errors.append(f"{symbol}: missing {flag} {symbol}=...")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Frozen True-set + 4h-grid quality-flag gate (option A)."
    )
    parser.add_argument("--dataset-dir", required=True)
    parser.add_argument("--raw-dir", required=True)
    parser.add_argument("--evidence", required=True)
    parser.add_argument("--contracts", required=True)
    parser.add_argument("--timeframe", required=True, choices=["4hour", "1day"])
    parser.add_argument("--cutoff", required=True)
    parser.add_argument("--window-bars", required=True, type=int)
    parser.add_argument("--stitch-point", action="append", required=True)
    parser.add_argument("--stitch-config", required=True)
    parser.add_argument("--backfill-binance", action="store_true")
    parser.add_argument("--bad-ticks", required=True)
    parser.add_argument("--expected-bad-ticks-sha256", required=True)
    parser.add_argument("--expected-bad-ticks", action="append", required=True)
    parser.add_argument("--expected-rows", action="append", required=True)
    parser.add_argument("--expected-external", action="append", required=True)
    parser.add_argument("--expected-non-comparable", action="append", required=True)
    parser.add_argument("--grid-start", action="append", required=True)
    parser.add_argument("--expected-last-open", action="append", required=True)
    parser.add_argument("--expected-htx-rows", action="append", required=True)
    parser.add_argument("--expected-binance-rows", action="append", required=True)
    parser.add_argument("--expected-htx-start", action="append", required=True)
    parser.add_argument("--expected-htx-end", action="append", required=True)
    parser.add_argument("--expected-binance-start", action="append", required=True)
    parser.add_argument("--expected-binance-end", action="append", required=True)
    parser.add_argument("--kline-gaps", required=True)
    parser.add_argument("--package-tar", required=True)
    parser.add_argument("--package-sha256", required=True)
    args = parser.parse_args(argv)

    errors: list[str] = []
    notes: list[str] = [
        "option A: collect raw without native flag columns is expected",
        "hard-coded official symbols: BTC-USDT,ETH-USDT",
        "missing YAML confirmed defaults to True (rc.13 load_bad_ticks)",
        "object-dtype parquet counterexample is invalid (roundtrip -> bool); string-typed is the dtype case",
    ]
    symbols = split_csv(args.contracts)
    if symbols != HARD_CODED_SYMBOLS:
        errors.append(
            f"contracts {symbols} != hard-coded expected {HARD_CODED_SYMBOLS}"
        )
    cutoff = _parse_utc(args.cutoff)
    stitch_cfg = Path(args.stitch_config)
    stitch_points: dict[str, str | None] = {}
    if not stitch_cfg.is_file():
        errors.append(f"stitch-config not found: {stitch_cfg}")
    else:
        try:
            stitch_points.update(load_mapping_yaml(stitch_cfg))
        except Exception as exc:  # noqa: BLE001
            errors.append(f"stitch-config: {exc}")
    stitch_points.update(parse_stitch_point_items(args.stitch_point))
    for symbol in HARD_CODED_SYMBOLS:
        if symbol not in stitch_points:
            errors.append(f"{symbol}: stitch point not declared")

    expected_bad = parse_symbol_items(args.expected_bad_ticks, "--expected-bad-ticks", kind="int")
    expected_rows = parse_symbol_items(args.expected_rows, "--expected-rows", kind="int")
    expected_external = parse_symbol_items(args.expected_external, "--expected-external", kind="int")
    expected_nc = parse_symbol_items(
        args.expected_non_comparable, "--expected-non-comparable", kind="int"
    )
    grid_start = parse_symbol_items(args.grid_start, "--grid-start", kind="utc")
    last_open = parse_symbol_items(args.expected_last_open, "--expected-last-open", kind="utc")
    htx_rows = parse_symbol_items(args.expected_htx_rows, "--expected-htx-rows", kind="int")
    bn_rows = parse_symbol_items(args.expected_binance_rows, "--expected-binance-rows", kind="int")
    htx_start = parse_symbol_items(args.expected_htx_start, "--expected-htx-start", kind="utc")
    htx_end = parse_symbol_items(args.expected_htx_end, "--expected-htx-end", kind="utc")
    bn_start = parse_symbol_items(args.expected_binance_start, "--expected-binance-start", kind="utc")
    bn_end = parse_symbol_items(args.expected_binance_end, "--expected-binance-end", kind="utc")
    for flag, mapping in (
        ("--expected-bad-ticks", expected_bad),
        ("--expected-rows", expected_rows),
        ("--expected-external", expected_external),
        ("--expected-non-comparable", expected_nc),
        ("--grid-start", grid_start),
        ("--expected-last-open", last_open),
        ("--expected-htx-rows", htx_rows),
        ("--expected-binance-rows", bn_rows),
        ("--expected-htx-start", htx_start),
        ("--expected-htx-end", htx_end),
        ("--expected-binance-start", bn_start),
        ("--expected-binance-end", bn_end),
    ):
        require_per_symbol(mapping, HARD_CODED_SYMBOLS, flag, errors)

    tar_path = Path(args.package_tar)
    hashed_tar = None
    if not tar_path.is_file():
        errors.append(f"package tar not found: {tar_path}")
    else:
        hashed_tar = file_sha256(tar_path)
        if hashed_tar != args.package_sha256:
            errors.append(
                f"package tar sha256 {hashed_tar} != --package-sha256 {args.package_sha256}"
            )

    ticks_path = Path(args.bad_ticks)
    ticks: list[dict] = []
    ticks_sha = None
    parsed_tick_rows = 0
    if not ticks_path.is_file():
        errors.append(f"bad_ticks file not found: {ticks_path}")
    else:
        ticks_sha = file_sha256(ticks_path)
        if ticks_sha != args.expected_bad_ticks_sha256:
            errors.append(
                f"bad_ticks.yaml sha256 {ticks_sha} != expected {args.expected_bad_ticks_sha256}"
            )
        try:
            parsed = load_bad_ticks_yaml(ticks_path)
            parsed_tick_rows = len(parsed)
            ticks = [tick for tick in parsed if tick.get("confirmed") is True]
        except Exception as exc:  # noqa: BLE001
            errors.append(f"bad_ticks parse: {exc}")

    gaps_path = Path(args.kline_gaps)
    declared_gaps: dict[str, set[str]] = {}
    kline_gaps_sha = None
    if not gaps_path.is_file():
        errors.append(f"kline-gaps file not found: {gaps_path}")
    else:
        kline_gaps_sha = file_sha256(gaps_path)
        try:
            declared_gaps = load_kline_gaps_yaml(gaps_path)
        except Exception as exc:  # noqa: BLE001
            errors.append(f"kline-gaps parse: {exc}")

    raw_dir = Path(args.raw_dir)
    dataset_dir = Path(args.dataset_dir)
    consumed, consume_errors, roles = consumed_raw_paths(
        raw_dir=raw_dir,
        symbols=HARD_CODED_SYMBOLS,
        timeframe=args.timeframe,
        stitch_points=stitch_points,
        backfill_binance=args.backfill_binance,
    )
    errors.extend(consume_errors)

    raw_inventory: list[dict] = []
    raw_dup_errors: list[str] = []
    for path in consumed:
        try:
            info = inspect_raw_file(path)
            raw_inventory.append(info)
            if info["duplicate_bar_times"]:
                raw_dup_errors.append(
                    f"raw {path.name}: duplicate bar_time: {info['duplicate_bar_times'][:8]}"
                )
            if not info["has_source"]:
                errors.append(f"raw {path.name}: missing required source column")
        except Exception as exc:  # noqa: BLE001
            errors.append(f"raw {path}: {exc}")
    errors.extend(raw_dup_errors)

    manifest_path = dataset_dir / "manifest.json"
    manifest_symbols = None
    if manifest_path.is_file():
        try:
            manifest = json.loads(manifest_path.read_text())
            manifest_symbols = list(manifest.get("symbols") or [])
            if sorted(manifest_symbols) != sorted(HARD_CODED_SYMBOLS):
                errors.append(
                    f"manifest symbols {manifest_symbols} != hard-coded {HARD_CODED_SYMBOLS}"
                )
        except Exception as exc:  # noqa: BLE001
            errors.append(f"manifest: {exc}")

    frozen: dict[str, dict] = {}
    raw_ok = not consume_errors and not raw_dup_errors
    for symbol in HARD_CODED_SYMBOLS:
        parquet = dataset_dir / f"{symbol}.parquet"
        if not parquet.is_file():
            errors.append(f"frozen parquet missing: {parquet}")
            continue
        if not raw_ok or symbol not in grid_start:
            frozen[symbol] = {
                "path": str(parquet),
                "ok": False,
                "error": "skipped: raw/declaration prerequisite failed",
            }
            continue
        stamp = stitch_points.get(symbol)
        stitch = None if stamp is None else _parse_utc(stamp)
        try:
            info, row_errors = inspect_symbol(
                symbol,
                parquet,
                raw_dir=raw_dir,
                stitch=stitch,
                window_bars=args.window_bars,
                cutoff=cutoff,
                timeframe=args.timeframe,
                ticks=ticks,
                backfill_binance=args.backfill_binance,
                declared_gaps=set(declared_gaps.get(symbol, set())),
                grid_start=grid_start[symbol],
                expected_rows=expected_rows[symbol],
                expected_external=expected_external[symbol],
                expected_non_comparable=expected_nc[symbol],
                expected_bad_count=expected_bad[symbol],
                expected_binance_rows=bn_rows[symbol],
                expected_htx_rows=htx_rows[symbol],
                expected_binance_start=bn_start[symbol],
                expected_binance_end=bn_end[symbol],
                expected_htx_start=htx_start[symbol],
                expected_htx_end=htx_end[symbol],
                expected_last_open=last_open[symbol],
            )
        except Exception as exc:  # noqa: BLE001
            info = {"path": str(parquet), "ok": False, "error": str(exc)}
            row_errors = [f"frozen {symbol}: {exc}"]
        frozen[symbol] = info
        errors.extend(row_errors)

    script_path = Path(__file__).resolve()
    ok = not errors
    payload = {
        "ok": ok,
        "product_option": "A",
        "checked_at_utc": _utc_now(),
        "script_path": str(script_path),
        "script_sha256": file_sha256(script_path),
        "package_tar": str(tar_path),
        "package_tar_sha256": hashed_tar,
        "package_sha256_arg": args.package_sha256,
        "package_sha256_verified": hashed_tar == args.package_sha256,
        "hard_coded_symbols": HARD_CODED_SYMBOLS,
        "contracts": symbols,
        "manifest_symbols": manifest_symbols,
        "dataset_dir": str(dataset_dir.resolve()),
        "raw_dir": str(raw_dir.resolve()),
        "consumed_files": [str(path) for path in consumed],
        "consumed_file_roles": roles,
        "raw_inventory": raw_inventory,
        "raw_native_flags_required": False,
        "bad_ticks_path": str(ticks_path),
        "bad_ticks_sha256": ticks_sha,
        "expected_bad_ticks_sha256": args.expected_bad_ticks_sha256,
        "parsed_bad_tick_rows": parsed_tick_rows,
        "confirmed_bad_tick_rows": len(ticks),
        "expected_bad_ticks": expected_bad,
        "expected_rows": expected_rows,
        "expected_external": expected_external,
        "expected_non_comparable": expected_nc,
        "grid_start": {key: iso_z(value) for key, value in grid_start.items()},
        "kline_gaps_path": str(gaps_path),
        "kline_gaps_sha256": kline_gaps_sha,
        "kline_gaps": {key: sorted(values) for key, values in declared_gaps.items()},
        "window_bars": args.window_bars,
        "cutoff": args.cutoff,
        "timeframe": args.timeframe,
        "stitch_points": stitch_points,
        "notes": notes,
        "frozen": frozen,
        "errors": errors,
    }
    evidence = Path(args.evidence)
    evidence.parent.mkdir(parents=True, exist_ok=True)
    evidence.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n")
    if not ok:
        print(f"FAIL quality-flag gate; evidence={evidence}", file=sys.stderr)
        for item in errors:
            print(item, file=sys.stderr)
        return 1
    print(f"OK quality-flag gate; evidence={evidence}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
