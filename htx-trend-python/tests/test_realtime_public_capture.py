import gzip
import json
import logging
import threading
import time
from pathlib import Path
from types import SimpleNamespace

import pytest

from htx_trend.realtime.public_capture import (
    Capture,
    CONTROL_RESERVE,
    GIB,
    atomic_json,
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
        assert c.events.maxsize == 65536
        for _ in range(c.events.maxsize):
            c.emit(dict(kind="GAP", received_ms=1, reason="fill"))
        assert c.failure is None and not c.stop.is_set()
        assert c.events.qsize() == c.events.maxsize
        c.emit(dict(kind="GAP", received_ms=2, reason="overflow"))
        assert c.stop.is_set() and c.failure == "QUEUE_OVERFLOW"
        # The rejected event is not queued and earlier events are not dropped.
        assert c.events.qsize() == c.events.maxsize
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


def test_fast_segments_keep_exact_canonical_identity_and_legacy_reader(tmp_path):
    from htx_trend.research.store import canonical, digest
    from decimal import Decimal

    records = [normalize_ws(depth(i), "e", i + 1)[0][0] for i in (1000, 1100)]
    a = SegmentRecorder(tmp_path / "a")
    b = SegmentRecorder(tmp_path / "b", gzip_level=1, json_ready=True)
    for r in records:
        a.append(r)
        b.append(r)
    expected = {"schema": "htx-realtime-segment-v1", "events": records}
    assert a.flush() == b.flush() == digest(expected)
    assert b.verify() == records
    for store in (a, b):
        assert gzip.decompress(
            (store.root / (store.last_ref + ".json.gz")).read_bytes()
        ) == canonical(expected)
        assert not store.encoded
    default = SegmentRecorder(tmp_path / "typed")
    default.append(dict(kind="test", received_ms=1, price=Decimal("1.20")))
    default.flush()
    assert default.verify()[0]["price"] == "1.2"


def close_capture(c):
    c.client.close()
    if c.recorder.segments:c.recorder.segments.close()
    c.lock.close()


def test_segmented_capture_resume_preserves_quota_objects_and_original_provenance(tmp_path):
    root=tmp_path/'capture';pool={'contracts':['ETH-USDT'],'source_commit':'frozen-pool'}
    c=Capture(root,['ETH-USDT'],seconds=1,segmented=True,resume=True,observed_runtime=pool)
    c.write(dict(kind='BBO',contract='ETH-USDT',received_ms=1000,bid='100',ask='101'))
    sha=c.recorder.flush();before=c.recorder.used
    c.recorder.segment_boundary(force=True)
    config=(root/'capture.json').read_bytes()
    close_capture(c)
    c=Capture(root,['ETH-USDT'],seconds=2,segmented=True,resume=True,observed_runtime=pool)
    assert c.recorder.used>=before and c.recorder.last_ref==sha
    assert (root/'capture.json').read_bytes()==config
    c.write(dict(kind='BBO',contract='ETH-USDT',received_ms=2000,bid='101',ask='102'))
    c.recorder.flush()
    assert len(c.recorder.verify())==2
    assert c.status()['counts_scope']=='CURRENT_PROCESS'
    assert c.client._access_key is None and c.client._secret_key is None
    close_capture(c)


def test_resume_requires_same_contract_pool_limits_and_exclusive_writer(tmp_path):
    root=tmp_path/'capture';c=Capture(root,['ETH-USDT'],segmented=True,resume=True)
    with pytest.raises(BlockingIOError):Capture(root,['ETH-USDT'],segmented=True,resume=True)
    close_capture(c)
    for kwargs in [{'contracts':['BTC-USDT']},{'contracts':['ETH-USDT'],'max_bytes':GIB},
                   {'contracts':['ETH-USDT'],'min_free_bytes':6*GIB}]:
        with pytest.raises(ValueError,match='configuration changed'):
            Capture(root,segmented=True,resume=True,**kwargs)
    with pytest.raises(ValueError,match='requires explicit segmented'):
        Capture(root,['ETH-USDT'],resume=True)
    old=Capture(tmp_path/'legacy',['ETH-USDT']);close_capture(old)
    with pytest.raises(ValueError,match='enrolled segmented'):
        Capture(tmp_path/'legacy',['ETH-USDT'],segmented=True,resume=True)


def test_resume_does_not_reset_remaining_capacity(tmp_path):
    root=tmp_path/'capture';options=dict(seconds=1,segmented=True,resume=True,max_bytes=CONTROL_RESERVE+500)
    c=Capture(root,['ETH-USDT'],**options)
    for n in range(2):c.write(dict(kind='BBO',received_ms=n,raw='data'));c.recorder.flush()
    used=c.recorder.used;close_capture(c)
    c=Capture(root,['ETH-USDT'],**options)
    assert c.recorder.used>=used
    for n in range(20):c.write(dict(kind='BBO',received_ms=100+n,raw='data'));c.recorder.flush()
    assert c.recorder.failure=='CAPTURE_QUOTA_EXCEEDED'
    assert c.recorder.used<=500
    close_capture(c)


def test_missing_stream_dependency_stops_before_partial_rest_only_capture(tmp_path,monkeypatch):
    c=Capture(tmp_path/'missing',['ETH-USDT'],seconds=1)
    def missing():raise ModuleNotFoundError('websockets')
    monkeypatch.setattr(c,'stream_dependency_check',missing)
    monkeypatch.setattr(c,'load_specifications',lambda:pytest.fail('do not capture a partial stream silently'))
    monkeypatch.setattr('htx_trend.realtime.public_capture.shutil.disk_usage',lambda _:SimpleNamespace(free=20*GIB))
    c.run()
    assert c.failure=='STREAM_DEPENDENCY_UNAVAILABLE'
    assert json.loads((c.root/'status.json').read_bytes())['final']


def test_overflow_is_nonblocking_fail_closed_and_logs_high_water(tmp_path, caplog):
    c = Capture(tmp_path / "overflow", ["ETH-USDT"], seconds=1)
    try:
        caplog.set_level(logging.WARNING)
        limit = c.events.maxsize
        for _ in range(limit):
            c.emit(dict(kind="GAP", received_ms=1, reason="fill"))
        assert c.failure is None
        assert c.queue_depth_high_water >= int(limit * 0.9)
        assert any("high-water" in record.message for record in caplog.records)
        held = c.events.qsize()
        box = {}

        def once():
            c.emit(dict(kind="GAP", received_ms=2, reason="overflow"))
            box["failure"] = c.failure
            box["stop"] = c.stop.is_set()

        worker = threading.Thread(target=once)
        worker.start()
        worker.join(2)
        assert not worker.is_alive()
        assert box["failure"] == "QUEUE_OVERFLOW" and box["stop"] is True
        assert c.events.qsize() == held
        body = c.status()
        assert body["queue_depth_high_water"] >= limit
        assert body["queue_maxsize"] == limit
        assert "last_flush_ms" in body and "last_seal_ms" in body
        assert "flush_ms_high_water" in body and "seal_ms_high_water" in body
    finally:
        close_capture(c)


def test_consumer_drains_backlog_in_bounded_batches(tmp_path):
    c = Capture(tmp_path / "batch", ["ETH-USDT"], seconds=1)
    try:
        burst = 2000
        started = time.monotonic()
        for i in range(burst):
            c.emit(
                dict(
                    kind="BBO",
                    contract="ETH-USDT",
                    received_ms=i,
                    bid="100",
                    ask="101",
                )
            )
        assert c.failure is None
        seen = 0
        while c.events.qsize():
            batch = c.drain_batch()
            assert 1 <= len(batch) <= 512
            assert c.write_batch(batch) is True
            seen += len(batch)
        elapsed = time.monotonic() - started
        assert seen == burst
        assert c.counts["BBO"] == burst and c.channels["ETH-USDT:BBO"] == burst
        assert c.queue_depth_high_water >= 512
        # 2000 events is a short burst, not a multi-second stall.
        assert elapsed < 2
    finally:
        close_capture(c)


def test_run_loop_uses_nowait_batches_and_writes_every_event(tmp_path, monkeypatch):
    c = Capture(tmp_path / "run-batch", ["ETH-USDT"], seconds=1)
    monkeypatch.setattr(c, "stream_dependency_check", lambda: None)
    monkeypatch.setattr(c, "load_specifications", lambda: None)
    monkeypatch.setattr(c, "stream_loop", lambda: c.stop.wait(5))
    monkeypatch.setattr(c, "rest_loop", lambda: c.stop.wait(5))
    monkeypatch.setattr(
        "htx_trend.realtime.public_capture.shutil.disk_usage",
        lambda _: SimpleNamespace(free=20 * GIB),
    )
    calls = {"nowait": 0}
    original = c.events.get_nowait

    def counted():
        calls["nowait"] += 1
        return original()

    c.events.get_nowait = counted
    for i in range(1000):
        c.events.put_nowait(dict(kind="GAP", received_ms=i, reason="burst"))
    c.run()
    assert c.failure == "DURATION_LIMIT"
    assert c.counts["GAP"] == 1000
    assert calls["nowait"] >= 500
    final = json.loads((c.root / "status.json").read_bytes())
    assert final["final"] is True
    assert final["status_detail"] == "full"
    assert "channels" in final
    assert final["queue_maxsize"] == 65536
    assert "queue_depth_high_water" in final
    assert "last_flush_ms" in final and "last_seal_ms" in final


def test_heartbeat_omits_channels_and_seal_is_split_from_flush(tmp_path, monkeypatch):
    c = Capture(tmp_path / "maintain", ["ETH-USDT"], seconds=1)
    try:
        seals, flushes, syncs = [], [], []
        monkeypatch.setattr(
            c.recorder,
            "segment_boundary",
            lambda *args, **kwargs: seals.append(1),
        )

        def flush():
            flushes.append(1)
            return None

        monkeypatch.setattr(c.recorder, "flush", flush)
        real_atomic = atomic_json

        def spy(path, value, *, sync=True):
            syncs.append((value.get("status_detail"), sync))
            return real_atomic(path, value, sync=sync)

        monkeypatch.setattr("htx_trend.realtime.public_capture.atomic_json", spy)
        c.write(dict(kind="BBO", contract="ETH-USDT", received_ms=1000, bid="1", ask="2"))
        t0 = 10_000.0
        c._flush_at = c._seal_at = c._heartbeat_at = t0
        assert c.maintain(t0 + 10) is True
        assert flushes == [1] and seals == []
        heartbeat = json.loads((c.root / "status.json").read_bytes())
        assert heartbeat["status_detail"] == "heartbeat"
        assert "channels" not in heartbeat
        assert heartbeat["channel_keys"] == 1
        assert heartbeat["counts"]["BBO"] == 1
        assert "ETH-USDT:BBO" in heartbeat["stale_price_channels"]
        assert "queue_depth_high_water" in heartbeat
        assert heartbeat["last_flush_ms"] >= 0
        assert "last_seal_ms" in heartbeat
        assert syncs == [("heartbeat", False)]
        # Flush and seal both become due together; seal waits one turn.
        assert c.maintain(t0 + 60) is True
        assert flushes == [1, 1] and seals == []
        assert c.maintain(t0 + 60) is True
        assert seals == [1]
        full = json.loads((c.root / "status.json").read_bytes())
        assert full["status_detail"] == "full"
        assert full["channels"]["ETH-USDT:BBO"] == 1
        assert full["channel_keys"] == 1
        assert full["last_seal_ms"] >= 0
        assert syncs[-1] == ("full", True)
    finally:
        close_capture(c)


def test_unexpected_capture_thread_exit_is_visible_and_stops_run(tmp_path,monkeypatch):
    c=Capture(tmp_path/'dead-thread',['ETH-USDT'],seconds=2)
    monkeypatch.setattr(c,'stream_dependency_check',lambda:None)
    monkeypatch.setattr(c,'load_specifications',lambda:None)
    monkeypatch.setattr(c,'stream_loop',lambda:None)
    monkeypatch.setattr(c,'rest_loop',lambda:c.stop.wait(3))
    monkeypatch.setattr('htx_trend.realtime.public_capture.shutil.disk_usage',lambda _:SimpleNamespace(free=20*GIB))
    c.run()
    assert c.failure=='CAPTURE_THREAD_EXITED'
