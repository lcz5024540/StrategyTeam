"""Load completed round-trip trades from backtest run artifacts."""

from __future__ import annotations

from pathlib import Path
from typing import Any, Sequence

import pandas as pd

from ..engine.contract_value import coin_notional, require_contract_size

CORE_SYMBOLS = frozenset({"BTC-USDT", "ETH-USDT"})


def run_artifacts_dir(workspace: Path, run_id: str) -> Path:
    return workspace / "runs" / run_id


def resolve_trades_path(
    workspace: Path,
    *,
    run_id: str | None,
    trades_path: str | None,
) -> Path:
    if trades_path:
        path = Path(trades_path)
        if not path.is_file():
            raise FileNotFoundError(f"trades file not found: {path}")
        return path
    if not run_id:
        raise ValueError("Either --run-id or --trades is required")
    path = run_artifacts_dir(workspace, run_id) / "return_series.parquet"
    if not path.is_file():
        raise FileNotFoundError(
            f"return_series.parquet not found for run {run_id}: {path}"
        )
    return path


def load_completed_trades(path: Path) -> pd.DataFrame:
    df = pd.read_parquet(path)
    required = {"net_pnl", "symbol", "entry_time"}
    missing = required - set(df.columns)
    if missing:
        raise ValueError(f"trades missing columns {sorted(missing)}: {path}")
    if df.empty:
        return df
    out = df.copy()
    out["entry_time"] = pd.to_datetime(out["entry_time"], utc=True)
    if "exit_time" in out.columns:
        out["exit_time"] = pd.to_datetime(out["exit_time"], utc=True)
    out["net_pnl"] = out["net_pnl"].astype(float)
    if "side" not in out.columns:
        raise ValueError(f"trades missing side column for permutation templates: {path}")
    return out


def net_pnls_from_fills(fills: Sequence[Any]) -> list[float]:
    """Conservative net PnL per closed round-trip (same formula as return_series)."""
    open_fills: dict[str, Any] = {}
    pnls: list[float] = []
    for fill in fills:
        if fill.is_entry:
            open_fills[fill.symbol] = fill
            continue
        entry = open_fills.pop(fill.symbol, None)
        if entry is None:
            continue
        contract_size = require_contract_size(
            getattr(entry, "contract_size", None), entry.symbol
        )
        if entry.side == "LONG":
            price_pnl = coin_notional(
                fill.price - entry.price, entry.size, contract_size, entry.symbol
            )
        else:
            price_pnl = coin_notional(
                entry.price - fill.price, entry.size, contract_size, entry.symbol
            )
        costs = (
            float(entry.fees)
            + float(fill.fees)
            + float(entry.slippage)
            + float(fill.slippage)
            + float(getattr(fill, "funding", 0.0) or 0.0)
        )
        pnls.append(float(price_pnl - costs))
    return pnls


def attach_regime_from_decisions(
    trades: pd.DataFrame,
    decisions_path: Path,
) -> pd.DataFrame:
    if trades.empty or not decisions_path.is_file():
        return trades
    decisions = pd.read_parquet(decisions_path)
    if "regime" not in decisions.columns:
        return trades
    decisions = decisions.copy()
    decisions["bar_time"] = pd.to_datetime(decisions["bar_time"], utc=True)
    regime_map = (
        decisions.drop_duplicates(subset=["symbol", "bar_time"])
        .set_index(["symbol", "bar_time"])["regime"]
    )
    out = trades.copy()
    keys = list(zip(out["symbol"], out["entry_time"]))
    out["trend_state"] = [
        _trend_state_from_regime(regime_map.get(key))
        for key in keys
    ]
    return out


def _trend_state_from_regime(regime: Any) -> str:
    if regime is None or (isinstance(regime, float) and pd.isna(regime)):
        return "UNKNOWN"
    text = str(regime)
    for prefix in ("TREND_UP", "TREND_DOWN", "RANGE", "TRANSITION"):
        if text.startswith(prefix):
            return prefix
    return "UNKNOWN"


def coin_group(symbol: str) -> str:
    return "core" if symbol in CORE_SYMBOLS else "alt"


def group_trades(
    trades: pd.DataFrame,
    group_key: str,
) -> dict[str, pd.DataFrame]:
    if trades.empty:
        return {"overall": trades}
    if group_key == "coin_group":
        col = trades["symbol"].map(coin_group)
    elif group_key == "trend_state":
        if "trend_state" not in trades.columns:
            raise ValueError(
                "trend_state grouping requires decisions.parquet on the run dir "
                "(regime at entry)"
            )
        col = trades["trend_state"]
    else:
        raise ValueError(f"unsupported group-by: {group_key}")
    groups: dict[str, pd.DataFrame] = {"overall": trades}
    for name, frame in trades.groupby(col, sort=True):
        groups[str(name)] = frame.reset_index(drop=True)
    return groups
