"""PAPER maker freeze table + P-SHADOW-TAKER integration tests."""
from __future__ import annotations

from datetime import UTC, datetime, timezone
from decimal import Decimal as D
from pathlib import Path
from types import SimpleNamespace as NS
from unittest.mock import Mock

import pytest

from htx_trend.execution import ExecutionConfig
from htx_trend.java_entry_contract import JavaEntryConfig
from htx_trend.ledger import Account, TrendLedger
from htx_trend.maker_execution import (
    MakerExecutionConfig,
    MakerTimeoutAction,
)
from htx_trend.market_data import Candle, ContractSpec, FundingRate, KlineWindow, TopOfBook
from htx_trend.models import ModelCandidate, ModelCode, TrendSide
from htx_trend.paper import PaperExchange
from htx_trend.runtime import PaperTrendRuntime
from htx_trend.strategy_profiles import STRATEGY_15M_V2


@pytest.fixture
def ledger(tmp_path):
    database, schema = tmp_path / "paper.sqlite", Path(__file__).parents[1] / "sql" / "schema.sql"
    TrendLedger.initialize(database, schema)
    led = TrendLedger(database)
    led.account(Account("main", "Main", "PAPER"))
    yield led
    led.close()


def candidate(side: TrendSide = TrendSide.LONG, bar_time: int = 9_000) -> ModelCandidate:
    structure = NS(
        upper=D("110"), lower=D("90"), high=D("110"), low=D("90"),
        target_low=D("97.64"), target_high=D("102.36"))
    return ModelCandidate(
        ModelCode.FIB_PULLBACK_CONTINUATION, side, bar_time, D("95"), D("100"),
        D("95"), D("110"), D("115"), D("2"), structure, ("fib",), bar_time - 1_800)


def _bar(index: int, close: str, low: str | None = None, high: str | None = None) -> Candle:
    value = D(close)
    return Candle(
        index * 900, value - D("1"), value + D("2"),
        D(low) if low is not None else value - D("2"),
        value, turnover=D("1000"))


def _window(contract: str, period: str, *closes: tuple[int, str, str | None, str | None]) -> KlineWindow:
    return KlineWindow(contract, period, tuple(_bar(i, c, l, h) for i, c, l, h in closes), len(closes))


def _book(bid: str = "99.99", ask: str = "100.01") -> TopOfBook:
    return TopOfBook(D(bid), D(ask))


def _contract() -> ContractSpec:
    return ContractSpec("BTC-USDT", D("0.01"), D("0.01"), 1)


def _execution_config() -> ExecutionConfig:
    return ExecutionConfig(
        min_score=D("0"), min_net_r=D("-100"), min_notional=D("1"),
        target_notional=D("100"), max_notional=D("100"), fee_rate=D("0.0004"))


def _entry_config() -> JavaEntryConfig:
    return JavaEntryConfig(
        fast_ema_period=4, medium_ema_period=8, slow_ema_period=20, breakout_lookback=10,
        momentum_lookback=5, atr_period=5, adx_period=5, volume_lookback=5, min_adx=D("1"),
        min_atr_pct=D("0"), entry_score_threshold=D("1"), min_score_gap=D("0"),
        box_window_bars=8, combination_require_trending_regime=False)


def _make_runtime(ledger, *, maker_config: MakerExecutionConfig | None = None,
                  execution_config: ExecutionConfig | None = None, evidence_store=None):
    exchange = PaperExchange(ledger)
    cfg = maker_config if maker_config is not None else MakerExecutionConfig()
    runtime = PaperTrendRuntime(
        ledger, exchange, account_id="main", entry_config=_entry_config(),
        execution_config=execution_config or _execution_config(), maker_config=cfg, evidence_store=evidence_store)
    return runtime


def _mock_decide(runtime, side: TrendSide = TrendSide.LONG, now_epoch: int = 90 * 900):
    item = candidate(side, bar_time=now_epoch - 900)
    runtime._decision.decide = Mock(return_value=NS(
        signal=NS(reason="fixture", side=side, indicators=NS(atr=D("2"), ema_fast=D("100"))),
        candidates=(item,), strategy_version=STRATEGY_15M_V2, decision_hash="fixture"))
    return item


def _orders_for_run(ledger, run_id: str):
    rows = ledger._connection.execute(
        "SELECT * FROM trend_order WHERE client_order_id LIKE ?",
        (f"paper-{run_id}-%",)).fetchall()
    return rows


def _decisions_for_run(ledger, run_id: str, kinds: tuple[str, ...] | None = None):
    sql = "SELECT * FROM trend_decision WHERE run_id=?"
    params: list = [run_id]
    if kinds:
        sql += " AND decision_kind IN (" + ",".join("?" * len(kinds)) + ")"
        params.extend(kinds)
    return ledger._connection.execute(sql, params).fetchall()


def test_paper_maker_off_uses_market_taker(ledger):
    runtime = _make_runtime(ledger)
    now_epoch = 90 * 900
    _mock_decide(runtime, now_epoch=now_epoch)
    entry = _window("BTC-USDT", "15min", (89, "100", None, None))
    book = _book()
    result = runtime.scan(
        run_id="r1", entry_window=entry, trend_window=entry, now_epoch=now_epoch,
        at=datetime(2026, 1, 1, tzinfo=timezone.utc), book=book,
        contract=_contract(), funding=FundingRate("BTC-USDT", D("0.0001")),
        execution_candle=_bar(89, "100"))
    assert result.paper_order_id is not None
    orders = _orders_for_run(ledger, "r1")
    assert len(orders) == 1
    assert orders[0]["order_type"] == "MARKET"
    assert len(ledger.active_positions("main")) == 1


def test_paper_maker_on_limit_not_touched_cancels_no_position(ledger):
    runtime = _make_runtime(ledger, maker_config=MakerExecutionConfig(
        enabled=True, offset_bps=D("0"), max_wait_bars=1, max_amends=0,
        partial_accept=False, timeout_action=MakerTimeoutAction.CANCEL))
    now_epoch = 90 * 900
    _mock_decide(runtime, now_epoch=now_epoch)
    entry = _window("BTC-USDT", "15min", (89, "100", None, None))
    book = _book()
    # Candle low is above best bid (99.99) so the LIMIT is not touched.
    execution_candle = _bar(89, "100.50", "100.00", "100.60")
    result = runtime.scan(
        run_id="r1", entry_window=entry, trend_window=entry, now_epoch=now_epoch,
        at=datetime(2026, 1, 1, tzinfo=timezone.utc), book=book,
        contract=_contract(), funding=FundingRate("BTC-USDT", D("0.0001")),
        execution_candle=execution_candle)
    assert result.paper_order_id is None
    assert len(ledger.active_positions("main")) == 0
    decisions = _decisions_for_run(ledger, "r1")
    kinds = {d["decision_kind"] for d in decisions}
    assert kinds & {"MAKER_CANCELLED", "MAKER_EXPIRED"}, kinds


def test_paper_maker_on_limit_touched_partial_accept_false_fills_full_position(ledger):
    runtime = _make_runtime(ledger, maker_config=MakerExecutionConfig(
        enabled=True, offset_bps=D("0"), max_wait_bars=1, max_amends=0,
        partial_accept=False, timeout_action=MakerTimeoutAction.CANCEL))
    now_epoch = 90 * 900
    _mock_decide(runtime, now_epoch=now_epoch)
    entry = _window("BTC-USDT", "15min", (89, "100", None, None))
    book = _book()
    # Candle low equals best bid (99.99) so the LIMIT is touched.
    # With partial_accept=False the lifecycle fills the full quantity.
    execution_candle = _bar(89, "100", "99.99", "100.60")
    result = runtime.scan(
        run_id="r1", entry_window=entry, trend_window=entry, now_epoch=now_epoch,
        at=datetime(2026, 1, 1, tzinfo=timezone.utc), book=book,
        contract=_contract(), funding=FundingRate("BTC-USDT", D("0.0001")),
        execution_candle=execution_candle)
    assert result.paper_order_id is not None
    assert len(ledger.active_positions("main")) == 1
    orders = _orders_for_run(ledger, "r1")
    assert len(orders) == 1
    assert orders[0]["order_type"] == "LIMIT"



def test_paper_maker_on_writes_p_shadow_taker_evidence(ledger):
    store = Mock()
    store.put = Mock(return_value="ref-1")
    runtime = _make_runtime(ledger, maker_config=MakerExecutionConfig(
        enabled=True, offset_bps=D("0"), max_wait_bars=1, max_amends=0,
        partial_accept=False, timeout_action=MakerTimeoutAction.CANCEL), evidence_store=store)
    now_epoch = 90 * 900
    _mock_decide(runtime, now_epoch=now_epoch)
    entry = _window("BTC-USDT", "15min", (89, "100", None, None))
    book = _book()
    execution_candle = _bar(89, "100.50", "100.00", "100.60")
    runtime.scan(
        run_id="r1", entry_window=entry, trend_window=entry, now_epoch=now_epoch,
        at=datetime(2026, 1, 1, tzinfo=timezone.utc), book=book,
        contract=_contract(), funding=FundingRate("BTC-USDT", D("0.0001")),
        execution_candle=execution_candle)
    # evidence_store path
    p_shadow_calls = [call for call in store.put.call_args_list if call.args[0] == "p_shadow_taker"]
    assert len(p_shadow_calls) == 1
    payload = p_shadow_calls[0].args[1]
    assert payload["contract_code"] == "BTC-USDT"
    legs = {leg["leg"]: leg for leg in payload["legs"]}
    assert "maker_live" in legs and "taker_shadow" in legs
    assert D(legs["maker_live"]["price"]) == D("99.99")
    assert D(legs["taker_shadow"]["price"]) == D("100.01")
    # ledger decision audit path
    decisions = _decisions_for_run(ledger, "r1", kinds=("P_SHADOW_TAKER",))
    assert len(decisions) == 1
