"""HTX_TREND_V1 feature/condition catalog and side-effect-free trace adapter."""
from __future__ import annotations

from dataclasses import asdict
from decimal import Decimal
from typing import Sequence

from ..java_entry_contract import JavaEntryConfig, JavaTrendSignal
from ..market_data import Candle
from ..models import ModelCode
from .contracts import (ConditionSpec, ConditionStage, ConditionStatus, ConditionUsage,
                        DecisionContext, FeatureSpec, Registry, StrategySpec, TraceBuilder)


FEATURES = (
    FeatureSpec("ema20", "legacy-v1", "recursive EMA(close,20), seeded with first close", ("close",),
                {"period": 20}, 20, 0, "price", "reject missing", "trend alignment"),
    FeatureSpec("ema60", "legacy-v1", "recursive EMA(close,60), seeded with first close", ("close",),
                {"period": 60}, 60, 0, "price", "reject missing", "trend alignment"),
    FeatureSpec("ema120", "legacy-v1", "recursive EMA(close,120), seeded with first close", ("close",),
                {"period": 120}, 120, 0, "price", "reject missing", "trend alignment"),
    FeatureSpec("donchian20", "legacy-v1", "max(high),min(low) over prior 20 closed bars", ("high", "low"),
                {"period": 20}, 21, 1, "price", "reject missing", "breakout"),
    FeatureSpec("atr14", "legacy-v1", "mean true range over trailing 14", ("high", "low", "close"),
                {"period": 14}, 15, 0, "price", "reject missing", "volatility/risk"),
    FeatureSpec("adx14", "legacy-v1", "legacy mean DX using trailing directional movement", ("high", "low", "close"),
                {"period": 14}, 15, 0, "percent", "reject missing", "trend strength"),
    FeatureSpec("roc12", "legacy-v1", "close[-1]/close[-13]-1", ("close",),
                {"period": 12}, 13, 0, "ratio", "reject missing", "momentum"),
    FeatureSpec("amount_ratio20", "legacy-v1", "current first positive amount/vol/turnover divided by prior mean", ("amount", "volume", "turnover"),
                {"period": 20}, 21, 0, "ratio", "zero when unavailable", "volume confirmation"),
    FeatureSpec("vwap20", "legacy-v1", "rolling typical price weighted by volume else amount", ("high", "low", "close", "volume", "amount"),
                {"period": 20, "anchor": "rolling", "weight_unit": "HTX volume then amount fallback"}, 20, 0, "price", "zero when no positive weight", "record only"),
    FeatureSpec("rsi14", "legacy-v1", "simple trailing average gain/loss RSI", ("close",),
                {"period": 14}, 15, 0, "percent", "neutral or boundary fallback", "record only"),
    FeatureSpec("macd12_26_9", "legacy-v1", "EMA12-EMA26 and EMA9 signal", ("close",),
                {"fast": 12, "slow": 26, "signal": 9}, 27, 0, "price", "zero when empty", "record only"),
)


def _condition(condition_id, stage, usage, features=(), *, safety=False, description=""):
    return ConditionSpec(condition_id, "legacy-v1", stage, usage, tuple(features), {}, description, safety)


CONDITIONS = (
    _condition("data.warmup", ConditionStage.DATA, ConditionUsage.STRATEGY_GATE, description="enough completed bars"),
    _condition("data.positive_ohlc", ConditionStage.DATA, ConditionUsage.STRATEGY_GATE, description="positive price inputs"),
    _condition("signal.ema_full_stack", ConditionStage.CONFIRMATION, ConditionUsage.SCORE, ("ema20", "ema60", "ema120")),
    _condition("signal.ema_partial_stack", ConditionStage.CONFIRMATION, ConditionUsage.SCORE, ("ema20", "ema60", "ema120")),
    _condition("signal.momentum", ConditionStage.CONFIRMATION, ConditionUsage.SCORE, ("roc12",)),
    _condition("signal.donchian_breakout", ConditionStage.CANDIDATE, ConditionUsage.SCORE, ("donchian20",)),
    _condition("signal.adx", ConditionStage.CONFIRMATION, ConditionUsage.SCORE, ("adx14",)),
    _condition("signal.atr_activity", ConditionStage.CONFIRMATION, ConditionUsage.SCORE, ("atr14",)),
    _condition("signal.volume_ratio", ConditionStage.CONFIRMATION, ConditionUsage.SCORE, ("amount_ratio20",)),
    _condition("signal.vwap_observation", ConditionStage.CONFIRMATION, ConditionUsage.RECORD, ("vwap20",)),
    _condition("signal.rsi_observation", ConditionStage.CONFIRMATION, ConditionUsage.RECORD, ("rsi14",)),
    _condition("signal.macd_observation", ConditionStage.CONFIRMATION, ConditionUsage.RECORD, ("macd12_26_9",)),
    _condition("signal.score_threshold", ConditionStage.CANDIDATE, ConditionUsage.STRATEGY_GATE),
    _condition("signal.score_gap", ConditionStage.CANDIDATE, ConditionUsage.STRATEGY_GATE),
    _condition("entry.period_15m", ConditionStage.CANDIDATE, ConditionUsage.STRATEGY_GATE),
    _condition("entry.trending_regime", ConditionStage.CANDIDATE, ConditionUsage.STRATEGY_GATE),
    _condition("entry.donchian_candidate", ConditionStage.CANDIDATE, ConditionUsage.RECORD, ("donchian20",)),
    _condition("entry.fib_candidate", ConditionStage.CANDIDATE, ConditionUsage.RECORD),
    _condition("entry.ema_momentum_candidate", ConditionStage.CANDIDATE, ConditionUsage.RECORD, ("ema20", "ema60", "ema120", "adx14", "roc12")),
    _condition("gate.model_score", ConditionStage.ENTRY_GATE, ConditionUsage.STRATEGY_GATE),
    _condition("gate.funding_available", ConditionStage.ENTRY_GATE, ConditionUsage.RISK_CONSTRAINT),
    _condition("gate.funding_cost", ConditionStage.ENTRY_GATE, ConditionUsage.STRATEGY_GATE),
    _condition("data.signal_freshness", ConditionStage.DATA, ConditionUsage.STRATEGY_GATE),
    _condition("gate.cooldown", ConditionStage.ENTRY_GATE, ConditionUsage.STRATEGY_GATE),
    _condition("gate.counter_market", ConditionStage.MARKET, ConditionUsage.STRATEGY_GATE),
    _condition("gate.candidate_state", ConditionStage.ENTRY_GATE, ConditionUsage.STRATEGY_GATE),
    _condition("gate.mixed_market_quality", ConditionStage.MARKET, ConditionUsage.STRATEGY_GATE),
    _condition("gate.fib_quality", ConditionStage.ENTRY_GATE, ConditionUsage.STRATEGY_GATE),
    _condition("gate.unique_structure", ConditionStage.ENTRY_GATE, ConditionUsage.RISK_CONSTRAINT),
    _condition("gate.book_spread", ConditionStage.ENTRY_GATE, ConditionUsage.STRATEGY_GATE),
    _condition("gate.atr_pct", ConditionStage.ENTRY_GATE, ConditionUsage.STRATEGY_GATE, ("atr14",)),
    _condition("gate.open_orders", ConditionStage.EXECUTION_SAFETY, ConditionUsage.RISK_CONSTRAINT, safety=True),
    _condition("gate.external_position", ConditionStage.EXECUTION_SAFETY, ConditionUsage.RISK_CONSTRAINT, safety=True),
    _condition("gate.position_capacity", ConditionStage.SIZING, ConditionUsage.RISK_CONSTRAINT, safety=True),
    _condition("gate.entry_chase", ConditionStage.ENTRY_GATE, ConditionUsage.STRATEGY_GATE),
    _condition("sizing.valid_contracts", ConditionStage.SIZING, ConditionUsage.RISK_CONSTRAINT),
    _condition("gate.average_turnover", ConditionStage.ENTRY_GATE, ConditionUsage.STRATEGY_GATE),
    _condition("gate.executable_depth", ConditionStage.ENTRY_GATE, ConditionUsage.RISK_CONSTRAINT),
    _condition("gate.fee_protection", ConditionStage.ENTRY_GATE, ConditionUsage.STRATEGY_GATE),
    _condition("gate.margin", ConditionStage.SIZING, ConditionUsage.RISK_CONSTRAINT, safety=True),
    _condition("holding.management_data_complete", ConditionStage.HOLDING, ConditionUsage.RISK_CONSTRAINT),
    _condition("holding.signal_freshness", ConditionStage.HOLDING, ConditionUsage.RISK_CONSTRAINT),
    _condition("holding.higher_timeframe_opposition", ConditionStage.HOLDING, ConditionUsage.RECORD),
    _condition("holding.funding_cost", ConditionStage.HOLDING, ConditionUsage.ACTION_TRIGGER),
    _condition("exit.structure_invalidation", ConditionStage.EXIT, ConditionUsage.ACTION_TRIGGER),
    _condition("exit.profit_protection", ConditionStage.EXIT, ConditionUsage.ACTION_TRIGGER),
    _condition("exit.target", ConditionStage.EXIT, ConditionUsage.ACTION_TRIGGER),
    _condition("exit.time_or_flat", ConditionStage.EXIT, ConditionUsage.ACTION_TRIGGER),
    _condition("runtime.writer_guard", ConditionStage.EXECUTION_SAFETY, ConditionUsage.RISK_CONSTRAINT, safety=True),
    _condition("execution.account_identity", ConditionStage.EXECUTION_SAFETY, ConditionUsage.RISK_CONSTRAINT, safety=True),
    _condition("execution.order_idempotency", ConditionStage.EXECUTION_SAFETY, ConditionUsage.RISK_CONSTRAINT, safety=True),
    _condition("execution.risk_reservation", ConditionStage.EXECUTION_SAFETY, ConditionUsage.RISK_CONSTRAINT, safety=True),
    _condition("position.exchange_quantity_match", ConditionStage.EXECUTION_SAFETY, ConditionUsage.RISK_CONSTRAINT, safety=True),
    _condition("position.hard_stop", ConditionStage.EXIT, ConditionUsage.ACTION_TRIGGER, safety=True),
)

REGISTRY = Registry(FEATURES, CONDITIONS)


def baseline_strategy(config: JavaEntryConfig = JavaEntryConfig()) -> StrategySpec:
    return StrategySpec("HTX_TREND", "HTX_TREND_V1", {x.feature_id: x.version for x in FEATURES},
        {x.condition_id: x.version for x in CONDITIONS},
        {**asdict(config), "ema_candidate_min_adx": Decimal("20"),
         "ema_invalidation_atr": Decimal("1.25"), "fib_extension_1": Decimal("1.272"),
         "fib_extension_2": Decimal("1.618")},
        ({"from": "SCAN", "to": "CANDIDATE", "when": "signal and model conditions pass"},
         {"from": "CANDIDATE", "to": "GATE", "when": "policy selected"},
         {"from": "GATE", "to": "ORDER_INTENT", "when": "all entry gates pass"},
         {"from": "OPEN", "to": "MANAGED", "when": "exchange fill confirmed"},
         {"from": "MANAGED", "to": "EXIT_INTENT", "when": "prioritized exit action triggers"}),
        ("data integrity failure", "exchange/account mismatch", "risk reservation failure"),
        ("evidence persistence unavailable for new risk", "writer lock lost"))


def trace_entry(context: DecisionContext, candles: Sequence[Candle], period: str,
                signal: JavaTrendSignal, candidates: Sequence, config: JavaEntryConfig = JavaEntryConfig()):
    strategy = baseline_strategy(config)
    builder = TraceBuilder(REGISTRY, strategy, context)
    required = max(config.slow_ema_period, config.breakout_lookback, config.momentum_lookback,
                   config.atr_period, config.adx_period, config.volume_lookback) + 5
    enough = len(candles) >= required
    positive = bool(candles) and all(min(x.open, x.high, x.low, x.close) > 0 for x in candles)
    builder.record("data.warmup", ConditionStatus.PASS if enough else ConditionStatus.FAIL,
                   actual=len(candles), threshold=required)
    builder.record("data.positive_ohlc", ConditionStatus.PASS if positive else ConditionStatus.FAIL)
    if signal.indicators is not None:
        f = signal.indicators
        full = signal.close_price > f.ema_fast > f.ema_medium > f.ema_slow or signal.close_price < f.ema_fast < f.ema_medium < f.ema_slow
        partial = signal.close_price > f.ema_medium and f.ema_fast > f.ema_slow or signal.close_price < f.ema_medium and f.ema_fast < f.ema_slow
        builder.record("signal.ema_full_stack", ConditionStatus.PASS if full else ConditionStatus.FAIL,
                       actual={"close": signal.close_price, "ema20": f.ema_fast, "ema60": f.ema_medium, "ema120": f.ema_slow})
        builder.record("signal.ema_partial_stack", ConditionStatus.PASS if partial else ConditionStatus.FAIL)
        builder.record("signal.momentum", ConditionStatus.PASS if abs(f.roc) >= config.min_momentum_pct else ConditionStatus.FAIL,
                       actual=f.roc, threshold=config.min_momentum_pct)
        breakout = signal.close_price > signal.breakout_high or signal.close_price < signal.breakout_low
        builder.record("signal.donchian_breakout", ConditionStatus.PASS if breakout else ConditionStatus.FAIL,
                       actual=signal.close_price, threshold={"high": signal.breakout_high, "low": signal.breakout_low})
        builder.record("signal.adx", ConditionStatus.PASS if f.adx >= config.min_adx else ConditionStatus.FAIL,
                       actual=f.adx, threshold=config.min_adx)
        builder.record("signal.atr_activity", ConditionStatus.PASS if f.atr_pct >= config.min_atr_pct else ConditionStatus.FAIL,
                       actual=f.atr_pct, threshold=config.min_atr_pct)
        builder.record("signal.volume_ratio", ConditionStatus.PASS if signal.volume_ratio >= config.min_volume_ratio else ConditionStatus.FAIL,
                       actual=signal.volume_ratio, threshold=config.min_volume_ratio)
        builder.record("signal.vwap_observation", ConditionStatus.PASS, actual=f.vwap, reason="record-only in HTX_TREND_V1")
        builder.record("signal.rsi_observation", ConditionStatus.PASS, actual=f.rsi, reason="record-only in HTX_TREND_V1")
        builder.record("signal.macd_observation", ConditionStatus.PASS,
                       actual={"line": f.macd, "signal": f.macd_signal, "histogram": f.macd_histogram},
                       reason="record-only in HTX_TREND_V1")
    builder.record("signal.score_threshold", ConditionStatus.PASS if signal.score >= config.entry_score_threshold else ConditionStatus.FAIL,
                   actual=signal.score, threshold=config.entry_score_threshold)
    builder.record("signal.score_gap", ConditionStatus.PASS if signal.score_gap >= config.min_score_gap else ConditionStatus.FAIL,
                   actual=signal.score_gap, threshold=config.min_score_gap)
    builder.record("entry.period_15m", ConditionStatus.PASS if period == "15min" else ConditionStatus.FAIL, actual=period)
    trending = not config.combination_require_trending_regime or signal.regime in {"TRENDING_UP", "TRENDING_DOWN"}
    builder.record("entry.trending_regime", ConditionStatus.PASS if trending else ConditionStatus.FAIL, actual=signal.regime)
    models = {x.model for x in candidates}
    builder.record("entry.donchian_candidate", ConditionStatus.PASS if ModelCode.DONCHIAN_BREAKOUT in models else ConditionStatus.FAIL)
    builder.record("entry.fib_candidate", ConditionStatus.PASS if ModelCode.FIB_PULLBACK_CONTINUATION in models else ConditionStatus.FAIL)
    builder.record("entry.ema_momentum_candidate", ConditionStatus.PASS if ModelCode.EMA_VWAP_MOMENTUM_CONTINUATION in models else ConditionStatus.FAIL,
                   threshold={"adx": Decimal("20"), "ema": "20/60/120 full stack", "roc": "directional"})
    return builder.finish(action="CANDIDATES" if candidates else "NO_CANDIDATE", reason=signal.reason)
