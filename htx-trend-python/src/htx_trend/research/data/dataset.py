"""数据集冻结和管理"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Literal

import pandas as pd

from ..ops.logging import LogComponent, log_event


@dataclass
class FrozenDataset:
    dataset_id: str
    symbols: list[str]
    timeframe: str
    start_date: datetime
    end_date: datetime
    total_bars: int
    train_end_idx: int
    val_end_idx: int
    manifest_hash: str
    created_at: datetime
    
    def get_split(
        self, split: Literal["train", "validation", "test"]
    ) -> tuple[int, int]:
        if split == "train":
            return 0, self.train_end_idx
        elif split == "validation":
            return self.train_end_idx, self.val_end_idx
        else:
            return self.val_end_idx, self.total_bars
    
    def to_dict(self) -> dict:
        return {
            "dataset_id": self.dataset_id,
            "symbols": self.symbols,
            "timeframe": self.timeframe,
            "start_date": self.start_date.isoformat(),
            "end_date": self.end_date.isoformat(),
            "total_bars": self.total_bars,
            "train_end_idx": self.train_end_idx,
            "val_end_idx": self.val_end_idx,
            "manifest_hash": self.manifest_hash,
            "created_at": self.created_at.isoformat(),
        }
    
    @classmethod
    def from_dict(cls, data: dict) -> FrozenDataset:
        data = data.copy()
        data["start_date"] = datetime.fromisoformat(data["start_date"])
        data["end_date"] = datetime.fromisoformat(data["end_date"])
        data["created_at"] = datetime.fromisoformat(data["created_at"])
        return cls(**data)


class DatasetManager:
    def __init__(self, datasets_dir: Path):
        self.datasets_dir = datasets_dir
        self.datasets_dir.mkdir(parents=True, exist_ok=True)
    
    def freeze_dataset(
        self,
        data: dict[str, pd.DataFrame],
        timeframe: str,
        train_ratio: float = 0.6,
        val_ratio: float = 0.2,
    ) -> FrozenDataset:
        symbols = sorted(data.keys())
        
        all_dates = []
        total_bars = 0
        for symbol in symbols:
            df = data[symbol]
            all_dates.extend(df["bar_time"].tolist())
            total_bars += len(df)
        
        all_dates = sorted(set(all_dates))
        start_date = min(all_dates)
        end_date = max(all_dates)
        
        manifest = {
            "symbols": symbols,
            "timeframe": timeframe,
            "start_date": start_date.isoformat(),
            "end_date": end_date.isoformat(),
            "total_bars": total_bars,
        }
        
        manifest_str = json.dumps(manifest, sort_keys=True)
        manifest_hash = hashlib.sha256(manifest_str.encode()).hexdigest()[:16]
        
        dataset_id = f"{timeframe}_{manifest_hash}"
        dataset_dir = self.datasets_dir / dataset_id
        dataset_dir.mkdir(parents=True, exist_ok=True)
        
        for symbol, df in data.items():
            output_path = dataset_dir / f"{symbol}.parquet"
            df.to_parquet(output_path, index=False)
        
        sample_df = data[symbols[0]]
        n_bars = len(sample_df)
        train_end_idx = int(n_bars * train_ratio)
        val_end_idx = int(n_bars * (train_ratio + val_ratio))
        
        train_hash = self._compute_split_hash(data, symbols, 0, train_end_idx)
        val_hash = self._compute_split_hash(
            data, symbols, train_end_idx, val_end_idx
        )
        test_hash = self._compute_split_hash(data, symbols, val_end_idx, n_bars)
        
        frozen = FrozenDataset(
            dataset_id=dataset_id,
            symbols=symbols,
            timeframe=timeframe,
            start_date=start_date,
            end_date=end_date,
            total_bars=total_bars,
            train_end_idx=train_end_idx,
            val_end_idx=val_end_idx,
            manifest_hash=manifest_hash,
            created_at=datetime.now(),
        )
        
        manifest_with_splits = manifest.copy()
        manifest_with_splits.update({
            "dataset_id": dataset_id,
            "train_end_idx": train_end_idx,
            "val_end_idx": val_end_idx,
            "manifest_hash": manifest_hash,
            "train_hash": train_hash,
            "val_hash": val_hash,
            "test_hash": test_hash,
            "created_at": frozen.created_at.isoformat(),
        })
        
        manifest_path = dataset_dir / "manifest.json"
        manifest_path.write_text(json.dumps(manifest_with_splits, indent=2))
        
        log_event(
            LogComponent.DATA,
            f"Dataset frozen: {dataset_id}",
            dataset_id=dataset_id,
            symbols=symbols,
            timeframe=timeframe,
            total_bars=total_bars,
            train_bars=train_end_idx,
            val_bars=val_end_idx - train_end_idx,
            test_bars=n_bars - val_end_idx,
        )
        
        return frozen
    
    def load_dataset(
        self, dataset_id: str, symbols: list[str] | None = None
    ) -> tuple[dict[str, pd.DataFrame], FrozenDataset]:
        dataset_dir = self.datasets_dir / dataset_id
        
        if not dataset_dir.exists():
            raise FileNotFoundError(f"Dataset not found: {dataset_id}")
        
        manifest_path = dataset_dir / "manifest.json"
        manifest = json.loads(manifest_path.read_text())
        
        frozen = FrozenDataset.from_dict(manifest)
        
        symbols_to_load = symbols or frozen.symbols
        data = {}
        
        for symbol in symbols_to_load:
            parquet_path = dataset_dir / f"{symbol}.parquet"
            if parquet_path.exists():
                data[symbol] = pd.read_parquet(parquet_path)
        
        log_event(
            LogComponent.DATA,
            f"Dataset loaded: {dataset_id}",
            dataset_id=dataset_id,
            symbols=list(data.keys()),
        )
        
        return data, frozen
    
    def _compute_split_hash(
        self, data: dict[str, pd.DataFrame], symbols: list[str], start: int, end: int
    ) -> str:
        hasher = hashlib.sha256()
        
        for symbol in sorted(symbols):
            df = data[symbol].iloc[start:end]
            hasher.update(symbol.encode())
            hasher.update(df["bar_time"].astype(str).str.cat().encode())
            hasher.update(df["close"].astype(str).str.cat().encode())
        
        return hasher.hexdigest()[:16]
