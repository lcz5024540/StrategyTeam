"""Profit-factor bootstrap (formal gate)."""

from __future__ import annotations

import json
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import numpy as np

from ..ledger import LedgerManager
from .metrics import summarize_pf_bootstrap
from .trades import (
    attach_regime_from_decisions,
    group_trades,
    load_completed_trades,
    resolve_trades_path,
    run_artifacts_dir,
)


def _default_seed(run_id: str | None) -> int:
    if run_id:
        import hashlib

        digest = hashlib.sha256(f"pf-bootstrap:{run_id}".encode()).digest()
        return int.from_bytes(digest[:8], "big") % (2**31 - 1)
    return int(np.random.SeedSequence().generate_state(1)[0] % (2**31 - 1))


def run_pf_bootstrap(
    workspace: Path,
    *,
    run_id: str | None,
    trades_path: str | None,
    target_run_id: str | None,
    n_bootstrap: int,
    confidence: float,
    seed: int | None,
    group_by: list[str],
    output: Path | None,
    write_ledger: bool,
) -> dict[str, Any]:
    path = resolve_trades_path(workspace, run_id=run_id, trades_path=trades_path)
    trades = load_completed_trades(path)
    ledger_run = target_run_id or run_id
    if ledger_run:
        decisions = run_artifacts_dir(workspace, ledger_run) / "decisions.parquet"
        trades = attach_regime_from_decisions(trades, decisions)

    chosen_seed = seed if seed is not None else _default_seed(ledger_run)
    rng = np.random.default_rng(chosen_seed)

    slices: dict[str, Any] = {}
    slices["overall"] = summarize_pf_bootstrap(
        trades["net_pnl"].tolist(),
        rng=rng,
        n_bootstrap=n_bootstrap,
        confidence=confidence,
    )
    for key in group_by:
        for label, frame in group_trades(trades, key).items():
            if label == "overall":
                continue
            slice_name = f"{key}:{label}"
            slices[slice_name] = summarize_pf_bootstrap(
                frame["net_pnl"].tolist(),
                rng=rng,
                n_bootstrap=n_bootstrap,
                confidence=confidence,
            )

    payload: dict[str, Any] = {
        "kind": "pf_bootstrap",
        "recorded_at": datetime.now(UTC).isoformat(),
        "source_trades_path": str(path),
        "target_run_id": ledger_run,
        "seed": chosen_seed,
        "n_bootstrap": n_bootstrap,
        "confidence": confidence,
        "method": "percentile_bootstrap",
        "slices": slices,
    }

    if ledger_run and output is None:
        output = run_artifacts_dir(workspace, ledger_run) / "stats" / "pf_bootstrap.json"
    if output is not None:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n")

    if write_ledger and ledger_run:
        ledger = LedgerManager(workspace / "ledger.db")
        existing = ledger.get_run(ledger_run)
        prior = (existing.metrics if existing else None) or {}
        merged = dict(prior)
        gates = dict(merged.get("statistical_gates") or {})
        gates["pf_bootstrap"] = {
            "seed": chosen_seed,
            "n_bootstrap": n_bootstrap,
            "confidence": confidence,
            "output_path": str(output) if output else None,
            "slices": {
                name: slice_payload.get("profit_factor_bootstrap")
                for name, slice_payload in slices.items()
            },
        }
        merged["statistical_gates"] = gates
        ledger.update_run_metrics(ledger_run, merged)

    return payload
