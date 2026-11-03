import gzip
import json
from pathlib import Path
from types import SimpleNamespace

import pytest

from htx_trend.realtime.public_capture import (
    Capture,
    CONTROL_RESERVE,
    GIB,
    audit,
    iter_records,
    normalize_window,
    normalize_ws,
)
from htx_trend.realtime.storage import SegmentRecorder


def depth(at=1000):
    return dict(
        ch="market.ETH-USDT.depth.step0",
        tick=dict(ts=at, mrid=42, bids=[[100, 2], [99, 3]], asks=[[101, 4], [102, 5]]),
    )


def test_depth_snapshot_retains_actual_exchange_clock_and_no_invented_request():
    value = depth()
    rows, pong = normalize_ws(value, "epoch", 1100)
    r = rows[0]
    assert r["source"] == "HTX_WS_FULL_DEPTH" and r["exchange_ms"] == 1000
    assert r["received_ms"] == 1100 and "requested_ms" not in r
    assert r["bids"] == [["100", "2"], ["99", "3"]] and r["raw"] == value
    assert pong is None
    value["tick"]["bids"].reverse()
    with pytest.raises(ValueError, match="unordered"):
        normalize_ws(value, "epoch", 1100)


def test_closed_window_revisions_preserved_and_forming_bar_excluded():
    bars = [dict(id=i * 900, open=100, high=101, low=99, close=100) for i in range(161)]
    raw = dict(data=list(reversed(bars)))
    w = normalize_window("ETH-USDT", "15min", raw, 160 * 900000)
    assert len(w["candles"]) == 160 and w["candles"][-1]["open_time"] == 159 * 900
    raw["data"][1]["close"] = 101
    updated = normalize_window("ETH-USDT", "15min", raw, 160 * 900000)
    assert updated["candles"][-1]["close"] == "101"
    with pytest.raises(ValueError, match="incomplete"):
        normalize_window("ETH-USDT", "15min", dict(data=bars[2:]), 160 * 900000)


def test_streaming_export_hashes_and_explicit_missing_baseline(tmp_path):
    root = tmp_path / "bundle"
    root.mkdir()
    (root / "capture.json").write_text(json.dumps(dict(contracts=["ETH-USDT"])))
    store = SegmentRecorder(root / "segments")
    r = normalize_ws(depth(), "e", 1100)[0][0]
    store.append(r)
    ref = store.flush()
    output = tmp_path / "export.jsonl.gz"
    result = audit(root, output)
    assert result["counts"] == {"DEPTH": 1}
    assert result["status"] == "PUBLIC_INPUT_ONLY_NOT_ELIGIBLE"
    assert (
        "INDEPENDENT_BASELINE_SCANS_AND_DECISIONS" in result["remaining_requirements"]
    )
    exported = json.loads(gzip.decompress(output.read_bytes()))
    assert exported["source_ref"]["segment"] == ref and exported["observation"] == r
    (root / "segments" / (ref + ".json.gz")).write_bytes(gzip.compress(b"{}"))
    with pytest.raises((ValueError, KeyError)):
        list(iter_records(root))


def test_capture_does_not_reuse_directory_or_allow_private_requests(tmp_path):
    c = Capture(tmp_path / "run", ["ETH-USDT"], seconds=1)
    try:
        with pytest.raises(ValueError, match="non-public"):
            c.get("/v5/trade/order", {})
        with pytest.raises(FileExistsError):
            Capture(tmp_path / "run", ["ETH-USDT"], seconds=1)
        assert c.client._access_key is None and c.client._secret_key is None
    finally:
        c.client.close()
        c.lock.close()
    with pytest.raises(ValueError, match="bounds"):
        Capture(tmp_path / "large", ["ETH-USDT"], max_bytes=3 * GIB)


def test_headroom_stops_before_network_and_queue_loss_stops_capture(
    tmp_path, monkeypatch
):
    c = Capture(tmp_path / "disk", ["ETH-USDT"], seconds=1)
    monkeypatch.setattr(
        "htx_trend.realtime.public_capture.shutil.disk_usage",
        lambda _: SimpleNamespace(free=1),
    )
    monkeypatch.setattr(
        c, "load_specifications", lambda: pytest.fail("must not access network")
    )
    c.run()
    assert c.failure == "INSUFFICIENT_START_HEADROOM"
    assert json.loads((c.root / "status.json").read_text())["final"]
    c = Capture(tmp_path / "queue", ["ETH-USDT"], seconds=1)
    try:
        for _ in range(4097):
            c.emit(dict(kind="GAP", received_ms=1))
        assert c.stop.is_set() and c.failure == "QUEUE_OVERFLOW"
    finally:
        c.client.close()
        c.lock.close()


def test_total_cap_reserves_status_space_and_never_deletes_existing_segments(tmp_path):
    c = Capture(
        tmp_path / "quota", ["ETH-USDT"], seconds=1, max_bytes=CONTROL_RESERVE + 100
    )
    c.write(dict(kind="BBO", received_ms=1000, raw="nonempty" * 300))
    c.recorder.flush()
    assert c.recorder.failure == "CAPTURE_QUOTA_EXCEEDED"
    assert c.recorder.used <= 100 and (c.root / "capture.json").exists()
    c.client.close()
    c.lock.close()


def test_segment_reference_and_websocket_depth_are_replay_compatible(tmp_path):
    from htx_trend.realtime.public_capture import read_reference
    from htx_trend.realtime.replay import FrozenMarket

    store = SegmentRecorder(tmp_path / "segments")
    row = normalize_ws(depth(), "e", 1100)[0][0]
    store.append(row)
    sha = store.flush()
    from htx_trend.research.store import digest

    ref = dict(segment=sha, index=0, event_hash=digest(row))
    assert read_reference(tmp_path, ref) == row
    with pytest.raises(ValueError, match="observation hash"):
        read_reference(tmp_path, {**ref, "event_hash": "0" * 64})
    with pytest.raises(ValueError, match="invalid capture reference"):
        read_reference(tmp_path, {**ref, "segment": "../elsewhere"})
    m = FrozenMarket()
    m.observe(row)
    assert str(m.order_book("ETH-USDT").top.bid) == "100"
    m.now_ms = 3100
    with pytest.raises(ValueError, match="EXCHANGE_STALE"):
        m.order_book("ETH-USDT")
