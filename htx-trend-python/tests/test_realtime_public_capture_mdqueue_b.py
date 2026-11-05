"""Path-B (mdqueue-b) regressions for the keepalive → DEPTH/BBO overflow.

Queue maxsize stays 65536. Overflow stays fail-closed. These tests cover the
reconnect throttle, the gzip/drain knobs, and the reconnect catch-up markers.
"""

import json
import queue

import pytest

from htx_trend.realtime import public_capture as mod
from htx_trend.realtime.public_capture import (
    CAPTURE_GZIP_LEVEL,
    DEPTH_CATCHUP_MAX_S,
    DEPTH_CATCHUP_MIN_INTERVAL_MS,
    DEPTH_CATCHUP_POLICY,
    DEPTH_CATCHUP_WINDOW_S,
    EVENT_QUEUE_MAXSIZE,
    RECONNECT_BACKOFF_INITIAL_S,
    RECONNECT_CIRCUIT_OPEN_S,
    RECONNECT_FAILURE_CIRCUIT,
    RECONNECT_HEALTHY_S,
    RECONNECT_MIN_INTERVAL_S,
    RESUBSCRIBE_CHUNK_PAUSE_S,
    RESUBSCRIBE_INITIAL_DELAY_S,
    RESUBSCRIBE_INTERVAL_S,
    YIELD_MAX_DEFERRALS,
    Capture,
    ReconnectGovernor,
    drain_limit,
    read_drain_batch,
    read_flush_interval_s,
    read_gzip_level,
    resubscribe_schedule,
)


def close_capture(capture):
    capture.client.close()
    if capture.recorder.segments:
        capture.recorder.segments.close()
    capture.lock.close()


def depth_event(contract="ETH-USDT", mrid="1", received_ms=10_000, bid=100):
    return dict(
        kind="DEPTH",
        source="HTX_WS_FULL_DEPTH",
        contract=contract,
        received_ms=received_ms,
        event_id=str(mrid),
        bids=[[str(bid), "1"]],
        asks=[[str(bid + 1), "1"]],
    )


def bbo_event(received_ms, event_id="b"):
    return dict(
        kind="BBO",
        contract="ETH-USDT",
        received_ms=received_ms,
        event_id=event_id,
        bid="100",
        ask="101",
    )


def test_gzip_drain_and_flush_defaults_are_bounded(tmp_path, monkeypatch):
    # Storage's own default is still 9. Public capture pins 1: the segment
    # hash is over canonical bytes, and level 1 is the fastest compressing gzip.
    assert CAPTURE_GZIP_LEVEL == 1
    assert EVENT_QUEUE_MAXSIZE == 65536
    capture = Capture(tmp_path / "defaults", ["ETH-USDT"], seconds=1)
    try:
        assert capture.recorder.gzip_level == 1
        assert capture.drain_batch_limit == 512
        assert capture.flush_interval_s == 10.0
        body = capture.status()
        assert body["queue_maxsize"] == 65536
        assert body["gzip_level"] == 1
        assert body["drain_batch"] == 512
        assert body["flush_interval_s"] == 10.0
        assert body["reconnect_circuit"] == "closed"
        assert body["depth_paused"] is False
        assert body["depth_coalesced"] == 0
        assert body["depth_downsampled"] == 0
    finally:
        close_capture(capture)

    monkeypatch.setenv("HTX_PUBLIC_CAPTURE_GZIP_LEVEL", "3")
    monkeypatch.setenv("HTX_PUBLIC_CAPTURE_DRAIN_BATCH", "128")
    monkeypatch.setenv("HTX_PUBLIC_CAPTURE_FLUSH_INTERVAL_S", "4.5")
    tuned = Capture(tmp_path / "tuned", ["ETH-USDT"], seconds=1)
    try:
        assert tuned.recorder.gzip_level == 3
        assert tuned.drain_batch_limit == 128
        assert tuned.flush_interval_s == 4.5
    finally:
        close_capture(tuned)

    monkeypatch.setenv("HTX_PUBLIC_CAPTURE_GZIP_LEVEL", "0")
    assert read_gzip_level() == 1
    monkeypatch.setenv("HTX_PUBLIC_CAPTURE_GZIP_LEVEL", "99")
    assert read_gzip_level() == 9
    monkeypatch.setenv("HTX_PUBLIC_CAPTURE_GZIP_LEVEL", "nope")
    assert read_gzip_level() == 1
    monkeypatch.setenv("HTX_PUBLIC_CAPTURE_DRAIN_BATCH", "1")
    assert read_drain_batch() == 64
    monkeypatch.setenv("HTX_PUBLIC_CAPTURE_DRAIN_BATCH", "99999")
    assert read_drain_batch() == 4096
    monkeypatch.setenv("HTX_PUBLIC_CAPTURE_FLUSH_INTERVAL_S", "0.1")
    assert read_flush_interval_s() == 1.0
    monkeypatch.setenv("HTX_PUBLIC_CAPTURE_FLUSH_INTERVAL_S", "600")
    assert read_flush_interval_s() == 60.0


def test_high_water_yields_flush_and_enlarges_drain(tmp_path, monkeypatch):
    monkeypatch.setattr(mod, "YIELD_WATER_FRACTION", 0.01)
    monkeypatch.setattr(mod, "YIELD_HIGH_DRAIN_FRACTION", 0.01)
    capture = Capture(tmp_path / "yield", ["ETH-USDT"], seconds=1)
    try:
        for i in range(700):
            capture.emit(dict(kind="GAP", received_ms=i, reason="fill"))
        flushes = []
        monkeypatch.setattr(capture.recorder, "flush", lambda: flushes.append(1))
        started = 10_000.0
        capture._flush_at = capture._seal_at = capture._heartbeat_at = started
        for _ in range(YIELD_MAX_DEFERRALS):
            assert capture.maintain(started + 10) is True
            assert flushes == []
        assert capture.maintain(started + 10) is True
        assert flushes == [1]
        assert drain_limit(capture.events.qsize(), capture.events.maxsize, 512) == 2048
        assert drain_limit(10, 65536, 512) == 512
        assert mod.consumer_extra_drain(10, 65536) is False
        assert mod.consumer_extra_drain(capture.events.qsize(), capture.events.maxsize)
    finally:
        close_capture(capture)


def test_reconnect_governor_throttles_and_opens_depth_circuit():
    now = {"t": 0.0}
    governor = ReconnectGovernor(clock=lambda: now["t"])
    assert governor.seconds_until_attempt() == 0.0
    delays = []
    states = []
    for _ in range(RECONNECT_FAILURE_CIRCUIT):
        governor.begin_attempt(now["t"])
        states.append(governor.note_failure(now["t"]))
        delays.append(governor.seconds_until_attempt(now["t"]))
        now["t"] += delays[-1]
    assert states[:-1] == ["backoff"] * (RECONNECT_FAILURE_CIRCUIT - 1)
    assert states[-1] == "opened"
    assert delays[0] == RECONNECT_MIN_INTERVAL_S
    assert delays[1] == RECONNECT_BACKOFF_INITIAL_S * 2
    assert all(delay >= RECONNECT_MIN_INTERVAL_S for delay in delays[:-1])
    assert delays[-1] == RECONNECT_CIRCUIT_OPEN_S
    assert governor.circuit == "open"
    assert governor.depth_paused is True
    assert governor.circuit_opens == 1
    assert governor.attempts == RECONNECT_FAILURE_CIRCUIT

    now["t"] += 0  # already sitting on the cooldown boundary from the loop above
    assert governor.seconds_until_attempt(now["t"]) == 0.0
    governor.begin_attempt(now["t"])
    assert governor.circuit == "half_open"
    assert governor.depth_paused is True
    assert governor.note_failure(now["t"]) == "opened"
    assert governor.circuit_opens == 2
    assert governor.seconds_until_attempt(now["t"]) == RECONNECT_CIRCUIT_OPEN_S

    governor.begin_attempt(now["t"] + RECONNECT_CIRCUIT_OPEN_S)
    now["t"] += RECONNECT_CIRCUIT_OPEN_S
    governor.note_observation(now["t"])
    assert governor.poll_health(now["t"] + RECONNECT_HEALTHY_S - 0.1) is False
    assert governor.circuit == "half_open"
    assert governor.poll_health(now["t"] + RECONNECT_HEALTHY_S) is True
    assert governor.circuit == "closed"
    assert governor.depth_paused is False
    assert governor.consecutive_failures == 0
    snap = governor.snapshot()
    assert snap["reconnect_circuit"] == "closed"
    assert snap["reconnect_circuit_opens"] == 2
    assert snap["depth_paused"] is False


def test_resubscribe_is_delayed_chunked_and_puts_depth_last():
    contracts = tuple(f"C{i:02d}-USDT" for i in range(13))
    schedule = resubscribe_schedule(contracts, include_depth=True, chunk=12)
    assert schedule[0][0] == RESUBSCRIBE_INITIAL_DELAY_S
    assert schedule[0][2] == "trade.detail"
    channels = [row[2] for row in schedule]
    assert channels.index("bbo") < channels.index("depth.step0")
    assert channels[:13] == ["trade.detail"] * 13
    assert channels[13:26] == ["bbo"] * 13
    assert schedule[12][0] - schedule[11][0] == pytest.approx(
        RESUBSCRIBE_INTERVAL_S + RESUBSCRIBE_CHUNK_PAUSE_S
    )
    paused = resubscribe_schedule(contracts, include_depth=False)
    assert all(row[2] != "depth.step0" for row in paused)
    assert {row[2] for row in paused} == {"trade.detail", "bbo"}


def test_catchup_coalesce_keeps_newer_depth_and_emits_gap_markers(tmp_path):
    clock = {"t": 50.0}
    capture = Capture(
        tmp_path / "coalesce",
        ["ETH-USDT", "BTC-USDT"],
        seconds=1,
        clock=lambda: clock["t"],
    )
    try:
        capture.open_catchup("epoch-1")
        capture.emit(depth_event(mrid="old", received_ms=50_000))
        capture.emit(depth_event(mrid="new", received_ms=50_010, bid=110))
        capture.emit(depth_event(contract="BTC-USDT", mrid="btc", received_ms=50_020))
        capture.emit(
            dict(kind="TRADE", contract="ETH-USDT", received_ms=50_021, event_id="t")
        )
        capture.emit(bbo_event(50_022))
        assert capture.catchup.coalesced == 1
        assert capture.catchup.downsampled == 0
        batch = capture.drain_batch()
        kinds = [event["kind"] for event in batch]
        assert kinds[0] == "GAP"
        assert batch[0]["reason"] == "DEPTH_CATCHUP_BEGIN"
        assert batch[0]["policy"] == DEPTH_CATCHUP_POLICY
        assert batch[0]["preserve"] == "TRADE_AND_BBO"
        assert "DOWNSAMPLE" in batch[0]["policy"]
        assert kinds.index("TRADE") < kinds.index("DEPTH")
        assert kinds.index("BBO") < kinds.index("DEPTH")
        depth_ids = [event["event_id"] for event in batch if event["kind"] == "DEPTH"]
        assert depth_ids == ["new", "btc"]
        capture.close_catchup()
        end = None
        while True:
            try:
                event = capture.events.get_nowait()
            except queue.Empty:
                break
            if event.get("reason") == "DEPTH_CATCHUP_END":
                end = event
        assert end is not None
        assert end["kind"] == "GAP"
        assert end["coalesced"] == 1
        assert end["downsampled"] == 0
        assert capture.status()["depth_coalesced"] == 1
    finally:
        close_capture(capture)


def test_catchup_downsample_keeps_the_newest_snapshot(tmp_path):
    clock = {"t": 10.0}
    capture = Capture(
        tmp_path / "downsample", ["ETH-USDT"], seconds=1, clock=lambda: clock["t"]
    )
    try:
        capture.open_catchup("epoch-d")
        capture.emit(depth_event(mrid="a", received_ms=10_000))
        first = [
            event["event_id"]
            for event in capture.drain_batch()
            if event["kind"] == "DEPTH"
        ]
        assert first == ["a"]
        clock["t"] = 10.2
        capture.emit(depth_event(mrid="b", received_ms=10_200))
        capture.emit(depth_event(mrid="c", received_ms=10_300, bid=120))
        assert capture.catchup.downsampled == 1
        held = [
            event["event_id"]
            for event in capture.drain_batch()
            if event["kind"] == "DEPTH"
        ]
        assert held == []
        clock["t"] = 10.0 + (DEPTH_CATCHUP_MIN_INTERVAL_MS / 1000.0) + 0.2
        released = [
            event["event_id"]
            for event in capture.drain_batch()
            if event["kind"] == "DEPTH"
        ]
        assert released == ["c"]
        capture.close_catchup()
        end = None
        while True:
            try:
                event = capture.events.get_nowait()
            except queue.Empty:
                break
            if event.get("reason") == "DEPTH_CATCHUP_END":
                end = event
        assert end["downsampled"] == 1
        assert end["reason"] == "DEPTH_CATCHUP_END"
    finally:
        close_capture(capture)


def test_trade_and_bbo_outside_and_inside_catchup_are_not_coalesced(tmp_path):
    capture = Capture(tmp_path / "priority", ["ETH-USDT"], seconds=1)
    try:
        capture.emit(depth_event(mrid="1"))
        capture.emit(depth_event(mrid="2"))
        assert capture.events.qsize() == 2
        assert capture.catchup.coalesced == 0
        capture.open_catchup("epoch-p")
        capture.emit(dict(kind="TRADE", contract="ETH-USDT", received_ms=1, event_id="t1"))
        capture.emit(dict(kind="TRADE", contract="ETH-USDT", received_ms=2, event_id="t2"))
        capture.emit(bbo_event(3, "b1"))
        capture.emit(bbo_event(4, "b2"))
        queued = []
        while True:
            try:
                queued.append(capture.events.get_nowait())
            except queue.Empty:
                break
        trades = [event for event in queued if event["kind"] == "TRADE"]
        bbos = [event for event in queued if event["kind"] == "BBO"]
        assert [event["event_id"] for event in trades] == ["t1", "t2"]
        assert [event["event_id"] for event in bbos] == ["b1", "b2"]
        assert capture.catchup.coalesced == 0
    finally:
        close_capture(capture)


def test_catchup_extends_only_while_the_queue_stays_high(tmp_path, monkeypatch):
    monkeypatch.setattr(mod, "YIELD_HIGH_DRAIN_FRACTION", 0.001)
    clock = {"t": 0.0}
    capture = Capture(
        tmp_path / "extend", ["ETH-USDT"], seconds=1, clock=lambda: 20.0
    )
    capture._monotonic = lambda: clock["t"]
    try:
        capture.open_catchup("epoch-x")
        clock["t"] = DEPTH_CATCHUP_WINDOW_S
        capture.poll_catchup(clock["t"])
        assert capture.catchup.active is False
        reasons = _drain_reasons(capture)
        assert "DEPTH_CATCHUP_END" in reasons

        clock["t"] = 0.0
        capture.open_catchup("epoch-y")
        for i in range(80):
            capture.emit(dict(kind="GAP", received_ms=i, reason="pressure"))
        clock["t"] = DEPTH_CATCHUP_WINDOW_S
        capture.poll_catchup(clock["t"])
        assert capture.catchup.active is True
        assert capture.catchup.extended is True
        assert "DEPTH_CATCHUP_EXTENDED" in _peek_reasons(capture)
        clock["t"] = DEPTH_CATCHUP_MAX_S
        capture.poll_catchup(clock["t"])
        assert capture.catchup.active is False
    finally:
        close_capture(capture)


def test_overflow_stays_fail_closed_during_depth_catchup(tmp_path):
    capture = Capture(tmp_path / "overflow", ["ETH-USDT"], seconds=1)
    try:
        assert capture.events.maxsize == 65536
        capture.open_catchup("epoch-o")
        for i in range(500):
            capture.emit(depth_event(mrid=str(i), received_ms=1000 + i))
        assert capture.failure is None
        assert capture.catchup.coalesced == 499
        assert capture.events.qsize() < 10
        slots = capture.events.maxsize - capture.events.qsize()
        for i in range(slots):
            capture.emit(bbo_event(i, str(i)))
        assert capture.failure is None
        assert capture.events.qsize() == capture.events.maxsize
        capture.emit(bbo_event(999_999, "overflow"))
        assert capture.failure == "QUEUE_OVERFLOW"
        assert capture.stop.is_set()
        assert capture.events.qsize() == capture.events.maxsize
    finally:
        close_capture(capture)


def test_stream_loop_backs_off_and_opens_the_circuit(tmp_path, monkeypatch):
    clock = {"t": 1_000.0}
    capture = Capture(
        tmp_path / "backoff", ["ETH-USDT"], seconds=1, clock=lambda: clock["t"]
    )
    capture._monotonic = lambda: clock["t"]

    def wait(seconds):
        clock["t"] += seconds
        if capture.reconnect.circuit_opens >= 1 and seconds >= RECONNECT_CIRCUIT_OPEN_S:
            capture.stop.set()
        return capture.stop.is_set()

    monkeypatch.setattr(capture.stop, "wait", wait)

    def connect(*_args, **_kwargs):
        raise ConnectionError("keepalive ping timeout")

    monkeypatch.setattr(capture, "_ws_connect", lambda: connect)
    try:
        capture.stream_loop()
        reasons = _drain_reasons(capture)
        assert capture.reconnect.attempts == RECONNECT_FAILURE_CIRCUIT
        assert capture.reconnect.circuit_opens == 1
        assert capture.reconnect.depth_paused is True
        assert reasons.count("CONNECT_OR_RECONNECT") == RECONNECT_FAILURE_CIRCUIT
        assert "ConnectionError" in reasons
        assert "RECONNECT_CIRCUIT_OPEN" in reasons
        assert capture.failure != "QUEUE_OVERFLOW"
    finally:
        close_capture(capture)


def test_stream_loop_chunks_subscriptions_and_skips_depth_while_paused(
    tmp_path, monkeypatch
):
    contracts = tuple(f"C{i:02d}-USDT" for i in range(13))
    clock = {"t": 2_000.0}
    capture = Capture(
        tmp_path / "subs", contracts, seconds=1, clock=lambda: clock["t"]
    )
    capture._monotonic = lambda: clock["t"]
    _open_circuit(capture.reconnect, clock)
    sent = []

    class Socket:
        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return False

        def send(self, payload):
            message = json.loads(payload)
            sent.append((clock["t"], message["sub"]))
            if len(sent) == 26:
                capture.stop.set()

        def recv(self, timeout=None):
            clock["t"] += timeout or 0
            raise TimeoutError()

    monkeypatch.setattr(capture, "_ws_connect", lambda: (lambda *_a, **_k: Socket()))
    started = clock["t"]
    try:
        capture.stream_loop()
        channels = [_channel(sub) for _at, sub in sent]
        assert channels[:13] == ["trade.detail"] * 13
        assert channels[13:] == ["bbo"] * 13
        assert "depth.step0" not in channels
        assert sent[0][0] - started == pytest.approx(RESUBSCRIBE_INITIAL_DELAY_S)
        assert sent[12][0] - sent[11][0] == pytest.approx(
            RESUBSCRIBE_INTERVAL_S + RESUBSCRIBE_CHUNK_PAUSE_S
        )
        assert "DEPTH_SUBSCRIPTION_PAUSED" in _drain_reasons(capture)
    finally:
        close_capture(capture)


def test_stream_loop_resumes_depth_after_a_healthy_bbo_session(tmp_path, monkeypatch):
    monkeypatch.setattr(mod, "RECONNECT_HEALTHY_S", 1.0)
    contracts = ("ETH-USDT",)
    clock = {"t": 3_000.0}
    capture = Capture(
        tmp_path / "resume", contracts, seconds=1, clock=lambda: clock["t"]
    )
    capture._monotonic = lambda: clock["t"]
    _open_circuit(capture.reconnect, clock)
    sent = []
    delivered = {"bbo": False}

    class Socket:
        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return False

        def send(self, payload):
            message = json.loads(payload)
            sent.append(message["sub"])
            if message["sub"].endswith("depth.step0"):
                capture.stop.set()

        def recv(self, timeout=None):
            clock["t"] += timeout or 0
            trade_bbo_done = any(sub.endswith(".bbo") for sub in sent)
            if trade_bbo_done and not delivered["bbo"]:
                delivered["bbo"] = True
                return json.dumps(
                    dict(
                        ch="market.ETH-USDT.bbo",
                        tick=dict(ts=1, bid=[100, 1], ask=[101, 1], mrid=7),
                    )
                )
            raise TimeoutError()

    monkeypatch.setattr(capture, "_ws_connect", lambda: (lambda *_a, **_k: Socket()))
    try:
        capture.stream_loop()
        assert any(sub.endswith(".bbo") for sub in sent)
        assert any(sub.endswith("depth.step0") for sub in sent)
        assert sent.index(next(sub for sub in sent if sub.endswith(".bbo"))) < sent.index(
            next(sub for sub in sent if sub.endswith("depth.step0"))
        )
        assert capture.reconnect.circuit == "closed"
        assert capture.reconnect.depth_paused is False
        reasons = _drain_reasons(capture)
        assert reasons.count("DEPTH_CATCHUP_BEGIN") >= 1
        assert "DEPTH_CATCHUP_BEGIN" in reasons
    finally:
        close_capture(capture)


def _open_circuit(governor, clock):
    cursor = clock["t"]
    for _ in range(RECONNECT_FAILURE_CIRCUIT):
        governor.begin_attempt(cursor)
        governor.note_failure(cursor)
        cursor += governor.seconds_until_attempt(cursor)
    clock["t"] = cursor
    assert governor.depth_paused is True
    assert governor.seconds_until_attempt(clock["t"]) == 0.0


def _channel(sub):
    rest = sub[len("market.") :]
    _contract, channel = rest.split(".", 1)
    return channel


def _drain_reasons(capture):
    reasons = []
    while True:
        try:
            event = capture.events.get_nowait()
        except queue.Empty:
            return reasons
        reason = event.get("reason")
        if reason:
            reasons.append(reason)


def _peek_reasons(capture):
    reasons = []
    held = []
    while True:
        try:
            event = capture.events.get_nowait()
        except queue.Empty:
            break
        held.append(event)
        reason = event.get("reason")
        if reason:
            reasons.append(reason)
    for event in held:
        capture.events.put_nowait(event)
    return reasons
