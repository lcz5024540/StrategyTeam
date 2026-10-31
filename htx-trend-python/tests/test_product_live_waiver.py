"""Tests for ProductLiveWaiver (path B) and runtime_guard integration."""
from __future__ import annotations

import json
from dataclasses import replace
from datetime import UTC, datetime, timedelta
from pathlib import Path
from types import SimpleNamespace

import pytest

from htx_trend.admission import runtime_guard
from htx_trend.live_approval import LiveRiskPolicy
from htx_trend.product_live_waiver import (
    ProductLiveWaiver,
    ProductLiveWaiverBody,
    UserRulingRef,
    assert_no_dual_live_authorization,
)
from htx_trend.strategy_profiles import STRATEGY_15M_V2, profile_strategy
from htx_trend.timeframes import TimeframeProfile
from htx_trend.worker import _validate_live_approval

SHA = "a" * 64
COMMIT = "73fe4c1b9b5c4a42401d062e9d2b2835c093c95a"
STAMP_SHA = "3a1adf97" + "0" * 56  # placeholder; tests use arbitrary valid digest


def _ruling() -> UserRulingRef:
    return UserRulingRef(
        channel="BrainBot",
        ruled_at_cst="2026-10-31 19:18 Asia/Shanghai",
        summary="Path B product waiver encode",
    )


def _body(*, package_sha: str = SHA, risk: LiveRiskPolicy | None = None, expires_days: int = 30) -> ProductLiveWaiverBody:
    issued = datetime(2026, 10, 31, 11, 0, tzinfo=UTC)
    spec = profile_strategy(TimeframeProfile.INTRADAY_15M)
    return ProductLiveWaiverBody(
        package_sha256=package_sha,
        source_commit=COMMIT,
        strategy_version=STRATEGY_15M_V2,
        timeframe_profile=TimeframeProfile.INTRADAY_15M.value,
        instance_key="htx-trend-live-primary",
        config_fingerprint=SHA,
        strategy_spec_hash=spec.spec_hash,
        account_id=7,
        risk=risk or LiveRiskPolicy(),
        exit_version="HTX_TREND_EXIT_V4",
        waived_gates=("PAPER_EXECUTION_14D", "SIGMA_D_ORDERPX"),
        user_ruling_ref=_ruling(),
        product_stamp_sha256=STAMP_SHA,
        issued_at=issued.isoformat(),
        expires_at=(issued + timedelta(days=expires_days)).isoformat(),
        approved_by="product-reviewer",
        rollback_point="RB-path-B-encode",
    )


def _waiver(**kwargs) -> ProductLiveWaiver:
    return ProductLiveWaiver(synthetic=False, waiver=_body(**kwargs).sealed())


def _write_waiver(path: Path, waiver: ProductLiveWaiver) -> None:
    path.write_text(json.dumps(waiver.to_document(), ensure_ascii=False, indent=2), encoding="utf-8")


def _instance(**overrides):
    base = dict(
        strategy_name=STRATEGY_15M_V2,
        config_fingerprint=SHA,
        instance_key="htx-trend-live-primary",
        execution_mode="LIVE",
    )
    base.update(overrides)
    return SimpleNamespace(**base)


def _account(account_id: int = 7):
    return SimpleNamespace(account_id=account_id)


def _scan_config():
    return SimpleNamespace(
        max_active_positions=3,
        entry_enabled=True,
        timeframe_profile=TimeframeProfile.INTRADAY_15M,
    )


def test_valid_waiver_seals_and_loads(tmp_path: Path) -> None:
    waiver = _waiver()
    path = tmp_path / "waiver.json"
    _write_waiver(path, waiver)
    loaded = ProductLiveWaiver.load(path, now=datetime(2026, 11, 1, tzinfo=UTC))
    assert loaded.waiver.waiver_hash == waiver.waiver.waiver_hash


def test_tampered_hash_rejected() -> None:
    body = _body().sealed()
    tampered = replace(body, account_id=8)
    with pytest.raises(ValueError, match="modified"):
        ProductLiveWaiver(synthetic=False, waiver=tampered).validate(
            now=datetime(2026, 11, 1, tzinfo=UTC))


def test_expired_waiver_rejected() -> None:
    waiver = _waiver(expires_days=5)
    with pytest.raises(ValueError, match="expired"):
        waiver.validate(now=datetime(2026, 11, 10, tzinfo=UTC))


def test_risk_must_be_three_slots() -> None:
    body = _body(risk=LiveRiskPolicy(max_active_positions=5)).sealed()
    with pytest.raises(ValueError, match="three active"):
        ProductLiveWaiver(synthetic=False, waiver=body).validate(now=datetime(2026, 11, 1, tzinfo=UTC))


def test_waiver_rejects_wrong_strategy() -> None:
    body = _body().sealed()
    bad = replace(body, strategy_version="HTX_TREND_4H_V2")
    with pytest.raises(ValueError, match="HTX_TREND_15M_V2"):
        ProductLiveWaiver(synthetic=False, waiver=bad).validate(now=datetime(2026, 11, 1, tzinfo=UTC))


def test_runtime_guard_accepts_valid_waiver(tmp_path: Path, monkeypatch) -> None:
    waiver = _waiver()
    path = tmp_path / "waiver.json"
    _write_waiver(path, waiver)
    monkeypatch.setenv("HTX_TREND_PRODUCT_LIVE_WAIVER_PATH", str(path))
    monkeypatch.setenv("HTX_PYTHON_PACKAGE_SHA256", SHA)
    monkeypatch.setenv("HTX_PYTHON_SOURCE_COMMIT", COMMIT)
    monkeypatch.delenv("HTX_TREND_LIVE_APPROVAL_PATH", raising=False)
    monkeypatch.delenv("HTX_TREND_APPROVAL_CONTEXT_PATH", raising=False)
    _, view = runtime_guard(_instance(), _account(), TimeframeProfile.INTRADAY_15M.value)
    assert view.strategy_version == STRATEGY_15M_V2


def test_runtime_guard_package_sha_mismatch(tmp_path: Path, monkeypatch) -> None:
    path = tmp_path / "waiver.json"
    _write_waiver(path, _waiver())
    monkeypatch.setenv("HTX_TREND_PRODUCT_LIVE_WAIVER_PATH", str(path))
    monkeypatch.setenv("HTX_PYTHON_PACKAGE_SHA256", "b" * 64)
    monkeypatch.setenv("HTX_PYTHON_SOURCE_COMMIT", COMMIT)
    with pytest.raises(ValueError, match="package_sha256"):
        runtime_guard(_instance(), _account(), TimeframeProfile.INTRADAY_15M.value)


def test_dual_mount_fail_closed(tmp_path: Path, monkeypatch) -> None:
    path = tmp_path / "waiver.json"
    _write_waiver(path, _waiver())
    monkeypatch.setenv("HTX_TREND_PRODUCT_LIVE_WAIVER_PATH", str(path))
    monkeypatch.setenv("HTX_TREND_STRATEGY_ADMISSION_PATH", "/tmp/admission.json")
    monkeypatch.setenv("HTX_TREND_LIVE_APPROVAL_PATH", "/tmp/approval.json")
    monkeypatch.setenv("HTX_TREND_APPROVAL_CONTEXT_PATH", "/tmp/context.json")
    with pytest.raises(ValueError, match="cannot be configured together"):
        assert_no_dual_live_authorization()


def test_g2_formal_path_still_required_without_waiver(monkeypatch) -> None:
    monkeypatch.delenv("HTX_TREND_PRODUCT_LIVE_WAIVER_PATH", raising=False)
    monkeypatch.delenv("HTX_TREND_LIVE_APPROVAL_PATH", raising=False)
    with pytest.raises(ValueError, match="LIVE requires"):
        runtime_guard(_instance(), _account(), TimeframeProfile.INTRADAY_15M.value)


def test_validate_live_approval_waiver_branch(tmp_path: Path, monkeypatch) -> None:
    path = tmp_path / "waiver.json"
    _write_waiver(path, _waiver())
    monkeypatch.setenv("HTX_TREND_PRODUCT_LIVE_WAIVER_PATH", str(path))
    monkeypatch.setenv("HTX_PYTHON_PACKAGE_SHA256", SHA)
    monkeypatch.setenv("HTX_PYTHON_SOURCE_COMMIT", COMMIT)
    monkeypatch.setenv("HTX_TREND_MAX_ACTIVE_POSITIONS", "3")
    admission = SimpleNamespace(artifact_hash=SHA)
    _validate_live_approval(_instance(), _account(), admission, (_scan_config(),))
