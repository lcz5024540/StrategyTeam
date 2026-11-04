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
    with pytest.raises(ValueError, match="stale"):
        normalize_window("ETH-USDT", "15min", raw, 162 * 900000)


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


def test_unexpected_capture_thread_exit_is_visible_and_stops_run(tmp_path,monkeypatch):
    c=Capture(tmp_path/'dead-thread',['ETH-USDT'],seconds=2)
    monkeypatch.setattr(c,'stream_dependency_check',lambda:None)
    monkeypatch.setattr(c,'load_specifications',lambda:None)
    monkeypatch.setattr(c,'stream_loop',lambda:None)
    monkeypatch.setattr(c,'rest_loop',lambda:c.stop.wait(3))
    monkeypatch.setattr('htx_trend.realtime.public_capture.shutil.disk_usage',lambda _:SimpleNamespace(free=20*GIB))
    c.run()
    assert c.failure=='CAPTURE_THREAD_EXITED'


def test_status_never_calls_missing_or_stale_channels_healthy(tmp_path):
    now = [2_000_000]
    c = Capture(tmp_path/'health',['ETH-USDT'],seconds=1,clock=lambda:now[0]/1000)
    try:
        assert c.status()['continuity']=='STARTING'
        for kind in ('BBO','DEPTH','FRAME'):
            c.write(dict(kind=kind,contract='ETH-USDT',received_ms=now[0]))
        assert c.status()['continuity']=='HEALTHY'
        now[0] += 20 * 60 * 1000 + 1
        status = c.status()
        assert status['continuity']=='DEGRADED'
        assert status['stale_kline_channels']==['ETH-USDT:FRAME']
        c.failure='LOW_DISK_HEADROOM'
        assert c.status(final=True)['continuity']=='STOPPED'
    finally:
        close_capture(c)


def test_stream_recovery_records_measured_gap(tmp_path, monkeypatch):
    c = Capture(tmp_path/'gap',['ETH-USDT'],seconds=1,clock=lambda:1.5)

    class Socket:
        calls = 0
        def __enter__(self): return self
        def __exit__(self, *_): return None
        def send(self, _): return None
        def recv(self, timeout):
            self.calls += 1
            if self.calls == 1:
                return json.dumps(depth(1000))
            c.stop.set()
            raise TimeoutError

    monkeypatch.setattr('websockets.sync.client.connect',lambda *_,**__:Socket())
    try:
        c.stream_loop()
        events=[]
        while not c.events.empty(): events.append(c.events.get_nowait())
        assert [e['kind'] for e in events]==['GAP','DEPTH','GAP_RECOVERED']
        assert events[-1]['epoch']==events[0]['epoch']
        assert events[-1]['gap_started_ms']==1500 and events[-1]['gap_ms']==0
    finally:
        close_capture(c)
