"""数据集冻结和管理"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass, field, fields
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Literal

import pandas as pd

from ..ledger.ledger import ApprovalDecision, ApprovalGate
from ..ops.logging import LogComponent, log_event
from .immutable_storage import ImmutableKlineStorage
from .manifest import DatasetManifest, VerificationResult

BAR_DURATION = {
    "4hour": timedelta(hours=4),
    "4h": timedelta(hours=4),
    "1day": timedelta(days=1),
    "1d": timedelta(days=1),
}


def parse_utc_datetime(value: datetime | pd.Timestamp | str) -> datetime:
    if isinstance(value, pd.Timestamp):
        value = value.to_pydatetime()
    if isinstance(value, datetime):
        if value.tzinfo is None:
            return value.replace(tzinfo=timezone.utc)
        return value.astimezone(timezone.utc)
    text = str(value).strip()
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    parsed = datetime.fromisoformat(text)
    if parsed.tzinfo is None:
        return parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def utc_iso(value: datetime | pd.Timestamp | str) -> str:
    return parse_utc_datetime(value).strftime("%Y-%m-%dT%H:%M:%SZ")


def require_utc_midnight(value: datetime, *, field_name: str) -> datetime:
    instant = parse_utc_datetime(value)
    if instant.hour or instant.minute or instant.second or instant.microsecond:
        raise ValueError(f"{field_name} must be aligned to UTC 00:00, got {utc_iso(instant)}")
    return instant


def bar_duration(timeframe: str) -> timedelta:
    try:
        return BAR_DURATION[timeframe]
    except KeyError as exc:
        raise ValueError(f"Unsupported timeframe for cutoff/split: {timeframe}") from exc


def filter_closed_by_cutoff(
    df: pd.DataFrame,
    timeframe: str,
    cutoff: datetime,
) -> pd.DataFrame:
    if df is None or df.empty:
        return df
    work = df.copy()
    times = pd.to_datetime(work["bar_time"], utc=True)
    closes = times + bar_duration(timeframe)
    return work.loc[closes <= pd.Timestamp(cutoff)].copy()


def unique_utc_days(bar_times: Any) -> list[datetime]:
    days = (
        pd.to_datetime(pd.Series(list(bar_times)), utc=True)
        .dt.normalize()
        .drop_duplicates()
        .sort_values()
    )
    return [ts.to_pydatetime() for ts in days]


def compute_split_boundaries(
    days: list[datetime],
    train_ratio: float = 0.6,
    val_ratio: float = 0.2,
    test_start_utc: datetime | str | None = None,
    validation_start_utc: datetime | str | None = None,
) -> tuple[datetime, datetime]:
    if not days:
        raise ValueError("No UTC days remain at or before data_cutoff_close_utc")
    if (test_start_utc is None) ^ (validation_start_utc is None):
        raise ValueError(
            "validation_start_utc and test_start_utc must be passed together"
        )
    if validation_start_utc is not None:
        validation_start = require_utc_midnight(
            validation_start_utc, field_name="validation_start_utc"
        )
        test_start = require_utc_midnight(test_start_utc, field_name="test_start_utc")
    else:
        n_days = len(days)
        val_idx = min(int(n_days * train_ratio), n_days - 1)
        test_idx = min(int(n_days * (train_ratio + val_ratio)), n_days - 1)
        validation_start = parse_utc_datetime(days[val_idx])
        test_start = parse_utc_datetime(days[test_idx])
    return validation_start, test_start


def require_timeframe(*values: object) -> str:
    for value in values:
        if value is None:
            continue
        text = str(value).strip()
        if text:
            return text
    raise ValueError("timeframe is required; refusing to default to '4hour'")


def assert_split_bounds(
    validation_start: datetime,
    test_start: datetime,
    cutoff: datetime,
    *,
    data_start: datetime | None = None,
    data_end: datetime | None = None,
) -> None:
    val = parse_utc_datetime(validation_start)
    test = parse_utc_datetime(test_start)
    cut = parse_utc_datetime(cutoff)
    if not (val < test <= cut):
        raise ValueError(
            "split bounds must satisfy validation_start < test_start <= cutoff, "
            f"got {utc_iso(val)} / {utc_iso(test)} / {utc_iso(cut)}"
        )
    if data_start is not None:
        start = parse_utc_datetime(data_start)
        if val < start or test < start:
            raise ValueError(
                "split bounds must fall within the data time range, "
                f"got validation_start={utc_iso(val)} test_start={utc_iso(test)} "
                f"data_start={utc_iso(start)}"
            )
    if data_end is not None:
        end = parse_utc_datetime(data_end)
        if val > end or test > end:
            raise ValueError(
                "split bounds must fall within the data time range, "
                f"got validation_start={utc_iso(val)} test_start={utc_iso(test)} "
                f"data_end={utc_iso(end)}"
            )


def load_split_from_manifest(path: Path | str) -> tuple[str, str, str]:
    payload = json.loads(Path(path).read_text())
    val_start, test_start, cutoff = _require_manifest_split(payload)
    return utc_iso(val_start), utc_iso(test_start), utc_iso(cutoff)


def assert_stitch_in_train(
    stitch_points: dict | None,
    validation_start: datetime,
) -> None:
    if not stitch_points:
        return
    val = parse_utc_datetime(validation_start)
    for symbol, raw in stitch_points.items():
        stitch = parse_utc_datetime(raw)
        if stitch >= val:
            raise ValueError(
                f"stitch point for {symbol} ({utc_iso(stitch)}) must fall inside "
                f"the train segment (before validation_start_utc {utc_iso(val)})"
            )


def _require_manifest_split(manifest: dict) -> tuple[datetime, datetime, datetime]:
    split_info = manifest.get("split") or {}
    val_raw = split_info.get("validation_start_utc") or manifest.get("validation_start_utc")
    test_raw = split_info.get("test_start_utc") or manifest.get("test_start_utc")
    cutoff_raw = manifest.get("data_cutoff_close_utc")
    if not val_raw or not test_raw or not cutoff_raw:
        raise ValueError(
            "manifest split missing validation_start_utc/test_start_utc "
            "or data_cutoff_close_utc; freeze the dataset again"
        )
    return (
        parse_utc_datetime(val_raw),
        parse_utc_datetime(test_raw),
        parse_utc_datetime(cutoff_raw),
    )


def _mask_split(
    bar_times: pd.Series,
    split: str,
    validation_start: datetime,
    test_start: datetime,
    cutoff: datetime,
    timeframe: str,
) -> pd.Series:
    times = pd.to_datetime(bar_times, utc=True)
    closes = times + bar_duration(timeframe)
    val_ts = pd.Timestamp(validation_start)
    test_ts = pd.Timestamp(test_start)
    cutoff_ts = pd.Timestamp(cutoff)
    in_cutoff = closes <= cutoff_ts
    if split == "train":
        return (times < val_ts) & in_cutoff
    if split in ("val", "validation"):
        return (times >= val_ts) & (times < test_ts) & in_cutoff
    return (times >= test_ts) & in_cutoff


@dataclass
class FrozenDataset:
    dataset_id: str
    symbols: list[str]
    timeframe: str
    start_date: datetime
    end_date: datetime
    total_bars: int
    manifest_hash: str
    created_at: datetime
    data_cutoff_close_utc: datetime | None = None
    validation_start_utc: datetime | None = None
    test_start_utc: datetime | None = None
    test_hash: str | None = None
    segment_counts: dict = field(default_factory=dict)
    stitch_points: dict | None = None
    verified_funding_paths: dict = field(default_factory=dict)
    verified_spec_paths: dict = field(default_factory=dict)
    verified_kline_paths: dict = field(default_factory=dict)
    verified_aux_paths: dict = field(default_factory=dict)
    verified_input_paths: dict = field(default_factory=dict)

    VALID_SPLITS = ("train", "val", "validation", "test")
    _RUNTIME_PATH_FIELDS = frozenset(
        {
            "verified_funding_paths",
            "verified_spec_paths",
            "verified_kline_paths",
            "verified_aux_paths",
            "verified_input_paths",
        }
    )

    def get_split(self, split: str) -> tuple[int, int]:
        raise ValueError(
            "index-based get_split is removed; slice by manifest timestamps "
            "(validation_start_utc / test_start_utc / data_cutoff_close_utc)"
        )
    
    def to_dict(self) -> dict:
        return {
            "dataset_id": self.dataset_id,
            "symbols": self.symbols,
            "timeframe": self.timeframe,
            "start_date": self.start_date.isoformat(),
            "end_date": self.end_date.isoformat(),
            "total_bars": self.total_bars,
            "manifest_hash": self.manifest_hash,
            "created_at": self.created_at.isoformat(),
            "data_cutoff_close_utc": (
                utc_iso(self.data_cutoff_close_utc) if self.data_cutoff_close_utc else None
            ),
            "validation_start_utc": (
                utc_iso(self.validation_start_utc) if self.validation_start_utc else None
            ),
            "test_start_utc": utc_iso(self.test_start_utc) if self.test_start_utc else None,
            "test_hash": self.test_hash,
            "segment_counts": self.segment_counts,
            "split": {
                "validation_start_utc": (
                    utc_iso(self.validation_start_utc) if self.validation_start_utc else None
                ),
                "test_start_utc": utc_iso(self.test_start_utc) if self.test_start_utc else None,
            },
        }
    
    @classmethod
    def from_dict(cls, data: dict) -> FrozenDataset:
        allowed = {item.name for item in fields(cls)} - cls._RUNTIME_PATH_FIELDS
        filtered = {key: value for key, value in data.items() if key in allowed}
        split = data.get("split") or {}
        if filtered.get("validation_start_utc") is None:
            filtered["validation_start_utc"] = split.get("validation_start_utc")
        if filtered.get("test_start_utc") is None:
            filtered["test_start_utc"] = split.get("test_start_utc")
        filtered["start_date"] = datetime.fromisoformat(filtered["start_date"])
        filtered["end_date"] = datetime.fromisoformat(filtered["end_date"])
        filtered["created_at"] = datetime.fromisoformat(filtered["created_at"])
        for key in ("data_cutoff_close_utc", "validation_start_utc", "test_start_utc"):
            value = filtered.get(key)
            if isinstance(value, str):
                filtered[key] = parse_utc_datetime(value)
        return cls(**filtered)


class DatasetManager:
    def __init__(
        self,
        datasets_dir: Path,
        storage: ImmutableKlineStorage | None = None,
        ledger: Any = None,
        current_run_id: str | None = None,
    ):
        self.datasets_dir = datasets_dir
        self.datasets_dir.mkdir(parents=True, exist_ok=True)
        self.manifests_dir = datasets_dir / "manifests"
        self.manifests_dir.mkdir(parents=True, exist_ok=True)
        self.storage = storage
        self.ledger = ledger
        self.current_run_id = current_run_id
    
    def freeze_dataset(
        self,
        data: dict[str, pd.DataFrame],
        timeframe: str,
        train_ratio: float = 0.6,
        val_ratio: float = 0.2,
        extra_metadata: dict | None = None,
        data_cutoff_close_utc: datetime | str | None = None,
        test_start_utc: datetime | str | None = None,
        validation_start_utc: datetime | str | None = None,
    ) -> FrozenDataset:
        if data_cutoff_close_utc is None:
            raise ValueError("data_cutoff_close_utc is required")
        cutoff = parse_utc_datetime(data_cutoff_close_utc)
        filtered: dict[str, pd.DataFrame] = {}
        for symbol, df in data.items():
            kept = filter_closed_by_cutoff(df, timeframe, cutoff)
            if kept is not None and not kept.empty:
                filtered[symbol] = kept.reset_index(drop=True)
        if not filtered:
            raise ValueError("No bars remain at or before data_cutoff_close_utc")
        data = filtered
        symbols = sorted(data.keys())

        all_dates = []
        total_bars = 0
        for symbol in symbols:
            df = data[symbol]
            all_dates.extend(df["bar_time"].tolist())
            total_bars += len(df)

        days = unique_utc_days(all_dates)
        validation_start, test_start = compute_split_boundaries(
            days,
            train_ratio=train_ratio,
            val_ratio=val_ratio,
            test_start_utc=test_start_utc,
            validation_start_utc=validation_start_utc,
        )
        last_open = parse_utc_datetime(max(all_dates))
        last_close = last_open + bar_duration(timeframe)
        assert_split_bounds(
            validation_start,
            test_start,
            cutoff,
            data_start=days[0],
            data_end=last_close,
        )
        assert_stitch_in_train((extra_metadata or {}).get("stitch_points"), validation_start)

        all_dates = sorted(set(all_dates))
        start_date = min(all_dates)
        end_date = max(all_dates)
        cutoff_iso = utc_iso(cutoff)
        split_payload = {
            "validation_start_utc": utc_iso(validation_start),
            "test_start_utc": utc_iso(test_start),
        }

        manifest = {
            "symbols": symbols,
            "timeframe": timeframe,
            "start_date": start_date.isoformat() if hasattr(start_date, "isoformat") else str(start_date),
            "end_date": end_date.isoformat() if hasattr(end_date, "isoformat") else str(end_date),
            "total_bars": total_bars,
            "data_cutoff_close_utc": cutoff_iso,
            "split": split_payload,
        }
        if extra_metadata:
            for key in (
                "stitch_points",
                "bad_ticks_hash",
                "funding_sources",
                "funding_files",
                "spec_files",
                "aux_files",
            ):
                if key in extra_metadata:
                    manifest[key] = extra_metadata[key]

        manifest_str = json.dumps(manifest, sort_keys=True)
        manifest_hash = hashlib.sha256(manifest_str.encode()).hexdigest()[:16]

        dataset_id = f"{timeframe}_{manifest_hash}"
        dataset_dir = self.datasets_dir / dataset_id
        dataset_dir.mkdir(parents=True, exist_ok=True)

        for symbol, df in data.items():
            output_path = dataset_dir / f"{symbol}.parquet"
            frame = df.copy()
            for flag in ("incomplete", "non_comparable", "bad_tick"):
                if flag not in frame.columns:
                    frame[flag] = False
            frame.to_parquet(output_path, index=False)
            data[symbol] = frame

        segment_counts: dict[str, dict[str, int]] = {}
        for symbol in symbols:
            frame = data[symbol]
            segment_counts[symbol] = {
                name: int(
                    _mask_split(
                        frame["bar_time"],
                        name,
                        validation_start,
                        test_start,
                        cutoff,
                        timeframe,
                    ).sum()
                )
                for name in ("train", "val", "test")
            }
        train_hash = self._compute_split_hash_by_time(
            data, symbols, "train", validation_start, test_start, cutoff, timeframe
        )
        val_hash = self._compute_split_hash_by_time(
            data, symbols, "val", validation_start, test_start, cutoff, timeframe
        )
        test_hash = self._compute_split_hash_by_time(
            data, symbols, "test", validation_start, test_start, cutoff, timeframe
        )

        frozen = FrozenDataset(
            dataset_id=dataset_id,
            symbols=symbols,
            timeframe=timeframe,
            start_date=start_date,
            end_date=end_date,
            total_bars=total_bars,
            manifest_hash=manifest_hash,
            created_at=datetime.now(),
            data_cutoff_close_utc=cutoff,
            validation_start_utc=validation_start,
            test_start_utc=test_start,
            test_hash=test_hash,
            segment_counts=segment_counts,
            stitch_points=(extra_metadata or {}).get("stitch_points"),
        )

        manifest_with_splits = manifest.copy()
        manifest_with_splits.update({
            "dataset_id": dataset_id,
            "manifest_hash": manifest_hash,
            "train_hash": train_hash,
            "val_hash": val_hash,
            "test_hash": test_hash,
            "created_at": frozen.created_at.isoformat(),
            "data_cutoff_close_utc": cutoff_iso,
            "validation_start_utc": split_payload["validation_start_utc"],
            "test_start_utc": split_payload["test_start_utc"],
            "split": split_payload,
            "segment_counts": segment_counts,
        })

        manifest_path = dataset_dir / "manifest.json"
        manifest_path.write_text(json.dumps(manifest_with_splits, indent=2))

        batch_manifest = DatasetManifest.generate_from_dataset_dir(dataset_dir, dataset_id)
        batch_meta = {
            "timeframe": timeframe,
            "frozen_manifest_hash": manifest_hash,
            "data_cutoff_close_utc": cutoff_iso,
            "split": split_payload,
        }
        if extra_metadata:
            batch_meta.update(extra_metadata)
        batch_manifest.metadata.update(batch_meta)
        aux = dict(batch_manifest.metadata.get("aux_files") or {})
        aux["dataset_manifest"] = {
            "path": "manifest.json",
            "sha256": f"sha256:{hashlib.sha256(manifest_path.read_bytes()).hexdigest()}",
        }
        batch_manifest.metadata["aux_files"] = aux
        batch_manifest.save(self.manifests_dir / f"{dataset_id}.json")

        log_event(
            LogComponent.DATA,
            f"Dataset frozen: {dataset_id}",
            dataset_id=dataset_id,
            symbols=symbols,
            timeframe=timeframe,
            total_bars=total_bars,
            train_bars=sum(item["train"] for item in segment_counts.values()),
            val_bars=sum(item["val"] for item in segment_counts.values()),
            test_bars=sum(item["test"] for item in segment_counts.values()),
        )

        return frozen
    
    def load_dataset(
        self,
        dataset_id: str,
        symbols: list[str] | None = None,
        split: str = "train",
        approval_id: str | None = None,
        run_id: str | None = None,
        strategy_version: str | None = None,
        verified_kline_paths: dict[str, Path] | None = None,
        verified_manifest_path: Path | None = None,
    ) -> tuple[dict[str, pd.DataFrame], FrozenDataset]:
        if split not in FrozenDataset.VALID_SPLITS:
            raise ValueError(
                f"Invalid split name: {split}. Must be one of: train, val, test"
            )

        dataset_dir = self.datasets_dir / dataset_id
        
        if not dataset_dir.exists():
            raise FileNotFoundError(f"Dataset not found: {dataset_id}")
        
        manifest_path = verified_manifest_path or (dataset_dir / "manifest.json")
        manifest = json.loads(manifest_path.read_text())
        val_start, test_start, cutoff = _require_manifest_split(manifest)

        frozen = FrozenDataset.from_dict(manifest)
        frozen.validation_start_utc = val_start
        frozen.test_start_utc = test_start
        frozen.data_cutoff_close_utc = cutoff
        frozen.test_hash = manifest.get("test_hash") or frozen.test_hash

        if split == "test":
            self._enforce_holdout_gate(
                dataset_id=dataset_id,
                dataset_hash=frozen.manifest_hash,
                approval_id=approval_id,
                run_id=run_id or self.current_run_id,
                strategy_version=strategy_version,
            )
            test_hash = frozen.test_hash
            if not test_hash:
                raise ValueError("manifest missing test_hash; freeze the dataset first")
            if self.ledger is not None:
                self.ledger.lock_test_set(
                    frozen.manifest_hash,
                    test_hash,
                    run_id or self.current_run_id or "unknown",
                )

        symbols_to_load = symbols or frozen.symbols
        data = {}
        timeframe = require_timeframe(frozen.timeframe, manifest.get("timeframe"))

        for symbol in symbols_to_load:
            parquet_path = (verified_kline_paths or {}).get(symbol)
            if parquet_path is None:
                parquet_path = dataset_dir / f"{symbol}.parquet"
            if Path(parquet_path).exists():
                frame = pd.read_parquet(parquet_path)
                mask = _mask_split(
                    frame["bar_time"],
                    split,
                    val_start,
                    test_start,
                    cutoff,
                    timeframe,
                )
                data[symbol] = frame.loc[mask].copy()
        
        log_event(
            LogComponent.DATA,
            f"Dataset loaded: {dataset_id}",
            dataset_id=dataset_id,
            symbols=list(data.keys()),
            split=split,
        )
        
        return data, frozen

    def _enforce_holdout_gate(
        self,
        dataset_id: str,
        dataset_hash: str,
        approval_id: str | None,
        run_id: str | None,
        strategy_version: str | None = None,
    ) -> None:
        if not approval_id:
            raise ValueError(
                "Test set access requires an approval_id. "
                "Run must have an APPROVED STAGE_ACCEPTANCE approval."
            )
        if self.ledger is None:
            raise ValueError("Test set access requires a ledger on DatasetManager")

        approval = self.ledger.get_approval(approval_id)
        if approval is None:
            raise ValueError(f"Approval {approval_id} not found")
        if approval.gate != ApprovalGate.STAGE_ACCEPTANCE:
            raise ValueError(
                f"Test set requires STAGE_ACCEPTANCE approval, got {approval.gate.value}"
            )
        if approval.decision != ApprovalDecision.APPROVED:
            raise ValueError(
                f"Approval {approval_id} is {approval.decision.value}, not APPROVED"
            )
        if not run_id:
            raise ValueError("Test set access requires run_id bound to the approval")
        if approval.run_id != run_id:
            raise ValueError(
                f"Approval {approval_id} is bound to run {approval.run_id}, not {run_id}"
            )
        if not strategy_version:
            raise ValueError("Test set access requires strategy_version bound to the approval")
        if approval.strategy_version != strategy_version:
            raise ValueError(
                f"Approval {approval_id} strategy_version {approval.strategy_version} "
                f"does not match {strategy_version}"
            )
        if self.ledger.is_holdout_accessed(dataset_hash, str(approval_id)):
            raise ValueError(
                f"Dataset {dataset_id} (hash {dataset_hash}) "
                f"has already been accessed with this approval. "
                f"Test set can only be used once per approval."
            )
        self.ledger.log_holdout_access(
            run_id=run_id or "unknown",
            dataset_id=dataset_id,
            dataset_hash=dataset_hash,
            approval_id=str(approval_id),
            accessed_at=datetime.now(timezone.utc),
            split_requested="test",
        )
    
    def _compute_split_hash_by_time(
        self,
        data: dict[str, pd.DataFrame],
        symbols: list[str],
        split: str,
        validation_start: datetime,
        test_start: datetime,
        cutoff: datetime,
        timeframe: str,
    ) -> str:
        hasher = hashlib.sha256()
        for symbol in sorted(symbols):
            frame = data[symbol]
            mask = _mask_split(
                frame["bar_time"],
                split,
                validation_start,
                test_start,
                cutoff,
                timeframe,
            )
            sliced = frame.loc[mask]
            hasher.update(symbol.encode())
            hasher.update(sliced["bar_time"].astype(str).str.cat().encode())
            hasher.update(sliced["close"].astype(str).str.cat().encode())
        return hasher.hexdigest()[:16]

    def freeze_storage_dataset(self, dataset_id: str) -> DatasetManifest:
        """冻结底层不可变存储，生成带 SHA256 的批次清单。"""
        if self.storage is None:
            raise ValueError("DatasetManager.storage is required to freeze from storage")
        manifest = DatasetManifest.generate_from_storage(self.storage, dataset_id)
        manifest.save(self.manifests_dir / f"{dataset_id}.json")
        return manifest

    def load_dataset_with_verification(
        self,
        dataset_id: str,
        manifest_hash: str,
        symbols: list[str] | None = None,
        split: str = "train",
        approval_id: str | None = None,
        run_id: str | None = None,
        strategy_version: str | None = None,
        allow_missing_funding: bool = False,
        require_specs: bool = False,
    ) -> tuple[dict[str, pd.DataFrame], FrozenDataset]:
        manifest_path = self.manifests_dir / f"{dataset_id}.json"
        if not manifest_path.exists():
            raise FileNotFoundError(f"Manifest not found for dataset {dataset_id}")

        manifest = DatasetManifest.load(manifest_path)
        actual_hash = manifest.sha256()
        if actual_hash != manifest_hash:
            raise ValueError(
                f"Manifest hash mismatch for {dataset_id}: "
                f"expected {manifest_hash}, got {actual_hash}"
            )

        dataset_dir = self.datasets_dir / dataset_id
        uses_storage_paths = any(
            entry.file_path.startswith("batches/") for entry in manifest.batches
        )
        if uses_storage_paths and self.storage is not None:
            result: VerificationResult = manifest.verify(self.storage)
        else:
            result = manifest.verify(base_path=dataset_dir)

        if not result.is_valid:
            raise ValueError(
                f"Dataset {dataset_id} failed hash verification: "
                f"mismatches={result.mismatches} missing={result.missing_files}"
            )
        required = list(symbols) if symbols else [entry.symbol for entry in manifest.batches]
        kline_paths = self._verified_kline_paths(manifest, dataset_dir, required)
        verified_funding = self._verify_hashed_files(
            (manifest.metadata or {}).get("funding_files"),
            required,
            field_name="funding_files",
            default_name="{symbol}_funding.parquet",
            required=not allow_missing_funding,
            allow_missing=allow_missing_funding,
        )
        verified_specs = self._verify_hashed_files(
            (manifest.metadata or {}).get("spec_files"),
            required,
            field_name="spec_files",
            default_name="{symbol}_spec.json",
            required=require_specs,
            allow_missing=not require_specs,
        )
        verified_aux = self._verify_aux_files(
            (manifest.metadata or {}).get("aux_files"),
            dataset_dir,
        )

        data, frozen = self.load_dataset(
            dataset_id,
            symbols,
            split=split,
            approval_id=approval_id,
            run_id=run_id,
            strategy_version=strategy_version,
            verified_kline_paths=kline_paths,
            verified_manifest_path=verified_aux.get("dataset_manifest"),
        )
        frozen.verified_funding_paths = verified_funding
        frozen.verified_spec_paths = verified_specs
        frozen.verified_kline_paths = kline_paths
        frozen.verified_aux_paths = verified_aux
        if not frozen.stitch_points:
            frozen.stitch_points = (manifest.metadata or {}).get("stitch_points")
        verified_inputs: dict[str, Path] = {
            f"kline:{symbol}": path for symbol, path in kline_paths.items()
        }
        verified_inputs.update(
            {f"funding:{symbol}": path for symbol, path in verified_funding.items()}
        )
        verified_inputs.update(
            {f"spec:{symbol}": path for symbol, path in verified_specs.items()}
        )
        verified_inputs.update({f"aux:{name}": path for name, path in verified_aux.items()})
        verified_inputs["aux:batch_manifest"] = manifest_path
        frozen.verified_input_paths = verified_inputs
        return data, frozen

    def _verified_kline_paths(
        self,
        manifest: DatasetManifest,
        dataset_dir: Path,
        symbols: list[str],
    ) -> dict[str, Path]:
        uses_storage = any(
            entry.file_path.startswith("batches/") for entry in manifest.batches
        )
        root = (
            self.storage.storage_dir
            if uses_storage and self.storage is not None
            else dataset_dir
        )
        wanted = set(symbols)
        verified: dict[str, Path] = {}
        for entry in manifest.batches:
            if wanted and entry.symbol not in wanted:
                continue
            verified[entry.symbol] = root / entry.file_path
        return verified

    def _verify_hashed_files(
        self,
        files: dict | None,
        symbols: list[str],
        *,
        field_name: str,
        default_name: str,
        required: bool,
        allow_missing: bool = False,
        base_dir: Path | None = None,
    ) -> dict[str, Path]:
        if not files:
            if allow_missing or not required:
                return {}
            raise ValueError(
                f"{field_name} missing from manifest; "
                "freeze the files or set the matching allow_missing flag"
            )
        missing = [symbol for symbol in symbols if symbol not in files]
        if missing and required and not allow_missing:
            raise ValueError(
                f"{field_name} missing symbols required for backtest: "
                + ", ".join(missing)
            )
        raw_dir = base_dir or (self.datasets_dir.parent / "raw")
        verified: dict[str, Path] = {}
        for symbol in symbols:
            if symbol not in files:
                continue
            info = files.get(symbol) or {}
            rel = info.get("path") or default_name.format(symbol=symbol)
            path = raw_dir / rel
            if not path.exists():
                raise ValueError(f"{field_name} file missing for {symbol}: {path}")
            actual = f"sha256:{hashlib.sha256(path.read_bytes()).hexdigest()}"
            expected = info.get("sha256")
            if actual != expected:
                if field_name == "funding_files":
                    label = "Funding"
                elif field_name == "spec_files":
                    label = "Spec"
                else:
                    label = field_name
                raise ValueError(
                    f"{label} hash mismatch for {symbol}: expected {expected}, got {actual}"
                )
            verified[symbol] = path
        return verified

    def _verify_aux_files(
        self,
        files: dict | None,
        dataset_dir: Path,
    ) -> dict[str, Path]:
        if not files:
            return {}
        verified: dict[str, Path] = {}
        for name, info in files.items():
            payload = info or {}
            rel = payload.get("path") or f"{name}.json"
            path = dataset_dir / rel
            if not path.exists():
                raise ValueError(f"aux file missing for {name}: {path}")
            actual = f"sha256:{hashlib.sha256(path.read_bytes()).hexdigest()}"
            expected = payload.get("sha256")
            if actual != expected:
                raise ValueError(
                    f"aux hash mismatch for {name}: expected {expected}, got {actual}"
                )
            verified[name] = path
        return verified

    def _verify_funding_files(
        self,
        manifest: DatasetManifest,
        symbols: list[str],
        allow_missing_funding: bool = False,
    ) -> dict[str, Path]:
        return self._verify_hashed_files(
            (manifest.metadata or {}).get("funding_files"),
            symbols,
            field_name="funding_files",
            default_name="{symbol}_funding.parquet",
            required=not allow_missing_funding,
            allow_missing=allow_missing_funding,
        )
