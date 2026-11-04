from dataclasses import replace
from decimal import Decimal as D
from types import SimpleNamespace
import pytest

from htx_trend.market_data import (
    Candle,
    KlineWindow,
    ContractSpec,
    OrderBook,
    FundingRate,
)
from htx_trend.models import TrendSide
from htx_trend.realtime.indicator_union import (
    features,
    matches,
    CANDIDATE,
    MODEL,
    EXITS,
)
from htx_trend.realtime.plans import Parameters, create_engine, StructurePlan
from htx_trend.realtime.storage import PaperPlanStore
from htx_trend.realtime.brokers import PaperBroker, RealtimePaperExchange
from htx_trend.realtime.risk import evaluate
from htx_trend.realtime.management import PaperManagement, LiveManagement
from htx_trend.paper_worker import PaperFileLedger
from htx_trend.execution import ExecutionConfig, ExecutionFacts
from htx_trend.realtime import VERSION
from htx_trend.runtime import PaperTrendRuntime
from htx_trend.continuation import ContinuationConfig
from htx_trend.holding_policy import HoldingPolicy, paper_exit_config
from htx_trend.java_entry_contract import JavaEntryConfig
from htx_trend.timeframes import TimeframeProfile
from test_realtime_plans import book

NOW = 900000000


def frame(last, side=TrendSide.LONG):
    def window(period, step, end):
        candles = []
        for i in range(160):
            close = D(90) + D(i * i) / 10000
            candles.append(
                Candle(
                    end - (159 - i) * step,
                    close - D(".01"),
                    close + D(".01"),
                    close - D(".08"),
                    close,
                    volume=D(100),
                )
            )
        if side is TrendSide.SHORT:
            candles = [
                replace(
                    c,
                    open=D(200) - c.open,
                    high=D(200) - c.low,
                    low=D(200) - c.high,
                    close=D(200) - c.close,
                )
                for c in candles
            ]
        return KlineWindow("ETH-USDT", period, tuple(candles), 160)

    entry = window("15min", 900, last)
    hour = window("60min", 3600, 892800 if last < 899100 else 896400)
    return {
        "entry": entry,
        "trend": hour,
        "windows": {"15min": entry, "60min": hour},
        "contract": ContractSpec("ETH-USDT", D(".01"), D(".001"), 1),
        "received_ms": NOW - 1000,
        "evidence_failed": False,
    }


def policy():
    return HoldingPolicy.create(
        instance_key="p",
        account_id="paper:p",
        strategy_family="TREND",
        strategy_name=VERSION,
        mode="PAPER",
        profile=TimeframeProfile.INTRADAY_15M,
        entry_config=JavaEntryConfig(),
        exit_config=paper_exit_config(),
        continuation_version="linked-v1",
    )


def scenario(tmp_path, exit_mode, side=TrendSide.LONG):
    sign = D(1) if side is TrendSide.LONG else D(-1)
    params = Parameters(min_net_r=D(0), candidate=CANDIDATE, target_mode=exit_mode)
    ledger = PaperFileLedger(
        tmp_path / "p.json",
        {"instance": "p", "strategy": VERSION},
        mirror=lambda _: None,
    )
    engine = create_engine(PaperPlanStore(ledger), "p", parameters=params)
    a = frame(898200, side)
    engine.refresh_union_frame(a, now_ms=NOW - 1000)
    f = features(a["entry"], a["trend"])
    # Prior quote is after previous bar close and before the next frame arrives.
    engine.accept(
        book(
            NOW - 1000,
            str(f["close"] + sign * D(".02")),
            str(f["close"] + sign * D(".02") + D(".001")),
        ),
        now_ms=NOW - 1000,
    )
    b = frame(899100, side)
    b["received_ms"] = NOW
    engine.refresh_union_frame(b, now_ms=NOW)
    f = features(b["entry"], b["trend"])
    bid = (f["close"] + sign * D(".02")).quantize(D(".001"))
    ask = bid + D(".001")
    event = book(NOW + 1, str(bid), str(ask))
    triggers = engine.accept(event, now_ms=NOW + 1)
    assert len(triggers) == 1
    return SimpleNamespace(
        params=params,
        ledger=ledger,
        engine=engine,
        trigger=triggers[0],
        frame=b,
        event=event,
        bid=bid,
        ask=ask,
    )


@pytest.mark.parametrize("side", [TrendSide.LONG, TrendSide.SHORT])
def test_five_pattern_tags_and_directional_symmetry(side):
    sign = D(1) if side is TrendSide.LONG else D(-1)
    f = {
        "close": D(100),
        "open": D(100) - sign,
        "lower": D(2),
        "upper": D(2),
        "macd": sign,
        "cci": sign,
        "channel": sign,
        "cmf": sign,
    }
    bid = D(100) + sign - D(".001")
    ask = bid + D(".002")
    tags = matches(
        f, side, bid, ask, newbar=True, previous=D(100) - sign, previous_ema=D(100)
    )
    assert tags == ("M1", "M2", "M3", "M4", "M5")
    assert matches(f, side, bid, ask, newbar=False) == ()


@pytest.mark.parametrize("mode", EXITS)
@pytest.mark.parametrize("cause", ["target", "stop"])
@pytest.mark.parametrize("side", [TrendSide.LONG, TrendSide.SHORT])
def test_real_paper_ioc_to_frozen_bracket_and_restart(tmp_path, mode, cause, side):
    s = scenario(tmp_path, mode, side)
    sign = D(1) if side is TrendSide.LONG else D(-1)
    assert set(s.engine.rows[s.trigger.plan.plan_id]["matched_modes"]) >= {
        "M1",
        "M2",
        "M3",
        "M5",
    }
    assert StructurePlan.load(s.trigger.plan.dump()) == s.trigger.plan
    config = replace(
        ExecutionConfig(),
        min_average_turnover=D(0),
        max_risk_per_trade_usdt=D(8),
        account_equity_usdt=D(777),
    )
    facts = ExecutionFacts(
        actual_taker_fee_rate=D(".0004"),
        account_equity_usdt=D(777),
        available_margin_usdt=D(777),
        leverage=D(3),
        active_position_count=0,
        same_direction_position_count=0,
        reserved_margin_usdt=D(0),
        used_margin_usdt=D(0),
    )
    depth = OrderBook(((s.bid, D(100000)),), ((s.ask, D(100000)),))
    gate, intent = evaluate(
        s.trigger,
        now_ms=NOW + 1,
        book=depth,
        contract=s.frame["contract"],
        funding=FundingRate("ETH-USDT", D(0)),
        facts=facts,
        instance_key="p",
        parameters=s.params,
        config=config,
    )
    assert gate.allowed, gate
    assert (
        intent.model == MODEL
        and intent.exit_policy_ref == "INDICATOR_BRACKET_V1/" + mode
    )
    broker = PaperBroker(s.ledger, s.engine, "paper:p", latency_ms=250)
    assert (
        broker.submit(
            intent,
            s.trigger,
            s.frame["contract"],
            now_ms=NOW + 1,
            fee_rate=D(".0004"),
            holding_parameters=policy().to_dict(),
        )
        == "SUBMITTED"
    )
    # Only a later actual book fills IOC. Fraction of requested quantity survives.
    ids = broker.on_book(
        replace(
            s.event,
            received_ms=NOW + 300,
            exchange_ms=NOW + 300,
            event_id="fill",
            ask_size=D(1),
            bid_size=D(1),
        ),
        now_ms=NOW + 300,
    )
    assert len(ids) == 1 and s.ledger.position(ids[0]).quantity == 1
    ctx = s.ledger.position_context(ids[0])
    assert ctx["matched_modes"] and ctx["initial_stop"] == str(
        intent.price.invalidation
    )
    params2 = Parameters(min_net_r=D(0), candidate=CANDIDATE, target_mode="ATR_2_2")
    engine2 = create_engine(PaperPlanStore(s.ledger), "p", parameters=params2)
    assert engine2.rows[s.trigger.plan.plan_id]["phase"] == "PARTIAL"
    # New config cannot alter this holding's bracket.
    exit_px = (
        intent.price.structure_target + sign * D(".01")
        if cause == "target"
        else intent.price.invalidation - sign * D(".01")
    )
    market = SimpleNamespace(
        order_book=lambda _: OrderBook(
            ((exit_px, D(100)),), ((exit_px + D(".001"), D(100)),)
        )
    )
    runtime = PaperTrendRuntime(
        s.ledger,
        RealtimePaperExchange(s.ledger),
        account_id="paper:p",
        strategy_version=VERSION,
        continuation_config=ContinuationConfig("linked-v1"),
        holding_policy=policy(),
        require_holding_policy=True,
        evidence_context={"instance": "p", "timeframe_profile": "INTRADAY_15M"},
    )
    management = PaperManagement(
        runtime,
        broker,
        {},
        market,
        exit_config=paper_exit_config(),
        entry_config=JavaEntryConfig(),
    )
    assert management.tick(NOW + 400) == []
    assert not s.ledger.active_positions("paper:p")
    fills = len(s.ledger.data["fills"])
    assert management.tick(NOW + 500) == []
    assert len(s.ledger.data["fills"]) == fills
    s.ledger.close()


def test_duplicate_revision_restart_and_no_historical_trigger(tmp_path):
    s = scenario(tmp_path, "ATR_1_1")
    ident = s.trigger.plan.plan_id
    assert not s.engine.accept(s.event, now_ms=NOW + 1)
    s.engine.refresh_union_frame(s.frame, now_ms=NOW + 2)
    assert not s.engine.accept(
        replace(s.event, event_id="next", received_ms=NOW + 3, exchange_ms=NOW + 3),
        now_ms=NOW + 3,
    )
    restart = create_engine(PaperPlanStore(s.ledger), "p", parameters=s.params)
    assert restart.rows[ident]["phase"] == "REJECTED"
    restart.refresh_union_frame(s.frame, now_ms=NOW + 4)
    assert not restart.accept(
        replace(s.event, event_id="restart", received_ms=NOW + 5, exchange_ms=NOW + 5),
        now_ms=NOW + 5,
    )
    s.ledger.close()


def test_union_parameters_explicit_and_profile(monkeypatch):
    from htx_trend.realtime.factory import parameters
    from htx_trend.strategy_profiles import profile_strategy

    monkeypatch.setenv("HTX_RT_CANDIDATE", CANDIDATE)
    monkeypatch.delenv("HTX_UNION_EXIT", raising=False)
    with pytest.raises(ValueError):
        parameters()
    for mode in EXITS:
        monkeypatch.setenv("HTX_UNION_EXIT", mode)
        assert parameters().target_mode == mode
        p = profile_strategy(TimeframeProfile.INTRADAY_15M, strategy_version=VERSION)
        assert p.primary_models == (MODEL,) and p.exit_version == "INDICATOR_BRACKET_V1"
    with pytest.raises(ValueError):
        profile_strategy(TimeframeProfile.SWING_4H, strategy_version=VERSION)


def test_live_target_latches_intent_and_continues_after_price_recovery():
    row = {
        "id": 1,
        "contract_code": "ETH-USDT",
        "side": "LONG",
        "primary_model": MODEL,
        "remaining_contract_volume": 2,
        "stop_price": "90",
    }
    state = {
        "exit_policy_ref": "INDICATOR_BRACKET_V1/ATR_1_1",
        "structure_target": "110",
    }
    repo = SimpleNamespace(
        managed_positions=lambda _: [row],
        exit_state=lambda _: dict(state),
        save_management_state=lambda _, v: state.update(v),
    )
    broker = SimpleNamespace(repo=repo, instance=SimpleNamespace())
    price = [D(111)]
    calls = []
    market = SimpleNamespace(
        order_book=lambda _: OrderBook(
            ((price[0], D(10)),), ((price[0] + D(".01"), D(10)),)
        )
    )

    def manage(*_):
        assert state["pending_realtime_exit"]["target_remaining"] == 0
        calls.append(dict(state["pending_realtime_exit"]))

    m = LiveManagement(broker, SimpleNamespace(_manage=manage), market)
    assert m.tick(1000) == []
    price[0] = D(100)
    assert m.tick(2000) == [] and len(calls) == 2 and calls[0] == calls[1]
    price[0] = D(80)
    assert m.tick(3000) == [] and calls[2] == calls[0]


def test_old_exact_holding_policy_accepted_unknown_hash_rejected():
    from htx_trend.realtime.union_compatibility import UNION_PREDECESSOR
    from htx_trend.holding_policy import digest

    raw = policy().to_dict()
    raw["implementation_hashes"] = UNION_PREDECESSOR
    raw["hash"] = digest({k: v for k, v in raw.items() if k != "hash"})
    assert HoldingPolicy.load(raw)
    raw["implementation_hashes"] = dict(UNION_PREDECESSOR, **{"models.py": "0" * 64})
    raw["hash"] = digest({k: v for k, v in raw.items() if k != "hash"})
    with pytest.raises(ValueError):
        HoldingPolicy.load(raw)


@pytest.mark.parametrize("mode", EXITS)
def test_factory_worker_open_and_close_with_explicit_profile(
    tmp_path, monkeypatch, mode
):
    from htx_trend.mysql_store import RuntimeInstance
    from htx_trend.realtime.factory import paper
    from htx_trend.research.store import FileEvidenceStore

    monkeypatch.setenv("HTX_RT_CANDIDATE", CANDIDATE)
    monkeypatch.setenv("HTX_UNION_EXIT", mode)
    monkeypatch.setenv("HTX_RT_CAPTURE_DIR", str(tmp_path / "capture"))
    monkeypatch.setenv("HTX_RT_ENTRY_ENABLED", "true")
    px = [D("92.548")]
    clock = [NOW - 1000]
    events = []
    market = SimpleNamespace(
        order_book=lambda _: OrderBook(
            ((px[0], D(100000)),), ((px[0] + D(".001"), D(100000)),)
        ),
        funding_rate=lambda _: FundingRate("ETH-USDT", D(0)),
    )
    evidence = FileEvidenceStore(tmp_path / "evidence", compress=True)
    w = paper(
        RuntimeInstance(
            "p", "TREND", VERSION, None, "paper", "PAPER", True, "test", None
        ),
        market,
        tmp_path / "state",
        evidence,
    )
    w.broker.ledger.mirror = lambda _: None
    w.execution = replace(
        w.execution, min_average_turnover=D(0), account_equity_usdt=D(777)
    )
    w.clock = lambda: clock[0] / 1000
    w.management.market.clock = w.clock
    w.stream = SimpleNamespace(
        connected=True,
        epoch="a",
        drain=lambda: [events.pop(0) for _ in range(len(events))],
        close=lambda: None,
    )
    w.loader = SimpleNamespace(errors={}, close=lambda: None)
    w.started = True
    w.last_epoch = "a"
    w.frames["ETH-USDT"] = frame(898200)
    events.append(book(clock[0], str(px[0]), str(px[0] + D(".001"))))
    w.tick()
    clock[0] = NOW + 1
    w.frames["ETH-USDT"] = frame(899100)
    events.append(book(clock[0], str(px[0]), str(px[0] + D(".001"))))
    health = w.tick()
    assert len(w.broker.ledger.data.get("realtime_pending", {})) == 1, health
    clock[0] = NOW + 300
    events.append(book(clock[0], str(px[0]), str(px[0] + D(".001"))))
    w.tick()
    holdings = w.broker.ledger.active_positions("paper:p")
    assert len(holdings) == 1
    context = w.broker.ledger.position_context(holdings[0].position_id)
    assert context["exit_policy_ref"] == "INDICATOR_BRACKET_V1/" + mode
    w.entry_enabled = False
    px[0] = D(context["structure_target"]) + D(".01")
    clock[0] = NOW + 500
    events.append(book(clock[0], str(px[0]), str(px[0] + D(".001"))))
    w.tick()
    assert not w.broker.ledger.active_positions("paper:p")
    assert len(w.broker.ledger.data["orders"]) == 2
    w.stop()
    evidence.close()


@pytest.mark.parametrize(
    "failure", ["stale", "reconnect", "reorder", "duplicate", "missing_frame"]
)
def test_no_trigger_across_invalid_observation(tmp_path, failure):
    s = scenario(tmp_path, "ATR_1_1")
    s.engine.disconnect()
    warm = replace(s.event, event_id="warm", received_ms=NOW + 10, exchange_ms=NOW + 10)
    assert not s.engine.accept(warm, now_ms=NOW + 10)
    test = replace(warm, event_id="new", received_ms=NOW + 11, exchange_ms=NOW + 11)
    now = NOW + 11
    if failure == "stale":
        now += 2001
    elif failure == "reconnect":
        test = replace(test, epoch="newconnection")
    elif failure == "reorder":
        test = replace(test, exchange_ms=NOW + 9)
    elif failure == "duplicate":
        test = replace(test, event_id="warm")
    else:
        s.engine.frames.clear()
    assert not s.engine.accept(test, now_ms=now)
    assert len(s.engine.rows) == 1
    s.ledger.close()


@pytest.mark.parametrize("failure", ["unknown", "zero"])
def test_union_ioc_no_retry_after_unknown_or_zero(tmp_path, failure):
    s = scenario(tmp_path, "ATR_1_1")
    # Use the same actual gate as production, without fabricated model identity.
    facts = ExecutionFacts(
        actual_taker_fee_rate=D(".0004"),
        account_equity_usdt=D(777),
        available_margin_usdt=D(777),
        leverage=D(3),
        active_position_count=0,
        same_direction_position_count=0,
        reserved_margin_usdt=D(0),
        used_margin_usdt=D(0),
    )
    config = replace(
        ExecutionConfig(), min_average_turnover=D(0), account_equity_usdt=D(777)
    )
    gate, intent = evaluate(
        s.trigger,
        now_ms=NOW + 1,
        book=OrderBook(((s.bid, D(100000)),), ((s.ask, D(100000)),)),
        contract=s.frame["contract"],
        funding=FundingRate("ETH-USDT", D(0)),
        facts=facts,
        instance_key="p",
        parameters=s.params,
        config=config,
    )
    assert gate.allowed
    broker = PaperBroker(s.ledger, s.engine, "paper:p")

    def submit():
        return broker.submit(
            intent,
            s.trigger,
            s.frame["contract"],
            now_ms=NOW + 1,
            fee_rate=D(".0004"),
            holding_parameters=policy().to_dict(),
        )

    assert submit() == "SUBMITTED"
    if failure == "unknown":
        broker.recover()
    else:
        assert not broker.on_book(book(NOW + 300, "100", "100.001"), now_ms=NOW + 300)
    assert submit() == "UNKNOWN"
    assert len(s.ledger.data["orders"]) == 1 and not s.ledger.active_positions(
        "paper:p"
    )
    s.ledger.close()


def test_live_bracket_actual_manager_cancel_unknown_partial_and_complete():
    from test_position_manager import Repo, Market, Executor, Client, position, manager
    from htx_trend.holding_policy import HoldingPolicy

    repo = Repo()
    market = Market()
    network = Executor()
    client = Client()
    row = {**position(), "primary_model": MODEL}
    repo.managed_positions = lambda _: [row] if row["remaining_contract_volume"] else []
    repo.save_management_state = lambda _, state: setattr(repo, "state", dict(state))
    m = manager(repo=repo, market=market, executor=network, client=client)
    m.require_holding_policy = True
    hp = HoldingPolicy.create(
        instance_key="p",
        account_id=7,
        strategy_family="TREND",
        strategy_name=m.instance.strategy_name,
        mode="LIVE",
        profile=TimeframeProfile.INTRADAY_15M,
        entry_config=JavaEntryConfig(),
        exit_config=m.config,
    )
    repo.state = {
        "holding_policy": hp.to_dict(),
        "exit_policy_ref": "INDICATOR_BRACKET_V1/ATR_2_1",
        "structure_target": "110",
    }
    live = LiveManagement(SimpleNamespace(repo=repo, instance=m.instance), m, market)
    market.price = D(111)
    repo.pending = [
        dict(
            position_id=1, leg="ENTRY", status="PARTIALLY_FILLED", exchange_order_id=77
        )
    ]
    assert live.tick(NOW) == []
    assert network.cancelled == [("BTC-USDT", "77")] and not network.sent
    # Target intent survives recovery and retracement; only remaining quantity closes.
    market.price = D(100)
    repo.pending = []
    live = LiveManagement(SimpleNamespace(repo=repo, instance=m.instance), m, market)
    assert live.tick(NOW + 1000) == []
    assert (
        len(network.sent) == 1
        and network.sent[0].volume == "4"
        and network.sent[0].reduce_only
    )
    repo.pending = [dict(position_id=1, leg="CLOSE", status="UNKNOWN")]
    assert live.tick(NOW + 2000) and len(network.sent) == 1
    repo.pending = []
    row["remaining_contract_volume"] = 2
    client.volume = 2
    assert live.tick(NOW + 3000) == []
    assert (
        len(network.sent) == 2
        and network.sent[1].volume == "2"
        and network.sent[1].reduce_only
    )
    row["remaining_contract_volume"] = 0
    client.volume = 0
    assert live.tick(NOW + 4000) == [] and len(network.sent) == 2


@pytest.mark.parametrize('mode', EXITS)
@pytest.mark.parametrize('live', [False, True])
def test_union_package_binds_exit_spec_and_disables_entry(tmp_path, mode, live):
    import importlib.util
    import json
    from pathlib import Path
    import tarfile
    path = Path(__file__).parents[1] / 'release/package.py'
    spec = importlib.util.spec_from_file_location('union_package_test', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    builder = module.build_live if live else module.build
    archive = builder(tmp_path, union_exit=mode)
    module.verify(archive)
    with tarfile.open(archive) as tar:
        cfg = json.load(tar.extractfile('release/config.example.json'))
    assert cfg['models'] == [MODEL]
    assert cfg['exit_policy'] == 'INDICATOR_BRACKET_V1/' + mode
    assert cfg['runtime_env_required']['HTX_UNION_EXIT'] == mode
    assert cfg['entry_enabled'] is False
    assert cfg['financial_admission'] == 'NOT_ELIGIBLE'
    assert 'account_equity_usdt' not in cfg
