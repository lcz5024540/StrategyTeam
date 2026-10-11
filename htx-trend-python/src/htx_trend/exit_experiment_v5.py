"""Evidence gate for v5 economic experiments; it never invents missing execution facts."""
from dataclasses import dataclass
from typing import Any

REQUIRED_MANIFEST = frozenset({
    "data_start_utc", "data_end_utc", "universe", "source_commit", "policy_hash",
    "fee_source", "order_book_source", "funding_source", "sample_split", "result_path",
})
REQUIRED_TRADE_FACTS = frozenset({
    "entry_fills", "exit_fills", "post_entry_bars", "fee_facts", "funding_facts", "order_book_facts",
})


@dataclass(frozen=True, slots=True)
class ExperimentGateV5:
    status: str
    level: str
    missing: tuple[str, ...]
    message: str


def evaluate_experiment_evidence(manifest: dict[str, Any], trades: tuple[dict[str, Any], ...]) -> ExperimentGateV5:
    missing = [f"manifest.{key}" for key in sorted(REQUIRED_MANIFEST) if not manifest.get(key)]
    if not trades:
        missing.append("trades")
    for index, trade in enumerate(trades):
        for key in sorted(REQUIRED_TRADE_FACTS):
            if not trade.get(key):
                missing.append(f"trades[{index}].{key}")
        if trade.get("accounting_complete") is not True:
            missing.append(f"trades[{index}].accounting_complete")
    if missing:
        return ExperimentGateV5("INSUFFICIENT_EVIDENCE", "L1", tuple(missing),
                                "execution-level economics are blocked until every frozen fact is present")
    return ExperimentGateV5("STRUCTURALLY_COMPLETE", "L2", (),
                            "execution facts are complete; strategy admission is still required")


def require_economic_evidence(manifest: dict[str, Any], trades: tuple[dict[str, Any], ...]) -> None:
    result = evaluate_experiment_evidence(manifest, trades)
    if result.status != "STRUCTURALLY_COMPLETE":
        raise ValueError(f"{result.status}: {','.join(result.missing)}")
