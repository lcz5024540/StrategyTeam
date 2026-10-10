import json
from hashlib import sha256
import pytest
from decimal import Decimal

from htx_trend.datasets import load_htx_envelope, replay_frozen_contract
from htx_trend.java_entry_contract import JavaEntryConfig


def test_frozen_envelope_replay_has_no_future_higher_timeframe_bars(tmp_path) -> None:
    bars = [{"id": index * 900, "open": str(100 + index), "high": str(102 + index), "low": str(99 + index), "close": str(101 + index)} for index in range(120)]
    trend = [{"id": index * 3600, "open": str(100 + index), "high": str(102 + index), "low": str(99 + index), "close": str(101 + index)} for index in range(40)]
    for period, data in (("15min", bars), ("60min", trend)):
        (tmp_path / f"BTC-USDT_{period}.json").write_text(json.dumps({"status": "ok", "data": list(reversed(data))}))
    inputs=[]
    for period in ("15min","60min"):
        path=tmp_path/f"BTC-USDT_{period}.json"
        inputs.append({"contract":"BTC-USDT","period":period,"file":path.name,
                       "sha256":sha256(path.read_bytes()).hexdigest(),"coverage":"COMPLETE"})
    manifest=tmp_path/"manifest.json"
    manifest.write_text(json.dumps({"schema":"htx-trend-research-dataset-manifest-v1",
        "inputIntegrity":"COMPLETE","missingOrPartial":[],"inputs":inputs}))
    assert load_htx_envelope(tmp_path / "BTC-USDT_15min.json")[0].open_time == 0
    report = replay_frozen_contract(tmp_path, "BTC-USDT", manifest_path=manifest,
        config=JavaEntryConfig(fast_ema_period=4,medium_ema_period=8,slow_ema_period=20,breakout_lookback=10,
            momentum_lookback=5,atr_period=5,adx_period=5,volume_lookback=5,min_adx=Decimal("1"),
            min_atr_pct=Decimal("0"),entry_score_threshold=Decimal("1"),min_score_gap=Decimal("0"),
            box_window_bars=8,combination_require_trending_regime=False))
    assert report.evaluated_bars > 0
    assert report.manifest_hash==sha256(manifest.read_bytes()).hexdigest()


def test_frozen_replay_rejects_manifest_hash_drift(tmp_path) -> None:
    for period,step in (("15min",900),("60min",3600)):
        path=tmp_path/f"BTC-USDT_{period}.json"
        path.write_text(json.dumps({"status":"ok","data":[{"id":step,"open":"1","high":"2","low":".5","close":"1"}]}))
    manifest=tmp_path/"manifest.json"
    manifest.write_text(json.dumps({"schema":"htx-trend-research-dataset-manifest-v1","inputIntegrity":"COMPLETE",
        "missingOrPartial":[],"inputs":[{"contract":"BTC-USDT","period":period,
        "file":f"BTC-USDT_{period}.json","sha256":"0"*64,"coverage":"COMPLETE"} for period in ("15min","60min")]}))
    with pytest.raises(ValueError,match="hash mismatch"):
        replay_frozen_contract(tmp_path,"BTC-USDT",manifest_path=manifest)
