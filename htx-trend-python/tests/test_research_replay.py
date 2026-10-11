from decimal import Decimal

import pytest

from htx_trend.market_data import Candle
from htx_trend.research.replay import load_manifest_candles, replay_entries
from htx_trend.research.store import FileEvidenceStore


def test_complete_manifest_replays_through_shared_core(tmp_path):
    with FileEvidenceStore(tmp_path) as store:
        values = tuple(Candle(i*900, Decimal(100+i), Decimal(102+i), Decimal(99+i), Decimal(101+i),
                              Decimal(10), Decimal(20), Decimal(2000), 4) for i in range(170))
        raw = store.put("raw_kline", {"data": []})
        quality = store.put("quality", {"integrity": "COMPLETE"}, refs=(raw,))
        page = store.put("kline_page", {"symbol": "BTC-USDT", "period": "15min", "integrity": "COMPLETE",
            "candles": [{"open_time": item.open_time, "bar": item} for item in values]}, refs=(raw, quality))
        manifest = store.put("dataset_manifest", {"integrity": "COMPLETE", "pages": [page]}, refs=(page,))
        assert load_manifest_candles(store, manifest, symbol="BTC-USDT", period="15min") == values
        result = store.get(replay_entries(store, manifest, symbol="BTC-USDT", period="15min"))["payload"]
        assert len(result["decisions"]) == 11


def test_partial_manifest_is_rejected(tmp_path):
    with FileEvidenceStore(tmp_path) as store:
        manifest = store.put("dataset_manifest", {"integrity": "PARTIAL", "pages": []})
        with pytest.raises(ValueError, match="COMPLETE"):
            load_manifest_candles(store, manifest, symbol="BTC-USDT", period="15min")
