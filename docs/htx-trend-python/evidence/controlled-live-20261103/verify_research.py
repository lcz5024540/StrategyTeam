"""Read-only M1 audit. Run from any directory; prints JSON, never authorizes LIVE."""

import gzip
import hashlib
import json
from decimal import Decimal
from pathlib import Path


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def main():
    source = Path(__file__).resolve().parent.parent / "fast-structure-20261103"
    manifest = json.loads((source / "artifact-manifest.json").read_text())
    for name, expected in manifest["artifacts"].items():
        path = source / name
        require(path.stat().st_size == expected["bytes"], f"size: {name}")
        require(sha(path) == expected["sha256"], f"sha256: {name}")
    tape = Path(manifest["input_tape"]["path"])
    require(tape.stat().st_size == manifest["input_tape"]["bytes"], "tape size")
    require(sha(tape) == manifest["input_tape"]["sha256"], "tape hash")
    result = json.loads(gzip.decompress((source / "results.json.gz").read_bytes()))
    summary = json.loads((source / "summary.json").read_text())
    require(result["summaries"] == summary["summaries"], "summary/result mismatch")
    require(result["tape_sha256"] == sha(tape), "result tape mismatch")
    require(result["orders_submitted"] == 0, "research unexpectedly contains orders")
    require(len(result["header"]["contracts"]) == 52, "pool changed")
    comparisons = []
    for k in ("0.05", "0.1"):
        def identities(variant):
            selected = set()
            for row in result["triggers"]:
                if row["variant"] != variant or row["k"] != k:
                    continue
                cost = row["cost_assumptions"]["0.10"]
                value = cost["net_r_excluding_unknown_funding"]
                if value is not None and Decimal(value) >= Decimal("1.5"):
                    trigger = row["trigger"]
                    plan = trigger["plan"]
                    selected.add((row["contract"], trigger["trigger_ms"], plan["line"],
                                  plan["side"], trigger["reference"], plan["target"]))
            return selected
        baseline = identities("A")
        for variant in ("M", "T"):
            current = identities(variant)
            require(current == baseline, f"R>=1.5 event identity differs: {variant}/{k}")
            comparisons.append({"variant": variant, "k": k, "eligible_events": len(current),
                                "same_event_time_line_price_target_as_A": True})
    print(json.dumps({
        "audit": "RESEARCH_INTEGRITY_VERIFIED_NOT_DEPLOYMENT_APPROVAL",
        "verified_artifacts": len(manifest["artifacts"]),
        "input_tape_sha256": sha(tape),
        "input_tape_bytes": tape.stat().st_size,
        "research_status": result["status"],
        "config_scope": result["config_scope"],
        "orders_submitted_in_research": result["orders_submitted"],
        "summaries": summary["summaries"],
        "r15_comparisons": comparisons,
        "selected_candidate": None,
        "production_changes": False,
        "limitations": ["Assumed costs; funding settlement unknown",
                        "No executable portfolio replay or IOC validation in this study",
                        "No user-confirmed production structure/parameter selection"],
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
