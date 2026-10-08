"""Load immutable runtime evidence and run deterministic paper callbacks."""
from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal
from pathlib import Path
from typing import Callable, Iterable
import json

from .market_data import Candle, KlineWindow


@dataclass(frozen=True, slots=True)
class ReplaySnapshot:
    key: str
    stage: str
    window: KlineWindow
    run_id: str | None
    position_id: str | None


def load_snapshots(root: Path) -> tuple[ReplaySnapshot, ...]:
    result: list[ReplaySnapshot] = []
    for path in sorted((root / "snapshots").glob("*.json")):
        metadata = json.loads(path.read_text(encoding="utf-8"))
        candles = []
        for hash_ in metadata["candle_hashes"]:
            item = json.loads((root / "candles" / f"{hash_}.json").read_text(encoding="utf-8"))
            candles.append(Candle(int(item["bar_time"]), Decimal(item["open"]), Decimal(item["high"]), Decimal(item["low"]),
                                  Decimal(item["close"]), _decimal(item.get("amount")), _decimal(item.get("volume")),
                                  _decimal(item.get("turnover")), item.get("trade_count")))
        result.append(ReplaySnapshot(metadata["snapshot_key"], metadata["capture_stage"],
                                     KlineWindow(metadata["contract_code"], metadata["period"], tuple(candles),
                                                 int(metadata["expected_window_size"])), metadata.get("run_id"),
                                     str(metadata["position_id"]) if metadata.get("position_id") is not None else None))
    return tuple(result)


def replay(snapshots: Iterable[ReplaySnapshot], handler: Callable[[ReplaySnapshot], object]) -> list[object]:
    """A deterministic callback runner; each immutable snapshot is handled once in key order."""
    return [handler(item) for item in sorted(snapshots, key=lambda item: item.key)]


def _decimal(value: str | None) -> Decimal | None: return Decimal(value) if value is not None else None
