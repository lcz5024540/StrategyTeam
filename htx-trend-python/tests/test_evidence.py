from decimal import Decimal
import json

from htx_trend.evidence import CaptureRequest, RuntimeKlineArchive
from htx_trend.market_data import Candle, KlineWindow


def candle(open_time: int) -> Candle:
    return Candle(open_time, Decimal("100"), Decimal("102"), Decimal("99"), Decimal("101"), Decimal("1"),
                  Decimal("2"), Decimal("202"), 3)


def test_archive_is_content_addressed_and_snapshot_is_immutable(tmp_path) -> None:
    window = KlineWindow("BTC-USDT", "15min", (candle(0), candle(900)), 2)
    request = CaptureRequest("ENTRY_SCAN", window, target_bar_time=900, run_id="run-1", position_id=5)
    with RuntimeKlineArchive(tmp_path) as archive:
        assert archive.capture(request)
        archive.flush()
        assert archive.capture(request)
        archive.flush()
        assert archive.status.completed == 1
        assert archive.status.complete_windows == 1
    snapshots = list((tmp_path / "snapshots").glob("*.json"))
    candles = list((tmp_path / "candles").glob("*.json"))
    assert len(snapshots) == 1
    assert len(candles) == 2
    content = json.loads(snapshots[0].read_text())
    assert content["data_integrity"] == "COMPLETE"
    assert content["candle_hashes"] == [item.content_hash for item in window.candles]


def test_archive_marks_a_gapped_window_partial(tmp_path) -> None:
    window = KlineWindow("BTC-USDT", "15min", (candle(0), candle(1800)), 2)
    with RuntimeKlineArchive(tmp_path) as archive:
        archive.capture(CaptureRequest("ENTRY_SCAN", window))
        archive.flush()
        assert archive.status.partial_windows == 1
