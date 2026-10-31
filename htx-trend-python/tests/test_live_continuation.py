import pytest

from htx_trend.continuation import BASELINE, CONFIRM, ContinuationConfig

def test_live_confirm_v1_accepted_for_15m(monkeypatch):
    monkeypatch.setenv('HTX_LIVE_CONTINUATION_VERSION', CONFIRM)
    monkeypatch.delenv('HTX_PAPER_CONTINUATION_VERSION', raising=False)
    cfg = ContinuationConfig.from_live_env()
    cfg.require_15m(True, live=True)
    assert cfg.version == CONFIRM


def test_live_rejects_unknown_version(monkeypatch):
    monkeypatch.setenv('HTX_LIVE_CONTINUATION_VERSION', 'typo')
    with pytest.raises(ValueError, match='unknown continuation'):
        ContinuationConfig.from_live_env()


def test_live_rejects_non_baseline_on_4h(monkeypatch):
    monkeypatch.setenv('HTX_LIVE_CONTINUATION_VERSION', CONFIRM)
    with pytest.raises(ValueError, match='15m LIVE'):
        ContinuationConfig.from_live_env().require_15m(False, live=True)


def test_paper_env_unchanged(monkeypatch):
    monkeypatch.setenv('HTX_PAPER_CONTINUATION_VERSION', CONFIRM)
    monkeypatch.setenv('HTX_PAPER_COMPLETED_TRADE_COOLDOWN', 'false')
    monkeypatch.delenv('HTX_LIVE_CONTINUATION_VERSION', raising=False)
    cfg = ContinuationConfig.from_paper_env()
    assert cfg.version == CONFIRM and not cfg.cooldown


def test_live_does_not_read_paper_env_alone(monkeypatch):
    monkeypatch.setenv('HTX_PAPER_CONTINUATION_VERSION', CONFIRM)
    monkeypatch.delenv('HTX_LIVE_CONTINUATION_VERSION', raising=False)
    assert ContinuationConfig.from_live_env().version == BASELINE


def test_live_env_isolated_from_paper_at_runtime(monkeypatch):
    """Documented contract: LIVE reads HTX_LIVE_* only."""
    monkeypatch.setenv('HTX_PAPER_CONTINUATION_VERSION', CONFIRM)
    monkeypatch.delenv('HTX_LIVE_CONTINUATION_VERSION', raising=False)
    assert ContinuationConfig.from_live_env().version == BASELINE
    monkeypatch.setenv('HTX_LIVE_CONTINUATION_VERSION', CONFIRM)
    assert ContinuationConfig.from_live_env().version == CONFIRM
