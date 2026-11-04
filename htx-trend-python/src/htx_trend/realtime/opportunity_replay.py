"""Same-framework selection ablation; no automatic threshold search or promotion.

Run with: python -m htx_trend.realtime.opportunity_replay --help
"""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime
from decimal import Decimal as D
import json
from pathlib import Path

from ..research.store import digest, primitive
from .plans import OPPORTUNITY, Parameters
from .replay import Actor, evidence_blockers, iso, load, metric, validate
from .selection import Calibration, fit

VARIANTS = ("STRUCTURE", "DIRECTION", "BOOK", "BOTH")


def interval_metrics(result, lo, hi):
    # Report boundaries carry the last observed mark. They are not new quotes/fills.
    curve = list(result["curve"])
    for at in (lo, hi):
        prior = [p for p in curve if datetime.fromisoformat(p["at"]).timestamp()*1000 <= at]
        if not prior:
            return None
        latest = max(prior, key=lambda p: datetime.fromisoformat(p["at"]).timestamp())
        curve.append({"at": iso(at), "equity": latest["equity"]})
    curve.sort(key=lambda p: p["at"])
    return metric({**result, "curve": curve}, lo, hi)


def ablate(tape, *, calibration=None, evidence_root=None):
    validate(tape)
    if calibration and calibration.artifact["cutoff_ms"] >= tape["start_ms"]:
        raise ValueError("training outcome availability must precede the entire evaluation tape")
    split = tape["start_ms"] + (tape["end_ms"]-tape["start_ms"])*7//10
    groups, discovery_sets = {}, []
    for variant in VARIANTS:
        parameters = Parameters(candidate=OPPORTUNITY, min_net_r=D(0),
                                target_mode="OWN_STRUCTURE", selection_variant=variant,
                                calibration_hash=calibration.artifact["hash"] if calibration else None)
        actor = Actor(tape, parameters, calibration=calibration)
        try:
            for observation in tape["observations"]:
                actor.step(observation)
            result = actor.result()
            funnel = Counter()
            discovered = set()
            for obj in result["evidence"].values():
                payload = obj["payload"]
                if obj["kind"] == "realtime_structure_refresh":
                    for item in payload.get("candidate_decisions", []):
                        if "plan_id" in item:
                            discovered.add(item["plan_id"])
                        funnel["discovery:"+item["reason"]] += 1
                elif obj["kind"] == "realtime_entry_decision":
                    # Records include both initial preflight and submit-time recheck.
                    funnel["evaluation:"+payload["gate"]["stage"]] += 1
            funnel.update({"unique_discoveries": len(discovered),
                           "registered_plans": len(result["plans"]),
                           "open_fills": sum(f["action"] == "OPEN" for f in result["fills"])})
            discovery_sets.append(discovered)
            groups[variant] = {
                "parameters": parameters.payload, "discovery_hash": digest(sorted(discovered)),
                "funnel": dict(funnel),
                "full": interval_metrics(result, tape["start_ms"], tape["end_ms"]),
                "diagnostic": interval_metrics(result, tape["start_ms"], split),
                "holdout": interval_metrics(result, split, tape["end_ms"]),
                "result": result,
            }
        finally:
            actor.close()
    if any(s != discovery_sets[0] for s in discovery_sets):
        raise ValueError("selector ablation changed the discovery universe")
    blockers = evidence_blockers(tape, evidence_root)
    if not calibration:
        blockers.append("PROBABILITY_UNCALIBRATED")
    if any(g["result"]["errors"] for g in groups.values()):
        blockers.append("RUNTIME_ERRORS")
    if not any(g["result"]["fills"] for g in groups.values()):
        blockers.append("NO_EXECUTION_SAMPLE")
    # No winner/promotion from an exploratory ablation: an independent acceptance run is required.
    blockers.append("INDEPENDENT_ACCEPTANCE_REQUIRED")
    return primitive({"schema": "opportunity-ablation-v1", "status": "RESEARCH_ONLY",
                      "eligible": False, "blockers": sorted(set(blockers)),
                      "tape_hash": digest(tape), "configuration_hash": tape["configuration_hash"],
                      "split_ms": split, "selection_uses_holdout": False,
                      "calibration_hash": calibration.artifact["hash"] if calibration else None,
                      "initial_inventory": "FLAT", "groups": groups,
                      "limitations": ["Cold-start features only break equal net-R ties.",
                          "Empirical win rates are in-sample summaries, not calibrated forecasts.",
                          "Ranking competes within a worker batch; one-event replay cannot show contention gains.",
                          "Same linked trend exits can close a countertrend opportunity on the next completed bar.",
                          "Completed-trade outcomes condition on fills; they do not estimate fill probability."]})


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    run = sub.add_parser("ablate")
    run.add_argument("--tape", type=Path, required=True)
    run.add_argument("--sha256", required=True, help="SHA256 of the exact input file")
    run.add_argument("--calibration", type=Path)
    run.add_argument("--evidence-root", type=Path)
    run.add_argument("--output", type=Path, required=True)
    train = sub.add_parser("fit")
    train.add_argument("--samples", type=Path, required=True, help="JSON array of mature cost-verified closed outcomes")
    train.add_argument("--cutoff-ms", type=int, required=True)
    train.add_argument("--policy-hash", required=True)
    train.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    if args.command == "fit":
        result = fit(json.loads(args.samples.read_text()), cutoff_ms=args.cutoff_ms,
                     policy_hash=args.policy_hash)
    else:
        calibration = Calibration(json.loads(args.calibration.read_text())) if args.calibration else None
        result = ablate(load(args.tape, args.sha256), calibration=calibration,
                        evidence_root=args.evidence_root)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2, ensure_ascii=False)+"\n")
    print(json.dumps({"output": str(args.output), "status": result.get("status", "FITTED_NOT_VALIDATED"),
                      "hash": digest(result)}))


if __name__ == "__main__":
    main()
