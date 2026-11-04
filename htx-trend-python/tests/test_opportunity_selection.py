from dataclasses import replace
from decimal import Decimal as D
from contextlib import closing

import pytest

from htx_trend.models import TrendSide
from htx_trend.realtime.opportunities import discover
from htx_trend.realtime.plans import OPPORTUNITY, Parameters, create_engine
from htx_trend.realtime.replay import Actor, FrozenMarket
from htx_trend.realtime.selection import Calibration, features, fit
from htx_trend.realtime.storage import PlanStore
from htx_trend.research.store import digest
from test_realtime_plans import trade, book
from test_realtime_replay import tape


def parameters(**changes):
    return Parameters(candidate=OPPORTUNITY, min_net_r=D(0), target_mode="OWN_STRUCTURE", **changes)


def frame():
    data = tape()
    market = FrozenMarket()
    market.observe(data["observations"][0])
    return market.frames["ETH-USDT"], market.now_ms


def test_discovery_is_both_sides_coherent_and_alias_deduplicated():
    f, now = frame()
    audit = []
    plans = discover(f["entry"], f["trend"], f["contract"], now_ms=now,
                     parameters=parameters(), audit=audit)
    assert {p.side for p in plans} == {TrendSide.LONG, TrendSide.SHORT}
    assert len({p.plan_id for p in plans}) == len(plans)
    assert any(p.target_kind == "OWN_BOX_WIDTH_PROJECTION" for p in plans)
    for p in plans:
        assert p.target > p.line+p.band if p.side is TrendSide.LONG else p.target < p.line-p.band
    other = discover(f["entry"], f["trend"], f["contract"], now_ms=now+1,
                     parameters=parameters(selection_variant="BOTH"), audit=[])
    assert {p.plan_id for p in plans} == {p.plan_id for p in other}
    assert all(row["reason"] in {"DISCOVERED", "NO_TARGET", "TARGET_INSIDE_ZONE"} for row in audit)


def test_future_or_gap_frame_does_not_create_opportunities():
    f, now = frame()
    audit = []
    assert not discover(f["entry"], f["trend"], f["contract"], now_ms=now-900000,
                        parameters=parameters(), audit=audit)
    assert audit[0]["reason"] == "STALE_INPUT"
    bad = replace(f["entry"], candles=f["entry"].candles[:-2]+f["entry"].candles[-1:])
    assert not discover(bad, f["trend"], f["contract"], now_ms=now,
                        parameters=parameters(), audit=[])


def test_frame_refresh_freezes_geometry_revision_invalidates_without_new_identity():
    f, now = frame()
    with closing(PlanStore(":memory:")) as store:
        engine = create_engine(store, "test", parameters=parameters())
        plans = engine.refresh_frame(f["entry"], f["trend"], f["contract"], now_ms=now)
        before = {p.plan_id: engine.rows[p.plan_id]["plan"] for p in plans}
        engine.refresh_frame(f["entry"], f["trend"], f["contract"], now_ms=now+10)
        assert {i: r["plan"] for i, r in engine.rows.items()} == before
        bars = f["entry"].candles
        revised = replace(f["entry"], candles=bars[:-1]+(replace(bars[-1], close=D("100.1")),))
        engine.refresh_frame(revised, f["trend"], f["contract"], now_ms=now+20)
        assert all(engine.rows[i]["reason"] == "STRUCTURE_BASIS_REVISED" for i in before)
        restarted = create_engine(store, "test", parameters=parameters())
        restarted.refresh_frame(revised, f["trend"], f["contract"], now_ms=now+30)
        assert all(restarted.rows[i]["phase"] == "INVALIDATED" for i in before)


def test_replay_uses_shared_broker_opens_despite_opposing_legacy_direction():
    data = tape()
    for i, c in enumerate(data["observations"][0]["windows"]["60min"]["candles"]):
        value = D(100)+D(i)/10
        c.update(open=str(value), close=str(value), high=str(value+2), low=str(value-2))
    actor = Actor(data, parameters())
    try:
        for row in data["observations"]:
            actor.step(row)
        result = actor.result()
        assert result["plans"]
        assert result["fills"], result["errors"]
        assert result["fills"][0]["action"] == "OPEN"
        assert result["fills"][0]["side"] == "SHORT"
        ctx = next(iter(actor.ledger.data["contexts"].values()))
        assert ctx["entry_candidate_rule"] == OPPORTUNITY
        assert ctx["holding_policy"]["continuation_version"] == "linked-v1"
        assert ctx["entry_parameters_fingerprint"] == parameters().fingerprint
    finally:
        actor.close()


def samples():
    return [{"opportunity_id": str(i), "status": "CLOSED", "cost_status": "VERIFIED",
             "policy_hash": digest("policy"), "feature_ms": 1, "entry_ms": 2,
             "exit_ms": 10+i, "available_ms": 10+i, "net_r": "2" if i%2 else "-1",
             "features": {"family": "RANGE_REJECTION", "direction": "1", "book": ".2"}}
            for i in range(40)]


def test_calibration_is_empirical_costed_and_point_in_time():
    rows = samples()
    artifact = fit(rows, cutoff_ms=100, policy_hash=digest("policy"))
    model = Calibration(artifact)
    estimate = model.estimate(rows[0]["features"], "BOTH", at_ms=101, policy_hash=digest("policy"))
    assert estimate["win_probability"] == .5
    assert estimate["mean_net_r"] == .5
    assert estimate["wilson95"][0] < .5 < estimate["wilson95"][1]
    with pytest.raises(ValueError):
        model.estimate(rows[0]["features"], "BOTH", at_ms=100, policy_hash=digest("policy"))
    with pytest.raises(ValueError):
        model.estimate(rows[0]["features"], "BOTH", at_ms=101, policy_hash=digest("other policy"))
    artifact["samples"][0]["net_r"] = "999"
    with pytest.raises(ValueError):
        Calibration(artifact)


@pytest.mark.parametrize("change", [{"status": "OPEN"}, {"cost_status": "UNKNOWN"},
                                    {"available_ms": 101}, {"feature_ms": 3}, {"net_r": "NaN"}])
def test_invalid_labels_never_enter_calibration(change):
    rows = samples()
    rows[0].update(change)
    with pytest.raises(ValueError):
        fit(rows, cutoff_ms=100, policy_hash=digest("policy"))


def test_missing_feature_and_small_sample_probability_remain_unknown():
    rows = samples()[:5]
    artifact = fit(rows, cutoff_ms=100, policy_hash=digest("policy"))
    estimate = Calibration(artifact).estimate(rows[0]["features"], "STRUCTURE", at_ms=101,
                                               policy_hash=digest("policy"))
    assert estimate["win_probability"] is None and estimate["n"] == 5
    f, now = frame()
    p = discover(f["entry"], f["trend"], f["contract"], now_ms=now,
                 parameters=parameters(), audit=[])[0]
    from htx_trend.market_data import OrderBook
    out = features(p, {}, OrderBook(((D(99), D(10)),), ((D(100), D(20)),)), now)
    assert out["direction"] is None and out["direction_status"] == "UNKNOWN"


def test_old_parameter_payload_unchanged_and_new_candidate_is_explicit(monkeypatch):
    from htx_trend.realtime.factory import parameters as configured
    default = Parameters()
    assert set(default.payload) == {"band_atr", "min_net_r", "max_age_ms"}
    monkeypatch.setenv("HTX_RT_CANDIDATE", OPPORTUNITY)
    assert configured() == parameters()
    with pytest.raises(ValueError):
        Parameters(min_net_r=D(0))
    from htx_trend.realtime.plans import generate
    from htx_trend.realtime.structures import generate_candidates
    f, now = frame()
    for entry_point in (generate, generate_candidates):
        with pytest.raises(ValueError, match="opportunity"):
            entry_point(f["entry"], f["trend"], f["contract"], now_ms=now, parameters=parameters())


@pytest.mark.parametrize("side", [TrendSide.LONG, TrendSide.SHORT])
@pytest.mark.parametrize("model", ["RT_RANGE_REJECTION", "RT_BREAKOUT_RETEST"])
def test_real_event_confirmation_symmetric_and_late_book_can_confirm(side, model):
    f, now = frame()
    with closing(PlanStore(":memory:")) as store:
        engine = create_engine(store, "test", parameters=parameters())
        plans = engine.refresh_frame(f["entry"], f["trend"], f["contract"], now_ms=now)
        p = next(p for p in plans if p.side is side and p.model == model)
        engine.rows = {p.plan_id: engine.rows[p.plan_id]}
        s = D(1) if side is TrendSide.LONG else D(-1)
        favorable = p.line+s*2*p.band
        prices = ([favorable, favorable, p.line, favorable, favorable]
                  if model == "RT_BREAKOUT_RETEST" else [favorable, p.line, favorable, favorable])
        for i, price in enumerate(prices, 1):
            event = trade(now+i, price)
            assert not engine.accept(event, now_ms=now+i)
            assert not engine.accept(event, now_ms=now+i)  # duplicate cannot count twice
        at = now+len(prices)+1
        quote = book(at, favorable-D(".01"), favorable+D(".01"))
        triggers = engine.accept(quote, now_ms=at)
        assert len(triggers) == 1
        trigger = triggers[0]
        assert len(set(trigger.trade_refs)) == 2
        assert abs(trigger.reference-trigger.invalidation_price) >= D("1.25")*p.atr
        assert not engine.accept(book(at+1, favorable-D(".01"), favorable+D(".01")), now_ms=at+1)
        engine.disconnect()
        assert engine.rows[p.plan_id]["phase"] == "INVALIDATED"
        assert not engine.accept(trade(at+2, favorable), now_ms=at+2)  # retired epoch


def test_late_pre_activation_quote_cannot_trigger_or_invalidate_target():
    f, now = frame()
    with closing(PlanStore(":memory:")) as store:
        engine = create_engine(store, "test", parameters=parameters())
        plans = engine.refresh_frame(f["entry"], f["trend"], f["contract"], now_ms=now)
        p = next(p for p in plans if p.side is TrendSide.SHORT and p.line == D(110))
        engine.rows = {p.plan_id: engine.rows[p.plan_id]}
        old = replace(book(now-1, p.target-1, p.target), received_ms=now+1)
        assert not engine.accept(old, now_ms=now+1)
        assert engine.rows[p.plan_id]["phase"] == "WAITING"
        fresh = book(now+2, p.target-1, p.target)
        assert not engine.accept(fresh, now_ms=now+2)
        assert engine.rows[p.plan_id]["reason"] == "TARGET_ALREADY_CROSSED"


def test_higher_low_pivots_form_pullback_without_requiring_4h_alignment():
    f, now = frame()
    bars = list(f["entry"].candles)
    for index, low in ((140, D(94)), (150, D(96))):
        bars[index] = replace(bars[index], low=low)
    # Remove the fixture's older extreme so it cannot dominate this local sequence.
    bars[141] = replace(bars[141], low=D(98))
    entry = replace(f["entry"], candles=tuple(bars))
    plans = discover(entry, f["trend"], f["contract"], now_ms=now,
                     parameters=parameters(), audit=[])
    assert any(p.model == "RT_TREND_PULLBACK" and p.line == D(96) for p in plans)
    from htx_trend.realtime.ownership import parse_model, supported_models
    assert parse_model("RT_TREND_PULLBACK") in supported_models()


def test_ablation_uses_identical_discoveries_and_never_promotes_synthetic_data():
    from htx_trend.realtime.opportunity_replay import ablate
    report = ablate(tape())
    assert len(report["groups"]) == 4
    assert len({g["discovery_hash"] for g in report["groups"].values()}) == 1
    assert all(g["result"]["fills"] for g in report["groups"].values())
    for g in report["groups"].values():
        curve = g["result"]["curve"]
        assert D(g["full"]["net"]) == D(curve[-1]["equity"])-D(curve[0]["equity"])
        assert D(g["full"]["net"]) == D(g["diagnostic"]["net"])+D(g["holdout"]["net"])
    assert not report["eligible"] and "ASSUMPTION_ONLY" in report["blockers"]
    assert report["selection_uses_holdout"] is False


def test_entry_off_still_manages_open_position_and_never_reopens():
    data = tape()
    actor = Actor(data, parameters())
    try:
        for row in data["observations"]:
            actor.step(row)
        initial_fills = len(actor.result()["fills"])
        assert initial_fills
        actor.worker.entry_enabled = False
        opened = actor.ledger.active_positions(actor.account)
        assert opened
        context = actor.ledger.position_context(opened[0].position_id)
        stop = D(context["initial_stop"])
        price = stop+1 if opened[0].side == "SHORT" else stop-1
        at = data["end_ms"]+1
        actor.step(dict(kind="DEPTH", received_ms=at, requested_ms=at,
                        contract="ETH-USDT", bids=[[str(price), "10000"]],
                        asks=[[str(price+D(".01")), "10000"]]))
        actor.step(book(at, price, price+D(".01")).dump())
        assert not actor.ledger.active_positions(actor.account)
        assert len(actor.result()["fills"]) > initial_fills
    finally:
        actor.close()


def test_ranking_features_do_not_pretend_to_be_probabilities():
    from types import SimpleNamespace
    from htx_trend.realtime.selection import rank
    a, b = (SimpleNamespace(plan=SimpleNamespace(plan_id=i)) for i in ("a", "b"))
    ready = [(D(2), a), (D(2), b)]
    decisions = {
        "a": {"features": {"direction": "-1", "book": "-.2"}, "estimate": {"mean_net_r": None}},
        "b": {"features": {"direction": "1", "book": ".2"}, "estimate": {"mean_net_r": None}},
    }
    assert rank(ready, decisions, "STRUCTURE")[0][0][1] is a
    assert rank(ready, decisions, "BOTH")[0][0][1] is b
    decisions["a"]["estimate"]["mean_net_r"] = 9
    assert rank(ready, decisions, "BOTH")[1] == "UNCALIBRATED_NET_R"
    decisions["b"]["estimate"]["mean_net_r"] = .1
    ordered, basis = rank(ready, decisions, "BOTH")
    assert ordered[0][1] is a and basis == "EMPIRICAL_MEAN_NET_R"


def test_ranking_evidence_failure_blocks_new_risk_without_breaking_management(monkeypatch):
    data = tape()
    actor = Actor(data, parameters())
    original = actor.evidence.put
    calls = []
    manage = actor.worker.management.tick
    def counted(*a, **kw):
        calls.append(1)
        return manage(*a, **kw)
    def failing(kind, payload, **kwargs):
        if kind == "opportunity_ranking":
            raise OSError("evidence disk full")
        return original(kind, payload, **kwargs)
    monkeypatch.setattr(actor.evidence, "put", failing)
    monkeypatch.setattr(actor.worker.management, "tick", counted)
    try:
        for row in data["observations"]:
            actor.step(row)
        result = actor.result()
        assert calls and not result["fills"]
        assert any("evidence disk full" in r.get("reason", "") for r in result["plans"].values())
    finally:
        actor.close()


def test_pullback_model_executes_and_reaches_normal_exit_management():
    import copy
    data = tape()
    frame_row = data["observations"][0]
    bars = frame_row["windows"]["15min"]["candles"]
    bars[141].update(high="102", low="98")
    bars[135]["high"] = "112"
    bars[149]["high"] = "108"
    mapping = {"110": "108", "108.9": "106.9", "108.8": "106.8"}
    for row in data["observations"]:
        if row["kind"] == "TRADE" and row["price"] in mapping:
            row["price"] = mapping[row["price"]]
        if row["kind"] == "BBO":
            for field in ("bid", "ask"):
                if D("108.7") <= D(row[field]) <= D(110):
                    row[field] = str(D(row[field])-2)
        if row["kind"] == "DEPTH":
            for field in ("bids", "asks"):
                for level in row[field]:
                    if D("108.7") <= D(level[0]) <= D(110):
                        level[0] = str(D(level[0])-2)
    actor = Actor(data, parameters())
    try:
        for row in data["observations"]:
            actor.step(row)
        result = actor.result()
        assert result["fills"] and result["fills"][0]["model"] == "RT_TREND_PULLBACK"
        now = frame_row["received_ms"]+900000
        actor.step(dict(kind="DEPTH", received_ms=now, requested_ms=now,
                        contract="ETH-USDT", bids=[["103", "10000"]], asks=[["103.01", "10000"]]))
        new = copy.deepcopy(frame_row)
        new["received_ms"] = now
        entry = new["windows"]["15min"]
        entry["received_ms"] = now
        last = entry["candles"][-1]
        entry["candles"] = entry["candles"][1:] + [{**last, "open_time": last["open_time"]+900}]
        actor.step(new)
        assert not any("ValueError" in r["detail"] for r in actor.result()["errors"])
        decisions = [d for d in actor.ledger.data["decisions"] if d["kind"] == "EXIT_DECISION"]
        assert decisions
        assert all("unsupported" not in d["payload"].get("reason", "") for d in decisions)
        assert next(iter(actor.ledger.data["contexts"].values()))["last_bar"] == last["open_time"]+900
    finally:
        actor.close()
