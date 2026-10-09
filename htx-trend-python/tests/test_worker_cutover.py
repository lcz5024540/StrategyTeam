from __future__ import annotations

import pytest

from htx_trend.worker import _validate_first_live_cutover


def test_first_live_cutover_requires_exit_acceptance(monkeypatch) -> None:
    monkeypatch.delenv("HTX_PYTHON_EXIT_PARITY_APPROVED", raising=False)
    monkeypatch.setenv("HTX_TREND_MIXED_MARKET_REQUIRES_STRONG_CANDIDATE", "false")

    with pytest.raises(SystemExit, match="exit parity acceptance"):
        _validate_first_live_cutover()


def test_first_live_cutover_requires_explicit_approved_mixed_policy(monkeypatch) -> None:
    monkeypatch.setenv("HTX_PYTHON_EXIT_PARITY_APPROVED", "true")
    monkeypatch.delenv("HTX_TREND_MIXED_MARKET_REQUIRES_STRONG_CANDIDATE", raising=False)

    with pytest.raises(SystemExit, match="MIXED_MARKET_REQUIRES_STRONG_CANDIDATE=false"):
        _validate_first_live_cutover()


def test_first_live_cutover_accepts_reviewed_configuration(monkeypatch) -> None:
    monkeypatch.setenv("HTX_PYTHON_EXIT_PARITY_APPROVED", "true")
    monkeypatch.setenv("HTX_TREND_MIXED_MARKET_REQUIRES_STRONG_CANDIDATE", "false")

    _validate_first_live_cutover()
