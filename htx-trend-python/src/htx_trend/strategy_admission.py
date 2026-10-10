"""Content-addressed admission contract for HTX Python strategy versions.

The artifact records evidence; it does not manufacture a pass.  Every surface
has ordered prerequisites derived from the strategy-development iron law.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass
from enum import IntEnum, StrEnum
from hashlib import sha256
from pathlib import Path
from typing import Any, Iterable
import json
import re


class ResearchStage(IntEnum):
    STRATEGY_CARD = 1
    FROZEN_DATA = 2
    DATA_QUALITY = 3
    FEATURE_DICTIONARY = 4
    SIGNAL_STATE_MACHINE = 5
    POSITION_RISK = 6
    COST_EXECUTION = 7
    SAME_SOURCE_BACKTEST = 8
    ABLATION_SENSITIVITY = 9
    OUT_OF_SAMPLE = 10
    PAPER_EXECUTION = 11
    RESEARCH_REPORT = 12


class StageStatus(StrEnum):
    PASS = "PASS"
    FAIL = "FAIL"
    NOT_RUN = "NOT_RUN"


class DecisionSurface(StrEnum):
    OBSERVE = "OBSERVE"
    BACKTEST = "BACKTEST"
    PAPER = "PAPER"
    RANKING = "RANKING"
    GATE = "GATE"
    SIZING = "SIZING"
    EXIT = "EXIT"


REQUIRED_STAGE = {
    DecisionSurface.OBSERVE: ResearchStage.SIGNAL_STATE_MACHINE,
    DecisionSurface.BACKTEST: ResearchStage.COST_EXECUTION,
    DecisionSurface.PAPER: ResearchStage.OUT_OF_SAMPLE,
    DecisionSurface.RANKING: ResearchStage.RESEARCH_REPORT,
    DecisionSurface.GATE: ResearchStage.RESEARCH_REPORT,
    DecisionSurface.SIZING: ResearchStage.RESEARCH_REPORT,
    DecisionSurface.EXIT: ResearchStage.RESEARCH_REPORT,
}


@dataclass(frozen=True, slots=True)
class StageEvidence:
    stage: ResearchStage
    status: StageStatus
    evidence_ref: str
    evidence_sha256: str
    conclusion: str

    def validate(self) -> None:
        if self.status is StageStatus.PASS:
            if not self.evidence_ref or not _sha256(self.evidence_sha256) or not self.conclusion:
                raise ValueError(f"stage {self.stage.name} PASS requires reference, sha256 and conclusion")


@dataclass(frozen=True, slots=True)
class StrategyAdmissionArtifact:
    strategy_id: str
    strategy_version: str
    exchange: str
    experiment_id: str
    source_commit: str
    policy_hash: str
    config_hash: str
    dataset_manifest_hash: str
    stages: tuple[StageEvidence, ...]
    allowed_surfaces: tuple[DecisionSurface, ...]
    failure_conditions: tuple[str, ...]
    pause_conditions: tuple[str, ...]
    artifact_hash: str = ""

    def validate(self) -> None:
        if not self.strategy_id or not self.strategy_version:
            raise ValueError("strategy identity is required")
        if self.exchange != "HTX":
            raise ValueError("HTX strategy admission cannot authorize another exchange")
        if not re.fullmatch(r"EXP-\d{8}-\d{3}", self.experiment_id):
            raise ValueError("experiment_id must match EXP-YYYYMMDD-NNN")
        for name, value in (("policy_hash", self.policy_hash), ("config_hash", self.config_hash),
                            ("dataset_manifest_hash", self.dataset_manifest_hash)):
            if not _sha256(value):
                raise ValueError(f"{name} must be a sha256 digest")
        if not self.source_commit:
            raise ValueError("source_commit is required")
        if not self.failure_conditions or not self.pause_conditions:
            raise ValueError("failure and pause conditions are required")
        by_stage = {item.stage: item for item in self.stages}
        if len(by_stage) != len(self.stages):
            raise ValueError("research stages must be unique")
        for item in self.stages:
            item.validate()
        for surface in self.allowed_surfaces:
            required = REQUIRED_STAGE[surface]
            missing = [stage.name for stage in ResearchStage
                       if stage <= required and (stage not in by_stage or by_stage[stage].status is not StageStatus.PASS)]
            if missing:
                raise ValueError(f"{surface.value} permission has incomplete prerequisites: {','.join(missing)}")
        expected = canonical_hash(self, include_artifact_hash=False)
        if self.artifact_hash and self.artifact_hash != expected:
            raise ValueError("strategy admission artifact hash mismatch")

    def require(self, surface: DecisionSurface | str) -> None:
        self.validate()
        requested = DecisionSurface(surface)
        if requested not in self.allowed_surfaces:
            raise ValueError(f"strategy {self.strategy_version} is not admitted for {requested.value}")

    def sealed(self) -> "StrategyAdmissionArtifact":
        values = asdict(self)
        values["artifact_hash"] = canonical_hash(self, include_artifact_hash=False)
        result = _from_dict(values)
        result.validate()
        return result

    def to_json(self) -> str:
        self.validate()
        return json.dumps(_canonical_value(self), ensure_ascii=False, sort_keys=True, separators=(",", ":"))

    @classmethod
    def load(cls, path: Path) -> "StrategyAdmissionArtifact":
        artifact = _from_dict(json.loads(path.read_text(encoding="utf-8")))
        artifact.validate()
        if not artifact.artifact_hash:
            raise ValueError("persisted strategy admission must be sealed")
        for stage in artifact.stages:
            if stage.status is not StageStatus.PASS:
                continue
            evidence = Path(stage.evidence_ref)
            evidence = evidence if evidence.is_absolute() else path.parent / evidence
            if not evidence.is_file() or sha256(evidence.read_bytes()).hexdigest() != stage.evidence_sha256:
                raise ValueError(f"stage evidence missing or hash mismatch: {stage.stage.name}")
        return artifact


def canonical_hash(value: Any, *, include_artifact_hash: bool = True) -> str:
    payload = _canonical_value(value)
    if isinstance(payload, dict) and not include_artifact_hash:
        payload["artifact_hash"] = ""
    encoded = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return sha256(encoded.encode("utf-8")).hexdigest()


def build_artifact(*, strategy_id: str, strategy_version: str, experiment_id: str, source_commit: str,
                   policy_hash: str, config_hash: str, dataset_manifest_hash: str,
                   stages: Iterable[StageEvidence], allowed_surfaces: Iterable[DecisionSurface],
                   failure_conditions: Iterable[str], pause_conditions: Iterable[str]) -> StrategyAdmissionArtifact:
    return StrategyAdmissionArtifact(strategy_id, strategy_version, "HTX", experiment_id, source_commit,
        policy_hash, config_hash, dataset_manifest_hash, tuple(stages), tuple(allowed_surfaces),
        tuple(failure_conditions), tuple(pause_conditions)).sealed()


def _sha256(value: str) -> bool:
    return bool(re.fullmatch(r"[0-9a-f]{64}", value or ""))


def _canonical_value(value: Any) -> Any:
    if hasattr(value, "__dataclass_fields__"):
        return {key: _canonical_value(item) for key, item in asdict(value).items()}
    if isinstance(value, dict):
        return {str(key): _canonical_value(item) for key, item in value.items()}
    if isinstance(value, (tuple, list)):
        return [_canonical_value(item) for item in value]
    if isinstance(value, (StrEnum, IntEnum)):
        return value.value
    return value


def _from_dict(values: dict[str, Any]) -> StrategyAdmissionArtifact:
    return StrategyAdmissionArtifact(
        strategy_id=values["strategy_id"], strategy_version=values["strategy_version"], exchange=values["exchange"],
        experiment_id=values["experiment_id"], source_commit=values["source_commit"],
        policy_hash=values["policy_hash"], config_hash=values["config_hash"],
        dataset_manifest_hash=values["dataset_manifest_hash"],
        stages=tuple(StageEvidence(ResearchStage(item["stage"]), StageStatus(item["status"]),
                                   item["evidence_ref"], item["evidence_sha256"], item["conclusion"])
                     for item in values["stages"]),
        allowed_surfaces=tuple(DecisionSurface(item) for item in values["allowed_surfaces"]),
        failure_conditions=tuple(values["failure_conditions"]), pause_conditions=tuple(values["pause_conditions"]),
        artifact_hash=values.get("artifact_hash", ""),
    )
