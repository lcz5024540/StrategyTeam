from dataclasses import replace
from decimal import Decimal as D
import pytest
from htx_trend.realtime.coverage import Coverage
from htx_trend.realtime.feed import MarketEvent
from htx_trend.realtime.storage import SegmentRecorder
from htx_trend.research.store import FileEvidenceStore


def event(at, bid, epoch="e"):
    return MarketEvent(
        "BBO",
        "ETH-USDT",
        at,
        at,
        str(at),
        epoch,
        bid=D(bid),
        ask=D(bid) + D(".01"),
        bid_size=D(10),
        ask_size=D(10),
    )


def test_only_postfill_extrema_are_proven_and_chain_survives_buffer_rotation(tmp_path):
    c = Coverage(capacity=3)
    r = SegmentRecorder(tmp_path / "capture")
    e = FileEvidenceStore(tmp_path / "evidence")
    ref = e.put("fill", {"first_fill_ms": 1000})
    for at, bid in [(900, 150), (1200, 110), (1400, 105)]:
        b = event(at, bid)
        r.append(b)
        c.append(b, at)
    kw = dict(seed_high="100", seed_low="100", fill_ref=ref, evidence=e)
    proof = c.prove("ETH-USDT", "LONG", 1000, 1500, r, **kw)
    assert proof["highest"] == "110" and proof["lowest"] == "100"
    for at, bid in [(1500, 103), (1600, 106)]:
        b = event(at, bid)
        r.append(b)
        c.append(b, at)
    next_proof = c.prove(
        "ETH-USDT",
        "LONG",
        proof["through_ms"],
        1650,
        r,
        **{**kw, "seed_high": proof["highest"], "seed_low": proof["lowest"]},
        previous=proof,
    )
    assert (
        next_proof["highest"] == "110"
        and next_proof["previous_proof_ref"] == proof["evidence_ref"]
    )
    c.gap("ETH-USDT")
    b = event(1600, 105)
    r.append(b)
    c.append(b, 1600)
    assert c.prove("ETH-USDT", "LONG", 1600, 1650, r, **kw, previous=next_proof) is None
    e.close()


@pytest.mark.parametrize("failure", ["missing", "stale", "gap", "corrupt", "quota"])
def test_incomplete_durable_tape_never_proves_coverage(tmp_path, failure):
    c = Coverage()
    r = SegmentRecorder(tmp_path / "capture")
    e = FileEvidenceStore(tmp_path / "evidence")
    ref = e.put("fill", {})
    for at in (900, 1200):
        b = event(at, 100)
        if not (failure == "missing" and at == 900):
            r.append(b)
        c.append(b, at if failure != "stale" else at + 3000)
    if failure == "gap":
        r.append({"kind": "GAP", "contract": "ETH-USDT", "received_ms": 1100})
    if failure == "quota":
        r.max_bytes = 1
    if failure == "corrupt":
        saved = r.flush()
        (r.root / (saved + ".json.gz")).write_bytes(b"bad")
    try:
        result = c.prove(
            "ETH-USDT",
            "LONG",
            1000,
            1300,
            r,
            seed_high="100",
            seed_low="100",
            fill_ref=ref,
            evidence=e,
        )
    except (ValueError, OSError):
        assert failure == "corrupt"
    else:
        assert result is None
    e.close()
