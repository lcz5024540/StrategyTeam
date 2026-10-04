#!/usr/bin/env python3
"""Build the immutable BT-03 input from frozen price-plan snapshots and frozen future candles.

The builder never calls HTX and never substitutes a candle close for a missing price plan. A
report remains INSUFFICIENT_EVIDENCE unless every supplied plan is traceable to the COMPLETE
manifest and has enough post-plan bars for the requested execution comparison.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


SCHEMA = "htx-trend-bt03-input-v1"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_jsonl(path: Path) -> list[dict]:
    rows = []
    for line in path.read_text().splitlines():
        if line.strip():
            rows.append(json.loads(line))
    return rows


def plan_payload(row: dict) -> tuple[dict | None, str | None]:
    raw = row.get("factorsJson", row.get("factors_json", row))
    try:
        plan = json.loads(raw) if isinstance(raw, str) else raw
    except json.JSONDecodeError:
        return None, "invalid_price_plan_json"
    if plan.get("schema") != "trend-price-plan-v1":
        return None, "not_a_trend_price_plan_v1"
    entry, exit_, risk = plan.get("entry", {}), plan.get("exit", {}), plan.get("risk", {})
    required = ("side", "idealPrice", "maxAcceptablePrice", "plannedContracts", "contractSize", "validUntilEpoch")
    if any(entry.get(key) is None for key in required) or exit_.get("structureTargetPrice") is None:
        return None, "incomplete_price_plan"
    contract = row.get("contractCode", row.get("contract_code", plan.get("contractCode")))
    period = row.get("period", plan.get("period"))
    bar_time = row.get("barTime", row.get("bar_time", plan.get("barTime")))
    if not contract or not period or not bar_time:
        return None, "price_plan_identity_missing"
    notional = float(entry["plannedContracts"]) * float(entry["contractSize"]) * float(entry["idealPrice"])
    if notional <= 0:
        return None, "non_positive_plan_notional"
    return {"runId": plan.get("runId"), "contract": contract, "period": period, "barTime": int(bar_time),
            "validUntilEpoch": int(entry["validUntilEpoch"]), "plan": {"side": entry["side"],
            "ideal": float(entry["idealPrice"]), "worst": float(entry["maxAcceptablePrice"]),
            "notional": notional, "exit_price": float(exit_["structureTargetPrice"])},
            "plannedRiskUsdt": risk.get("plannedRiskUsdt")}, None


def build(plans_file: Path, data_dir: Path, manifest_file: Path, output: Path) -> dict:
    manifest = json.loads(manifest_file.read_text())
    rows = read_jsonl(plans_file)
    base = { (item.get("contract"), item.get("period")): item for item in manifest.get("inputs", []) }
    candidates, excluded = [], []
    complete_manifest = manifest.get("schema") == "htx-trend-research-dataset-manifest-v1" and manifest.get("inputIntegrity") == "COMPLETE"
    if not complete_manifest:
        excluded.append({"reason": "frozen_dataset_manifest_not_complete"})
    for row in rows:
        parsed, reason = plan_payload(row)
        if reason:
            excluded.append({"runId": row.get("runId"), "reason": reason}); continue
        source = base.get((parsed["contract"], parsed["period"]))
        if source is None or source.get("coverage") != "COMPLETE":
            excluded.append({"runId": parsed["runId"], "reason": "missing_complete_frozen_candle_source"}); continue
        candle_file = data_dir / source["file"]
        if not candle_file.is_file() or sha256(candle_file) != source.get("sha256"):
            excluded.append({"runId": parsed["runId"], "reason": "frozen_candle_checksum_mismatch"}); continue
        candles = sorted(json.loads(candle_file.read_text()).get("data", []), key=lambda bar: int(bar["id"]))
        after = [bar for bar in candles if int(bar["id"]) > parsed["barTime"]]
        period_seconds = after[0]["id"] - parsed["barTime"] if after else 0
        ttl_bars = max(1, (parsed["validUntilEpoch"] - parsed["barTime"] + max(1, period_seconds) - 1) // max(1, period_seconds))
        if len(after) <= ttl_bars:
            excluded.append({"runId": parsed["runId"], "reason": "insufficient_future_bars"}); continue
        parsed["plan"]["ttl_bars"] = ttl_bars
        candidates.append({"runId": parsed["runId"], "contract": parsed["contract"], "period": parsed["period"],
                           "barTime": parsed["barTime"], "plan": parsed["plan"],
                           "futureBars": [{key: float(bar[key]) for key in ("open", "high", "low", "close")} for bar in after]})
    state = "COMPLETE" if complete_manifest and candidates and not excluded else "INSUFFICIENT_EVIDENCE"
    report = {"schema": SCHEMA, "inputIntegrity": state, "datasetKey": manifest.get("datasetKey"),
              "pricePlanCount": len(rows), "candidateCount": len(candidates), "excluded": excluded,
              "candidates": candidates if state == "COMPLETE" else [],
              "executionBoundary": "RESEARCH_ONLY_NO_HTX_ORDER"}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plans", type=Path, required=True)
    parser.add_argument("--data-dir", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(build(args.plans, args.data_dir, args.manifest, args.output), ensure_ascii=False))


if __name__ == "__main__":
    main()
