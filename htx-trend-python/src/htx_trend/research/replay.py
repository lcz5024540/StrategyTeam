"""Replay frozen CAS datasets through the production entry decision core."""
from __future__ import annotations

from decimal import Decimal

from ..market_data import Candle, period_seconds
from ..strategy_admission import DecisionSurface
from ..strategy_decision import HtxStrategyDecisionEngine
from .store import FileEvidenceStore


def load_manifest_candles(store: FileEvidenceStore, manifest_ref: str, *, symbol: str,
                          period: str) -> tuple[Candle, ...]:
    store.verify(manifest_ref)
    manifest = store.get(manifest_ref)
    if manifest["kind"] != "dataset_manifest" or manifest["payload"]["integrity"] != "COMPLETE":
        raise ValueError("formal replay requires a COMPLETE dataset manifest")
    by_time: dict[int, Candle] = {}
    for page_ref in manifest["payload"]["pages"]:
        page = store.get(page_ref)["payload"]
        if page["symbol"] != symbol or page["period"] != period:
            continue
        if page["integrity"] != "COMPLETE":
            raise ValueError("manifest references a partial page")
        for item in page["candles"]:
            raw = item["bar"]
            candle = Candle(int(raw["open_time"]), *(Decimal(raw[name]) for name in ("open", "high", "low", "close")),
                            *(None if raw.get(name) is None else Decimal(raw[name])
                              for name in ("amount", "volume", "turnover")),
                            None if raw.get("trade_count") is None else int(raw["trade_count"]))
            previous = by_time.get(candle.open_time)
            if previous is not None and previous != candle:
                raise ValueError(f"manifest has conflicting candle revision at {candle.open_time}")
            by_time[candle.open_time] = candle
    candles = tuple(by_time[key] for key in sorted(by_time))
    if not candles:
        raise ValueError("manifest has no requested symbol/period")
    step = period_seconds(period)
    if any(b.open_time-a.open_time != step for a, b in zip(candles, candles[1:])):
        raise ValueError("manifest replay window is discontinuous")
    return candles


def replay_entries(store: FileEvidenceStore, manifest_ref: str, *, symbol: str, period: str,
                   window: int = 160) -> str:
    candles = load_manifest_candles(store, manifest_ref, symbol=symbol, period=period)
    if window < 125 or len(candles) < window:
        raise ValueError("replay requires at least 125 warmup bars")
    engine = HtxStrategyDecisionEngine()
    decisions = []
    for index in range(window, len(candles) + 1):
        decision = engine.decide(contract_code=symbol, period=period,
                                 candles=candles[index-window:index], surface=DecisionSurface.BACKTEST)
        decisions.append({"bar_time": decision.signal.bar_time, "side": decision.signal.side.value,
                          "score": decision.signal.score, "models": [item.model.value for item in decision.candidates],
                          "decision_hash": decision.decision_hash})
    return store.put("same_source_entry_replay", {"manifest_ref": manifest_ref, "symbol": symbol,
        "period": period, "window": window, "decisions": decisions}, refs=(manifest_ref,))
