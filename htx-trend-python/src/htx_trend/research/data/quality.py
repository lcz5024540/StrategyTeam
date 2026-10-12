"""Stitch-point policy, overlap audit, and versioned bad-tick exclusions."""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, Iterable

import pandas as pd
import yaml

DEFAULT_STITCH_POINT = datetime(2020, 11, 1, 0, 0, tzinfo=UTC)
DEFAULT_STITCH_POINTS = {
    "BTC-USDT": DEFAULT_STITCH_POINT,
    "ETH-USDT": DEFAULT_STITCH_POINT,
}
STITCH_WINDOW_BARS = 30
OVERLAP_AUDIT_THRESHOLD = 0.003
MANUAL_REVIEW_THRESHOLD = 0.015

QUALITY_FLAG_COLUMNS = ("incomplete", "non_comparable", "bad_tick", "external")
BAD_TICK_KINDS = ("frozen", "spike")
EXTERNAL_SOURCES = frozenset({"binance", "binance_external", "backfill"})
DEFAULT_KIND_STOP_ON_BAR = {
    "frozen": False,
    "spike": True,
}

KNOWN_CANDIDATES = [
    {
        "symbol": "BTC-USDT",
        "bar_time": "2020-10-21T00:00:00Z",
        "end_time": "2020-10-27T23:59:59Z",
        "reason": "HTX USDT-M contract launch week",
        "confirmed": False,
    },
    {
        "symbol": "ETH-USDT",
        "bar_time": "2020-10-21T00:00:00Z",
        "end_time": "2020-10-27T23:59:59Z",
        "reason": "HTX USDT-M contract launch week",
        "confirmed": False,
    },
    {
        "symbol": "BTC-USDT",
        "bar_time": "2021-02-19T12:00:00Z",
        "reason": "frozen-price bar 12:00 UTC (identical closes on both symbols)",
        "confirmed": False,
    },
    {
        "symbol": "BTC-USDT",
        "bar_time": "2021-02-19T16:00:00Z",
        "reason": "frozen-price bar 16:00 UTC (identical closes on both symbols)",
        "confirmed": False,
    },
    {
        "symbol": "BTC-USDT",
        "bar_time": "2021-02-19T20:00:00Z",
        "reason": "frozen-price bar 20:00 UTC (identical closes on both symbols)",
        "confirmed": False,
    },
    {
        "symbol": "ETH-USDT",
        "bar_time": "2021-02-19T12:00:00Z",
        "reason": "frozen-price bar 12:00 UTC (identical closes on both symbols)",
        "confirmed": False,
    },
    {
        "symbol": "ETH-USDT",
        "bar_time": "2021-02-19T16:00:00Z",
        "reason": "frozen-price bar 16:00 UTC (identical closes on both symbols)",
        "confirmed": False,
    },
    {
        "symbol": "ETH-USDT",
        "bar_time": "2021-02-19T20:00:00Z",
        "reason": "frozen-price bar 20:00 UTC (identical closes on both symbols)",
        "confirmed": False,
    },
]


@dataclass(frozen=True)
class BadTick:
    symbol: str
    bar_time: datetime
    reason: str
    confirmed: bool = True
    kind: str = "frozen"
    end_time: datetime | None = None
    stop_on_bar: bool | None = None


def normalize_bad_tick_kind(kind: str | None) -> str:
    if kind is None:
        raise ValueError("bad_tick kind is required; expected 'frozen' or 'spike'")
    value = str(kind).strip().lower()
    if value in {"", "nan", "none"}:
        raise ValueError("bad_tick kind is required; expected 'frozen' or 'spike'")
    if value not in BAD_TICK_KINDS:
        raise ValueError(f"bad_tick kind must be one of {BAD_TICK_KINDS}, got {kind!r}")
    return value


def stop_on_bar_for_kind(kind: str | None, config: dict | None = None) -> bool:
    resolved = normalize_bad_tick_kind(kind)
    merged = dict(DEFAULT_KIND_STOP_ON_BAR)
    if config:
        for key, value in config.items():
            if isinstance(value, dict) and "stop_on_bar" in value:
                merged[str(key)] = bool(value["stop_on_bar"])
            elif isinstance(value, bool):
                merged[str(key)] = value
    return bool(merged.get(resolved, True))


def parse_utc(value: str | datetime) -> datetime:
    if isinstance(value, datetime):
        parsed = value
    else:
        parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        return parsed.replace(tzinfo=UTC)
    return parsed.astimezone(UTC)


def default_quality_dir() -> Path:
    return Path(__file__).resolve().parents[4] / "data" / "quality"


def load_stitch_points(
    path: Path | None = None,
    override: str | datetime | None = None,
) -> dict[str, datetime]:
    points = dict(DEFAULT_STITCH_POINTS)
    candidate = path or default_quality_dir() / "stitch_points.yaml"
    if candidate.exists():
        payload = yaml.safe_load(candidate.read_text()) or {}
        raw = payload.get("points") or payload
        if isinstance(raw, dict):
            for symbol, value in raw.items():
                if symbol in {"version", "points"}:
                    continue
                points[str(symbol)] = parse_utc(value)
    if override is not None:
        stamp = parse_utc(override)
        return {symbol: stamp for symbol in points} | {
            "BTC-USDT": stamp,
            "ETH-USDT": stamp,
        }
    return points


def stitch_point_for(
    symbol: str,
    points: dict[str, datetime] | None = None,
    override: str | datetime | None = None,
) -> datetime:
    if override is not None:
        return parse_utc(override)
    table = points or load_stitch_points()
    return table.get(symbol, DEFAULT_STITCH_POINT)


def require_quality_flag_column(df: pd.DataFrame, name: str, *, symbol: str) -> None:
    if name not in df.columns:
        raise ValueError(
            f"{symbol} dataset missing required quality flag columns: ['{name}']. "
            "Only an existing column with value False means a normal bar."
        )
    series = df[name]
    if series.isna().any():
        raise ValueError(
            f"{symbol} dataset quality flag column {name!r} contains null "
            "values (NaN/None); refusing to coerce with bool()."
        )
    if series.dtype != bool:
        raise ValueError(
            f"{symbol} dataset quality flag column {name!r} must be strictly "
            f"boolean (numpy.bool_/bool), got dtype={series.dtype}."
        )
    for value in series.tolist():
        if value is not True and value is not False and type(value).__name__ != "bool_":
            raise ValueError(
                f"{symbol} dataset quality flag column {name!r} must be strictly "
                f"boolean (numpy.bool_/bool), got {value!r}."
            )


def require_quality_flag_columns(df: pd.DataFrame, *, symbol: str) -> None:
    missing = [name for name in QUALITY_FLAG_COLUMNS if name not in df.columns]
    if missing:
        raise ValueError(
            f"{symbol} dataset missing required quality flag columns: {missing}. "
            "Only an existing column with value False means a normal bar."
        )
    for name in QUALITY_FLAG_COLUMNS:
        require_quality_flag_column(df, name, symbol=symbol)


def load_bad_ticks(path: Path | None = None) -> list[BadTick]:
    candidate = path or default_quality_dir() / "bad_ticks.yaml"
    if not candidate.exists():
        return []
    payload = yaml.safe_load(candidate.read_text()) or {}
    rows = payload.get("ticks") or payload.get("entries") or []
    ticks: list[BadTick] = []
    for item in rows:
        stop_override = item.get("stop_on_bar")
        ticks.append(
            BadTick(
                symbol=str(item["symbol"]),
                bar_time=parse_utc(item["bar_time"]),
                reason=str(item.get("reason") or ""),
                confirmed=bool(item.get("confirmed", True)),
                kind=normalize_bad_tick_kind(item.get("kind")),
                end_time=parse_utc(item["end_time"]) if item.get("end_time") else None,
                stop_on_bar=None if stop_override is None else bool(stop_override),
            )
        )
    return ticks


def confirmed_bad_ticks(ticks: Iterable[BadTick]) -> list[BadTick]:
    return [tick for tick in ticks if tick.confirmed]


def bad_ticks_hash(path: Path | None, ticks: Iterable[BadTick] | None = None) -> str:
    if path is not None and path.exists():
        return "sha256:" + hashlib.sha256(path.read_bytes()).hexdigest()
    payload = {
        "ticks": [
            {
                "symbol": tick.symbol,
                "bar_time": tick.bar_time.isoformat(),
                "reason": tick.reason,
                "confirmed": tick.confirmed,
                "kind": tick.kind,
            }
            for tick in confirmed_bad_ticks(ticks or [])
        ]
    }
    return "sha256:" + hashlib.sha256(
        json.dumps(payload, sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()


def apply_bad_ticks(df: pd.DataFrame, symbol: str, ticks: Iterable[BadTick]) -> pd.DataFrame:
    work = df.copy()
    if "incomplete" not in work.columns:
        work["incomplete"] = False
    if "non_comparable" not in work.columns:
        work["non_comparable"] = False
    if "bad_tick" not in work.columns:
        work["bad_tick"] = False
    if "bad_tick_kind" not in work.columns:
        work["bad_tick_kind"] = ""
    if work.empty:
        return work
    times = pd.to_datetime(work["bar_time"], utc=True)
    for tick in confirmed_bad_ticks(ticks):
        if tick.symbol != symbol:
            continue
        if tick.end_time is not None:
            mask = (times >= pd.Timestamp(tick.bar_time)) & (times <= pd.Timestamp(tick.end_time))
        else:
            mask = times == pd.Timestamp(tick.bar_time)
        work.loc[mask, "bad_tick"] = True
        work.loc[mask, "bad_tick_kind"] = tick.kind
    return work


def overlap_audit_rows(
    overlap: pd.DataFrame,
    symbol: str,
    audit_threshold: float = OVERLAP_AUDIT_THRESHOLD,
    review_threshold: float = MANUAL_REVIEW_THRESHOLD,
) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    if overlap is None or overlap.empty:
        return rows
    for item in overlap.itertuples(index=False):
        deviation = float(item.deviation)
        if deviation <= audit_threshold:
            continue
        rows.append(
            {
                "timestamp": pd.Timestamp(item.bar_time).isoformat(),
                "symbol": symbol,
                "htx_close": float(item.close_htx),
                "binance_close": float(item.close_binance),
                "deviation": deviation,
                "flag_manual_review": deviation > review_threshold,
            }
        )
    return rows


def write_quality_audit(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, default=str))
    markdown = path.with_suffix(".md")
    lines = [
        "# Data quality audit",
        "",
        f"- stitch_points: {payload.get('stitch_points')}",
        f"- non_comparable_window_bars: {payload.get('non_comparable_window_bars')}",
        f"- bad_ticks_hash: {payload.get('bad_ticks_hash')}",
        "",
        "## Overlap deviations (not dropped from signals)",
        "",
        "| timestamp | symbol | htx_close | binance_close | deviation | manual_review |",
        "| --- | --- | --- | --- | --- | --- |",
    ]
    for row in payload.get("overlap_audit") or []:
        lines.append(
            "| {timestamp} | {symbol} | {htx} | {bn} | {dev:.6f} | {flag} |".format(
                timestamp=row["timestamp"],
                symbol=row["symbol"],
                htx=row["htx_close"],
                bn=row["binance_close"],
                dev=row["deviation"],
                flag="YES" if row.get("flag_manual_review") else "",
            )
        )
    lines.extend(["", "## Known ops candidates (not auto-marked)", ""])
    for item in payload.get("known_candidates") or KNOWN_CANDIDATES:
        lines.append(
            f"- {item['symbol']} {item['bar_time']}"
            f"{' .. ' + item['end_time'] if item.get('end_time') else ''}"
            f": {item['reason']} (confirmed={item.get('confirmed', False)})"
        )
    markdown.write_text("\n".join(lines) + "\n")
