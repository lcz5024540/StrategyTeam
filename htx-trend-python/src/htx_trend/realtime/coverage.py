"""Bounded post-fill BBO coverage; never infer extrema from a whole candle."""

from collections import deque
from decimal import Decimal as D
import uuid
import gzip, json
from ..research.store import digest


class Coverage:
    def __init__(self, *, capacity=4096, max_gap_ms=2000):
        self.capacity, self.max_gap_ms = capacity, max_gap_ms
        self.books = {}
        self.tokens = {}

    def gap(self, contract=None):
        if contract is None:
            self.books.clear()
            self.tokens.clear()
        else:
            self.books.pop(contract, None)
            self.tokens.pop(contract, None)

    def append(self, event, now_ms):
        if event.kind != "BBO":
            return
        if not event.fresh(now_ms):
            self.gap(event.contract)
            return
        rows = self.books.setdefault(event.contract, deque(maxlen=self.capacity))
        point = (
            event.exchange_ms,
            event.received_ms,
            event.event_id,
            event.epoch,
            event.bid,
            event.ask,
        )
        if rows and (
            point[3] != rows[-1][3]
            or point[0] < rows[-1][0]
            or point[0] - rows[-1][0] > self.max_gap_ms
            or point[1] - rows[-1][1] > self.max_gap_ms
        ):
            rows.clear()
        if not rows:
            self.tokens[event.contract] = uuid.uuid4().hex
        rows.append(point)

    def prove(
        self,
        contract,
        side,
        start_ms,
        now_ms,
        recorder,
        *,
        seed_high,
        seed_low,
        fill_ref,
        evidence,
        previous=None,
    ):
        rows = list(self.books.get(contract, ()))
        if (
            not rows
            or rows[0][0] > start_ms
            or rows[-1][0] < start_ms
            or not 0 <= now_ms - rows[-1][0] <= self.max_gap_ms
            or not 0 <= now_ms - rows[-1][1] <= self.max_gap_ms
        ):
            return None
        if previous and previous.get("continuity_token") != self.tokens.get(contract):
            return None
        selected = [p for p in rows if p[0] >= start_ms]
        before = [p for p in rows if p[0] <= start_ms][-1]
        if not selected or not recorder.flush() or recorder.failure:
            return None
        refs = [
            r
            for r in recorder.records
            if r["last_received_ms"] >= before[1]
            and r["first_received_ms"] <= rows[-1][1]
        ]
        if not refs:
            return None
        # Verify both bytes and the actual bounded observation identities. A
        # manifest timestamp range alone does not prove those observations exist.
        expected = {(p[0], p[1], p[2], p[3]): p for p in [before, *selected]}
        found = set()
        for ref in refs:
            path = recorder.root / (ref["hash"] + ".json.gz")
            value = json.loads(gzip.decompress(path.read_bytes()))
            if digest(value) != ref["hash"]:
                raise ValueError("coverage segment hash mismatch")
            for e in value["events"]:
                if (
                    e.get("kind") == "GAP"
                    and e.get("contract") in (None, contract)
                    and before[1] <= e["received_ms"] <= rows[-1][1]
                ):
                    return None
                if e.get("kind") != "BBO" or e.get("contract") != contract:
                    continue
                key = (e["exchange_ms"], e["received_ms"], e["event_id"], e["epoch"])
                if key in expected:
                    p = expected[key]
                    if D(e["bid"]) != p[4] or D(e["ask"]) != p[5]:
                        raise ValueError("coverage price mismatch")
                    found.add(key)
        if found != set(expected):
            return None
        prices = [p[4] if side == "LONG" else p[5] for p in selected]
        high = max(D(seed_high) if seed_high is not None else prices[0], *prices)
        low = min(D(seed_low) if seed_low is not None else prices[0], *prices)
        proof = {
            "contract": contract,
            "side": side,
            "from_ms": start_ms,
            "through_ms": rows[-1][0],
            "epoch": rows[-1][3],
            "continuity_token": self.tokens[contract],
            "previous_proof_ref": previous["evidence_ref"] if previous else None,
            "origin_ms": previous.get("origin_ms", start_ms) if previous else start_ms,
            "segments": [r["hash"] for r in refs],
            "capture_root": str(recorder.root),
            "fill_receipt_ref": fill_ref,
            "highest": str(high),
            "lowest": str(low),
        }
        proof["evidence_ref"] = evidence.put(
            "realtime_extrema_coverage",
            proof,
            refs=(fill_ref, *((previous["evidence_ref"],) if previous else ())),
        )
        return proof
