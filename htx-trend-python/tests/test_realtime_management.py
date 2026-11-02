from dataclasses import replace
from decimal import Decimal as D
from types import SimpleNamespace
import pytest

from test_realtime_execution import setup, submit
from test_realtime_plans import book
from htx_trend.paper_worker import WorkerPaperExchange
from htx_trend.runtime import PaperTrendRuntime
from htx_trend.realtime.management import PaperManagement, LiveManagement
from htx_trend.realtime import VERSION
from htx_trend.continuation import ContinuationConfig
from htx_trend.holding_policy import HoldingPolicy, paper_exit_config
from htx_trend.java_entry_contract import JavaEntryConfig
from htx_trend.market_data import OrderBook, FundingRate, KlineWindow, Candle
from htx_trend.models import TrendSide


def management(s, market, frames=None):
    policy = HoldingPolicy.load(
        s.ledger.position_context(s.ledger.active_positions("paper:p")[0].position_id)[
            "holding_policy"
        ]
    )
    runtime = PaperTrendRuntime(
        s.ledger,
        WorkerPaperExchange(s.ledger),
        account_id="paper:p",
        strategy_version=VERSION,
        continuation_config=ContinuationConfig("linked-v1"),
        holding_policy=policy,
        require_holding_policy=True,
        evidence_context={"instance": "p", "timeframe_profile": "INTRADAY_15M"},
    )
    return PaperManagement(
        runtime,
        s.broker,
        frames or {},
        market,
        exit_config=paper_exit_config(),
        entry_config=JavaEntryConfig(),
    )


class Market:
    price = D(102)

    def order_book(self, _):
        return OrderBook(((self.price - D(".01"), D(1000)),), ((self.price, D(1000)),))

    def funding_rate(self, _):
        return FundingRate("ETH-USDT", D(0))


def test_hard_stop_independent_of_frames_evidence_and_contract_fees(setup):
    s = setup
    submit(s)
    s.broker.on_book(book(1300, "98", "98.01"), now_ms=1300)
    p = s.ledger.active_positions("paper:p")[0]
    m = management(s, Market())
    assert not m.tick(1400)
    assert not s.ledger.active_positions("paper:p")
    fee = D(102) * p.quantity * D(".01") * D(".0004")
    assert s.ledger.data["fills"][-1]["fee"] == fee
    assert (
        s.ledger.position(p.position_id).realized_pnl
        == (D(98) - D(102)) * p.quantity * D(".01") - fee
    )
    count = len(s.ledger.data["fills"])
    m.tick(1500)
    assert len(s.ledger.data["fills"]) == count


def test_authoritative_ledger_failure_stops_more_simulated_executions(
    setup, monkeypatch
):
    s = setup
    submit(s)
    s.broker.on_book(book(1300, "98", "98.01"), now_ms=1300)
    m = management(s, Market())
    save = s.ledger.checkpoint

    def fail():
        raise OSError("full")

    monkeypatch.setattr(s.ledger, "checkpoint", fail)
    with pytest.raises(OSError):
        m.tick(1400)
    assert m.ledger_failed
    count = len(s.ledger.data["fills"])
    with pytest.raises(OSError):
        m.tick(1500)
    assert len(s.ledger.data["fills"]) == count
    monkeypatch.setattr(s.ledger, "checkpoint", save)
    assert m.tick(1600) == ["AUTHORITATIVE_LEDGER_RECOVERED"]


def test_first_closed_bar_excludes_prefill_high_low_and_repeated_bar(
    setup, monkeypatch
):
    s = setup
    submit(s)
    s.broker.on_book(book(1300, "98", "98.01"), now_ms=1300)
    p = s.ledger.active_positions("paper:p")[0]
    frames = {}
    market = Market()
    market.price = D("98.01")
    m = management(s, market, frames)
    # 10:07 fill, with pre-fill extreme 50/150 in the 10:00 bar.
    first = (10 * 3600 + 7 * 60) * 1000
    context = s.ledger.position_context(p.position_id)
    context.update(
        first_fill_ms=first,
        last_bar=35100,
        continuation_last_bar=35100,
        highest="98",
        lowest="98",
        extrema_coverage="COMPLETE",
    )
    s.ledger.save_position_context(p.position_id, context)
    bar = Candle(36000, D(100), D(150), D(50), D(98))
    window = KlineWindow("ETH-USDT", "15min", (bar,), 1)
    m.frames = {"ETH-USDT": {"entry": window, "trend": window, "windows": {}}}
    monkeypatch.setattr(
        "htx_trend.realtime.management.evaluate",
        lambda *_: SimpleNamespace(
            side=TrendSide.SHORT,
            indicators=SimpleNamespace(atr=D(2), ema_fast=D(97), ema_slow=D(99)),
        ),
    )
    captured = []
    m.runtime.manage = lambda **kw: captured.append(kw)
    assert not m.tick(36910000)
    assert captured[0]["highest_price"] == 98 and captured[0]["lowest_price"] == 98
    assert captured[0]["flat_streak"] == 0
    m.tick(36911000)
    assert len(captured) == 1


def test_live_stop_does_not_read_missing_bar_cache():
    row = {
        "id": 1,
        "contract_code": "ETH-USDT",
        "side": "LONG",
        "remaining_contract_volume": 1,
        "stop_price": "90",
    }
    state = {"first_fill_ms": 1000, "highest": "100", "lowest": "100"}
    repo = SimpleNamespace(
        managed_positions=lambda _: [row],
        exit_state=lambda _: dict(state),
        save_management_state=lambda _, value: state.update(value),
    )
    broker = SimpleNamespace(repo=repo, instance=SimpleNamespace())
    market = Market()
    market.price = D(80)
    called = []
    manager = SimpleNamespace(_manage=lambda *args: called.append(args))
    m = LiveManagement(broker, manager, market)
    assert not m.tick(2000) and len(called) == 1


def test_partial_entry_deferred_exit_survives_same_bar_cancel_recovery(monkeypatch):
    from test_position_manager import (
        Repo,
        Market as ExitMarket,
        Executor,
        manager,
        position,
    )
    from htx_trend.strategy_context import MarketContext
    from htx_trend.models import TrendSide
    from htx_trend.exits import decide_v4

    repo = Repo()
    market = ExitMarket()
    market.price = D(100)
    live = Executor()
    row = position()
    row.update(primary_model="RT_RANGE_REJECTION", strategy_name=VERSION)
    repo.pending = [
        dict(
            position_id=1, leg="ENTRY", status="PARTIALLY_FILLED", exchange_order_id=77
        )
    ]
    repo.state = {"continuation_version": "linked-v1"}
    mgr = manager(repo=repo, market=market, executor=live)
    mgr.instance = replace(mgr.instance, strategy_name=VERSION)
    monkeypatch.setattr(
        "htx_trend.position_manager.evaluate",
        lambda *_: SimpleNamespace(
            side=TrendSide.SHORT,
            indicators=SimpleNamespace(atr=D(2), ema_fast=D(98), ema_slow=D(100)),
        ),
    )
    mgr._manage(row, 144900, MarketContext())
    assert live.cancelled == [("BTC-USDT", "77")] and not live.sent
    assert repo.state["pending_realtime_exit"]["target_remaining"] == 0
    saved = repo.state["last_bar"]
    repo.pending = []

    def no_new_bar(*_a, **_k):
        raise AssertionError("deferred exit must not await another bar")

    market.kline_window = no_new_bar
    mgr._manage(row, 144901, MarketContext())
    assert (
        len(live.sent) == 1 and live.sent[0].reduce_only and live.sent[0].volume == "4"
    )
    assert repo.state["last_bar"] == saved


def test_nondefault_owned_paper_exit_fee(setup):
    from htx_trend.realtime.brokers import RealtimePaperExchange
    from htx_trend.timeframes import TimeframeProfile

    s = setup
    owned = HoldingPolicy.create(
        instance_key="p",
        account_id="paper:p",
        strategy_family="TREND",
        strategy_name=VERSION,
        mode="PAPER",
        profile=TimeframeProfile.INTRADAY_15M,
        entry_config=JavaEntryConfig(),
        exit_config=replace(paper_exit_config(), fee_rate=D(".0006")),
        continuation_version="linked-v1",
    )
    s.broker.submit(
        s.intent,
        s.trigger,
        s.contract,
        now_ms=1000,
        fee_rate=D(".0004"),
        holding_parameters=owned.to_dict(),
    )
    s.broker.on_book(book(1300, "98", "98.01"), now_ms=1300)
    p = s.ledger.active_positions("paper:p")[0]
    m = management(s, Market())
    m.runtime._exchange = RealtimePaperExchange(s.ledger)
    m.tick(1400)
    assert s.ledger.data["fills"][-1]["fee"] == D(102) * p.quantity * D(".01") * D(
        ".0006"
    )


def test_same_epoch_stale_data_marks_extrema_gap(setup):
    s = setup
    submit(s)
    s.broker.on_book(book(1300, "98", "98.01"), now_ms=1300)
    m = management(s, Market())
    p = s.ledger.active_positions("paper:p")[0]
    context = s.ledger.position_context(p.position_id)
    context["extrema_coverage"] = "COMPLETE"
    s.ledger.save_position_context(p.position_id, context)
    m.gap(contract="ETH-USDT")
    m.event(book(4000, "97", "97.01"), 4000)
    assert s.ledger.position_context(p.position_id)["extrema_coverage"] == "GAP"


def test_manager_failure_does_not_restore_stale_cached_exit_state():
    row = {
        "id": 1,
        "contract_code": "ETH-USDT",
        "side": "LONG",
        "remaining_contract_volume": 1,
        "stop_price": "90",
    }
    durable = {
        "first_fill_ms": 1000,
        "position_boundary_initialized": True,
        "highest": "100",
        "lowest": "100",
    }
    repo = SimpleNamespace(
        managed_positions=lambda _: [row],
        exit_state=lambda _: dict(durable),
        save_management_state=lambda _, value: (durable.clear(), durable.update(value)),
    )
    broker = SimpleNamespace(repo=repo, instance=SimpleNamespace())
    market = Market()
    market.price = D(80)

    def persist_then_fail(*_):
        durable["pending_realtime_exit"] = {"target_remaining": 0, "reason": "protect"}
        raise TimeoutError("cancel result unknown")

    m = LiveManagement(broker, SimpleNamespace(_manage=persist_then_fail), market)
    assert m.tick(2000)
    m.event(book(2001, "100", "100.01"), 2001)
    assert m.state(1)["pending_realtime_exit"]["target_remaining"] == 0
    m.flush()
    assert durable["pending_realtime_exit"]["reason"] == "protect"


def test_live_hard_stop_precedes_policy_state_read_and_write_failure():
    row = {
        "id": 1,
        "contract_code": "ETH-USDT",
        "side": "LONG",
        "remaining_contract_volume": 1,
        "stop_price": "90",
    }

    def fail(*_):
        raise ValueError("holding policy unavailable")

    repo = SimpleNamespace(
        managed_positions=lambda _: [row], exit_state=fail, save_management_state=fail
    )
    broker = SimpleNamespace(repo=repo, instance=SimpleNamespace())
    market = Market()
    market.price = D(80)
    called = []
    m = LiveManagement(
        broker, SimpleNamespace(_manage=lambda *args: called.append(args)), market
    )
    assert m.tick(2000) == [] and len(called) == 1


def test_alias_unmanaged_timeframe_is_not_reported_healthy():
    row = {"id": 7, "timeframe_profile": "SWING_4H"}
    owner = SimpleNamespace(strategy_name="HTX_TREND_V1")
    instance = SimpleNamespace(strategy_name=VERSION)
    repo = SimpleNamespace(managed_positions=lambda who: [row] if who == owner else [])
    broker = SimpleNamespace(repo=repo, instance=instance)
    manager = SimpleNamespace(manage=lambda *_: [], instance=instance)
    m = LiveManagement(broker, manager, Market(), owners=(owner,))
    assert m.tick(45000) == ["position=7:profile has no realtime management worker"]


def test_live_late_receipt_proves_only_postfill_bbo_and_preserves_bar_state(tmp_path):
    from test_realtime_coverage import event
    from htx_trend.realtime.storage import SegmentRecorder
    from htx_trend.research.store import FileEvidenceStore

    row = dict(
        id=1,
        contract_code="ETH-USDT",
        side="LONG",
        remaining_contract_volume=1,
        stop_price="90",
        entry_avg_price="100",
    )
    durable = dict(
        position_boundary_initialized=True,
        first_fill_observed_ms=1500,
        first_fill_bound_ms=1500,
        highest="100",
        lowest="100",
        last_bar=900,
        continuation_last_bar=900,
        continuation_breach_count=1,
    )
    repo = SimpleNamespace(
        managed_positions=lambda _: [row],
        exit_state=lambda _: dict(durable),
        save_management_state=lambda _, value: (durable.clear(), durable.update(value)),
    )
    broker = SimpleNamespace(repo=repo, instance=SimpleNamespace())
    market = Market()
    market.kline_window = lambda *a, **k: SimpleNamespace(
        candles=[SimpleNamespace(open_time=0)]
    )
    calls = []
    m = LiveManagement(
        broker, SimpleNamespace(_manage=lambda *a: calls.append(a)), market
    )
    m.coverage_recorder = SegmentRecorder(tmp_path / "capture")
    store = m.coverage_evidence = FileEvidenceStore(tmp_path / "evidence")
    for at, bid in [(900, 150), (1200, 110), (1400, 105)]:
        b = event(at, bid)
        m.coverage_recorder.append(b)
        m.event(b, at)
    broker.before_recovery()
    durable.update(
        first_fill_ms=1000,
        fill_receipt_status="COMPLETE",
        fill_receipt_ref=store.put("receipt", {}),
        detail_max_fill_price="120",
        detail_min_fill_price="80",
    )
    broker.after_recovery()
    assert m.tick(1500) == []
    assert durable["highest"] == "110" and durable["lowest"] == "100"
    assert durable["extrema_coverage"] == "COMPLETE"
    assert durable["first_fill_bound_ms"] == 1000
    assert durable["last_bar"] == 900 and durable["continuation_breach_count"] == 1
    m.gap()
    b = event(1600, 105)
    m.coverage_recorder.append(b)
    m.event(b, 1600)
    state = m.state(1)
    m._prove_coverage(row, state, 1650)
    assert state["extrema_coverage"] == "GAP"
    market.price = D(80)
    assert m.tick(1700) == [] and len(calls) == 1
    store.close()


def test_live_proof_io_failure_does_not_block_due_trend_exit(tmp_path, monkeypatch):
    from test_realtime_coverage import event
    from htx_trend.realtime.storage import SegmentRecorder
    from htx_trend.research.store import FileEvidenceStore

    row = dict(
        id=1,
        contract_code="ETH-USDT",
        side="LONG",
        remaining_contract_volume=1,
        stop_price="90",
        entry_avg_price="100",
    )
    state = dict(
        first_fill_ms=1000,
        position_boundary_initialized=True,
        highest="100",
        lowest="100",
        fill_receipt_status="COMPLETE",
        last_bar=-900,
    )
    repo = SimpleNamespace(
        managed_positions=lambda _: [row],
        exit_state=lambda _: dict(state),
        save_management_state=lambda _, value: (state.clear(), state.update(value)),
    )
    market = Market()
    market.kline_window = lambda *a, **k: SimpleNamespace(
        candles=[SimpleNamespace(open_time=0)]
    )
    calls = []
    m = LiveManagement(
        SimpleNamespace(repo=repo, instance=SimpleNamespace()),
        SimpleNamespace(_manage=lambda *a: calls.append(dict(state))),
        market,
    )
    m.coverage_recorder = SegmentRecorder(tmp_path / "capture")
    store = m.coverage_evidence = FileEvidenceStore(tmp_path / "evidence")
    state["fill_receipt_ref"] = store.put("receipt", {})
    for at in (900, 1200):
        b = event(at, 105)
        m.coverage_recorder.append(b)
        m.event(b, at)
    monkeypatch.setattr(
        store, "put", lambda *a, **k: (_ for _ in ()).throw(OSError("full"))
    )
    assert m.tick(1500) == []
    assert len(calls) == 1 and calls[0]["extrema_coverage"] == "GAP"
    assert calls[0]["coverage_error"] == "OSError"
    store.close()


def test_partial_entry_average_never_enters_raw_bbo_extrema(tmp_path):
    from test_realtime_coverage import event
    from htx_trend.realtime.storage import SegmentRecorder
    from htx_trend.research.store import FileEvidenceStore

    repo = SimpleNamespace()
    broker = SimpleNamespace(repo=repo, instance=SimpleNamespace())
    m = LiveManagement(broker, SimpleNamespace(), Market())
    store = m.coverage_evidence = FileEvidenceStore(tmp_path / "evidence")
    m.coverage_recorder = SegmentRecorder(tmp_path / "capture")
    row = dict(
        contract_code="ETH-USDT",
        side="LONG",
        entry_avg_price="110",
        entry_filled_contracts="1",
    )
    state = dict(
        first_fill_ms=1000,
        fill_receipt_status="COMPLETE",
        detail_quantity="1",
        fill_receipt_ref=store.put("receipt", {}),
    )
    for at in (900, 1200):
        b = event(at, 100)
        m.coverage_recorder.append(b)
        m.coverage.append(b, at)
    m._prove_coverage(row, state, 1300)
    assert state["highest"] == "110"  # zero-PnL floor, not a realized quote
    assert state["coverage_proof"]["highest"] == "100"
    row.update(entry_avg_price="100", entry_filled_contracts="2")
    state["detail_quantity"] = "2"
    b = event(1400, 100)
    m.coverage_recorder.append(b)
    m.coverage.append(b, 1400)
    m._prove_coverage(row, state, 1500)
    assert state["highest"] == state["coverage_proof"]["highest"] == "100"
    store.close()


def test_actual_decision_rechecks_coverage_after_private_read_delay(monkeypatch):
    from test_position_manager import (
        Repo,
        Market as ExitMarket,
        Client,
        manager,
        position,
    )
    from htx_trend.strategy_context import MarketContext

    repo, market, client = Repo(), ExitMarket(), Client()
    market.price = D(100)
    clock = [144900.0]
    market.clock = lambda: clock[0]
    row = dict(position(), primary_model="RT_RANGE_REJECTION", strategy_name=VERSION)
    repo.state = dict(
        extrema_coverage="COMPLETE",
        detail_quantity="4",
        fill_receipt_status="COMPLETE",
        coverage_proof=dict(epoch="e", continuity_token="t", through_ms=144900000),
    )
    mgr = manager(repo=repo, market=market, client=client)
    mgr.instance = replace(mgr.instance, strategy_name=VERSION)
    m = LiveManagement(SimpleNamespace(repo=repo, instance=mgr.instance), mgr, market)
    m.coverage_stream = SimpleNamespace(connected=True, epoch="e")
    m.coverage_recorder = SimpleNamespace(failure=None)
    m.coverage.tokens["BTC-USDT"] = "t"
    assert m.coverage_valid(row, repo.state)

    def delayed_positions(_):
        clock[0] += 3
        m.coverage_stream.connected = False
        return [{"position_side": "long", "volume": 4}]

    client.positions = delayed_positions
    captured = []
    from htx_trend.exits import decide_v4

    def capture(input_):
        captured.append(input_)
        return decide_v4(input_)

    monkeypatch.setattr("htx_trend.position_manager.decide_v4", capture)
    mgr._manage(row, 144900, MarketContext())
    assert captured and not captured[0].mfe_coverage_complete
