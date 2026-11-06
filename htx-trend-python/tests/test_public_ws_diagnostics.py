import json
import logging
from types import SimpleNamespace

import pytest
from websockets.exceptions import ConnectionClosedError
from websockets.frames import Close

from htx_trend.realtime import public_capture as capture_mod


def diagnostics():
    return capture_mod.WsDiagnostics(["ETH-USDT", "BTC-USDT"])


def market(kind="BBO", at=1000, code="ETH-USDT"):
    return dict(kind=kind, contract=code, received_ms=at)


def test_per_channel_outage_includes_backoff_and_partial_recovery():
    d = diagnostics()
    d.begin_attempt("a", 1, 900, 0.9)
    first = d.observe(market(), 1)
    assert first["total_outage_ms"] is None
    assert first["recovery_scope"] == "FIRST_OBSERVATION_IN_PROCESS"
    d.observe(market("DEPTH", 1001), 1.001)
    d.failure(ConnectionError("lost"), 1010, 1.01, None, {})
    d.begin_attempt("b", 2, 121010, 121.01)
    # Another failed handshake must not reset the last good channel clocks.
    d.failure(TimeoutError("handshake"), 122010, 122.01, None, {})
    d.begin_attempt("c", 3, 131000, 131)
    bbo = d.observe(market(at=132000), 132)
    assert bbo["kind"] == "WS_CHANNEL_RECOVERED"
    assert bbo["last_good_ms"] == 1000
    assert bbo["first_recovered_ms"] == 132000
    assert bbo["total_outage_ms"] == 131000
    assert bbo["attempt"] == 3
    assert d.observe(market(at=132001), 132.001) is None
    snap = d.snapshot(132001, 132.001, None)
    assert snap["pending_channels"] == [
        "ETH-USDT:DEPTH",
        "BTC-USDT:BBO",
        "BTC-USDT:DEPTH",
    ]
    dep = d.observe(market("DEPTH", 174000), 174)
    assert dep["total_outage_ms"] == 172999


def test_non_market_events_never_certify_ws_recovery_and_clock_is_monotonic():
    d = diagnostics()
    d.begin_attempt("a", 1, 1000, 1)
    assert d.observe(market("FRAME"), 1) is None
    assert d.snapshot(1000, 1, None)["last_market_received_ms"] is None
    d.observe(market(), 1)
    d.begin_attempt("b", 2, 900, 121)
    event = d.observe(market(at=800), 133)
    assert event["total_outage_ms"] == 132000
    assert event["wall_interval_ms"] == -200


def test_close_direction_exception_chain_stack_and_redaction():
    d = diagnostics()
    d.begin_attempt("epoch", 7, 1000, 1)
    d.start("recv", 2)
    try:
        try:
            raise TimeoutError(
                "Authorization: Bearer EXAMPLE_SECRET https://host/path?token=HIDDEN"
            )
        except TimeoutError as cause:
            raise ConnectionClosedError(
                None, Close(1011, "keepalive ping timeout token=PRIVATE"), None
            ) from cause
    except ConnectionClosedError as error:
        d.end("recv", 3)
        result = d.failure(error, 3000, 3, None, {"queue_depth": 42})
    assert result["phase"] == "recv"
    assert result["attempt"] == 7
    assert result["close_sent"]["code"] == 1011
    assert result["close_received"] is None
    assert result["exception_chain"][1]["type"] == "TimeoutError"
    assert result["exception_chain"][0]["frames"]
    assert result["runtime"]["queue_depth"] == 42
    assert result["durations_ms"]["recv"] == 1000
    raw = json.dumps(result)
    for secret in ("EXAMPLE_SECRET", "HIDDEN", "PRIVATE"):
        assert secret not in raw
    assert "keepalive ping timeout" in raw


def test_repeated_stacks_are_throttled_but_counts_and_times_remain():
    d = diagnostics()
    d.begin_attempt("a", 1, 0, 0)
    error = ValueError("bad " + "x" * 10000)
    a = d.failure(error, 1000, 1, None, {})
    b = d.failure(error, 2000, 2, None, {})
    c = d.failure(error, 62000, 62, None, {})
    assert a["stack_included"] is True
    assert b["stack_included"] is False
    assert "frames" not in b["exception_chain"][0]
    assert b["repeat"]["count"] == 2
    assert c["repeat"] == dict(count=3, first_ms=1000, last_ms=62000)
    assert c["stack_included"] is True
    assert len(a["exception_chain"][0]["message"]) <= 512
    assert len(json.dumps(a)) < 12000


def test_heartbeat_layers_are_separate_and_unknown_values_not_invented():
    d = diagnostics()
    d.begin_attempt("e", 1, 1000, 1)
    d.raw_received(2000, 2)
    d.htx_ping(2000, 2)
    d.htx_pong(2003, 2.003)
    socket = SimpleNamespace(
        latency=0.123, ping_interval=20, ping_timeout=20, close_timeout=2
    )
    snap = d.snapshot(3000, 3, socket)
    assert snap["protocol_heartbeat"]["last_rtt_ms"] == 123
    assert snap["protocol_heartbeat"]["last_pong_ms"] is None
    assert snap["htx_heartbeat"]["last_ping_ms"] == 2000
    assert snap["htx_heartbeat"]["last_pong_sent_ms"] == 2003
    assert snap["htx_heartbeat"]["response_ms"] == pytest.approx(3)
    assert snap["last_raw_age_ms"] == 1000
    d.begin_attempt("f", 2, 4000, 4)
    snap = d.snapshot(4000, 4, SimpleNamespace(latency=0))
    assert snap["protocol_heartbeat"]["last_rtt_ms"] is None
    assert snap["htx_heartbeat"]["last_ping_ms"] is None


@pytest.fixture
def capture(tmp_path):
    c = capture_mod.Capture(tmp_path / "capture", ["ETH-USDT"], seconds=1)
    yield c
    c.client.close()
    c.lock.close()


def drain(c):
    return [c.events.get_nowait() for _ in range(c.events.qsize())]


def test_failed_handshake_has_durable_and_journal_context(capture, monkeypatch, caplog):
    clock = [1.0]
    capture.clock = capture._monotonic = lambda: clock[0]

    def connect(*args, **kwargs):
        assert kwargs == dict(open_timeout=10, close_timeout=2, max_size=8 * 1024**2)
        clock[0] += 0.25
        raise TimeoutError("opening handshake timeout")

    monkeypatch.setattr(capture, "_ws_connect", lambda: connect)
    monkeypatch.setattr(capture.stop, "wait", lambda _: capture.stop.set() or True)
    with caplog.at_level(logging.WARNING):
        capture.stream_loop()
    failure = next(e for e in drain(capture) if e.get("reason") == "TimeoutError")
    diag = failure["diagnostic"]
    assert diag["phase"] == "connect"
    assert diag["attempt"] == 1
    assert diag["runtime"]["backoff_seconds"] == pytest.approx(5)
    assert diag["runtime"]["next_retry_at_ms"] == 6250
    assert diag["durations_ms"]["connect"] == 250
    assert "opening handshake timeout" in caplog.text
    assert failure["epoch"] in caplog.text


@pytest.mark.parametrize("phase", ["recv", "decode", "pong_send", "subscribe"])
def test_stream_errors_report_phase_without_changing_retry(capture, monkeypatch, phase):
    clock = [1000.0]
    capture.clock = capture._monotonic = lambda: clock[0]

    class Socket:
        def __enter__(self):
            return self

        def __exit__(self, *args):
            return False

        def send(self, payload):
            raise OSError("send failed")

        def recv(self, timeout=None):
            clock[0] += timeout
            if phase == "recv":
                raise EOFError("stream ended")
            if phase == "decode":
                return "not-json"
            if phase == "pong_send":
                return '{"ping":123}'
            raise TimeoutError()

    monkeypatch.setattr(capture, "_ws_connect", lambda: lambda *a, **kw: Socket())
    monkeypatch.setattr(capture.stop, "wait", lambda _: capture.stop.set() or True)
    capture.stream_loop()
    diag = next(e["diagnostic"] for e in drain(capture) if "diagnostic" in e)
    assert diag["phase"] == phase
    assert diag["runtime"]["reconnect_consecutive_failures"] == 1
    assert capture.reconnect.attempts == 1
    if phase == "pong_send":
        assert diag["htx_heartbeat"]["last_ping_ms"] is not None
        assert diag["htx_heartbeat"]["last_pong_sent_ms"] is None


def test_diagnostic_failure_and_full_queue_do_not_stop_capture(capture, monkeypatch):
    def broken(*args, **kwargs):
        raise RuntimeError("diagnostic bug")

    monkeypatch.setattr(capture.ws_diagnostics, "snapshot", broken)
    assert capture.status()["ws_diagnostics"]["diagnostic_errors"] == 1
    import queue

    capture.events = queue.Queue(maxsize=1)
    capture.emit(market())
    capture.emit_diagnostic(dict(kind="WS_CHANNEL_RECOVERED", received_ms=2000))
    assert capture.failure is None and not capture.stop.is_set()
    assert capture.diagnostic_events_dropped == 1
    capture.emit(market(at=3000))
    assert capture.failure == "QUEUE_OVERFLOW"


@pytest.mark.parametrize(
    "message,secret",
    [
        ("Cookie: sid=one; other=SECOND_SECRET\nnext line", "SECOND_SECRET"),
        ('{"SecretId":"COS_ID", "apiSecret":"API_SECRET"}', "COS_ID"),
        ('{"SecretId":"COS_ID", "apiSecret":"API_SECRET"}', "API_SECRET"),
        ("X-Auth-Token: HEADER_SECRET", "HEADER_SECRET"),
    ],
)
def test_header_and_json_secrets_redacted(message, secret):
    d = diagnostics()
    result = d.failure(ValueError(message), 1, 1, None, {})
    assert secret not in json.dumps(result)


def test_channel_snapshot_exposes_last_good_and_unseen(capture):
    d = capture.ws_diagnostics
    d.begin_attempt("a", 1, 1000, 1)
    d.observe(market(), 1)
    d.begin_attempt("b", 2, 121000, 121)
    snap = d.snapshot(132000, 132, None)
    assert snap["channels"]["ETH-USDT:BBO"] == dict(
        last_good_ms=1000, age_ms=131000, observed_in_attempt=False
    )
    assert snap["channels"]["ETH-USDT:DEPTH"]["last_good_ms"] is None


def test_diagnostic_event_roundtrip_does_not_pollute_market_channels(capture):
    d = capture.ws_diagnostics
    d.begin_attempt("a", 1, 1000, 1)
    event = d.observe(market(), 1)
    capture.write(event)
    capture.recorder.flush()
    assert capture.channels == {}
    assert capture.last_seen == {}
    rows = list(capture_mod.iter_records(capture.root))
    assert len(rows) == 1
    assert rows[0][0] == event


def test_broken_diagnostics_leave_original_gap_and_retry(capture, monkeypatch, caplog):
    def broken(*args):
        raise RuntimeError("telemetry failed")

    monkeypatch.setattr(capture.ws_diagnostics, "failure", broken)

    def connect(*args, **kwargs):
        raise OSError("original failure")

    monkeypatch.setattr(capture, "_ws_connect", lambda: connect)
    monkeypatch.setattr(capture.stop, "wait", lambda _: capture.stop.set() or True)
    capture.stream_loop()
    failure = next(e for e in drain(capture) if e.get("reason") == "OSError")
    assert failure["diagnostic"]["diagnostic_unavailable"]
    assert capture.reconnect.consecutive_failures == 1
    assert "diagnostic_unavailable" in caplog.text


def test_logger_failure_does_not_propagate(capture, monkeypatch):
    def broken(*args):
        raise OSError("log sink unavailable")

    monkeypatch.setattr(capture_mod.logger, "log", broken)
    capture._diagnostic_log(logging.INFO, {"kind": "TEST"})
    assert capture.diagnostic_errors == 1
    assert capture.failure is None


def test_repeat_cache_and_status_size_are_bounded():
    d = capture_mod.WsDiagnostics([f"C{i}-USDT" for i in range(100)])
    for i in range(100):
        d.start(str(i), i)
        d.failure(ValueError("error"), i * 1000, i, None, {})
    assert len(d.repeats) == 64
    assert len(json.dumps(d.snapshot(100000, 100, None))) < 64000


def test_decode_duration_covers_json_and_normalization(capture, monkeypatch):
    clock = [1000.0]
    capture.clock = capture._monotonic = lambda: clock[0]
    loads, normalize = json.loads, capture_mod.normalize_ws

    def slow_loads(raw):
        clock[0] += 0.05
        return loads(raw)

    def slow_normalize(*args):
        clock[0] += 0.1
        return normalize(*args)

    class Socket:
        n = 0

        def __enter__(self):
            return self

        def __exit__(self, *args):
            return False

        def send(self, payload):
            pass

        def recv(self, timeout=None):
            self.n += 1
            if self.n == 1:
                return '{"ch":"market.ETH-USDT.depth.step0","tick":{"ts":1,"mrid":1,"bids":[[100,1]],"asks":[[101,1]]}}'
            capture.stop.set()
            raise TimeoutError()

    monkeypatch.setattr(capture_mod.json, "loads", slow_loads)
    monkeypatch.setattr(capture_mod, "normalize_ws", slow_normalize)
    monkeypatch.setattr(capture, "_ws_connect", lambda: lambda *a, **kw: Socket())
    capture.stream_loop()
    snap = capture.ws_diagnostics.snapshot(1000150, 1000.15, None)
    assert snap["durations_ms"]["decode"] == 150
    assert snap["session_had_market"] is True
    assert snap["session_elapsed_ms"] == 150
    assert snap["operation_in_progress_ms"] is None


def test_real_websocket_close_metadata_and_market_bytes(capture, monkeypatch):
    import threading
    from websockets.sync.client import connect
    from websockets.sync.server import serve

    payload = '{"ch":"market.ETH-USDT.bbo","tick":{"ts":1000,"mrid":1,"bid":[100,1],"ask":[101,1]}}'

    def handler(socket):
        socket.send(payload)
        socket.close(1011, "restart token=PRIVATE_VALUE")

    with serve(handler, "127.0.0.1", 0) as server:
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        port = server.socket.getsockname()[1]
        monkeypatch.setattr(
            capture,
            "_ws_connect",
            lambda: lambda _url, **kw: connect(f"ws://127.0.0.1:{port}", **kw),
        )
        monkeypatch.setattr(capture.stop, "wait", lambda _: capture.stop.set() or True)
        try:
            capture.stream_loop()
        finally:
            server.shutdown()
            thread.join(5)
    events = drain(capture)
    diag = next(e["diagnostic"] for e in events if "diagnostic" in e)
    assert diag["phase"] == "recv"
    assert diag["close_received"]["code"] == 1011
    assert diag["close_sent"]["code"] == 1011
    assert diag["close_received_then_sent"] is True
    assert "PRIVATE_VALUE" not in json.dumps(diag)
    bbo = next(e for e in events if e["kind"] == "BBO")
    expected = capture_mod.normalize_ws(
        json.loads(payload), bbo["epoch"], bbo["received_ms"]
    )[0][0]
    assert bbo == expected
    assert capture.failure is None
