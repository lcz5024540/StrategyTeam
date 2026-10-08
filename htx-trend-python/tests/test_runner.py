from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path

from htx_trend.evidence import RuntimeKlineArchive
from htx_trend.ledger import Account, TrendLedger
from htx_trend.market_data import Candle, ContractSpec, FundingRate, KlineWindow, TopOfBook
from htx_trend.paper import PaperExchange
from htx_trend.runner import PaperTrendScanner, ScannerConfig
from htx_trend.runtime import PaperTrendRuntime


def test_scanner_deduplicates_contracts_and_archives_each_window(tmp_path) -> None:
    class Market:
        def kline_window(self, contract, period, size, now_epoch):
            return KlineWindow(contract, period, (Candle(0, Decimal("1"), Decimal("2"), Decimal("0.5"), Decimal("1.5")),), 1)
        def top_of_book(self, contract): return TopOfBook(Decimal("1"), Decimal("1.0001"))
        def contract_spec(self, contract): return ContractSpec(contract, Decimal("1"), Decimal("0.1"), 1)
        def funding_rate(self, contract): return FundingRate(contract, Decimal("0"))
    database, schema = tmp_path / "paper.sqlite", Path(__file__).parents[1] / "sql" / "schema.sql"
    TrendLedger.initialize(database, schema)
    ledger = TrendLedger(database); ledger.account(Account("paper", "Paper", "PAPER"))
    with RuntimeKlineArchive(tmp_path / "evidence") as archive:
        scanner = PaperTrendScanner(Market(), PaperTrendRuntime(ledger, PaperExchange(ledger), account_id="paper"), archive,
                                    ScannerConfig(max_concurrency=2, kline_size=1))
        result = scanner.run_once(["BTC-USDT", "BTC-USDT"], now=datetime(2026, 1, 1, tzinfo=timezone.utc))
        archive.flush()
        assert set(result) == {"BTC-USDT"}
        assert archive.status.completed == 2
    ledger.close()
