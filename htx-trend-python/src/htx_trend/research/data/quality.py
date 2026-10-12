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

STITCH_WINDOW_BARS = 30
OVERLAP_AUDIT_THRESHOLD = 0.003
MANUAL_REVIEW_THRESHOLD = 0.015
NOMINAL_FUNDING_INTERVAL_HOURS = 8
NOMINAL_FUNDING_INTERVAL = pd.Timedelta(hours=NOMINAL_FUNDING_INTERVAL_HOURS)
EXPECTED_FUNDING_GAPS_SHA256 = (
    "08b52442ca4a60cf2d95a9e047b8f602a3d753a73a2832c86b1f81276fdbd221"
)
REGISTERED_DROP_REASONS = frozenset(
    {
        "htx_before_stitch",
        "binance_at_or_after_stitch",
        "closed_after_cutoff",
        "duplicate_boundary",
    }
)
_STITCH_NONE_TOKENS = frozenset({"none", "null", "~"})

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


@dataclass(frozen=True)
class FundingGap:
    symbol: str
    prev: datetime
    next: datetime
    reason: str
    hours: float | None = None
    split: str | None = None


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


def utc_iso_z(value: datetime | pd.Timestamp | str) -> str:
    stamp = pd.Timestamp(value)
    if stamp.tzinfo is None:
        stamp = stamp.tz_localize("UTC")
    else:
        stamp = stamp.tz_convert("UTC")
    return stamp.strftime("%Y-%m-%dT%H:%M:%SZ")


def default_funding_gaps_path() -> Path:
    return default_quality_dir() / "funding_gaps.yaml"


def load_funding_gaps(path: Path | None = None) -> list[FundingGap]:
    candidate = path or default_funding_gaps_path()
    if not candidate.exists():
        raise ValueError(f"funding gaps file not found: {candidate}")
    payload = yaml.safe_load(candidate.read_text()) or {}
    rows = payload.get("gaps")
    if not isinstance(rows, list):
        raise ValueError(f"funding gaps file {candidate} must have a top-level gaps list")
    gaps: list[FundingGap] = []
    for item in rows:
        hours = item.get("hours")
        gaps.append(
            FundingGap(
                symbol=str(item["symbol"]),
                prev=parse_utc(item["prev"]),
                next=parse_utc(item["next"]),
                reason=str(item.get("reason") or ""),
                hours=None if hours is None else float(hours),
                split=None if item.get("split") is None else str(item.get("split")),
            )
        )
    return gaps


def funding_gaps_hash(path: Path | None = None) -> str:
    candidate = path or default_funding_gaps_path()
    if not candidate.exists():
        raise ValueError(f"funding gaps file not found: {candidate}")
    return "sha256:" + hashlib.sha256(candidate.read_bytes()).hexdigest()


def require_funding_settlement_gaps(
    symbol: str,
    funding_times,
    gaps: Iterable[FundingGap],
    *,
    cutoff: datetime | str | None = None,
    last_bar_close: datetime | pd.Timestamp | None = None,
) -> None:
    times = (
        pd.to_datetime(pd.Series(list(funding_times)), utc=True)
        .sort_values()
        .drop_duplicates()
        .reset_index(drop=True)
    )
    if times.empty:
        return
    registry = {
        (gap.symbol, utc_iso_z(gap.prev), utc_iso_z(gap.next)) for gap in gaps
    }
    pairs: list[tuple[str, str]] = []
    for prev, nxt in zip(times.iloc[:-1], times.iloc[1:]):
        prev_s, next_s = utc_iso_z(prev), utc_iso_z(nxt)
        pairs.append((prev_s, next_s))
        delta = pd.Timestamp(nxt) - pd.Timestamp(prev)
        if delta != NOMINAL_FUNDING_INTERVAL:
            if (symbol, prev_s, next_s) not in registry:
                raise ValueError(
                    f"unregistered funding gap for {symbol}: {prev_s} -> {next_s} "
                    f"({delta})"
                )
    data_min = pd.Timestamp(times.iloc[0])
    data_max = pd.Timestamp(times.iloc[-1])
    if cutoff is not None:
        cut = pd.Timestamp(parse_utc(cutoff) if not isinstance(cutoff, datetime) else cutoff)
        if cut.tzinfo is None:
            cut = cut.tz_localize("UTC")
        else:
            cut = cut.tz_convert("UTC")
        data_max = min(data_max, cut)
    pair_set = set(pairs)
    for gap in gaps:
        if gap.symbol != symbol:
            continue
        prev_ts = pd.Timestamp(gap.prev)
        next_ts = pd.Timestamp(gap.next)
        if prev_ts.tzinfo is None:
            prev_ts = prev_ts.tz_localize("UTC")
        else:
            prev_ts = prev_ts.tz_convert("UTC")
        if next_ts.tzinfo is None:
            next_ts = next_ts.tz_localize("UTC")
        else:
            next_ts = next_ts.tz_convert("UTC")
        if prev_ts < data_min or next_ts > data_max:
            continue
        key = (utc_iso_z(prev_ts), utc_iso_z(next_ts))
        if key not in pair_set:
            raise ValueError(
                f"registered funding gap missing from data for {symbol}: "
                f"{key[0]} -> {key[1]}"
            )
    if last_bar_close is not None:
        last = pd.Timestamp(times.iloc[-1])
        close = pd.Timestamp(last_bar_close)
        if close.tzinfo is None:
            close = close.tz_localize("UTC")
        else:
            close = close.tz_convert("UTC")
        if close - last > NOMINAL_FUNDING_INTERVAL:
            raise ValueError(
                f"bars more than one nominal funding interval after last "
                f"settlement for {symbol}: last={utc_iso_z(last)} "
                f"bar_close={utc_iso_z(close)}"
            )


def parse_stitch_declaration(value: Any) -> datetime | None:
    if value is None:
        return None
    if isinstance(value, float) and pd.isna(value):
        return None
    if isinstance(value, str):
        text = value.strip()
        if text.lower() in _STITCH_NONE_TOKENS:
            return None
        return parse_utc(text)
    if isinstance(value, datetime):
        return parse_utc(value)
    raise ValueError(f"invalid stitch declaration: {value!r}")


def stitch_points_for_manifest(
    points: dict[str, datetime | None],
) -> dict[str, str | None]:
    payload: dict[str, str | None] = {}
    for symbol, value in points.items():
        if value is None:
            payload[symbol] = None
        else:
            stamp = parse_utc(value)
            payload[symbol] = stamp.strftime("%Y-%m-%dT%H:%M:%SZ")
    return payload


def parse_stitch_point_overrides(
    values: list[str] | str | datetime | None,
) -> dict[str, datetime]:
    """Accept only repeatable SYMBOL=TIME forms. Bare timestamps raise."""
    if values is None:
        return {}
    if isinstance(values, datetime):
        raise ValueError("bare stitch-point time is not allowed; use SYMBOL=TIME")
    items = values if isinstance(values, list) else [values]
    parsed: dict[str, datetime] = {}
    for item in items:
        if isinstance(item, datetime):
            raise ValueError("bare stitch-point time is not allowed; use SYMBOL=TIME")
        text = str(item).strip()
        if not text:
            continue
        if "=" not in text:
            raise ValueError(f"stitch-point must be SYMBOL=TIME, got {item!r}")
        symbol, _, raw = text.partition("=")
        symbol = symbol.strip()
        stamp = raw.strip()
        if not symbol or not stamp:
            raise ValueError(f"stitch-point must be SYMBOL=TIME, got {item!r}")
        parsed[symbol] = parse_utc(stamp)
    return parsed


def load_stitch_points(
    path: Path | None = None,
    override: list[str] | str | datetime | None = None,
) -> dict[str, datetime | None]:
    candidate = path or default_quality_dir() / "stitch_points.yaml"
    if not candidate.exists():
        raise ValueError(f"stitch points file not found: {candidate}")
    payload = yaml.safe_load(candidate.read_text()) or {}
    raw = payload.get("points") or payload.get("stitch") or payload
    if not isinstance(raw, dict):
        raise ValueError(f"stitch points file {candidate} must be a mapping")
    points: dict[str, datetime | None] = {}
    for symbol, value in raw.items():
        if symbol in {"version", "points", "stitch"}:
            continue
        points[str(symbol)] = parse_stitch_declaration(value)
    overrides = parse_stitch_point_overrides(override)
    for symbol, stamp in overrides.items():
        if symbol not in points:
            raise ValueError(
                f"unknown/undeclared stitch-point symbol: {symbol}"
            )
        points[symbol] = stamp
    return points


def require_declared_stitch_points(
    raw: dict | None,
    symbols: Iterable[str],
) -> dict[str, datetime | None]:
    if raw is None or not isinstance(raw, dict):
        raise ValueError(
            "stitch_points declaration is required for every symbol "
            "(use an ISO timestamp or none)"
        )
    missing = [symbol for symbol in symbols if symbol not in raw]
    if missing:
        raise ValueError(f"symbols missing from stitch declaration: {missing}")
    return {
        str(symbol): parse_stitch_declaration(raw[symbol]) for symbol in symbols
    }


def stitch_point_for(
    symbol: str,
    points: dict[str, datetime | None] | None = None,
    override: list[str] | str | datetime | None = None,
) -> datetime | None:
    if override is not None:
        if isinstance(override, datetime):
            return parse_utc(override)
        overrides = parse_stitch_point_overrides(override)
        if symbol in overrides:
            return overrides[symbol]
        if not overrides and not isinstance(override, list):
            raise ValueError(
                f"stitch-point must be SYMBOL=TIME, got {override!r}"
            )
    table = load_stitch_points() if points is None else points
    if symbol not in table:
        raise ValueError(
            f"{symbol} is not declared in stitch config; "
            "HTX-only symbols must be declared none"
        )
    return parse_stitch_declaration(table[symbol])


def require_source_column(df: pd.DataFrame, *, context: str) -> None:
    if "source" not in df.columns:
        raise ValueError(f"{context} missing required source column")
    series = df["source"]
    if series.isna().any():
        raise ValueError(f"{context} source contains null values")


def require_source_value(value: Any, *, context: str) -> str:
    if value is None:
        raise ValueError(f"{context} missing required source")
    if isinstance(value, float) and pd.isna(value):
        raise ValueError(f"{context} missing required source")
    text = str(value).strip()
    if not text or text.lower() in {"nan", "none", "<na>"}:
        raise ValueError(f"{context} missing required source")
    return text


def require_row_count_accounting(
    in_count: int,
    dropped: dict[str, int],
    out_count: int,
) -> dict[str, Any]:
    unknown = set(dropped) - REGISTERED_DROP_REASONS
    if unknown:
        raise ValueError(f"unregistered drop reasons: {sorted(unknown)}")
    dropped_total = sum(int(value) for value in dropped.values())
    if int(in_count) - dropped_total != int(out_count):
        raise ValueError(
            f"row count mismatch: in={in_count} dropped={dropped} out={out_count}; "
            "no registered reason accounts for the difference"
        )
    return {
        "in": int(in_count),
        "dropped": {key: int(value) for key, value in dropped.items()},
        "out": int(out_count),
    }


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
