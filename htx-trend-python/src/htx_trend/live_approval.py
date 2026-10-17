"""Immutable LIVE approvals for the legacy bridge and admitted V2 profiles."""
from __future__ import annotations

from dataclasses import asdict, dataclass
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from hashlib import sha256
from pathlib import Path
from typing import Any
import json
import re

from .timeframes import TimeframeProfile

EXIT_V4 = "HTX_TREND_EXIT_V4"
LEGACY_VERSION = "HTX_TREND_V1"


def _canonical(value: Any) -> Any:
    if hasattr(value, "__dataclass_fields__"):
        return _canonical(asdict(value))
    if isinstance(value, dict):
        return {str(key): _canonical(item) for key, item in value.items()}
    if isinstance(value, (tuple, list)):
        return [_canonical(item) for item in value]
    if isinstance(value, Decimal):
        return str(value)
    return value


def _hash(value: Any, *, clear: str | None = None) -> str:
    payload = _canonical(value)
    if clear and isinstance(payload, dict):
        payload[clear] = ""
    encoded = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return sha256(encoded.encode()).hexdigest()


def _digest(value: str) -> bool:
    return bool(re.fullmatch(r"[0-9a-f]{64}", value or ""))


@dataclass(frozen=True, slots=True)
class LiveRiskPolicy:
    per_trade_equity_rate: Decimal = Decimal("0.005")
    total_open_equity_rate: Decimal = Decimal("0.015")
    max_active_positions: int = 3

    @property
    def policy_hash(self) -> str:
        return _hash(self)

    def validate(self) -> None:
        if self.per_trade_equity_rate != Decimal("0.005"):
            raise ValueError("LIVE per-trade risk must be exactly 0.5% for this approval")
        if self.total_open_equity_rate != Decimal("0.015"):
            raise ValueError("LIVE total open risk must be exactly 1.5% for this approval")
        if self.max_active_positions != 3:
            raise ValueError("LIVE approval requires exactly three active-position slots")


@dataclass(frozen=True, slots=True)
class LegacyLiveWaiver:
    strategy_version: str
    source_commit: str
    package_sha256: str
    config_fingerprint: str
    account_id: int
    profiles: tuple[str, ...]
    risk: LiveRiskPolicy
    exit_version: str
    issued_at: str
    expires_at: str
    waiver_hash: str = ""

    def validate(self, *, now: datetime | None = None) -> None:
        if self.strategy_version != LEGACY_VERSION:
            raise ValueError("legacy waiver only authorizes HTX_TREND_V1")
        if not self.source_commit or not _digest(self.package_sha256) or not _digest(self.config_fingerprint):
            raise ValueError("legacy waiver requires source, package and config fingerprints")
        expected = {TimeframeProfile.INTRADAY_15M.value, TimeframeProfile.SWING_4H.value}
        if set(self.profiles) != expected:
            raise ValueError("legacy waiver must explicitly bind both 15m and 4h profiles")
        self.risk.validate()
        if self.exit_version != EXIT_V4:
            raise ValueError("LIVE may only use the V4 exit engine")
        issued, expires = _time(self.issued_at), _time(self.expires_at)
        if expires <= issued or expires - issued > timedelta(days=45):
            raise ValueError("legacy waiver may authorize at most 45 days")
        if (now or datetime.now(UTC)) >= expires:
            raise ValueError("legacy LIVE waiver expired")
        expected_hash = _hash(self, clear="waiver_hash")
        if not self.waiver_hash or self.waiver_hash != expected_hash:
            raise ValueError("legacy LIVE waiver is not sealed or was modified")

    def sealed(self) -> "LegacyLiveWaiver":
        values = asdict(self)
        values["waiver_hash"] = _hash(self, clear="waiver_hash")
        values["risk"] = LiveRiskPolicy(**values["risk"])
        return LegacyLiveWaiver(**values)

    @classmethod
    def load(cls, path: Path, *, now: datetime | None = None) -> "LegacyLiveWaiver":
        values = json.loads(path.read_text(encoding="utf-8"))
        values["profiles"] = tuple(values["profiles"])
        values["risk"] = LiveRiskPolicy(**{key: Decimal(str(value)) if key.endswith("rate") else value
                                            for key, value in values["risk"].items()})
        result = cls(**values)
        result.validate(now=now)
        return result


@dataclass(frozen=True, slots=True)
class LiveApproval:
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
    approval_hash: str = ""

    def validate(self, *, now: datetime | None = None) -> None:
        if self.strategy_version == LEGACY_VERSION:
            raise ValueError("legacy strategy requires LegacyLiveWaiver")
        profile = TimeframeProfile(self.timeframe_profile)
        expected_version = ("HTX_TREND_15M_V2" if profile is TimeframeProfile.INTRADAY_15M
                            else "HTX_TREND_4H_V2")
        if self.strategy_version != expected_version:
            raise ValueError("LIVE approval strategy version does not match its timeframe profile")
        if not self.source_commit:
            raise ValueError("LIVE approval requires a source commit")
        for name, value in (("package", self.package_sha256), ("config", self.config_fingerprint),
                            ("spec", self.strategy_spec_hash), ("admission", self.admission_hash)):
            if not _digest(value):
                raise ValueError(f"LIVE approval {name} fingerprint must be sha256")
        self.risk.validate()
        if self.exit_version != EXIT_V4:
            raise ValueError("V5 and unknown exit engines cannot enter LIVE")
        issued, expires = _time(self.issued_at), _time(self.expires_at)
        if expires <= issued or (now or datetime.now(UTC)) >= expires:
            raise ValueError("LIVE approval is expired or has an invalid time range")
        if not self.approval_hash or self.approval_hash != _hash(self, clear="approval_hash"):
            raise ValueError("LIVE approval is not sealed or was modified")

    def sealed(self) -> "LiveApproval":
        values = asdict(self)
        values["approval_hash"] = _hash(self, clear="approval_hash")
        values["risk"] = LiveRiskPolicy(**values["risk"])
        return LiveApproval(**values)

    @classmethod
    def load(cls, path: Path, *, now: datetime | None = None) -> "LiveApproval":
        values = json.loads(path.read_text(encoding="utf-8"))
        values["risk"] = LiveRiskPolicy(**{key: Decimal(str(value)) if key.endswith("rate") else value
                                            for key, value in values["risk"].items()})
        result = cls(**values)
        result.validate(now=now)
        return result


def _time(value: str) -> datetime:
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError("LIVE approval timestamps require a timezone")
    return parsed.astimezone(UTC)
