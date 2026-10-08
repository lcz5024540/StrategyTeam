from datetime import datetime, timezone
from decimal import Decimal
import json

from htx_trend.ledger import Account, TrendLedger
from htx_trend.paper import PaperExchange, PaperOrder
from htx_trend.replay import load_snapshots, replay
from htx_trend.market_data import Candle


def test_ledger_requires_explicit_schema_and_separates_accounts(tmp_path) -> None:
    database, schema = tmp_path / "paper.sqlite", __import__("pathlib").Path(__file__).parents[1] / "sql" / "schema.sql"
    TrendLedger.initialize(database, schema)
    ledger = TrendLedger(database)
    ledger.account(Account("primary", "Main", "PAPER"))
    ledger.account(Account("mirror", "TradeX", "PAPER"))
    exchange = PaperExchange(ledger)
    now = datetime(2026, 1, 1, tzinfo=timezone.utc)
    candle = Candle(0, Decimal("100"), Decimal("102"), Decimal("99"), Decimal("101"))
    order = exchange.submit(PaperOrder("primary", "BTC-USDT", "LONG", "BUY", 2, "MARKET", now, "open-1"), candle)
    assert order.status == "FILLED"
    position = exchange.open_position("primary", "BTC-USDT", "LONG", 2, Decimal("101"), now, "run-1")
    assert len(ledger.active_positions("primary")) == 1
    assert ledger.active_positions("mirror") == []
    ledger.close()


def test_replay_loads_content_addressed_runtime_evidence(tmp_path) -> None:
    (tmp_path / "candles").mkdir(); (tmp_path / "snapshots").mkdir()
    candle = {"bar_time": 0, "open": "1", "high": "2", "low": "0.5", "close": "1.5", "amount": None, "volume": None, "turnover": None, "trade_count": None}
    (tmp_path / "candles" / "a.json").write_text(json.dumps(candle))
    snapshot = {"snapshot_key": "s1", "capture_stage": "ENTRY_SCAN", "contract_code": "BTC-USDT", "period": "15min", "expected_window_size": 1, "candle_hashes": ["a"]}
    (tmp_path / "snapshots" / "s.json").write_text(json.dumps(snapshot))
    rows = load_snapshots(tmp_path)
    assert replay(rows, lambda item: item.window.candles[-1].close) == [Decimal("1.5")]
