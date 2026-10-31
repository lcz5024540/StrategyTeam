"""Product-issued LIVE waiver (path B): sealed bypass for formal PAPER≥14d G2 admission."""
from __future__ import annotations

from dataclasses import asdict, dataclass
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from pathlib import Path
from typing import Any
import json
import os

from .live_approval import EXIT_V4, LiveRiskPolicy, _digest, _hash, _time
from .strategy_profiles import profile_strategy
from .timeframes import TimeframeProfile

SCHEMA = "htx-product-live-waiver-g2"
STRATEGY_15M_V2 = "HTX_TREND_15M_V2"
LIVE_INSTANCE_KEY = "htx-trend-live-primary"
REQUIRED_WAIVED_GATES = frozenset({"PAPER_EXECUTION_14D", "SIGMA_D_ORDERPX"})


@dataclass(frozen=True, slots=True)
class UserRulingRef:
    channel: str
    ruled_at_cst: str
    summary: str
    verbatim_excerpt: str = ""

    def validate(self) -> None:
        if not self.channel.strip() or not self.ruled_at_cst.strip() or not self.summary.strip():
            raise ValueError("user_ruling_ref requires channel, ruled_at_cst and summary")


@dataclass(frozen=True, slots=True)
class ProductLiveWaiverBody:
    package_sha256: str
    source_commit: str
    strategy_version: str
    timeframe_profile: str
    instance_key: str
    config_fingerprint: str
    strategy_spec_hash: str
    account_id: int
    risk: LiveRiskPolicy
    exit_version: str
    waived_gates: tuple[str, ...]
    user_ruling_ref: UserRulingRef
    product_stamp_sha256: str
    issued_at: str
    expires_at: str
    approved_by: str
    rollback_point: str
    waiver_hash: str = ""

    def validate(self, *, now: datetime | None = None) -> None:
        if self.strategy_version != STRATEGY_15M_V2:
            raise ValueError("product LIVE waiver only authorizes HTX_TREND_15M_V2")
        if self.timeframe_profile != TimeframeProfile.INTRADAY_15M.value:
            raise ValueError("product LIVE waiver only authorizes INTRADAY_15M")
        if self.instance_key != LIVE_INSTANCE_KEY:
            raise ValueError("product LIVE waiver instance_key must be htx-trend-live-primary")
        if not self.source_commit.strip():
            raise ValueError("product LIVE waiver requires source_commit")
        for name, value in (("package", self.package_sha256), ("config", self.config_fingerprint),
                            ("spec", self.strategy_spec_hash), ("stamp", self.product_stamp_sha256)):
            if not _digest(value):
                raise ValueError(f"product LIVE waiver {name} fingerprint must be sha256")
        self.user_ruling_ref.validate()
        self.risk.validate()
        if self.exit_version != EXIT_V4:
            raise ValueError("product LIVE waiver requires HTX_TREND_EXIT_V4")
        gates = set(self.waived_gates)
        if not gates or not REQUIRED_WAIVED_GATES <= gates:
            raise ValueError("product LIVE waiver must waive PAPER_EXECUTION_14D and SIGMA_D_ORDERPX")
        if not self.approved_by.strip() or self.approved_by.strip().upper() == "SYNTHETIC":
            raise ValueError("product LIVE waiver requires a non-SYNTHETIC approved_by")
        if not self.rollback_point.strip() or self.rollback_point.strip().upper().startswith("SYNTHETIC"):
            raise ValueError("product LIVE waiver requires a non-SYNTHETIC rollback_point")
        issued, expires = _time(self.issued_at), _time(self.expires_at)
        if expires <= issued or expires - issued > timedelta(days=45):
            raise ValueError("product LIVE waiver may authorize at most 45 days")
        if (now or datetime.now(UTC)) >= expires:
            raise ValueError("product LIVE waiver expired")
        expected_hash = _hash(self, clear="waiver_hash")
        if not self.waiver_hash or self.waiver_hash != expected_hash:
            raise ValueError("product LIVE waiver is not sealed or was modified")

    def sealed(self) -> "ProductLiveWaiverBody":
        values = asdict(self)
        values["waiver_hash"] = _hash(self, clear="waiver_hash")
        values["risk"] = LiveRiskPolicy(**values["risk"])
        values["user_ruling_ref"] = UserRulingRef(**values["user_ruling_ref"])
        values["waived_gates"] = tuple(values["waived_gates"])
        return ProductLiveWaiverBody(**values)

    def validate_runtime(self, instance, account, profile: str, *, now: datetime | None = None) -> None:
        self.validate(now=now)
        if getattr(instance, "execution_mode", None) == "PAPER":
            raise ValueError("product LIVE waiver cannot authorize PAPER execution")
        if instance.strategy_name != self.strategy_version:
            raise ValueError("product LIVE waiver strategy_version mismatch")
        if profile != self.timeframe_profile:
            raise ValueError("product LIVE waiver timeframe_profile mismatch")
        if getattr(instance, "instance_key", None) != self.instance_key:
            raise ValueError("product LIVE waiver instance_key mismatch")
        package_sha = os.environ.get("HTX_PYTHON_PACKAGE_SHA256", "").strip()
        source_commit = os.environ.get("HTX_PYTHON_SOURCE_COMMIT", "").strip()
        if not package_sha or package_sha != self.package_sha256:
            raise ValueError("product LIVE waiver package_sha256 mismatch")
        if not source_commit or source_commit != self.source_commit:
            raise ValueError("product LIVE waiver source_commit mismatch")
        fingerprint = (getattr(instance, "config_fingerprint", None) or "").strip()
        if fingerprint != self.config_fingerprint:
            raise ValueError("product LIVE waiver config_fingerprint mismatch")
        spec_hash = profile_strategy(TimeframeProfile(profile)).spec_hash
        if spec_hash != self.strategy_spec_hash:
            raise ValueError("product LIVE waiver strategy_spec_hash mismatch")
        if account.account_id != self.account_id:
            raise ValueError("product LIVE waiver account_id mismatch")


@dataclass(frozen=True, slots=True)
class ProductLiveWaiver:
    synthetic: bool
    waiver: ProductLiveWaiverBody

    def validate(self, *, now: datetime | None = None) -> None:
        if self.synthetic is not False:
            raise ValueError("product LIVE waiver synthetic flag must be false")
        self.waiver.validate(now=now)

    def validate_runtime(self, instance, account, profile: str, *, now: datetime | None = None) -> None:
        self.validate(now=now)
        self.waiver.validate_runtime(instance, account, profile, now=now)

    def to_approval_view(self) -> LiveApprovalView:
        body = self.waiver
        return LiveApprovalView(
            strategy_version=body.strategy_version,
            timeframe_profile=body.timeframe_profile,
            source_commit=body.source_commit,
            package_sha256=body.package_sha256,
            config_fingerprint=body.config_fingerprint,
            strategy_spec_hash=body.strategy_spec_hash,
            admission_hash="",
            account_id=body.account_id,
            risk=body.risk,
            exit_version=body.exit_version,
            issued_at=body.issued_at,
            expires_at=body.expires_at,
        )

    @classmethod
    def load(cls, path: Path, *, now: datetime | None = None) -> "ProductLiveWaiver":
        raw = json.loads(path.read_text(encoding="utf-8"))
        if raw.get("schema") != SCHEMA:
            raise ValueError("invalid product LIVE waiver schema")
        if raw.get("synthetic") is not False:
            raise ValueError("invalid product LIVE waiver synthetic declaration")
        waiver_raw = raw.get("waiver")
        if not isinstance(waiver_raw, dict):
            raise ValueError("product LIVE waiver body missing")
        waiver_raw = dict(waiver_raw)
        ruling = waiver_raw.pop("user_ruling_ref")
        waiver_raw["user_ruling_ref"] = UserRulingRef(**ruling)
        waiver_raw["waived_gates"] = tuple(waiver_raw["waived_gates"])
        waiver_raw["risk"] = LiveRiskPolicy(**{
            key: Decimal(str(value)) if key.endswith("rate") else value
            for key, value in waiver_raw["risk"].items()
        })
        result = cls(synthetic=False, waiver=ProductLiveWaiverBody(**waiver_raw))
        result.validate(now=now)
        return result

    def to_document(self) -> dict[str, Any]:
        body = asdict(self.waiver)
        body["risk"] = {
            "per_trade_equity_rate": str(self.waiver.risk.per_trade_equity_rate),
            "total_open_equity_rate": str(self.waiver.risk.total_open_equity_rate),
            "max_active_positions": self.waiver.risk.max_active_positions,
        }
        body["user_ruling_ref"] = asdict(self.waiver.user_ruling_ref)
        return {"schema": SCHEMA, "synthetic": False, "waiver": body}


@dataclass(frozen=True, slots=True)
class LiveApprovalView:
    """Runtime view returned from ProductLiveWaiver; not a sealed LiveApproval artifact."""

    strategy_version: str
    timeframe_profile: str
    source_commit: str
    package_sha256: str
    config_fingerprint: str
    strategy_spec_hash: str
    admission_hash: str
    account_id: int
    risk: LiveRiskPolicy
    exit_version: str
    issued_at: str
    expires_at: str


def formal_g2_paths_configured() -> bool:
    import os as _os
    return all(_os.environ.get(name, "").strip() for name in (
        "HTX_TREND_STRATEGY_ADMISSION_PATH",
        "HTX_TREND_LIVE_APPROVAL_PATH",
        "HTX_TREND_APPROVAL_CONTEXT_PATH",
    ))


def product_waiver_path_configured() -> bool:
    return bool(os.environ.get("HTX_TREND_PRODUCT_LIVE_WAIVER_PATH", "").strip())


def assert_no_dual_live_authorization() -> None:
    if product_waiver_path_configured() and formal_g2_paths_configured():
        raise ValueError(
            "ProductLiveWaiver cannot be configured together with formal G2 admission, approval and context paths"
        )
