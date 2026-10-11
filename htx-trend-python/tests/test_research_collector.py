from pathlib import Path

from htx_trend.research.collector import ContinuousCollector
from htx_trend.research.store import FileEvidenceStore


def row(open_time: int, close: str = "101") -> dict:
    return {"id": open_time, "open": "100", "high": "102", "low": "99", "close": close,
            "amount": "10", "vol": "20", "trade_turnover": "2000", "count": 4}


class Client:
    def __init__(self, rows):
        self.rows = rows
        self.calls = 0

    def history_kline_envelope(self, *_args, **_kwargs):
        self.calls += 1
        return {"status": "ok", "data": self.rows}


def test_collector_freezes_raw_normalized_quality_and_manifest(tmp_path: Path) -> None:
    client = Client([row(0), row(900)])
    with FileEvidenceStore(tmp_path) as store:
        collector = ContinuousCollector(client, store, clock=lambda: 10_000)
        manifest_ref = collector.collect(("BTC-USDT",), start=0, end=1800, periods=("15min",))
        manifest = store.get(manifest_ref)["payload"]
        assert manifest["integrity"] == "COMPLETE"
        page = store.get(manifest["pages"][0])
        assert page["payload"]["integrity"] == "COMPLETE"
        assert store.verify(manifest_ref)["objects"] == 4


def test_revision_is_appended_and_gap_does_not_advance_coverage(tmp_path: Path) -> None:
    client = Client([row(0), row(900)])
    with FileEvidenceStore(tmp_path) as store:
        collector = ContinuousCollector(client, store, clock=lambda: 10_000)
        first = collector.page("BTC-USDT", "15min", 0, 1800, source="CONTINUOUS")
        client.rows = [row(0), row(900, "101.5")]
        revised = collector.page("BTC-USDT", "15min", 0, 1800, source="CONTINUOUS")
        assert first != revised
        assert len(store.keys("raw_kline")) == 2
        last_complete = store.checkpoint("coverage:BTC-USDT:15min:0:1800")
        client.rows = [row(0)]
        partial = collector.page("BTC-USDT", "15min", 0, 1800, source="CONTINUOUS")
        assert store.get(partial)["payload"]["integrity"] == "PARTIAL"
        assert store.checkpoint("coverage:BTC-USDT:15min:0:1800") == last_complete
