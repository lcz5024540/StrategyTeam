import pytest


@pytest.fixture(autouse=True)
def authorized_boundary(monkeypatch):
    # Downstream gate unit tests assume authorization has already succeeded.
    # G2 integration tests call the real guard without this module fixture.
    monkeypatch.setattr("htx_trend.admission.runtime_guard", lambda *args: None)


from decimal import Decimal

from htx_trend.account_facts import AccountSnapshot
from htx_trend.execution import ExecutionConfig, ExecutionFacts
from htx_trend.execution_service import HtxTrendEntryExecutor
from htx_trend.market_data import Candle, ContractSpec, FundingRate, OrderBook
from htx_trend.mirror import ConfirmedPrimaryFill, HtxTrendMirrorFollower
from htx_trend.models import ModelCandidate, ModelCode, PriceStructure, TrendSide
from htx_trend.mysql_store import AccountRiskLimit, CapitalAllocation, RiskReservationTotals, RuntimeInstance, TradeAccount, StrategyPolicyRecord
from htx_trend.strategy_context import MarketContext, MarketRegime
from htx_trend.strategy_admission import DecisionSurface, ResearchStage, StageEvidence, StageStatus, build_artifact


def candidate() -> ModelCandidate:
    structure = PriceStructure(Decimal("110"), Decimal("90"), Decimal("110"), Decimal("90"), Decimal("97.64"), Decimal("102.36"))
    return ModelCandidate(ModelCode.DONCHIAN_BREAKOUT, TrendSide.LONG, 9_000, Decimal("95"), Decimal("100"),
                          Decimal("95"), Decimal("110"), Decimal("110"), Decimal("2"), structure,
                          ("donchian",), structure_key="DONCHIAN_BREAKOUT:LONG:9000:9000")


class Repository:
    def __init__(self): self.calls = []
    def capital_allocation(self, **_): return CapitalAllocation(7, "primary", "TREND", "HTX_TREND_V1", Decimal("100"), 2, None)
    def enabled_strategy_policies(self):
        return [StrategyPolicyRecord("DONCHIAN_BOX", "combo-v2", "DONCHIAN_BREAKOUT", "BOX_BREAKOUT",
                                     Decimal("100"), Decimal("100"), Decimal("85"), 32)]
    def account_risk_limit(self, *_): return AccountRiskLimit(7, "primary", Decimal("200"), 2, None)
    def active_risk_reservation_totals(self, *_): return RiskReservationTotals(Decimal("0"), 0)
    def active_position_count(self, **_): return 0
    def active_direction_position_count(self, **_): return 0
    def active_contract_position(self, **_): return None
    def claim_entry_cycle(self, **_): return True
    def attach_entry_cycle_intent(self, **_): pass
    def abandon_entry_cycle(self, **_): pass
    def record_position_intent(self, value): self.calls.append(("position", value)); return 7
    def reserve_risk(self, **value): self.calls.append(("reserve", value))
    def record_order_intent(self, value): self.calls.append(("order", value)); return 8
    def mark_order_accepted(self, **value): self.calls.append(("accepted", value))
    def mark_position_status(self, **value): self.calls.append(("position_status", value))
    def mark_order_rejected(self, **value): self.calls.append(("rejected", value))
    def release_risk(self, *_): self.calls.append(("release", None))
    def mark_order_unknown(self, **value): self.calls.append(("unknown", value))
    def apply_exchange_order(self, **value): self.calls.append(("atomic_fill", value))
    def reconcile_order(self, **value): self.calls.append(("reconcile", value))
    def record_ledger_event(self, value): self.calls.append(("ledger", value))


class Facts:
    def snapshot(self, *_args, **_kwargs):
        # Java policy-notional sizing requests 100 contracts here; the Java
        # depth gate requires three times that executable depth.
        return AccountSnapshot(ExecutionFacts(actual_taker_fee_rate=Decimal("0.0004"), executable_depth_contracts=Decimal("500"),
            available_margin_usdt=Decimal("100"), leverage=Decimal("3"), active_position_count=0, max_active_positions=2,
            risk_capacity_usdt=Decimal("200")), "HTX_ACCOUNT", Decimal("100"), 0, Decimal("0"), Decimal("0"))


class FailingFacts(Facts):
    def snapshot(self, *_args, **_kwargs):
        raise RuntimeError("V5 account balance unavailable")


class Executor:
    def __init__(self): self.calls = []
    def set_leverage(self, *values): self.calls.append(("leverage", values)); return {}
    def place(self, request): self.calls.append(("place", request)); return {"order_id": "99", "client_order_id": request.client_order_id}
    def order(self, *_args, **_kwargs): return {"order_id": "99", "status": "filled", "trade_volume": "2", "trade_avg_price": "100", "fee": "0.01"}


def executor(repo: Repository, live: Executor, *, strategy_name="HTX_TREND_V1", admission=None,
             execution=None) -> HtxTrendEntryExecutor:
    return HtxTrendEntryExecutor(repository=repo, instance=RuntimeInstance("python-main", "TREND", strategy_name, 7, "primary", "LIVE", True, "unit", None),
        account=TradeAccount(7, "primary", "PRIMARY", True, False, "https://example.test"), facts=Facts(), executor=live,
        execution=execution or ExecutionConfig(min_score=Decimal("70"), min_net_r=Decimal("0"), min_notional=Decimal("1"), target_notional=Decimal("100"), max_notional=Decimal("100")),
        admission=admission)


def full_admission():
    digest="a"*64
    return build_artifact(strategy_id="htx-trend",strategy_version="HTX_TREND_V2",
        experiment_id="EXP-20261011-002",source_commit="test",policy_hash=digest,config_hash=digest,
        dataset_manifest_hash=digest,stages=tuple(StageEvidence(stage,StageStatus.PASS,f"e/{stage.name}",digest,"passed")
            for stage in ResearchStage),allowed_surfaces=(DecisionSurface.RANKING,DecisionSurface.GATE,DecisionSurface.SIZING),
        failure_conditions=("negative expectancy",),pause_conditions=("HTX state unknown",))


def test_entry_writes_intent_reserves_then_submits_java_market_order() -> None:
    repo, live = Repository(), Executor()
    bars = (Candle(9_000, Decimal("99"), Decimal("101"), Decimal("98"), Decimal("100"), turnover=Decimal("1000")),)
    result = executor(repo, live).submit(run_id="run", candidate=candidate(), period="15min", atr=Decimal("2"),
        ema_fast=Decimal("101"), ema_slow=Decimal("99"), adx=Decimal("30"), recent_candles=bars, book=OrderBook(((Decimal("99.99"), Decimal("100")),), ((Decimal("100.01"), Decimal("100")),)),
        contract=ContractSpec("BTC-USDT", Decimal("0.01"), Decimal("0.1"), 1),
        funding=FundingRate("BTC-USDT", Decimal("0.0001")), market_context=MarketContext(MarketRegime.BROAD_UP, TrendSide.LONG, Decimal("0.8")), now_epoch=10_000)
    assert result.accepted and result.exchange_order_id == 99
    assert [name for name, _ in repo.calls[:2]] == ["position", "order"]
    assert live.calls[0][0] == "leverage"
    assert live.calls[1][1].type == "market"
    intent = next(value for name, value in repo.calls if name == 'order')
    assert intent.order_px == Decimal('100') and intent.order_px_source == 'mid'
    assert intent.signal_px == candidate().entry_price
    assert intent.signal_ts.timestamp() == candidate().bar_time
    assert intent.order_ts is None and intent.cancel_count is None and intent.amend_count is None
    assert next(value for name, value in repo.calls if name == 'accepted')['order_ts'] is not None


def test_account_preflight_failure_is_a_rejection_and_does_not_escape_worker() -> None:
    repo, live = Repository(), Executor()
    entry = HtxTrendEntryExecutor(repository=repo,
        instance=RuntimeInstance("python-main", "TREND", "HTX_TREND_V1", 7, "primary", "LIVE", True, "unit", None),
        account=TradeAccount(7, "primary", "PRIMARY", True, False, "https://example.test"),
        facts=FailingFacts(), executor=live,
        execution=ExecutionConfig(min_score=Decimal("70"), min_net_r=Decimal("0"), min_notional=Decimal("1"),
                                  target_notional=Decimal("100"), max_notional=Decimal("100")))
    bars = (Candle(9_000, Decimal("99"), Decimal("101"), Decimal("98"), Decimal("100"), turnover=Decimal("1000")),)
    result = entry.submit(run_id="run", candidate=candidate(), period="15min", atr=Decimal("2"),
        ema_fast=Decimal("101"), ema_slow=Decimal("99"), adx=Decimal("30"), recent_candles=bars,
        book=OrderBook(((Decimal("99.99"), Decimal("100")),), ((Decimal("100.01"), Decimal("100")),)),
        contract=ContractSpec("BTC-USDT", Decimal("0.01"), Decimal("0.1"), 1),
        funding=FundingRate("BTC-USDT", Decimal("0.0001")),
        market_context=MarketContext(MarketRegime.BROAD_UP, TrendSide.LONG, Decimal("0.8")), now_epoch=10_000)
    assert not result.accepted
    assert result.gate.stage == "ACCOUNT_PREFLIGHT"
    assert "V5 account balance unavailable" in result.reason
    assert not live.calls and not repo.calls


def test_new_strategy_requires_admission_and_uses_risk_first_sizing() -> None:
    repo, live = Repository(), Executor()
    bars=(Candle(9000,Decimal(99),Decimal(101),Decimal(98),Decimal(100),turnover=Decimal(1000)),)
    common=dict(run_id="run",candidate=candidate(),period="15min",atr=Decimal(2),ema_fast=Decimal(101),
        ema_slow=Decimal(99),adx=Decimal(30),recent_candles=bars,
        book=OrderBook(((Decimal("99.99"),Decimal(100)),),((Decimal("100.01"),Decimal(100)),)),
        contract=ContractSpec("BTC-USDT",Decimal(".01"),Decimal(".1"),1),
        funding=FundingRate("BTC-USDT",Decimal(".0001")),
        market_context=MarketContext(MarketRegime.BROAD_UP,TrendSide.LONG,Decimal(".8")),now_epoch=10000)
    blocked=executor(repo,live,strategy_name="HTX_TREND_V2").submit(**common)
    assert not blocked.accepted and blocked.gate.stage=="STRATEGY_ADMISSION"
    execution=ExecutionConfig(min_score=Decimal("70"),min_net_r=Decimal("0"),min_notional=Decimal("1"),
        target_notional=Decimal("100"),max_notional=Decimal("100"),risk_per_trade=Decimal("1"))
    accepted=executor(repo,live,strategy_name="HTX_TREND_V2",admission=full_admission(),execution=execution).submit(**common)
    assert accepted.accepted
    assert [request for name,request in live.calls if name=="place"][-1].volume=="19"


def test_reconciliation_only_marks_fill_after_exchange_order_response() -> None:
    repo, live = Repository(), Executor()
    status = executor(repo, live).reconcile(client_order_id="1", contract_code="BTC-USDT", position_id=7, leg="ENTRY",
                                             factor_snapshot_id=None, model="DONCHIAN_BREAKOUT", contract_size=Decimal("0.01"))
    assert status == "FILLED"
    assert [name for name, _ in repo.calls] == ["atomic_fill"]
    assert repo.calls[0][1]["filled_contracts"] == Decimal("2")


def test_mirror_follows_only_a_confirmed_primary_fill() -> None:
    repo, live = Repository(), Executor()
    follower = HtxTrendMirrorFollower(repository=repo,
        instance=RuntimeInstance("python-mirror", "TREND", "HTX_TREND_V1", 7, "primary", "LIVE", True, "unit", None),
        account=TradeAccount(7, "primary", "MIRROR", True, True, "https://example.test"), facts=Facts(), executor=live)
    result = follower.sync_entry(ConfirmedPrimaryFill(1, "primary-key", "run", "BTC-USDT", TrendSide.LONG, 2,
        Decimal("100"), Decimal("0.01"), "DONCHIAN_BREAKOUT", None),
        OrderBook(((Decimal("99.99"), Decimal("100")),), ((Decimal("100.01"), Decimal("100")),)),
        ContractSpec("BTC-USDT", Decimal("0.01"), Decimal("0.1"), 1))
    assert result.accepted and result.mirrored_contracts == 2
    assert [name for name, _ in repo.calls[:3]] == ["position", "reserve", "order"]
    intent = next(value for name, value in repo.calls if name == 'order')
    assert intent.order_px == Decimal('100') and intent.order_px_source == 'mid'
    assert intent.signal_px is None and intent.signal_ts is None
    assert intent.cancel_count is None and intent.amend_count is None


def test_entry_refuses_candidate_without_database_authorized_policy() -> None:
    repo, live = Repository(), Executor()
    repo.enabled_strategy_policies = lambda: []
    bars = (Candle(9_000, Decimal("99"), Decimal("101"), Decimal("98"), Decimal("100"), turnover=Decimal("1000")),)
    result = executor(repo, live).submit(run_id="run", candidate=candidate(), period="15min", atr=Decimal("2"),
        ema_fast=Decimal("101"), ema_slow=Decimal("99"), adx=Decimal("30"), recent_candles=bars,
        book=OrderBook(((Decimal("99.99"), Decimal("100")),), ((Decimal("100.01"), Decimal("100")),)),
        contract=ContractSpec("BTC-USDT", Decimal("0.01"), Decimal("0.1"), 1), funding=None,
        market_context=MarketContext(), now_epoch=9_500)
    assert not result.accepted and result.gate.stage == "MODEL_POLICY"
    assert not live.calls


def test_entry_refuses_contract_owned_by_another_timeframe_profile() -> None:
    repo, live = Repository(), Executor()
    repo.active_contract_position = lambda **_: {
        "id": 99, "contract_code": "BTC-USDT", "timeframe_profile": "INTRADAY_15M", "status": "ACTIVE"}
    bars = (Candle(9_000, Decimal("99"), Decimal("101"), Decimal("98"), Decimal("100"), turnover=Decimal("1000")),)
    result = executor(repo, live).submit(run_id="swing", candidate=candidate(), period="4hour", atr=Decimal("2"),
        ema_fast=Decimal("101"), ema_slow=Decimal("99"), adx=Decimal("30"), recent_candles=bars,
        book=OrderBook(((Decimal("99.99"), Decimal("100")),), ((Decimal("100.01"), Decimal("100")),)),
        contract=ContractSpec("BTC-USDT", Decimal("0.01"), Decimal("0.1"), 1), funding=None,
        market_context=MarketContext(), timeframe_profile="SWING_4H", now_epoch=10_000)
    assert not result.accepted and result.gate.stage == "PROFILE_CONTRACT_MUTEX"
    assert "INTRADAY_15M" in result.reason and not live.calls and not repo.calls


def test_reconciliation_does_not_apply_python_only_risk_reservation_gate() -> None:
    repo, live = Repository(), Executor()
    executor(repo, live).reconcile(client_order_id="1", contract_code="BTC-USDT", position_id=7, leg="PARTIAL_CLOSE",
                                   factor_snapshot_id=None, model="DONCHIAN_BREAKOUT", contract_size=Decimal("0.01"),
                                   position_key="position-key")
    assert not any(name == "release" for name, _ in repo.calls)
    repo.calls.clear()
    executor(repo, live).reconcile(client_order_id="2", contract_code="BTC-USDT", position_id=7, leg="CLOSE",
                                   factor_snapshot_id=None, model="DONCHIAN_BREAKOUT", contract_size=Decimal("0.01"),
                                   position_key="position-key")
    assert not any(name == "release" for name, _ in repo.calls)


def test_reconcile_pending_queries_existing_intent_without_new_order() -> None:
    repo, live = Repository(), Executor()
    repo.pending_orders = lambda **_: [{"client_order_id": "1", "contract_code": "BTC-USDT", "position_id": 7,
                                        "leg": "ENTRY", "factor_snapshot_id": None, "model_name": "DONCHIAN_BREAKOUT",
                                        "contract_size": "0.01", "position_key": "position-key", "run_id": "run", "trend_side": "LONG"}]
    result = executor(repo, live).reconcile_pending()
    assert result == {"1": "FILLED"}
    assert [name for name, _ in live.calls] == []


def test_reconcile_pending_isolates_one_failed_lookup_and_continues() -> None:
    repo, live = Repository(), Executor()
    repo.pending_orders = lambda **_: [
        {"client_order_id": "1", "contract_code": "BTC-USDT", "position_id": 7, "leg": "ENTRY",
         "factor_snapshot_id": None, "model_name": "DONCHIAN_BREAKOUT", "contract_size": ".01",
         "position_key": "p1", "run_id": "r1", "trend_side": "LONG"},
        {"client_order_id": "2", "contract_code": "ETH-USDT", "position_id": 8, "leg": "ENTRY",
         "factor_snapshot_id": None, "model_name": "DONCHIAN_BREAKOUT", "contract_size": ".01",
         "position_key": "p2", "run_id": "r2", "trend_side": "LONG"},
    ]
    calls = []
    def order(contract_code, **_):
        calls.append(contract_code)
        if contract_code == "BTC-USDT": raise TimeoutError("lookup failed")
        return {"order_id": "2", "state": "filled", "trade_volume": "1", "trade_avg_price": "100"}
    live.order = order
    result = executor(repo, live).reconcile_pending()
    assert result["1"] == "ERROR:TimeoutError" and result["2"] == "FILLED"
    assert calls == ["BTC-USDT", "ETH-USDT"]


def test_mirror_reduction_requires_confirmed_primary_and_never_fakes_a_fill() -> None:
    repo, live = Repository(), Executor()
    repo.mirror_position = lambda **_: {"id": 15, "position_key": "mirror-key"}
    class MirrorFacts(Facts):
        def snapshot(self, *_args, **_kwargs):
            return AccountSnapshot(ExecutionFacts(actual_taker_fee_rate=Decimal("0.0004"), executable_depth_contracts=Decimal("100"),
                available_margin_usdt=Decimal("100"), leverage=Decimal("5"), active_position_count=0, max_active_positions=2,
                risk_capacity_usdt=Decimal("200")), "HTX_ACCOUNT", Decimal("100"), 0, Decimal("2"), Decimal("0"))
    follower = HtxTrendMirrorFollower(repository=repo,
        instance=RuntimeInstance("python-mirror", "TREND", "HTX_TREND_V1", 7, "primary", "LIVE", True, "unit", None),
        account=TradeAccount(7, "primary", "MIRROR", True, True, "https://example.test"), facts=MirrorFacts(), executor=live)
    from htx_trend.mirror import ConfirmedPrimaryReduction
    result = follower.sync_reduction(ConfirmedPrimaryReduction(1, "run", "BTC-USDT", TrendSide.LONG, 1,
        "DONCHIAN_BREAKOUT", None, False),
        OrderBook(((Decimal("99.99"), Decimal("100")),), ((Decimal("100.01"), Decimal("100")),)))
    assert result.accepted and result.mirrored_contracts == 1
    place = [value for name, value in live.calls if name == "place"]
    assert len(place) == 1 and place[0].reduce_only


def test_reconciliation_maps_java_v5_partial_cancel_with_fill_to_partial() -> None:
    from htx_trend.execution_service import _terminal_status
    assert _terminal_status({"state": "partially_canceled", "trade_volume": "1"}) == "PARTIALLY_CANCELLED"
    assert _terminal_status({"state": "partially_canceled", "trade_volume": "0"}) == "CANCELLED"
    assert _terminal_status({"status": 6, "trade_volume": "1"}) == "FILLED"


def test_submission_timeout_is_unknown_and_does_not_free_structure(monkeypatch):
    repo, live = Repository(), Executor()
    def timeout(_): raise TimeoutError("response lost after acceptance")
    live.place = timeout
    bars = (Candle(9000,Decimal(99),Decimal(101),Decimal(98),Decimal(100),turnover=Decimal(1000)),)
    result = executor(repo,live).submit(run_id="run",candidate=candidate(),period="15min",atr=Decimal(2),
        ema_fast=Decimal(101),ema_slow=Decimal(99),adx=Decimal(30),recent_candles=bars,
        book=OrderBook(((Decimal("99.99"),Decimal(100)),),((Decimal("100.01"),Decimal(100)),)),
        contract=ContractSpec("BTC-USDT",Decimal(".01"),Decimal(".1"),1),
        funding=FundingRate("BTC-USDT",Decimal(".0001")),
        market_context=MarketContext(MarketRegime.BROAD_UP,TrendSide.LONG,Decimal(".8")),now_epoch=10000)
    assert not result.accepted and "UNKNOWN" in result.reason
    assert any(name=="unknown" for name,_ in repo.calls)
    assert not any(name=="rejected" for name,_ in repo.calls)
