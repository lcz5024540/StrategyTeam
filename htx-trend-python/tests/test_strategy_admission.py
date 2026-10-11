import json
import hashlib
from dataclasses import replace
from decimal import Decimal

import pytest

from htx_trend.java_entry_contract import JavaEntryConfig
from htx_trend.market_data import Candle
from htx_trend.strategy_admission import (DecisionSurface, ResearchStage, StageEvidence, StageStatus,
                                          StrategyAdmissionArtifact, build_artifact)
from htx_trend.strategy_decision import HtxStrategyDecisionEngine
from htx_trend.research.store import FileEvidenceStore


DIGEST = "a" * 64


def artifact(*, through=ResearchStage.RESEARCH_REPORT, surfaces=(DecisionSurface.GATE,)):
    return build_artifact(strategy_id="htx-trend", strategy_version="HTX_TREND_V2",
        experiment_id="EXP-20261011-001", source_commit="abc123", policy_hash=DIGEST,
        config_hash=DIGEST, dataset_manifest_hash=DIGEST,
        stages=tuple(StageEvidence(stage, StageStatus.PASS, f"evidence/{stage.name}", DIGEST, "passed")
                     for stage in ResearchStage if stage <= through),
        allowed_surfaces=surfaces, failure_conditions=("post-cost expectancy <= 0",),
        pause_conditions=("HTX private state unknown",))


def candles():
    return tuple(Candle(i * 900, Decimal(100 + i), Decimal(102 + i), Decimal(99 + i),
                        Decimal(101 + i), volume=Decimal("10")) for i in range(40))


def config():
    return JavaEntryConfig(fast_ema_period=4, medium_ema_period=8, slow_ema_period=20,
        breakout_lookback=10, momentum_lookback=5, atr_period=5, adx_period=5, volume_lookback=5,
        min_adx=Decimal("1"), min_atr_pct=Decimal("0"), entry_score_threshold=Decimal("1"),
        min_score_gap=Decimal("0"), box_window_bars=8, combination_require_trending_regime=False)


def test_surface_permission_requires_every_preceding_iron_law_stage():
    with pytest.raises(ValueError, match="incomplete prerequisites"):
        artifact(through=ResearchStage.SAME_SOURCE_BACKTEST, surfaces=(DecisionSurface.PAPER,))
    admitted = artifact(through=ResearchStage.OUT_OF_SAMPLE, surfaces=(DecisionSurface.PAPER,))
    admitted.require(DecisionSurface.PAPER)
    with pytest.raises(ValueError, match="not admitted"):
        admitted.require(DecisionSurface.GATE)


def test_sealed_admission_detects_persisted_tampering(tmp_path):
    evidence=(tmp_path/"evidence")
    evidence.mkdir()
    content=b"passed"
    digest=hashlib.sha256(content).hexdigest()
    stages=[]
    for stage in ResearchStage:
        target=evidence/stage.name
        target.write_bytes(content)
        stages.append(StageEvidence(stage,StageStatus.PASS,f"evidence/{stage.name}",digest,"passed"))
    admitted=build_artifact(strategy_id="htx-trend",strategy_version="HTX_TREND_V2",
        experiment_id="EXP-20261011-001",source_commit="abc123",policy_hash=DIGEST,config_hash=DIGEST,
        dataset_manifest_hash=DIGEST,stages=stages,allowed_surfaces=(DecisionSurface.GATE,),
        failure_conditions=("post-cost expectancy <= 0",),pause_conditions=("HTX private state unknown",))
    path = tmp_path / "admission.json"
    path.write_text(admitted.to_json())
    assert StrategyAdmissionArtifact.load(path).artifact_hash == admitted.artifact_hash
    values = json.loads(path.read_text())
    values["failure_conditions"] = ["ignore every loss"]
    path.write_text(json.dumps(values))
    with pytest.raises(ValueError, match="hash mismatch"):
        StrategyAdmissionArtifact.load(path)


def test_new_strategy_is_fail_closed_and_decisions_are_content_addressed():
    engine = HtxStrategyDecisionEngine(strategy_version="HTX_TREND_V2", config=config())
    with pytest.raises(ValueError, match="requires a sealed admission"):
        engine.decide(contract_code="BTC-USDT", period="15min", candles=candles(),
                      surface=DecisionSurface.OBSERVE)
    admitted = artifact(through=ResearchStage.SIGNAL_STATE_MACHINE, surfaces=(DecisionSurface.OBSERVE,))
    engine = HtxStrategyDecisionEngine(strategy_version="HTX_TREND_V2", config=config(), admission=admitted)
    first = engine.decide(contract_code="BTC-USDT", period="15min", candles=candles(),
                          surface=DecisionSurface.OBSERVE)
    second = engine.decide(contract_code="BTC-USDT", period="15min", candles=tuple(reversed(candles())),
                           surface=DecisionSurface.OBSERVE)
    assert first.input_hash == second.input_hash
    assert first.decision_hash == second.decision_hash
    with pytest.raises(ValueError, match="not admitted"):
        engine.require(DecisionSurface.RANKING)


def test_legacy_decision_can_persist_complete_condition_trace(tmp_path):
    input_ref = "a" * 64
    with FileEvidenceStore(tmp_path) as store:
        # Use a real store object as the input reference so recursive verification succeeds.
        input_ref = store.put("runtime_kline_window", {"complete": True})
        engine = HtxStrategyDecisionEngine(config=config(), evidence_store=store)
        decision = engine.decide(contract_code="BTC-USDT", period="15min", candles=candles(),
                                 surface=DecisionSurface.OBSERVE, evidence_refs=(input_ref,))
        assert decision.trace_ref
        value = store.get(decision.trace_ref)
        assert value["kind"] == "decision_trace"
        assert value["payload"]["context"]["decision_id"] == decision.decision_hash
        assert store.verify(decision.trace_ref)["objects"] == 2
