"""Read-only adapter parity on the previously authorized public 49-minute tape."""

import gzip
import hashlib
import json
import sys
from decimal import Decimal as D
from pathlib import Path

from htx_trend.java_entry_contract import JavaEntryConfig, evaluate
from htx_trend.market_data import ContractSpec
from htx_trend.realtime.fast_structure_diagnostic import (
    EphemeralStore,
    read_tape,
    window,
)
from htx_trend.realtime.feed import EventOrder, MarketEvent
from htx_trend.realtime.plans import SBOX, Parameters, create_engine
from htx_trend.research.store import canonical, digest

path = Path("/tmp/coinstrategy-fast-structure-20261103/capture-prefix.jsonl.gz")
assert (
    hashlib.sha256(path.read_bytes()).hexdigest()
    == "b6cb0f950412325483982924c901cebb30c6240d23541a66f9b7a2d3a3a1f4fd"
)
params = Parameters(candidate=SBOX, target_mode="J1_BOX32")
engines = {}
now = 0
epoch = None
retired = set()
order = EventOrder()
got = []
counts = {}
frame_refs = set()
market_refs = set()
for item in read_tape(path):
    if item["type"] != "EVENT":
        continue
    v = item["observation"]
    now = max(now, v["received_ms"])
    kind = v["kind"]
    code = v.get("contract")
    counts[kind] = counts.get(kind, 0) + 1
    if kind == "GAP":
        if epoch is not None:
            retired.add(epoch)
        epoch = None
        order.reset()
        for e in engines.values():
            e.disconnect()
    elif kind == "FRAME":
        ref = digest(item["source_ref"])
        frame_refs.add(ref)
        entry = window(v["windows"]["15min"], code, "15min", ref)
        hour = window(v["windows"]["60min"], code, "60min", ref)
        raw = dict(v["spec"])
        for k in ["price_tick", "contract_size"]:
            raw[k] = D(raw[k])
        if code not in engines:
            engines[code] = create_engine(EphemeralStore(), code, parameters=params)
        e = engines[code]
        e.refresh_frame(
            entry,
            hour,
            ContractSpec(**raw),
            now_ms=now,
            signal_side=evaluate(entry.candles, JavaEntryConfig()).side,
        )
    elif kind in ("TRADE", "BBO"):
        event = MarketEvent.load(v)
        market_refs.add(digest(event))
        if event.epoch in retired:
            continue
        if epoch is not None and epoch != event.epoch:
            retired.add(epoch)
            order.reset()
            for e in engines.values():
                e.disconnect()
        epoch = event.epoch
        if not event.fresh(now) or not order.accept(event):
            continue
        if code in engines:
            for t in engines[code].accept(event, now_ms=now):
                assert (
                    t.plan.parameter_fingerprint == params.fingerprint
                    and t.plan.target > 0
                )
                r = engines[code].rows[t.plan.plan_id]
                got.append(
                    {
                        "fact_id": r["fact_id"],
                        "trigger_ms": t.trigger_ms,
                        "reference": str(t.reference),
                        "stop": str(t.invalidation_price),
                        "target": str(t.plan.target),
                        "contract": code,
                        "plan_id": t.plan.plan_id,
                    }
                )
# Fixed previous comparison file, not a newer/holdout sample.
prior = Path(
    "/Users/lichangzheng.ray/Ray/CoinStrategy-netr-cohort/docs/htx-trend-python/evidence/target-continuation-20261103/results.json.gz"
)
assert (
    hashlib.sha256(prior.read_bytes()).hexdigest()
    == "e6db67019b01c6cc2de9952968bbf7ee75371d89b5a4bb915644d051a751fec2"
)
with gzip.open(prior) as stream:
    baseline = json.load(stream)
expected = [
    o
    for o in baseline["observations"]
    if o["origin"] == "S"
    and o["k"] == "0.10"
    and o["targets"]["J1_BOX32"]["status"] == "AVAILABLE"
]


def key(r):
    return (
        r["fact_id"],
        r["trigger_ms"],
        D(r["reference"]),
        D(r["stop"]),
        D(r["target"]),
    )


expected_keys = {
    (
        o["fact"]["fact_id"],
        o["trigger_ms"],
        D(o["reference"]),
        D(o["stop"]),
        D(o["targets"]["J1_BOX32"]["price"]),
    )
    for o in expected
}
assert {key(r) for r in got} == expected_keys, (
    "adapter_parity_mismatch",
    len(got),
    len(expected),
)
assert len(got) == len({r["plan_id"] for r in got})
reference_count = 0
for e in engines.values():
    for row in e.rows.values():
        refs = row["plan"]["evidence_refs"]
        trigger = row["trigger"]
        assert set(refs) <= frame_refs
        assert set(trigger["trade_refs"]) | {trigger["book_ref"]} <= market_refs
        reference_count += len(refs) + len(trigger["trade_refs"]) + 1
result = {
    "scope": "REAL_PUBLIC_TAPE_FACTORY_ADAPTER_PARITY_NOT_ACCOUNT_REPLAY",
    "counts": counts,
    "matched": len(got),
    "expected": len(expected),
    "duplicate_plan_ids": 0,
    "orders_submitted": 0,
    "triggers": got,
    "final_exec_states": {code: e.rows for code, e in engines.items()},
    "input_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
    "reference_closure_count": reference_count,
    "source_refs_preserved": True,
}
Path(sys.argv[1]).write_bytes(gzip.compress(canonical(result), mtime=0))
print(
    json.dumps(
        {
            "matched": len(got),
            "expected": len(expected),
            "duplicate_plan_ids": 0,
            "orders_submitted": 0,
        }
    )
)
