"""Random-entry permutation test (primary metric: mean net PnL per trade)."""

from __future__ import annotations

import json
from collections import defaultdict, deque
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd
import yaml

from ..ledger import LedgerManager
from ..runner import BacktestRunner
from .metrics import mean_net_pnl_per_trade
from .trades import load_completed_trades, net_pnls_from_fills, run_artifacts_dir


def _default_seed(run_id: str) -> int:
    import hashlib

    digest = hashlib.sha256(f"permutation:{run_id}".encode()).digest()
    return int.from_bytes(digest[:8], "big") % (2**31 - 1)


def _trade_templates(trades: pd.DataFrame) -> dict[str, list[tuple[pd.Timestamp, str]]]:
    templates: dict[str, list[tuple[pd.Timestamp, str]]] = defaultdict(list)
    ordered = trades.sort_values("entry_time")
    for row in ordered.itertuples():
        templates[str(row.symbol)].append(
            (pd.Timestamp(row.entry_time), str(row.side))
        )
    return dict(templates)


def _eligible_entry_bars(df: pd.DataFrame, start_idx: int) -> list[pd.Timestamp]:
    times = pd.to_datetime(df["bar_time"], utc=True)
    eligible: list[pd.Timestamp] = []
    for idx in range(start_idx, len(df)):
        bar = df.iloc[idx]
        if bar["incomplete"] or bar["non_comparable"] or bar["bad_tick"]:
            continue
        eligible.append(times.iloc[idx])
    return eligible


def build_forced_entry_queues(
    templates: dict[str, list[tuple[pd.Timestamp, str]]],
    eligible: dict[str, list[pd.Timestamp]],
    rng: np.random.Generator,
) -> dict[str, deque[tuple[pd.Timestamp, str]]]:
    queues: dict[str, deque[tuple[pd.Timestamp, str]]] = {}
    for symbol, entries in templates.items():
        pool = list(eligible.get(symbol) or [])
        sides = [side for _time, side in entries]
        n = len(sides)
        if n == 0:
            continue
        if len(pool) < n:
            raise ValueError(
                f"not enough eligible bars for {symbol}: need {n}, have {len(pool)}"
            )
        indices = rng.choice(len(pool), size=n, replace=False)
        chosen_times = sorted(pool[i] for i in indices)
        queues[symbol] = deque(
            (bar_time, side) for bar_time, side in zip(chosen_times, sides, strict=True)
        )
    return queues


def _resolve_config_path(
    workspace: Path,
    source_run_id: str,
    config_path: Path | None,
    config: dict[str, Any],
) -> Path:
    if config_path is not None and config_path.is_file():
        return config_path
    staged = run_artifacts_dir(workspace, source_run_id) / "stats" / "_permutation_config.yaml"
    staged.parent.mkdir(parents=True, exist_ok=True)
    staged.write_text(yaml.safe_dump(config, sort_keys=False, allow_unicode=True))
    return staged


def run_permutation_test(
    workspace: Path,
    *,
    source_run_id: str,
    config_path: Path | None,
    n_permutations: int,
    seed: int | None,
    output: Path | None,
    write_ledger: bool,
) -> dict[str, Any]:
    trades_path = run_artifacts_dir(workspace, source_run_id) / "return_series.parquet"
    trades = load_completed_trades(trades_path)
    if trades.empty:
        raise ValueError(f"no completed trades in {trades_path}")

    ledger = LedgerManager(workspace / "ledger.db")
    source_run = ledger.get_run(source_run_id)
    if source_run is not None:
        config = dict(source_run.config_snapshot)
    elif config_path is not None and config_path.is_file():
        with open(config_path) as handle:
            config = yaml.safe_load(handle) or {}
    else:
        raise ValueError(
            f"run {source_run_id} not in ledger; pass --config for permutation replay"
        )

    resolved_config = _resolve_config_path(workspace, source_run_id, config_path, config)
    chosen_seed = seed if seed is not None else _default_seed(source_run_id)
    rng = np.random.default_rng(chosen_seed)

    runner = BacktestRunner(resolved_config, workspace)
    templates = _trade_templates(trades)

    data, frozen = runner._load_dataset()
    if runner._is_smoke:
        start_idx = max(
            0,
            len(next(iter(data.values()))) - int(runner.config["smoke_bar_window"]),
        )
    else:
        start_idx = 200
    eligible = {
        symbol: _eligible_entry_bars(df, start_idx) for symbol, df in data.items()
    }

    observed_pnls = trades["net_pnl"].tolist()
    observed_metric = mean_net_pnl_per_trade(observed_pnls)
    if observed_metric is None:
        raise ValueError("source run produced no closed-trade PnL")

    null_metrics: list[float] = []
    for _ in range(n_permutations):
        queues = build_forced_entry_queues(templates, eligible, rng)
        null_results = runner._run_backtest(data, frozen, forced_entry_queues=queues)
        pnls = net_pnls_from_fills(null_results["fills"])
        metric = mean_net_pnl_per_trade(pnls)
        if metric is not None:
            null_metrics.append(metric)

    if not null_metrics:
        raise ValueError("permutation loop produced no valid null metrics")

    null_arr = np.asarray(null_metrics, dtype=np.float64)
    p_value = float((np.sum(null_arr >= observed_metric) + 1) / (len(null_arr) + 1))

    payload: dict[str, Any] = {
        "kind": "permutation",
        "recorded_at": datetime.now(UTC).isoformat(),
        "source_run_id": source_run_id,
        "config_path": str(resolved_config),
        "seed": chosen_seed,
        "n_permutations": n_permutations,
        "primary_metric": "mean_net_pnl_per_trade_after_costs",
        "observed": {
            "mean_net_pnl_per_trade": observed_metric,
            "n_trades": len(observed_pnls),
        },
        "null_distribution": {
            "mean": float(np.mean(null_arr)),
            "std": float(np.std(null_arr, ddof=1)) if len(null_arr) > 1 else 0.0,
            "n_samples": len(null_arr),
        },
        "p_value": p_value,
        "gate_p_lt": 0.05,
        "gate_pass": p_value < 0.05,
        "reference_only": {
            "note": "hit_rate / MFE / MAE not computed in this CLI revision",
        },
    }

    if output is None:
        output = (
            run_artifacts_dir(workspace, source_run_id) / "stats" / "permutation.json"
        )
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n")

    if write_ledger:
        existing = ledger.get_run(source_run_id)
        prior = (existing.metrics if existing else None) or {}
        merged = dict(prior)
        gates = dict(merged.get("statistical_gates") or {})
        gates["permutation"] = {
            "seed": chosen_seed,
            "n_permutations": n_permutations,
            "p_value": p_value,
            "output_path": str(output),
            "observed_mean_net_pnl_per_trade": observed_metric,
        }
        merged["statistical_gates"] = gates
        ledger.update_run_metrics(source_run_id, merged)

    runner.close()
    return payload
