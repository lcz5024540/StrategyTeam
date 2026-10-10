#!/usr/bin/env python3
"""Replay the Java model-policy route funnel on immutable frozen 15-minute K-lines.

This is an observation test for the route-ledger change.  It uses a supplied
read-only policy export and reproduces Java's exact primary-model,
confirmation-string and raw-score matching.  It never connects to HTX or a
database and it cannot mutate a policy or submit an order.
"""
from __future__ import annotations

import argparse
import json
import sys
from collections import Counter, defaultdict
from dataclasses import asdict
from decimal import Decimal
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PYTHON_SOURCE = ROOT / "htx-trend-python" / "src"
if str(PYTHON_SOURCE) not in sys.path:
    sys.path.insert(0, str(PYTHON_SOURCE))

from htx_trend.java_entry_contract import JavaEntryConfig, candidates, evaluate
from htx_trend.market_data import Candle
from htx_trend.policy import StrategyPolicy, select_policy
from htx_trend.strategy_context import MarketContext
from htx_trend_backtest import load_bars

SCHEMA = "htx-trend-policy-route-replay-v1"
PERIOD = "15min"
WINDOW_BARS = 160


def _manifest(path: Path) -> dict:
    value = json.loads(path.read_text(encoding="utf-8"))
    if value.get("schema") != "htx-trend-research-dataset-manifest-v1":
        raise ValueError("unsupported dataset manifest")
    if value.get("inputIntegrity") != "COMPLETE" or value.get("periods") != [PERIOD]:
        raise ValueError("policy route replay requires COMPLETE 15-minute frozen data")
    return value


def _policies(path: Path) -> tuple[StrategyPolicy, ...]:
    raw = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(raw, list):
        raise ValueError("policy export must be a JSON list")
    policies = []
    for value in raw:
        policies.append(StrategyPolicy(
            str(value["policyCode"]), str(value["policyVersion"]), str(value["primaryModel"]),
            str(value["confirmationModels"]), Decimal(str(value["baseNotionalUsdt"])),
            Decimal(str(value["maxNotionalUsdt"])), Decimal(str(value["scoreThreshold"])),
            int(value.get("boxWindowBars", 32)),
        ))
    if not policies:
        raise ValueError("policy export is empty")
    return tuple(policies)


def _candles(bars) -> list[Candle]:
    return [Candle(bar.ts, Decimal(str(bar.open)), Decimal(str(bar.high)), Decimal(str(bar.low)), Decimal(str(bar.close)),
                   Decimal(str(bar.amount)), None, Decimal(str(bar.turnover))) for bar in bars]


def _route(candidate, policies: tuple[StrategyPolicy, ...]) -> tuple[str, str]:
    selected = select_policy((candidate,), policies, MarketContext.neutral())
    if selected:
        return "POLICY_PASSED", selected[0].policy.policy_code
    structural = [policy for policy in policies if policy.primary_model == candidate.model.value
                  and policy.confirmation_models == (candidate.policy_confirmations or "")]
    if structural:
        return "POLICY_SCORE_BELOW_THRESHOLD", ",".join(policy.policy_code for policy in structural)
    return "POLICY_NOT_MATCHED", ""


def run(data_dir: Path, manifest_path: Path, policy_path: Path) -> dict:
    manifest, policies = _manifest(manifest_path), _policies(policy_path)
    config = JavaEntryConfig()
    all_counts = Counter()
    by_route: dict[str, Counter] = defaultdict(Counter)
    by_model: dict[str, Counter] = defaultdict(Counter)
    directional_bars = candidate_count = 0
    for symbol in manifest["contracts"]:
        bars = load_bars(data_dir, symbol, PERIOD)
        if len(bars) < WINDOW_BARS:
            raise ValueError(f"{symbol} has insufficient frozen bars")
        candles_by_symbol = _candles(bars)
        for index in range(WINDOW_BARS - 1, len(candles_by_symbol)):
            window = candles_by_symbol[index + 1 - WINDOW_BARS:index + 1]
            signal = evaluate(window, config)
            if signal.side.value == "FLAT":
                continue
            directional_bars += 1
            for candidate in candidates(window, signal, config):
                candidate_count += 1
                outcome, policy_code = _route(candidate, policies)
                route_key = f"{candidate.model.value}|{candidate.policy_confirmations or ''}"
                all_counts[outcome] += 1
                by_route[route_key][outcome] += 1
                if policy_code:
                    by_route[route_key][f"POLICY:{policy_code}"] += 1
                by_model[candidate.model.value][outcome] += 1
    return {
        "schema": SCHEMA,
        "decision": "OBSERVE_ONLY",
        "readOnly": True,
        "datasetKey": manifest["datasetKey"],
        "inputIntegrity": manifest["inputIntegrity"],
        "scope": {
            "entryWindowBars": WINDOW_BARS,
            "routeContract": "primary_model + confirmation_models + raw score_threshold",
            "notEvaluated": ["market rank", "funding", "order book", "margin", "position capacity", "execution gate", "orders", "exit engine"],
        },
        "policies": [asdict(value) for value in policies],
        "funnel": {"directionalBars": directional_bars, "generatedCandidates": candidate_count,
                   "routeOutcome": dict(sorted(all_counts.items()))},
        "byModel": {key: dict(sorted(value.items())) for key, value in sorted(by_model.items())},
        "byModelAndConfirmation": {key: dict(sorted(value.items())) for key, value in sorted(by_route.items())},
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-dir", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--policies", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    result = run(args.data_dir, args.manifest, args.policies)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2, default=str) + "\n", encoding="utf-8")
    print(json.dumps({"out": str(args.out), **result["funnel"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
