from dataclasses import replace
from decimal import Decimal as D
from types import SimpleNamespace

import pytest
from htx_trend.realtime import VERSION
from htx_trend.realtime.plans import RealtimeTrigger, TriggerEngine
from htx_trend.realtime.storage import PlanStore, SegmentRecorder
from htx_trend.realtime.worker import RealtimeWorker
from test_realtime_plans import book, plan, trade


class Management:
    def __init__(self):
        self.calls = []

    def tick(self, now, **kwargs):
        self.calls.append(("stop", now))
        return []

    def gap(self):
        pass

    def event(self, event, now):
        self.calls.append(("book", event.event_id))


@pytest.fixture
def worker(tmp_path):
    store = PlanStore(tmp_path / "plans.db")
    engine = TriggerEngine(store, "p")
    engine.refresh("ETH-USDT", [plan()])
    events = []
    management = Management()
    stream = SimpleNamespace(
        connected=True,
        epoch="a",
        drain=lambda: list(events),
        start=lambda: None,
        close=lambda: None,
    )
    loader = SimpleNamespace(start=lambda: None, close=lambda: None, errors={})
    broker = SimpleNamespace(
        recover=lambda: None,
        account=SimpleNamespace(account_id=7),
        repo=SimpleNamespace(active_position_count=lambda **_: 0),
    )
    evidence = SimpleNamespace(put=lambda *_a, **_k: "evidence")
    w = RealtimeWorker(
        SimpleNamespace(
            instance_key="p", strategy_name=VERSION, execution_mode="PAPER"
        ),
        engine,
        broker,
        management,
        stream,
        loader,
        None,
        {},
        SegmentRecorder(tmp_path / "capture"),
        evidence,
        clock=lambda: 0.1,
    )
    w.started = True
    w.last_epoch = "a"
    yield w, events, management
    store.close()


def test_arrival_order_uses_book_at_trade_not_later_recovered_book(worker):
    w, events, management = worker
    events.extend(
        [
            book(1, "98.3", "98.4"),
            trade(2, "98"),
            trade(3, "100"),
            trade(4, "98.5"),
            trade(5, "98.4"),
            book(6, "100", "100.1"),
        ]
    )
    seen = []

    def preflight(t):
        seen.append(t)
        return SimpleNamespace(reason="TEST"), None, None, None, 100

    w._preflight = preflight
    health = w.tick()
    assert len(seen) == 1 and seen[0].book_ref == __import__(
        "htx_trend.research.store", fromlist=["digest"]
    ).digest(events[0])
    assert management.calls[0][0] == "stop"
    assert health.candidate_count == 1 and w.engine.rows["p"]["phase"] == "REJECTED"


def test_evidence_failure_does_not_skip_price_management(worker):
    w, events, management = worker
    w.recorder.failure = "CAPTURE_QUOTA_EXCEEDED"
    events.extend(
        [
            book(1, "98.3", "98.4"),
            trade(2, "98"),
            trade(3, "100"),
            trade(4, "98.5"),
            trade(5, "98.4"),
        ]
    )
    w._preflight = lambda _: pytest.fail("must block new risk")
    health = w.tick()
    assert health.status == "DEGRADED" and any(c[0] == "book" for c in management.calls)


def test_same_batch_net_r_priority_and_budget_rechecked(worker):
    w, events, management = worker
    w.entry_enabled = True
    triggers = [
        RealtimeTrigger(
            replace(plan(), plan_id=name),
            5,
            5,
            D(98),
            D(101),
            D(101),
            ("a", "b"),
            "b",
            "a",
        )
        for name in ("low", "high")
    ]
    for t in triggers:
        w.engine.refresh(t.plan.contract, [x.plan for x in triggers])
    original = w.engine.accept
    w.engine.accept = lambda event, **_: (
        tuple(triggers) if event.kind == "TRADE" else original(event, now_ms=100)
    )
    events.append(trade(5, "98"))
    calls = []

    def preflight(t):
        calls.append(t.plan.plan_id)
        return (
            SimpleNamespace(reason="pass"),
            SimpleNamespace(
                price=SimpleNamespace(
                    expected_net_r=D(3 if t.plan.plan_id == "high" else 2)
                )
            ),
            None,
            SimpleNamespace(actual_taker_fee_rate=D(".0004")),
            100,
        )

    w._preflight = preflight
    sent = []
    w.broker.submit = lambda intent, t, contract, **_: sent.append(t.plan.plan_id)
    w.tick()
    assert sent == ["high", "low"]
    assert calls == [
        "high",
        "low",
        "high",
        "low",
    ]  # stable preflight order, ranked submit order


def test_drain_between_candidates_blocks_submission(worker):
    w, events, management = worker
    w.entry_enabled = True
    events.extend(
        [
            book(1, "98.3", "98.4"),
            trade(2, "98"),
            trade(3, "100"),
            trade(4, "98.5"),
            trade(5, "98.4"),
        ]
    )
    w._preflight = lambda _: (
        SimpleNamespace(reason="pass"),
        SimpleNamespace(price=SimpleNamespace(expected_net_r=D(3))),
        None,
        None,
        100,
    )
    w._drain_requested = lambda: True
    w.broker.submit = lambda *_a, **_k: pytest.fail("draining submitted an order")
    assert w.tick().status == "DEGRADED"


def test_clock_is_refreshed_after_reconcile_before_consuming_messages(worker):
    w, events, management = worker
    clock = [0.1]
    w.clock = lambda: clock[0]
    w.broker.entry = SimpleNamespace()
    w.last_reconcile_ms = -1000

    def recover():
        clock[0] = 2.0

    w.broker.recover = recover
    events.append(book(2000, "98", "98.01"))
    w.tick()
    assert w.engine.books["ETH-USDT"].received_ms == 2000


def test_loader_publishes_valid_candles_despite_evidence_failure():
    from htx_trend.market_data import Candle, ContractSpec, KlineWindow
    from htx_trend.realtime.worker import FrameLoader

    class Market:
        def kline_window(self, c, p, n, **kw):
            return KlineWindow(
                c,
                p,
                (Candle(0, D(100), D(101), D(99), D(100)),),
                1,
                evidence_status="FAILED",
            )

        def contract_spec(self, c):
            return ContractSpec(c, D(".01"), D(".01"), 1)

    frame = FrameLoader(Market(), ["ETH-USDT"], {}).fetch("ETH-USDT")
    assert frame["evidence_failed"] and frame["entry"].candles


def test_rapid_scheduler_does_not_insert_a_heartbeat_for_every_tick():
    from htx_trend.strategy_platform import StrategySupervisor, WorkerHealth

    beats = []
    worker = SimpleNamespace(
        heartbeat_interval_seconds=45, tick=lambda: WorkerHealth("HEALTHY", "steady")
    )
    supervisor = StrategySupervisor(
        SimpleNamespace(heartbeat=lambda **kw: beats.append(kw)), {"p": worker}
    )
    for _ in range(1000):
        supervisor.tick_all()
    assert len(beats) == 1
    worker.tick = lambda: WorkerHealth("DEGRADED", "feed lost")
    supervisor.tick_all()
    assert len(beats) == 2


def test_current_package_decodes_realtime_checkpoint_and_rejects_unknown_version(
    tmp_path,
):
    from pathlib import Path

    from htx_trend.realtime.plans import Parameters
    from htx_trend.realtime.worker import RealtimeWorker
    from htx_trend.runtime_checkpoint import SCHEMA, encode
    from htx_trend.tools.holding_preflight import target_accepts

    state = dict(
        instance="p",
        strategy=VERSION,
        parameters=Parameters().fingerprint,
        failures=None,
        ledger_path=str(tmp_path / "p.json"),
    )
    checkpoint = dict(schema=SCHEMA, profiles={"REALTIME_STRUCTURE_15M": encode(state)})
    root = Path(__file__).parents[1]
    assert target_accepts(root, [], checkpoint=checkpoint)
    with pytest.raises(ValueError):
        RealtimeWorker.validate_runtime_state({**state, "strategy": "unknown"})


def test_quota_reached_inside_batch_blocks_that_batch_but_keeps_stop_checks(worker):
    w, events, management = worker
    w.recorder.max_bytes = 1
    w.recorder.segment_bytes = 1
    events.extend(
        [
            book(1, "98.3", "98.4"),
            trade(2, "98"),
            trade(3, "100"),
            trade(4, "98.5"),
            trade(5, "98.4"),
        ]
    )
    w._preflight = lambda _: pytest.fail("same-batch new risk must be blocked")
    health = w.tick()
    assert health.status == "DEGRADED"
    assert any(c[0] == "book" for c in management.calls)
    assert sum(c[0] == "stop" for c in management.calls) >= 2


@pytest.mark.parametrize("mode", ["PAPER", "LIVE"])
def test_conservative_generator_is_connected_to_shared_worker(worker, mode):
    from htx_trend.realtime.plans import create_engine
    from test_structure_events import PARAMS, inputs

    w, events, _ = worker
    entry, hour, spec, at = inputs()
    w.engine = create_engine(w.engine.store, "conservative", parameters=PARAMS)
    w.parameters = PARAMS
    w.instance.execution_mode = mode
    w.frames["ETH-USDT"] = {
        "entry": entry,
        "trend": hour,
        "contract": spec,
        "windows": {"15min": entry, "60min": hour},
    }
    w.clock = lambda: at / 1000
    w.tick()
    assert any(D(r["plan"]["line"]) == 110 for r in w.engine.rows.values())
    seen = []
    w._preflight = lambda t: (
        seen.append(t) or SimpleNamespace(reason="TEST"),
        None,
        None,
        None,
        at + 500,
    )
    events.extend(
        [
            book(at + 1, "108", "108.1"),
            trade(at + 2, "108"),
            trade(at + 3, "110"),
            trade(at + 4, "108"),
            trade(at + 5, "107.9"),
        ]
    )
    w.clock = lambda: (at + 500) / 1000
    w.tick()
    assert len(seen) == 1 and seen[0].plan.line == 110
    assert seen[0].plan.parameter_fingerprint == PARAMS.fingerprint
    assert w.engine.rows[seen[0].plan.plan_id]["phase"] == "REJECTED"


def test_conservative_preflight_rejects_invalidated_event_before_network(worker):
    from htx_trend.realtime.plans import create_engine
    from test_structure_events import PARAMS, inputs

    w, _, _ = worker
    entry, hour, spec, at = inputs()
    w.engine = create_engine(w.engine.store, "events", parameters=PARAMS)
    w.parameters = PARAMS
    w.frames["ETH-USDT"] = {"entry": entry, "trend": hour, "contract": spec}
    p = w.engine.refresh_frame(
        entry,
        hour,
        spec,
        now_ms=at,
        signal_side=__import__(
            "htx_trend.models", fromlist=["TrendSide"]
        ).TrendSide.FLAT,
    )[0]
    w.engine.terminal(p.plan_id, "INVALIDATED", reason="test")
    t = RealtimeTrigger(p, at, at, D(98), D(101), D(101), ("a", "b"), "book", "a")
    w.clock = lambda: at / 1000
    with pytest.raises(ValueError, match="no longer actionable"):
        w._preflight(t)
