from decimal import Decimal

from htx_trend.market_data import Candle
from htx_trend.models import TrendSide
from htx_trend.research.market_facts import FrozenMarketFacts, MarketFactsCollector
from htx_trend.research.store import FileEvidenceStore


class FakeClient:
    def contract_info_envelope(self, symbol):
        return {"status": "ok", "data": [{"contract_code": symbol, "contract_size": "0.01",
            "price_tick": "0.1", "contract_status": 1, "settlement_period": "8"}]}

    def contract_elements_envelope(self, symbol):
        return {"status": "ok", "data": [{"contract_code": symbol, "instrument_value": "0.01",
            "price_tick": "0.1", "settle_period": 8}]}

    def historical_funding_rate_envelope(self, symbol, *, page_index, page_size):
        assert page_size == 2
        pages = {
            1: [
                {"contract_code": symbol, "funding_time": "36000", "funding_rate": "0.001",
                 "realized_rate": None, "fee_asset": "USDT"},
                {"contract_code": symbol, "funding_time": "28800", "funding_rate": "0.001",
                 "realized_rate": "0.002", "fee_asset": "USDT"},
            ],
            2: [{"contract_code": symbol, "funding_time": "0", "funding_rate": "0.0001",
                 "realized_rate": None, "fee_asset": "USDT"}],
        }
        return {"status": "ok", "data": {"total_page": 2, "current_page": page_index,
                                             "total_size": 3, "data": pages[page_index]}}


def test_collector_freezes_raw_and_normalized_market_facts(tmp_path) -> None:
    with FileEvidenceStore(tmp_path) as store:
        manifest = MarketFactsCollector(FakeClient(), store, page_size=2, clock=lambda: 40_000).collect(
            ("BTC-USDT",), start=0, end=30_000)
        payload = store.get(manifest)["payload"]
        assert payload["integrity"] == "COMPLETE"
        assert payload["funding_cadence"] == "OBSERVED_EVENT_TIMESTAMPS_ONLY"
        assert store.verify(manifest)["objects"] == 8
        facts = FrozenMarketFacts.load(store, manifest)
        assert facts.contract("BTC-USDT").contract_size == Decimal("0.01")
        event = facts.rate_at("BTC-USDT", 29_000)
        assert event.effective_rate == Decimal("0.002")
        assert event.rate_source == "REALIZED_RATE"


def test_funding_charge_uses_only_crossed_events_and_direction(tmp_path) -> None:
    candles = (
        Candle(0, Decimal("100"), Decimal("102"), Decimal("99"), Decimal("101"), volume=Decimal("100")),
        Candle(14_400, Decimal("101"), Decimal("104"), Decimal("100"), Decimal("102"), volume=Decimal("100")),
        Candle(28_800, Decimal("102"), Decimal("105"), Decimal("101"), Decimal("103"), volume=Decimal("100")),
    )
    with FileEvidenceStore(tmp_path) as store:
        manifest = MarketFactsCollector(FakeClient(), store, page_size=2, clock=lambda: 40_000).collect(
            ("BTC-USDT",), start=0, end=30_000)
        facts = FrozenMarketFacts.load(store, manifest)
        long = facts.charge("BTC-USDT", TrendSide.LONG, quantity=10, entry_time=1_000,
                            exit_time=29_000, candles=candles, period="4hour")
        short = facts.charge("BTC-USDT", TrendSide.SHORT, quantity=10, entry_time=1_000,
                             exit_time=29_000, candles=candles, period="4hour")
        assert long.event_times == (28_800,) and long.event_count == 1
        assert long.amount == Decimal("0.0204")
        assert short.amount == -long.amount
        assert facts.charge("BTC-USDT", TrendSide.LONG, quantity=1, entry_time=30_001,
                            exit_time=30_002, candles=candles, period="4hour").evidence_status.startswith("UNKNOWN")
