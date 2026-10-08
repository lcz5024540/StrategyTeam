from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path

from htx_trend.execution import ExecutionConfig
from htx_trend.ledger import Account, TrendLedger
from htx_trend.market_data import Candle, ContractSpec, KlineWindow, TopOfBook
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
                          at=datetime(2026, 1, 1, tzinfo=timezone.utc), book=TopOfBook(Decimal("194.9"), Decimal("195.1")),
                          contract=ContractSpec("BTC-USDT", Decimal("0.01"), Decimal("0.1"), 1), funding=None)
    assert result.paper_order_id is not None
    assert len(ledger.active_positions("main")) == len(ledger.active_positions("trademax")) == 1
    ledger.close()
