from dataclasses import replace
from decimal import Decimal as D
import pytest
from htx_trend.market_data import (
    Candle,
    ContractSpec,
    KlineWindow,
    OrderBook,
    FundingRate,
)
from htx_trend.models import TrendSide
from htx_trend.execution import ExecutionConfig, ExecutionFacts
from htx_trend.realtime.plans import (
    Parameters,
    GRID,
    StructurePlan,
    RealtimeTrigger,
    TriggerEngine,
    generate,
)
from htx_trend.realtime.feed import MarketEvent
from htx_trend.realtime.storage import PlanStore
from htx_trend.realtime.risk import evaluate


def plan(model="RT_RANGE_REJECTION", side=TrendSide.SHORT):
    return StructurePlan(
        "p",
        "ETH-USDT",
        model,
        side,
        D(100),
        D(1),
        D(2),
        D(".01"),
        D(90) if side is TrendSide.SHORT else D(110),
        "EXISTING_BOUNDARY",
        0,
        0,
        0,
        ("20:HIGH",),
        (),
        Parameters().fingerprint,
        0,
    )


def trade(i, price, epoch="a", at=None):
    return MarketEvent(
        "TRADE",
        "ETH-USDT",
        i if at is None else at,
        i if at is None else at,
        str(i),
        epoch,
        price=D(price),
    )


def book(i, bid, ask, epoch="a"):
    return MarketEvent(
        "BBO",
        "ETH-USDT",
        i,
        i,
        str(i),
        epoch,
        bid=D(bid),
        ask=D(ask),
        bid_size=D(10000),
        ask_size=D(10000),
    )


@pytest.fixture
def engine(tmp_path):
    store = PlanStore(tmp_path / "plans.db")
    engine = TriggerEngine(store, "a")
    engine.refresh("ETH-USDT", [plan()])
    yield engine
    store.close()


def push(engine, event):
    return engine.accept(event, now_ms=event.received_ms)


@pytest.mark.parametrize(
    "side,prices,quote",
    [
        (TrendSide.SHORT, ["98", "100", "98.5", "98.4"], ("98.39", "98.4")),
        (TrendSide.LONG, ["102", "100", "101.5", "101.6"], ("101.6", "101.61")),
    ],
)
def test_rejection_two_distinct_trades_and_symmetric_stop(engine, side, prices, quote):
    engine.rows.clear()
    engine.refresh("ETH-USDT", [plan(side=side)])
    push(engine, book(1, *quote))
    for i, p in enumerate(prices[:-1], 2):
        assert not push(engine, trade(i, p))
    assert not push(engine, trade(4, prices[-2]))  # duplicate cannot confirm
    ts = push(engine, trade(5, prices[-1]))
    assert len(ts) == 1
    t = ts[0]
    assert abs(t.invalidation_price - t.reference) >= D("1.25") * t.plan.atr
    assert len(set(t.trade_refs)) == 2
    assert not push(engine, trade(6, prices[-1]))


def test_breakout_requires_separate_retest(engine):
    engine.rows.clear()
    engine.refresh("ETH-USDT", [plan("RT_BREAKOUT_RETEST")])
    push(engine, book(1, "98.3", "98.4"))
    for i, p in enumerate(["98.5", "98.4", "98.3", "100", "98.5"], 2):
        assert not push(engine, trade(i, p))
    assert len(push(engine, trade(7, "98.4"))) == 1


def test_price_recovers_and_quote_recovers_no_trigger(engine):
    push(engine, book(1, "100", "100.1"))
    for i, p in enumerate(["98", "100", "98.5", "98.4"], 2):
        assert not push(engine, trade(i, p))
    assert engine.rows["p"]["phase"] == "ARMED"
    push(engine, trade(6, "100"))
    assert engine.rows["p"]["count"] == 0


def test_restart_and_gap_require_new_approach(engine):
    push(engine, book(1, "98", "98.01"))
    for i, p in enumerate(["98", "100", "98.5"], 2):
        push(engine, trade(i, p))
    restarted = TriggerEngine(engine.store, "a")
    push(restarted, book(5, "98", "98.01", "b"))
    assert not push(restarted, trade(6, "98.4", "b"))
    assert restarted.rows["p"]["phase"] == "WAITING"


def test_terminal_plan_not_revived_by_refresh_or_restart(engine):
    engine.terminal("p", "REJECTED", reason="NET_R")
    engine.refresh("ETH-USDT", [replace(plan(), created_ms=2000, structure_bar=900)])
    assert engine.rows["p"]["phase"] == "REJECTED"
    assert TriggerEngine(engine.store, "a").rows["p"]["phase"] == "REJECTED"


def test_expired_and_out_of_order_trades_do_not_confirm(engine):
    push(engine, book(1, "98", "98.01"))
    for i, p in enumerate(["98", "100", "98.5"], 2):
        push(engine, trade(i, p))
    assert not engine.accept(trade(5, "98.4"), now_ms=4000)
    assert not push(engine, trade(2, "98.4"))
    assert engine.rows["p"]["count"] == 1


def test_generate_closed_only_stable_anchor_and_no_false_ema_score():
    now = 900 * 80
    entry = tuple(
        Candle(now - (32 - i) * 900, D(100), D(110), D(90), D(100)) for i in range(32)
    )
    hour = tuple(
        Candle(
            now - 60 * 3600 + i * 3600, D(200 - i), D(201 - i), D(199 - i), D(200 - i)
        )
        for i in range(60)
    )
    c = ContractSpec("ETH-USDT", D(".01"), D(".01"), 1)
    ew = KlineWindow("ETH-USDT", "15min", entry, 32)
    tw = KlineWindow("ETH-USDT", "60min", hour, 60)
    ps = generate(ew, tw, c, now_ms=now * 1000)
    assert len(ps) == 2 and all(p.side is TrendSide.SHORT for p in ps)
    assert not hasattr(ps[0], "score")
    assert [p.plan_id for p in ps] == [
        p.plan_id for p in generate(ew, tw, c, now_ms=now * 1000 + 500)
    ]
    assert generate(ew, tw, c, now_ms=now * 1000 - 1) == ()
    assert generate(ew, tw, c, now_ms=now * 1000, signal_side=TrendSide.LONG) == ()
    assert len(GRID) == 6 and len({p.fingerprint for p in GRID}) == 6


def test_ioc_geometry_fees_budget_and_stable_intent():
    p = plan()
    t = RealtimeTrigger(p, 1000, 1000, D("98"), D(101), D(101), ("a", "b"), "book", "a")
    b = OrderBook(((D("98"), D(10000)),), ((D("98.01"), D(10000)),))
    c = ContractSpec("ETH-USDT", D(".01"), D(".01"), 1)
    f = ExecutionFacts(
        actual_taker_fee_rate=D(".0004"),
        available_margin_usdt=D(300),
        account_equity_usdt=D(300),
        leverage=D(5),
        active_position_count=0,
        same_direction_position_count=0,
        reserved_margin_usdt=D(0),
        used_margin_usdt=D(0),
    )
    args = dict(
        now_ms=1100,
        book=b,
        contract=c,
        funding=FundingRate("ETH-USDT", D(0)),
        facts=f,
        instance_key="paper",
    )
    gate, intent = evaluate(t, **args)
    assert gate.allowed and intent.price.worst_acceptable_entry == D("97.80")
    cap = intent.price.worst_acceptable_entry
    slip = D(".2")
    fee = D(".0004")
    target = D(90) + slip
    stop = D(101) + slip
    assert intent.price.expected_net_reward == cap - target - fee * (cap + target)
    assert intent.price.net_risk == stop - cap + fee * (cap + stop)
    assert intent.size.risk_at_stop <= D("1.5")
    assert evaluate(t, **args)[1].client_order_id == intent.client_order_id
    assert (
        evaluate(t, **{**args, "facts": replace(f, reserved_risk_usdt=D("4.49"))})[
            0
        ].allowed
        is False
    )
    assert (
        evaluate(t, **{**args, "facts": replace(f, actual_taker_fee_rate=None)})[
            0
        ].stage
        == "ACCOUNT_FACTS"
    )
    assert (
        evaluate(t, **{**args, "facts": replace(f, contract_open_order=True)})[0].stage
        == "EXPOSURE"
    )


def test_retest_reclaims_opposite_side_is_terminal(engine):
    engine.rows.clear()
    engine.refresh("ETH-USDT", [plan("RT_BREAKOUT_RETEST")])
    push(engine, book(1, "98.3", "98.4"))
    for i, p in enumerate(["98.5", "98.4", "100", "102", "100", "98.5", "98.4"], 2):
        assert not push(engine, trade(i, p))
    assert engine.rows["p"]["phase"] == "INVALIDATED"
    assert engine.rows["p"]["reason"] == "RETEST_FAILED"


def test_unchanged_approach_does_not_rewrite_plan_for_every_trade(engine):
    writes = []
    save = engine.store.save
    engine.store.save = lambda *args: (writes.append(args), save(*args))[-1]
    push(engine, book(1, "98", "98.01"))
    for i in range(2, 1002):
        push(engine, trade(i, "98"))
    assert len(writes) <= 2
