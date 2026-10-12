"""Shared trade-level metrics for statistical gates."""

from __future__ import annotations

from typing import Any, Sequence

import numpy as np


def profit_factor(net_pnls: Sequence[float]) -> float | None:
    """Gross wins / gross losses (losses as positive denominator)."""
    if len(net_pnls) == 0:
        return None
    wins = float(sum(p for p in net_pnls if p > 0))
    losses = float(-sum(p for p in net_pnls if p < 0))
    if losses == 0:
        return None
    return float(wins / losses)


def mean_net_pnl_per_trade(net_pnls: Sequence[float]) -> float | None:
    if not net_pnls:
        return None
    return float(np.mean(net_pnls))


def bootstrap_percentile_ci(
    values: np.ndarray,
    confidence: float,
) -> tuple[float, float]:
    """Two-sided percentile CI; confidence in (0, 1)."""
    if values.size == 0:
        return (float("nan"), float("nan"))
    alpha = (1.0 - confidence) / 2.0
    low = float(np.quantile(values, alpha))
    high = float(np.quantile(values, 1.0 - alpha))
    return low, high


def summarize_pf_bootstrap(
    net_pnls: Sequence[float],
    *,
    rng: np.random.Generator,
    n_bootstrap: int,
    confidence: float,
) -> dict[str, Any]:
    observed_pf = profit_factor(net_pnls)
    observed_mean = mean_net_pnl_per_trade(net_pnls)
    n_trades = len(net_pnls)
    if n_trades == 0:
        return {
            "n_trades": 0,
            "observed_profit_factor": None,
            "observed_mean_net_pnl_per_trade": None,
            "bootstrap_n": n_bootstrap,
            "confidence": confidence,
            "profit_factor_bootstrap": None,
        }
    arr = np.asarray(net_pnls, dtype=np.float64)
    pf_samples = np.empty(n_bootstrap, dtype=np.float64)
    for i in range(n_bootstrap):
        sample = rng.choice(arr, size=n_trades, replace=True)
        pf = profit_factor(sample)
        pf_samples[i] = np.nan if pf is None else pf
    valid = pf_samples[~np.isnan(pf_samples)]
    if valid.size == 0:
        ci_low, ci_high = (float("nan"), float("nan"))
    else:
        ci_low, ci_high = bootstrap_percentile_ci(valid, confidence)
    return {
        "n_trades": n_trades,
        "observed_profit_factor": observed_pf,
        "observed_mean_net_pnl_per_trade": observed_mean,
        "bootstrap_n": n_bootstrap,
        "confidence": confidence,
        "profit_factor_bootstrap": {
            "mean": float(np.nanmean(pf_samples)) if pf_samples.size else None,
            "ci_lower": ci_low,
            "ci_upper": ci_high,
            "gate_ci_lower": ci_low,
        },
    }
