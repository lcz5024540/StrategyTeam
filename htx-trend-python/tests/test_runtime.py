from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path

from htx_trend.execution import ExecutionConfig
from htx_trend.ledger import Account, TrendLedger
from htx_trend.market_data import Candle, ContractSpec, FundingRate, KlineWindow, TopOfBook
from htx_trend.paper import PaperExchange
from htx_trend.runtime import PaperTrendRuntime
from htx_trend.models import TrendConfig


def bar(index, close):
    value = Decimal(close)
    return Candle(index * 900, value - 1, value + 2, value - 2, value, turnover=Decimal("1000"))


def test_primary_scan_can_mirror_only_a_filled_primary_paper_trade(tmp_path) -> None:
    database, schema = tmp_path / "paper.sqlite", Path(__file__).parents[1] / "sql" / "schema.sql"
    TrendLedger.initialize(database, schema)
    ledger = TrendLedger(database)
    ledger.account(Account("main", "Main", "PAPER")); ledger.account(Account("trademax", "TradeX", "PAPER"))
    exchange = PaperExchange(ledger)
    config = TrendConfig(medium_ema_period=16, slow_ema_period=20, breakout_lookback=10, atr_period=5, adx_period=5,
                         min_adx=Decimal("1"), min_atr_pct=Decimal("0"))
    runtime = PaperTrendRuntime(ledger, exchange, account_id="main", mirror_account_id="trademax", trend_config=config,
                                execution_config=ExecutionConfig(min_score=Decimal("0"), min_net_r=Decimal("-100"),
                                                                 min_notional=Decimal("1"), target_notional=Decimal("100"), max_notional=Decimal("100")))
    entry = [bar(i, 100 + i) for i in range(90)]; entry[-1] = bar(89, 195)
    trend = [bar(i, 100 + i) for i in range(90)]
    result = runtime.scan(run_id="r1", entry_window=KlineWindow("BTC-USDT", "15min", tuple(entry), len(entry)),
                          trend_window=KlineWindow("BTC-USDT", "60min", tuple(trend), len(trend)), now_epoch=90 * 900,
                          at=datetime(2026, 1, 1, tzinfo=timezone.utc), book=TopOfBook(Decimal("194.99"), Decimal("195.01")),
                              contract=ContractSpec("BTC-USDT", Decimal("0.01"), Decimal("0.1"), 1),
                              funding=FundingRate("BTC-USDT", Decimal("0.0001")))
    assert result.paper_order_id is not None
    assert len(ledger.active_positions("main")) == len(ledger.active_positions("trademax")) == 1
    ledger.close()


def test_v4_paper_exit_closes_primary_and_mirror_from_same_decision(tmp_path) -> None:
    database, schema = tmp_path / "exit.sqlite", Path(__file__).parents[1] / "sql" / "schema.sql"
    TrendLedger.initialize(database, schema)
    ledger = TrendLedger(database)
    ledger.account(Account("main", "Main", "PAPER")); ledger.account(Account("trademax", "TradeX", "PAPER"))
    exchange = PaperExchange(ledger)
    now = datetime(2026, 1, 1, tzinfo=timezone.utc)
    primary = exchange.open_position("main", "BTC-USDT", "LONG", 2, Decimal("100"), now, "open")
    mirror = exchange.open_position("trademax", "BTC-USDT", "LONG", 2, Decimal("100"), now, "open", primary.position_id)
    context = {"model": "FIB_PULLBACK_CONTINUATION", "contract_size": "1", "initial_stop": "95", "invalidation": "94",
               "structure_target": "110", "entry_fee": "0.08", "highest": "106", "lowest": "99", "protection": "101",
               "trailing_armed_at": now.isoformat(), "trailing_arm_reason": "RISK_REWARD_MILESTONE", "max_favorable_r": "1.2"}
    ledger.save_position_context(primary.position_id, context); ledger.save_position_context(mirror.position_id, {**context, "model": "MIRROR"})
    runtime = PaperTrendRuntime(ledger, exchange, account_id="main", mirror_account_id="trademax")
    from htx_trend.models import TrendSide
    decision = runtime.manage(run_id="close", position_id=primary.position_id, reference_price=Decimal("94"),
                              highest_price=Decimal("106"), lowest_price=Decimal("94"), atr=Decimal("2"),
                              signal_side=TrendSide.SHORT, ema_fast=Decimal("99"), ema_slow=Decimal("101"),
                              major_timeframe_opposition=1, market_context_opposes=True, at=now)
    assert decision.action.value == "FULL_CLOSE"
    assert ledger.position(primary.position_id).status == ledger.position(mirror.position_id).status == "CLOSED"
    ledger.close()


def test_paper_exit_fee_uses_contract_size(monkeypatch, tmp_path) -> None:
    database, schema = tmp_path / "fee.sqlite", Path(__file__).parents[1] / "sql" / "schema.sql"
    TrendLedger.initialize(database, schema)
    ledger = TrendLedger(database); ledger.account(Account("main", "Main", "PAPER"))
    exchange = PaperExchange(ledger); now = datetime(2026, 1, 1, tzinfo=timezone.utc)
    position = exchange.open_position("main", "BTC-USDT", "LONG", 2, Decimal("100"), now, "open")
    ledger.save_position_context(position.position_id, {"model":"FIB_PULLBACK_CONTINUATION","contract_size":"0.01",
        "initial_stop":"95","invalidation":"94","structure_target":"110","entry_fee":"0.0008",
        "highest":"101","lowest":"99","max_favorable_r":"0"})
    captured={}
    def capture(value):
        captured['input']=value
        from htx_trend.exits import decide_v4
        return decide_v4(value)
    monkeypatch.setattr('htx_trend.runtime.decide',capture)
    from htx_trend.models import TrendSide
    PaperTrendRuntime(ledger,exchange,account_id='main').manage(run_id='fee',position_id=position.position_id,
        reference_price=Decimal('101'),highest_price=Decimal('101'),lowest_price=Decimal('99'),atr=Decimal('2'),
        signal_side=TrendSide.LONG,ema_fast=Decimal('101'),ema_slow=Decimal('100'),major_timeframe_opposition=0,
        market_context_opposes=False,at=now)
    assert captured['input'].estimated_close_fee==Decimal('101')*Decimal('0.01')*2*Decimal('0.0004')
    ledger.close()
