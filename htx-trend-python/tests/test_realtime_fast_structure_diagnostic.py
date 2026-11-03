import gzip
import io
from decimal import Decimal as D

import pytest
from htx_trend.realtime.capture_prefix_export import canonical, export_prefix
from htx_trend.realtime.fast_structure_diagnostic import (
    Minutes,
    Research,
    Reversal,
    read_tape,
)
from htx_trend.realtime.feed import MarketEvent
from htx_trend.research.store import digest


def trade(at, p, ident=None, epoch="e"):
    return MarketEvent(
        "TRADE",
        "ETH-USDT",
        at,
        at,
        str(ident or at),
        epoch,
        price=D(str(p)),
        amount=D(2),
    )


def book(at, p=100):
    return MarketEvent(
        "BBO", "ETH-USDT", at, at, str(at), "e", bid=D(str(p)), ask=D(str(p)) + D(".01")
    )


def test_minute_watermark_keeps_early_next_minute_print():
    m = Minutes()
    m.reset(0)
    m.observe(trade(59000, 100), "a")
    assert not m.observe(trade(60010, 101), "b")
    sealed = m.observe(book(62000), "q")
    assert len(sealed) == 1 and sealed[0]["minute"] == 0
    assert sealed[0]["close"] == "100" and sealed[0]["count"] == 1
    assert m.pending[("ETH-USDT", 60000)]["open"] == "101"
    assert not m.observe(trade(59999, 90), "late")
    assert m.errors["LATE_SEALED_MINUTE"] == 1


def test_minutes_do_not_bridge_gap_or_fill_empty_minutes():
    m = Minutes()
    m.reset(0)
    for i, p in enumerate([100, 101, 105, 101, 100]):
        m.observe(trade(i * 60000 + 1000, p), str(i))
        m.observe(book((i + 1) * 60000 + 2000), "q" + str(i))
    assert m.pivots("ETH-USDT")[0][:2] == ("HIGH", D(105))
    m.reset(302001)
    m.observe(trade(360001, 99), "new")
    m.observe(book(422000), "qnew")
    assert not m.pivots("ETH-USDT")
    assert all(r["minute"] != 300000 for r in m.rows["ETH-USDT"])


@pytest.mark.parametrize(
    "prices,edge", [([100, 103, 102, 100], "HIGH"), ([100, 97, 98, 100], "LOW")]
)
def test_reversal_symmetric_and_threshold_frozen(prices, edge):
    r = Reversal()
    answers = []
    for i, p in enumerate(prices):
        answer = r.observe(trade(1000 + i * 100, p), D(2), str(i))
        if answer:
            answers.append(answer)
    assert answers[-1][0] == edge
    # Gapped trade resets extrema instead of confirming an old peak.
    assert r.observe(trade(10000, 110), D(2), "gap") is None


def test_reversal_does_not_widen_threshold_when_atr_changes():
    r = Reversal()
    assert r.observe(trade(1000, 100), D(2), "a") is None
    assert r.observe(trade(1100, 101), D(10), "b") is None
    answer = r.observe(trade(1200, 102), D(10), "c")
    assert answer[0] == "LOW" and answer[1] == D(100)


def test_prefix_export_hash_and_complete_footer(tmp_path):
    segment = {"schema": "htx-realtime-segment-v1", "events": [trade(1000, 100).dump()]}
    raw = canonical(segment)
    sha = digest(segment)
    packed = gzip.compress(raw, mtime=0)
    root = tmp_path / "segments"
    root.mkdir()
    (root / (sha + ".json.gz")).write_bytes(packed)
    manifest = {
        "manifest_sha256": "m",
        "first_ms": 1000,
        "last_ms": 1000,
        "capture": {"contracts": ["ETH-USDT"]},
        "segments": [{"hash": sha, "bytes": len(packed), "count": 1}],
    }
    out = io.BytesIO()
    export_prefix(tmp_path, manifest, out)
    path = tmp_path / "export.gz"
    path.write_bytes(out.getvalue())
    rows = list(read_tape(path))
    assert rows[-1]["type"] == "COMPLETE"
    assert rows[1]["source_ref"]["event_hash"] == digest(segment["events"][0])
    (root / (sha + ".json.gz")).write_bytes(gzip.compress(b"{}"))
    with pytest.raises(ValueError, match="integrity"):
        export_prefix(tmp_path, manifest, io.BytesIO())
    path.write_bytes(gzip.compress(b'{"type":"HEADER"}\n'))
    with pytest.raises(ValueError, match="incomplete"):
        list(read_tape(path))


def test_real_event_baseline_trigger_has_source_refs_and_no_order():
    t = 576000000
    code = "ETH-USDT"
    header = {"first_ms": t, "last_ms": t + 800000, "contracts": [code]}
    r = Research(header)
    windows = {}
    for period, seconds in (("15min", 900), ("60min", 3600)):
        candles = []
        for i in range(160):
            p = D(100) if period == "15min" else D(80) + D(i) / 10
            candles.append(
                {
                    "open_time": t // 1000 - (160 - i) * seconds,
                    "open": str(p),
                    "high": str(p + 1),
                    "low": str(p - 1),
                    "close": str(p),
                }
            )
        windows[period] = {"candles": candles}
    frame = {
        "kind": "FRAME",
        "contract": code,
        "received_ms": t,
        "windows": windows,
        "spec": {
            "contract_code": code,
            "contract_size": ".01",
            "price_tick": ".01",
            "contract_status": 1,
        },
    }

    def observe(e, index):
        value = e.dump() if isinstance(e, MarketEvent) else e
        ref = {"segment": "a" * 64, "index": index, "event_hash": digest(value)}
        r.observe(value, ref)

    observe(frame, 0)
    observe(book(t + 10, D("99.4")), 1)
    for i, p in enumerate([100, 99, D("99.3"), D("99.4")]):
        observe(trade(t + 100 + i * 100, p), i + 2)
    baseline = [v for v in r.triggers if v["variant"] == "A"]
    assert len(baseline) == 2
    assert all(not v["order_submitted"] for v in baseline)
    assert all(all(v["trade_source_refs"]) and v["book_source_ref"] for v in baseline)
    for v in baseline:
        assert all(key in r.source_refs for key in v["trade_source_refs"])
    count = len(r.triggers)
    observe(trade(t + 400, D("99.4")), 99)  # duplicate ID cannot add a confirmation
    assert len(r.triggers) == count


def test_export_footer_detects_dropped_observation(tmp_path):
    path = tmp_path / "bad.gz"
    rows = [{"type": "HEADER"}, {"type": "COMPLETE", "exported_counts": {"TRADE": 1}}]
    path.write_bytes(gzip.compress(b"".join(canonical(r) + b"\n" for r in rows)))
    with pytest.raises(ValueError, match="count mismatch"):
        list(read_tape(path))


def test_fresh_trade_without_frame_is_not_an_entry():
    r = Research({"first_ms": 1000, "last_ms": 10000, "contracts": ["ETH-USDT"]})
    e = trade(1000, 100).dump()
    r.observe(e, {"segment": "a" * 64, "index": 0, "event_hash": digest(e)})
    assert r.per_contract["ETH-USDT"]["TRADE_WITHOUT_FRESH_FRAME"] == 1
    assert not r.triggers


def test_stale_trade_does_not_create_a_minute_or_reversal():
    r = Research({"first_ms": 10000, "last_ms": 20000, "contracts": ["ETH-USDT"]})
    e = trade(1000, 100).dump()
    r.observe(e, {"segment": "a" * 64, "index": 0, "event_hash": digest(e)})
    assert r.counters["STALE_TRADE"] == 1
    assert not r.minutes.pending and not r.reversal.state
    assert r.rejected_trade_minutes["ETH-USDT:0"] == 1
