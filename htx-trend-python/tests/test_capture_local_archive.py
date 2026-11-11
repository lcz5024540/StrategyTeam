"""Real filesystem tests; no exchange account or trading process is started."""
from concurrent.futures import ThreadPoolExecutor
from decimal import Decimal
import gzip
from hashlib import sha256
import json
import os
from pathlib import Path
import random
import string
import time

import pytest

from htx_trend.realtime import local_archive as module
from htx_trend.realtime.coverage import Coverage
from htx_trend.realtime.feed import MarketEvent
from htx_trend.realtime.storage import SegmentRecorder
from htx_trend.research.store import FileEvidenceStore, canonical


def seed(root, count=20):
    recorder = SegmentRecorder(root)
    randomizer = random.Random(42)
    for i in range(count):
        recorder.append({
            "kind": "TEST", "received_ms": i,
            "payload": "".join(randomizer.choices(string.ascii_letters, k=2000)),
        })
        assert recorder.flush()
    records = recorder.records.copy()
    before = {r["hash"]: (root / (r["hash"] + ".json.gz")).read_bytes() for r in records}
    used = recorder.used
    recorder.close()
    return records, before, used


def open_local(root, **kwargs):
    return SegmentRecorder(
        root, local_archive=True, local_archive_min_free_bytes=0, **kwargs
    )


def wait_until(predicate, timeout=5):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(0.005)
    raise AssertionError("local archive did not reach the expected condition")


def test_background_release_preserves_paths_bytes_manifest_and_restart(tmp_path):
    root = tmp_path / "capture"
    records, before, used = seed(root)
    manifest = (root / "segments.jsonl").read_bytes()
    recorder = open_local(root, max_bytes=int(used / 0.9))
    try:
        wait_until(lambda: recorder.used <= recorder.local_archive.target)
        status = recorder.storage_status()
        assert status["cold_payload_bytes"] > 0
        assert status["hot_bytes"] < used
        assert status["failure"] is None
        assert not (root / (records[-1]["hash"] + ".json.gz")).is_symlink()
        assert (root / "segments.jsonl").read_bytes() == manifest
        for key, packed in before.items():
            assert (root / (key + ".json.gz")).read_bytes() == packed
        assert len(recorder.verify()) == len(records)
        recorder.append({"kind": "TEST", "received_ms": 100})
        assert recorder.flush()
        final_used = recorder.used
    finally:
        recorder.close()
    restarted = open_local(root, max_bytes=used * 2)
    try:
        assert restarted.used == final_used
        assert len(restarted.verify()) == len(records) + 1
        assert restarted.last_ref == restarted.records[-1]["hash"]
    finally:
        restarted.close()
    with pytest.raises(ValueError, match="explicit local-archive mode"):
        SegmentRecorder(root)


@pytest.mark.parametrize("phase", [
    "link", "cold_sync", "symlink", "replace_before", "replace_after", "root_sync",
])
def test_crash_boundaries_preserve_data_and_retry(tmp_path, monkeypatch, phase):
    root = tmp_path / "capture"
    records, before, _ = seed(root, 3)
    recorder = open_local(root)
    member = records[0]
    source = root / (member["hash"] + ".json.gz")
    used = recorder.used
    original_replace = os.replace
    original_sync = module.sync_directory

    def fail(*args, **kwargs):
        raise OSError("injected interruption")

    def replace_after(*args):
        original_replace(*args)
        fail()

    def sync(path):
        if phase == "cold_sync" and path == recorder.local_archive.cold:
            fail()
        if phase == "root_sync" and path == recorder.local_archive.root:
            fail()
        original_sync(path)

    with monkeypatch.context() as m:
        if phase in ("link", "symlink"):
            m.setattr(os, phase, fail)
        elif phase.startswith("replace"):
            m.setattr(os, "replace", replace_after if phase.endswith("after") else fail)
        else:
            m.setattr(module, "sync_directory", sync)
        with recorder.lock, pytest.raises(OSError):
            recorder.local_archive._promote(member)
    assert recorder.used == used  # Never reclaim quota before all durability barriers.
    assert source.read_bytes() == before[member["hash"]]
    recorder.close()
    restarted = open_local(root)
    try:
        with restarted.lock:
            restarted.local_archive._promote(member)
        assert source.is_symlink()
        assert source.read_bytes() == before[member["hash"]]
        assert len(restarted.verify()) == 3
    finally:
        restarted.close()


def test_conflicting_cold_bytes_never_overwritten(tmp_path):
    root = tmp_path / "capture"
    records, before, _ = seed(root, 2)
    recorder = open_local(root)
    try:
        key = records[0]["hash"]
        target = recorder.local_archive.cold / (key + ".json.gz")
        target.write_bytes(b"conflicting content")
        with recorder.lock, pytest.raises(ValueError, match="conflicting"):
            recorder.local_archive._promote(records[0])
        assert target.read_bytes() == b"conflicting content"
        assert (root / (key + ".json.gz")).read_bytes() == before[key]
    finally:
        recorder.close()


@pytest.mark.parametrize("target_kind", ["outside", "absolute", "missing", "nested_link"])
def test_invalid_aliases_are_not_free_quota(tmp_path, target_kind):
    root = tmp_path / "capture"
    records, _, _ = seed(root, 2)
    recorder = open_local(root)
    with recorder.lock:
        recorder.local_archive._promote(records[0])
    recorder.close()
    source = root / (records[0]["hash"] + ".json.gz")
    cold = root / module.DIRECTORY / source.name
    original = source.read_bytes()
    source.unlink()  # Test-owned alias only, immutable payload retained.
    if target_kind == "nested_link":
        external = tmp_path / "external.gz"
        external.write_bytes(original)
        cold.unlink()
        cold.symlink_to(external)
        source.symlink_to(f"{module.DIRECTORY}/{source.name}")
    else:
        targets = {"outside": "../external.gz", "absolute": str(cold), "missing": f"{module.DIRECTORY}/missing.json.gz"}
        source.symlink_to(targets[target_kind])
    with pytest.raises(ValueError, match="alias target"):
        open_local(root)


def test_symlink_cold_directory_rejected(tmp_path):
    root = tmp_path / "capture"
    seed(root, 1)
    external = tmp_path / "outside"
    external.mkdir()
    (root / module.DIRECTORY).symlink_to(external, target_is_directory=True)
    with pytest.raises(ValueError, match="non-symlink root"):
        open_local(root)


def test_two_new_writers_are_exclusive(tmp_path):
    root = tmp_path / "capture"
    recorder = open_local(root)
    try:
        with pytest.raises(BlockingIOError):
            open_local(root)
    finally:
        recorder.close()
    other = open_local(root)
    other.close()


def test_disk_reserve_failure_preserves_existing_data_and_latches(tmp_path):
    root = tmp_path / "capture"
    records, before, _ = seed(root, 2)
    recorder = SegmentRecorder(
        root, local_archive=True, local_archive_min_free_bytes=2**63
    )
    try:
        recorder.append({"kind": "TEST", "received_ms": 22})
        assert recorder.flush() is None
        assert recorder.failure.startswith("CAPTURE_IO:")
        assert recorder.append({"kind": "TEST", "received_ms": 23}) is False
        for r in records:
            assert (root / (r["hash"] + ".json.gz")).read_bytes() == before[r["hash"]]
    finally:
        recorder.close()


def test_modes_are_exclusive_and_reserve_is_explicit(tmp_path):
    with pytest.raises(ValueError, match="explicit disk reserve"):
        SegmentRecorder(tmp_path / "no-budget", local_archive=True)
    with pytest.raises(ValueError, match="segmented/COS"):
        SegmentRecorder(tmp_path / "segmented", local_archive=True, segmented=True)


def test_readers_can_hold_original_fds_during_alias_replacement(tmp_path):
    root = tmp_path / "capture"
    records, before, _ = seed(root, 2)
    recorder = open_local(root)
    path = root / (records[0]["hash"] + ".json.gz")
    try:
        with path.open("rb") as original_fd, ThreadPoolExecutor(max_workers=2) as pool:
            future = pool.submit(lambda: [path.read_bytes() for _ in range(500)])
            with recorder.lock:
                recorder.local_archive._promote(records[0])
            expected = before[records[0]["hash"]]
            assert original_fd.read() == expected
            assert all(packed == expected for packed in future.result())
    finally:
        recorder.close()


def bbo(at, price):
    return MarketEvent(
        "BBO", "ETH-USDT", at, at, str(at), "epoch",
        bid=Decimal(price), ask=Decimal(price) + Decimal(".01"),
        bid_size=Decimal(10), ask_size=Decimal(10),
    )


@pytest.mark.parametrize("with_gap", [False, True])
def test_coverage_proof_through_cold_preserves_gap_rejection(tmp_path, with_gap):
    recorder = open_local(tmp_path / "capture")
    evidence = FileEvidenceStore(tmp_path / "evidence")
    coverage = Coverage()
    try:
        for at, price in ((900, "100"), (1200, "110"), (1400, "105")):
            event = bbo(at, price)
            coverage.append(event, at)
            recorder.append(event)
            recorder.flush()
        if with_gap:
            recorder.append({"kind": "GAP", "contract": "ETH-USDT", "received_ms": 1100})
            recorder.flush()
        with recorder.lock:
            for record in recorder.records:
                recorder.local_archive._promote(record)
        proof = coverage.prove(
            "ETH-USDT", "LONG", 1000, 1500, recorder,
            seed_high="100", seed_low="100", fill_ref=evidence.put("fill", {}),
            evidence=evidence,
        )
        if with_gap:
            assert proof is None
        else:
            assert proof["highest"] == "110" and proof["lowest"] == "100"
            assert proof["segments"]
    finally:
        recorder.close()
        evidence.close()


def test_market_event_fast_encoding_preserves_canonical_bytes(tmp_path):
    recorder = SegmentRecorder(tmp_path / "capture")
    events = [bbo(1000, "100.00"), bbo(1100, "100.20")]
    for event in events:
        recorder.append(event)
    recorder.append({"kind": "GAP", "received_ms": 1200, "extra": Decimal("1.200")})
    key = recorder.flush()
    expected = canonical({
        "schema": "htx-realtime-segment-v1",
        "events": [e.dump() for e in events] + [
            {"kind": "GAP", "received_ms": 1200, "extra": Decimal("1.200")},
        ],
    })
    assert gzip.decompress((recorder.root / (key + ".json.gz")).read_bytes()) == expected
    assert sha256(expected).hexdigest() == key
    recorder.close()


def test_public_references_and_full_audit_read_cold_without_changes(tmp_path):
    from htx_trend.realtime.public_capture import iter_records, read_reference
    root = tmp_path / "capture"
    root.mkdir()
    seed(root / "segments", 3)
    recorder = open_local(root / "segments")
    try:
        original = list(iter_records(root))
        with recorder.lock:
            for record in recorder.records:
                recorder.local_archive._promote(record)
        assert list(iter_records(root)) == original
        for event, ref in original:
            assert read_reference(root, ref) == event
    finally:
        recorder.close()


def test_cold_corruption_is_not_hidden_from_audit(tmp_path):
    root = tmp_path / "capture"
    seed(root, 2)
    recorder = open_local(root)
    try:
        with recorder.lock:
            recorder.local_archive._promote(recorder.records[0])
        cold = recorder.local_archive.cold / (recorder.records[0]["hash"] + ".json.gz")
        cold.write_bytes(gzip.compress(b"{}"))
        with pytest.raises((ValueError, KeyError)):
            recorder.verify()
    finally:
        recorder.close()


def test_local_layout_cannot_be_enrolled_for_cos_reclamation(tmp_path):
    from htx_trend.sealing.control import initialize, SealingError
    recorder = open_local(tmp_path / "capture")
    try:
        with pytest.raises(SealingError, match="cannot be enrolled"):
            initialize(
                recorder.root, kind="capture", contract="capture-runtime",
                enroll_existing=True, writer_stopped=True,
            )
    finally:
        recorder.close()


def test_duplicate_archived_segment_has_no_double_charge(tmp_path):
    recorder = open_local(tmp_path / "capture")
    event = {"kind": "TEST", "received_ms": 1}
    try:
        recorder.append(event)
        ref = recorder.flush()
        with recorder.lock:
            recorder.local_archive._promote(recorder.records[0])
        used = recorder.used
        recorder.append(event)
        assert recorder.flush() == ref
        assert recorder.used == used
        assert len(recorder.records) == 1
    finally:
        recorder.close()


@pytest.mark.parametrize("invalid", ["packed_limit", "decoded_limit", "fifo"])
def test_archive_rejects_unbounded_or_non_regular_members(tmp_path, monkeypatch, invalid):
    root = tmp_path / "capture"
    recorder = open_local(root)
    try:
        recorder.append({"kind": "TEST", "received_ms": 1, "data": "x" * 2000})
        recorder.flush()
        record = recorder.records[0]
        source = root / (record["hash"] + ".json.gz")
        if invalid == "fifo":
            source.rename(root / "retained-test-member.gz")
            os.mkfifo(source)
        else:
            monkeypatch.setattr(module, "MAX_MEMBER_BYTES", 1 if invalid == "packed_limit" else 256)
        with recorder.lock, pytest.raises(ValueError, match="bounded"):
            recorder.local_archive._promote(record)
        assert not source.is_symlink()
    finally:
        recorder.close()


def test_quota_failure_does_not_resume_when_hot_bytes_later_decline(tmp_path):
    root = tmp_path / "capture"
    seed(root, 3)
    recorder = open_local(root)
    try:
        recorder.max_bytes = recorder.used
        recorder.append({"kind": "TEST", "received_ms": 9})
        assert recorder.flush() is None
        assert recorder.failure == "CAPTURE_QUOTA_EXCEEDED"
        with recorder.lock:
            recorder.local_archive._promote(recorder.records[0])
        assert recorder.used < recorder.max_bytes
        assert recorder.append({"kind": "TEST", "received_ms": 10}) is False
        assert recorder.failure == "CAPTURE_QUOTA_EXCEEDED"
    finally:
        recorder.close()


@pytest.mark.parametrize("manifest", ["{broken", '{"missing_hash":true}\n'])
def test_failed_manifest_load_releases_writer_lock(tmp_path, manifest):
    root = tmp_path / "capture"
    root.mkdir()
    (root / "segments.jsonl").write_text(manifest)
    with pytest.raises((ValueError, KeyError)):
        open_local(root)
    (root / "segments.jsonl").write_text("")
    recorder = open_local(root)
    recorder.close()


def test_factory_failure_closes_archive_stream_and_loader(tmp_path, monkeypatch):
    from types import SimpleNamespace
    from htx_trend.realtime import factory

    root = tmp_path / "capture"
    monkeypatch.setenv("HTX_RT_CAPTURE_DIR", str(root))
    monkeypatch.setenv("HTX_RT_CAPTURE_LOCAL_ARCHIVE", "true")
    monkeypatch.setenv("HTX_RT_CAPTURE_MIN_FREE_BYTES", "0")
    closed = []

    class Loader:
        def __init__(self, *args, **kwargs):
            pass

        def close(self):
            closed.append("loader")

    class Stream(Loader):
        def close(self):
            closed.append("stream")

    def fail(*args, **kwargs):
        raise RuntimeError("injected worker assembly failure")

    monkeypatch.setattr(factory, "FrameLoader", Loader)
    monkeypatch.setattr(factory, "PublicStream", Stream)
    monkeypatch.setattr(factory, "RealtimeWorker", fail)
    with pytest.raises(RuntimeError, match="assembly"):
        factory._common(
            "test", SimpleNamespace(parameters=SimpleNamespace(candidate="ORIGINAL")),
            None, None, None, (), None,
            execution=None, leverage=1, policy=SimpleNamespace(to_dict=lambda: {}),
            entry_config=None, management=None,
        )
    assert closed == ["stream", "loader"]
    recorder = open_local(root)
    recorder.close()


def test_close_prevents_appends_and_is_idempotent(tmp_path):
    recorder = open_local(tmp_path / "capture")
    assert recorder.append({"kind": "TEST", "received_ms": 1})
    recorder.close()
    recorder.close()
    assert recorder.append({"kind": "TEST", "received_ms": 2}) is False
    assert len(recorder.verify()) == 1


def test_worker_early_stop_error_still_flushes_and_releases_recorder(tmp_path):
    from types import SimpleNamespace
    from htx_trend.realtime.worker import RealtimeWorker

    def fail():
        raise OSError("injected stream shutdown failure")

    root = tmp_path / "capture"
    recorder = open_local(root)
    recorder.append({"kind": "TEST", "received_ms": 1})
    worker = object.__new__(RealtimeWorker)
    worker.stopped = False
    worker.entry_enabled = True
    worker.cost_observer = None
    worker.stream = SimpleNamespace(close=fail)
    worker.recorder = recorder
    with pytest.raises(OSError, match="stream shutdown"):
        worker.stop()
    worker.stop()
    assert recorder._closed
    assert not recorder.local_archive.thread.is_alive()
    other = open_local(root)
    try:
        assert len(other.verify()) == 1
    finally:
        other.close()


def test_recorder_flush_exception_still_releases_archive(tmp_path, monkeypatch):
    root = tmp_path / "capture"
    recorder = open_local(root)

    def fail():
        raise RuntimeError("injected flush failure")

    monkeypatch.setattr(recorder, "flush", fail)
    with pytest.raises(RuntimeError, match="flush failure"):
        recorder.close()
    assert not recorder.local_archive.thread.is_alive()
    other = open_local(root)
    other.close()
