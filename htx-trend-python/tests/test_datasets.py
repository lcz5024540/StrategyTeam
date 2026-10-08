import json
from decimal import Decimal

from htx_trend.datasets import load_htx_envelope, replay_frozen_contract
from htx_trend.models import TrendConfig


def test_frozen_envelope_replay_has_no_future_higher_timeframe_bars(tmp_path) -> None:
    bars = [{"id": index * 900, "open": str(100 + index), "high": str(102 + index), "low": str(99 + index), "close": str(101 + index)} for index in range(120)]
    trend = [{"id": index * 3600, "open": str(100 + index), "high": str(102 + index), "low": str(99 + index), "close": str(101 + index)} for index in range(40)]
    for period, data in (("15min", bars), ("60min", trend)):
        (tmp_path / f"BTC-USDT_{period}.json").write_text(json.dumps({"status": "ok", "data": list(reversed(data))}))
    assert load_htx_envelope(tmp_path / "BTC-USDT_15min.json")[0].open_time == 0
    report = replay_frozen_contract(tmp_path, "BTC-USDT", config=TrendConfig(medium_ema_period=16, slow_ema_period=20,
                                    breakout_lookback=10, atr_period=5, adx_period=5, min_adx=Decimal("1"), min_atr_pct=Decimal("0")))
    assert report.evaluated_bars > 0
