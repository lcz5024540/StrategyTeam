"""Dataset manifest with per-batch SHA256 verification."""

from __future__ import annotations

import hashlib
import json
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import pandas as pd

from .immutable_storage import ImmutableKlineStorage


@dataclass
class BatchEntry:
    """批次条目"""

    symbol: str
    interval: str
    file_path: str
    content_hash: str
    size_bytes: int
    bar_count: int


@dataclass
class VerificationResult:
    is_valid: bool
    mismatches: list[dict] = field(default_factory=list)
    missing_files: list[str] = field(default_factory=list)


@dataclass
class DatasetManifest:
    """数据集清单：每个批次文件的 SHA256。"""

    dataset_id: str
    created_at: str
    batches: list[BatchEntry]
    metadata: dict = field(default_factory=dict)

    def sha256(self) -> str:
        payload = {
            "dataset_id": self.dataset_id,
            "batches": [asdict(b) for b in self.batches],
            "metadata": self.metadata,
            "stitch_points": self.metadata.get("stitch_points"),
            "bad_ticks_hash": self.metadata.get("bad_ticks_hash"),
        }
        digest = hashlib.sha256(
            json.dumps(payload, sort_keys=True, separators=(",", ":")).encode()
        ).hexdigest()
        return f"sha256:{digest}"

    @classmethod
    def generate_from_storage(
        cls,
        storage: ImmutableKlineStorage,
        dataset_id: str,
    ) -> "DatasetManifest":
        batches: list[BatchEntry] = []
        for parquet in sorted(storage.batches_dir.glob("*.parquet")):
            symbol, interval = _parse_batch_filename(parquet.name)
            content = parquet.read_bytes()
            digest = hashlib.sha256(content).hexdigest()
            df = pd.read_parquet(parquet)
            batches.append(
                BatchEntry(
                    symbol=symbol,
                    interval=interval,
                    file_path=f"batches/{parquet.name}",
                    content_hash=f"sha256:{digest}",
                    size_bytes=len(content),
                    bar_count=len(df),
                )
            )
        return cls(
            dataset_id=dataset_id,
            created_at=datetime.now(timezone.utc).isoformat(),
            batches=batches,
            metadata={"storage_dir": str(storage.storage_dir)},
        )

    @classmethod
    def generate_from_dataset_dir(
        cls,
        dataset_dir: Path,
        dataset_id: str,
    ) -> "DatasetManifest":
        batches: list[BatchEntry] = []
        for parquet in sorted(dataset_dir.glob("*.parquet")):
            content = parquet.read_bytes()
            digest = hashlib.sha256(content).hexdigest()
            df = pd.read_parquet(parquet)
            batches.append(
                BatchEntry(
                    symbol=parquet.stem,
                    interval="",
                    file_path=parquet.name,
                    content_hash=f"sha256:{digest}",
                    size_bytes=len(content),
                    bar_count=len(df),
                )
            )
        return cls(
            dataset_id=dataset_id,
            created_at=datetime.now(timezone.utc).isoformat(),
            batches=batches,
            metadata={"dataset_dir": str(dataset_dir)},
        )

    def verify(
        self,
        storage: ImmutableKlineStorage | None = None,
        base_path: Path | None = None,
    ) -> VerificationResult:
        root = Path(base_path) if base_path is not None else storage.storage_dir
        mismatches: list[dict] = []
        missing_files: list[str] = []

        for entry in self.batches:
            file_path = root / entry.file_path
            if not file_path.exists():
                missing_files.append(entry.file_path)
                continue
            actual = f"sha256:{hashlib.sha256(file_path.read_bytes()).hexdigest()}"
            if actual != entry.content_hash:
                mismatches.append(
                    {
                        "file": entry.file_path,
                        "expected": entry.content_hash,
                        "actual": actual,
                    }
                )

        return VerificationResult(
            is_valid=not mismatches and not missing_files,
            mismatches=mismatches,
            missing_files=missing_files,
        )

    def save(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        payload = {
            "dataset_id": self.dataset_id,
            "created_at": self.created_at,
            "batches": [asdict(b) for b in self.batches],
            "metadata": self.metadata,
            "stitch_points": self.metadata.get("stitch_points"),
            "bad_ticks_hash": self.metadata.get("bad_ticks_hash"),
            "manifest_hash": self.sha256(),
        }
        path.write_text(json.dumps(payload, indent=2))

    @classmethod
    def load(cls, path: Path) -> "DatasetManifest":
        payload = json.loads(path.read_text())
        batches = [BatchEntry(**item) for item in payload.get("batches", [])]
        return cls(
            dataset_id=payload["dataset_id"],
            created_at=payload["created_at"],
            batches=batches,
            metadata=payload.get("metadata") or {},
        )


def _parse_batch_filename(name: str) -> tuple[str, str]:
    stem = name[:-8] if name.endswith(".parquet") else name
    parts = stem.split("_")
    if len(parts) < 2:
        return stem, ""
    return parts[0], parts[1]
