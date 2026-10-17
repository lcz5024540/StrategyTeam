"""No-lookahead candidate census and descriptive outcome labels for R2 P2.

This module does not select thresholds or authorize orders.  It records every
model evaluation from the production candidate core, then attaches outcomes
computed only from bars after the signal bar.
"""
from __future__ import annotations

from bisect import bisect_right
from collections import Counter, defaultdict
from dataclasses import dataclass
from decimal import Decimal
from math import sqrt
from pathlib import Path
from statistics import fmean
from typing import Any, Mapping, Sequence

from ..java_entry_contract import model_candidate_evaluations
from ..market_data import Candle, period_seconds
from ..models import ModelCandidate, ModelCode, TrendSide
from ..strategy_admission import DecisionSurface
from ..strategy_decision import StrategyDecision
from ..strategy_profiles import profile_strategy
from ..timeframes import TimeframeProfile
from .experiments import DEFAULT_COST_SCENARIOS
from .features import FeatureVector, feature_vector
from .market_facts import FrozenMarketFacts
from .profile_backtest import provisional_backtest_admission
from .regime import RegimeSnapshot, profile_regime
from .replay import load_manifest_candles
from .store import FileEvidenceStore, digest

ZERO = Decimal("0")
ONE = Decimal("1")


@dataclass(frozen=True, slots=True)
class CandidateLabelConfig:
    horizon_bars: int
    fee_rate: Decimal
    slippage_rate: Decimal
    quantity: int = 1

    @classmethod
    def for_profile(cls, profile: TimeframeProfile) -> "CandidateLabelConfig":
        base = next(item for item in DEFAULT_COST_SCENARIOS if item.name == "BASE")
        # Fixed before P2 output: 24 hours for 15m and seven days for 4h.
        horizon = 96 if profile is TimeframeProfile.INTRADAY_15M else 42
        return cls(horizon, base.fee_rate, base.slippage_rate)


@dataclass(frozen=True, slots=True)
class CandidateOutcomeLabel:
    status: str
    entry_time: int | None
    exit_time: int | None
    target_first: bool | None
    stop_first: bool | None
    direction_correct: bool | None
    net_profitable: bool | None
    time_to_target_bars: int | None
    time_to_stop_bars: int | None
    holding_bars: int | None
    mfe: Decimal | None
    mae: Decimal | None
    gross_pnl: Decimal | None
    fees: Decimal | None
    funding: Decimal | None
    net_pnl: Decimal | None
    funding_event_count: int
    funding_evidence_status: str
    exit_reason: str
    label_window_hash: str | None


@dataclass(frozen=True, slots=True)
class CandidateObservation:
    observation_id: str
    profile: str
    strategy_version: str
    spec_hash: str
    feature_hash: str
    condition_hash: str
    manifest_hash: str
    market_facts_manifest_hash: str
    symbol: str
    period: str
    model: str
    model_status: str
    reject_codes: tuple[str, ...]
    side: str
    signal_time: int
    window_start: int
    window_end: int
    window_size: int
    window_hash: str
    decision_hash: str
    local_regime: str
    higher_regime_status: str
    higher_regime: str
    higher_regime_hash: str
    score: Decimal
    expected_r: Decimal | None
    entry_price: Decimal | None
    invalidation_price: Decimal | None
    target_price: Decimal | None
    adx: Decimal | None
    roc: Decimal | None
    atr_pct: Decimal | None
    volume_ratio: Decimal | None
    ema_distance: Decimal | None
    vwap_distance: Decimal | None
    price_slope: Decimal | None
    ema_slope: Decimal | None
    label: CandidateOutcomeLabel | None


class CandidateLabelResearch:
    """Generate a complete per-bar, per-model census plus descriptive slices."""

    def __init__(self, store: FileEvidenceStore, manifest_hash: str,
                 market_facts_manifest_hash: str, profile: TimeframeProfile,
                 *, window: int = 160, label_config: CandidateLabelConfig | None = None,
                 chunk_size: int = 2_000) -> None:
        if window < 125 or chunk_size < 1:
            raise ValueError("candidate labels require >=125 warmup bars and positive chunks")
        self.store, self.manifest_hash, self.profile = store, manifest_hash, profile
        self.market_facts = FrozenMarketFacts.load(store, market_facts_manifest_hash)
        self.spec = profile_strategy(profile, read_env=False)
        self.window, self.label_config, self.chunk_size = (
            window, label_config or CandidateLabelConfig.for_profile(profile), chunk_size)
        admission = provisional_backtest_admission(self.spec, manifest_hash)
        from ..strategy_decision import HtxStrategyDecisionEngine
        self.engine = HtxStrategyDecisionEngine(strategy_version=self.spec.strategy.version,
                                                config=self.spec.entry_config, admission=admission)

    def run(self, symbols: Sequence[str] = ("BTC-USDT", "ETH-USDT")) -> str:
        periods = tuple(dict.fromkeys((self.spec.entry_period, self.spec.trend_period,
                                      *self.spec.confirmation_periods)))
        datasets = {symbol: {period: load_manifest_candles(
            self.store, self.manifest_hash, symbol=symbol, period=period)
            for period in periods} for symbol in symbols}
        observations: list[CandidateObservation] = []
        chunk_refs: list[str] = []
        for symbol in symbols:
            rows = self._symbol_observations(symbol, datasets[symbol])
            observations.extend(rows)
            for offset in range(0, len(rows), self.chunk_size):
                chunk = rows[offset:offset+self.chunk_size]
                chunk_refs.append(self.store.put("candidate_observation_chunk", {
                    "profile": self.profile.value, "symbol": symbol,
                    "offset": offset, "rows": chunk,
                }, refs=(self.manifest_hash, self.market_facts.manifest_hash)))
        report = self._report(observations)
        payload = {
            "schema": "htx-r2-candidate-labels-v1",
            "result_kind": "DEVELOPMENT_DIAGNOSTIC",
            "dataset_role": "ALREADY_SEEN_DEVELOPMENT_DATA_NOT_NEW_OOS",
            "profile": self.profile.value,
            "strategy_version": self.spec.strategy.version,
            "spec_hash": self.spec.spec_hash,
            "feature_hash": self.spec.feature_hash,
            "condition_hash": self.spec.condition_hash,
            "manifest_hash": self.manifest_hash,
            "market_facts_manifest_hash": self.market_facts.manifest_hash,
            "window": self.window,
            "label_config": self.label_config,
            "symbols": list(symbols),
            "observation_count": len(observations),
            "candidate_count": sum(item.model_status == "CANDIDATE" for item in observations),
            "known_label_count": sum(item.label is not None and item.label.status == "KNOWN" for item in observations),
            "observation_chunk_refs": chunk_refs,
            "trace_rule": "filter immutable chunk rows by the slice dimension; every row binds source window_hash and label_window_hash",
            "no_lookahead_rule": "features and rejection reasons use window_end only; future bars appear only under label",
            "report": report,
        }
        return self.store.put("candidate_accuracy_attribution", payload,
                              refs=(self.manifest_hash, self.market_facts.manifest_hash, *chunk_refs))

    def _symbol_observations(self, symbol: str,
                             datasets: Mapping[str, Sequence[Candle]]) -> list[CandidateObservation]:
        entry = datasets[self.spec.entry_period]
        times = {period: [item.open_time for item in candles] for period, candles in datasets.items()}
        rows: list[CandidateObservation] = []
        for index in range(len(entry)):
            window = entry[max(0, index-self.window+1):index+1]
            decision = self.engine.decide(contract_code=symbol, period=self.spec.entry_period,
                                          candles=window, surface=DecisionSurface.BACKTEST)
            as_of = entry[index].open_time + period_seconds(self.spec.entry_period)
            context = self._asof_windows(datasets, times, as_of)
            regime = profile_regime(self.profile, context, as_of_epoch=as_of)
            vector = feature_vector(window) if len(window) >= 121 else None
            evaluations = model_candidate_evaluations(window, decision.signal,
                                                      self.spec.entry_config, self.spec.entry_period)
            for evaluation in evaluations:
                candidate = max(evaluation.candidates,
                                key=lambda item: (item.score, item.expected_r, item.policy_confirmations or ""),
                                default=None)
                label = self._label(symbol, entry, index, candidate) if candidate is not None else None
                rows.append(self._observation(symbol, window, decision, regime, vector,
                                              evaluation.model, evaluation.reject_codes, candidate, label))
        return rows

    def _asof_windows(self, datasets: Mapping[str, Sequence[Candle]],
                      times: Mapping[str, Sequence[int]], as_of: int) -> dict[str, tuple[Candle, ...]]:
        result = {}
        for period, candles in datasets.items():
            last_open = as_of - period_seconds(period)
            end = bisect_right(times[period], last_open)
            result[period] = tuple(candles[max(0, end-self.window):end])
        return result

    def _observation(self, symbol: str, window: Sequence[Candle], decision: StrategyDecision,
                     regime: RegimeSnapshot, vector: FeatureVector | None, model: ModelCode,
                     reject_codes: tuple[str, ...], candidate: ModelCandidate | None,
                     label: CandidateOutcomeLabel | None) -> CandidateObservation:
        signal = decision.signal
        factors = signal.indicators
        signal_time = signal.bar_time if signal.bar_time is not None else window[-1].open_time
        identity = {
            "profile": self.profile.value, "strategy_version": self.spec.strategy.version,
            "spec_hash": self.spec.spec_hash, "manifest_hash": self.manifest_hash,
            "symbol": symbol, "period": self.spec.entry_period, "model": model.value,
            "signal_time": signal_time, "window_hash": decision.input_hash,
        }
        close = signal.close_price
        chosen_target = candidate.target_low if candidate is not None else None
        return CandidateObservation(
            digest(identity), self.profile.value, self.spec.strategy.version, self.spec.spec_hash,
            self.spec.feature_hash, self.spec.condition_hash, self.manifest_hash,
            self.market_facts.manifest_hash, symbol, self.spec.entry_period, model.value,
            "CANDIDATE" if candidate is not None else "REJECTED", reject_codes,
            signal.side.value, signal_time, window[0].open_time, window[-1].open_time,
            len(window), decision.input_hash, decision.decision_hash, signal.regime,
            regime.status.value, regime.trend.value, regime.snapshot_hash, signal.score,
            candidate.expected_r if candidate else None, candidate.entry_price if candidate else None,
            candidate.invalidation_price if candidate else None, chosen_target,
            factors.adx if factors else None, factors.roc if factors else None,
            factors.atr_pct if factors else None, signal.volume_ratio if factors else None,
            (close-factors.ema_fast)/close if factors and close else None,
            (close-factors.vwap)/close if factors and close else None,
            vector.close_slope20 if vector else None, vector.ema20_slope10 if vector else None, label,
        )

    def _label(self, symbol: str, candles: Sequence[Candle], signal_index: int,
               candidate: ModelCandidate) -> CandidateOutcomeLabel:
        config = self.label_config
        if signal_index + config.horizon_bars >= len(candles):
            return CandidateOutcomeLabel(
                status="UNKNOWN_INCOMPLETE_HORIZON", entry_time=None, exit_time=None,
                target_first=None, stop_first=None, direction_correct=None, net_profitable=None,
                time_to_target_bars=None, time_to_stop_bars=None, holding_bars=None,
                mfe=None, mae=None, gross_pnl=None, fees=None, funding=None, net_pnl=None,
                funding_event_count=0, funding_evidence_status="NOT_EVALUATED",
                exit_reason="UNKNOWN", label_window_hash=None)
        future = candles[signal_index+1:signal_index+config.horizon_bars+1]
        adverse_entry = ONE + config.slippage_rate if candidate.side is TrendSide.LONG else ONE - config.slippage_rate
        entry = future[0].open * adverse_entry
        target, stop = candidate.target_low, candidate.invalidation_price
        target_bar = stop_bar = None
        highest, lowest = entry, entry
        exit_bar, exit_price, exit_reason = config.horizon_bars, future[-1].close, "HORIZON"
        for number, candle in enumerate(future, 1):
            highest, lowest = max(highest, candle.high), min(lowest, candle.low)
            target_hit = candle.high >= target if candidate.side is TrendSide.LONG else candle.low <= target
            stop_hit = candle.low <= stop if candidate.side is TrendSide.LONG else candle.high >= stop
            target_bar = number if target_hit and target_bar is None else target_bar
            stop_bar = number if stop_hit and stop_bar is None else stop_bar
            if stop_hit:
                exit_bar, exit_reason = number, "STOP_FIRST" if target_hit or target_bar is None else "STOP_AFTER_TARGET"
                exit_price = min(candle.open, stop) if candidate.side is TrendSide.LONG else max(candle.open, stop)
                break
            if target_hit:
                exit_bar, exit_price, exit_reason = number, target, "TARGET_FIRST"
                break
        if exit_reason == "HORIZON":
            adverse_exit = ONE - config.slippage_rate if candidate.side is TrendSide.LONG else ONE + config.slippage_rate
            exit_price *= adverse_exit
        exit_time = future[exit_bar-1].open_time + period_seconds(self.spec.entry_period)
        contract = self.market_facts.contract(symbol)
        multiplier = contract.contract_size * Decimal(config.quantity)
        gross = ((exit_price-entry) if candidate.side is TrendSide.LONG else (entry-exit_price)) * multiplier
        fees = (entry+exit_price) * multiplier * config.fee_rate
        charge = self.market_facts.charge(symbol, candidate.side, quantity=config.quantity,
                                         entry_time=future[0].open_time, exit_time=exit_time,
                                         candles=candles, period=self.spec.entry_period)
        funding = charge.amount
        net = None if funding is None else gross-fees-funding
        direction_price = future[-1].close
        direction = direction_price > entry if candidate.side is TrendSide.LONG else direction_price < entry
        mfe = ((highest-entry) if candidate.side is TrendSide.LONG else (entry-lowest)) * multiplier
        mae = ((entry-lowest) if candidate.side is TrendSide.LONG else (highest-entry)) * multiplier
        label_hash = digest({"signal_time": candidate.bar_time,
                             "future": tuple(item.content_hash for item in future)})
        target_first = target_bar is not None and (stop_bar is None or target_bar < stop_bar)
        stop_first = stop_bar is not None and (target_bar is None or stop_bar <= target_bar)
        status = "KNOWN" if funding is not None else "UNKNOWN_FUNDING"
        return CandidateOutcomeLabel(
            status, future[0].open_time, exit_time, target_first, stop_first, direction,
            None if net is None else net > ZERO, target_bar, stop_bar, exit_bar, mfe, mae,
            gross, fees, funding, net, charge.event_count, charge.evidence_status,
            exit_reason, label_hash,
        )

    def _report(self, rows: Sequence[CandidateObservation]) -> dict[str, Any]:
        candidates = [item for item in rows if item.model_status == "CANDIDATE"]
        known = [item for item in candidates if item.label is not None and item.label.status == "KNOWN"]
        rejections = Counter((item.model, code) for item in rows for code in item.reject_codes)
        numeric = ("adx", "roc", "atr_pct", "volume_ratio", "ema_distance",
                   "vwap_distance", "price_slope", "ema_slope")
        cutoffs = {name: _quartiles([getattr(item, name) for item in known]) for name in numeric}
        dimensions: dict[str, Any] = {
            "model": _slice(known, lambda item: item.model),
            "side": _slice(known, lambda item: item.side),
            "symbol": _slice(known, lambda item: item.symbol),
            "local_regime": _slice(known, lambda item: item.local_regime),
            "higher_regime": _slice(known, lambda item: f"{item.higher_regime_status}:{item.higher_regime}"),
            "exit_reason": _slice(known, lambda item: item.label.exit_reason),
        }
        for name, boundaries in cutoffs.items():
            dimensions[name] = _slice(known, lambda item, n=name, b=boundaries: _quartile_name(getattr(item, n), b))
        return {
            "all_model_evaluations": len(rows),
            "candidates": len(candidates),
            "candidate_coverage": Decimal(len(candidates))/Decimal(len(rows)) if rows else ZERO,
            "rejected": len(rows)-len(candidates),
            "known_labels": len(known),
            "unknown_labels": len(candidates)-len(known),
            "rejection_distribution": [
                {"model": model, "code": code, "count": count}
                for (model, code), count in sorted(rejections.items())],
            "atr_and_numeric_quantile_cutoffs": cutoffs,
            "outcome_summary": _metrics(known),
            "slices": dimensions,
            "success_failure_distributions": {
                name: _success_failure(values, name) for name, values in
                ((field, known) for field in numeric)
            },
            "discriminability": {
                name: _standardized_difference(known, name) for name in numeric
            },
            "interpretation": "descriptive only; no cut-off was selected and no trading condition changed",
        }


def _quartiles(values: Sequence[Decimal]) -> tuple[Decimal, Decimal, Decimal]:
    if not values:
        return ZERO, ZERO, ZERO
    ordered = sorted(values)
    return tuple(ordered[min(len(ordered)-1, len(ordered)*part//4)] for part in (1, 2, 3))


def _quartile_name(value: Decimal, boundaries: tuple[Decimal, Decimal, Decimal]) -> str:
    return "Q1" if value <= boundaries[0] else "Q2" if value <= boundaries[1] else "Q3" if value <= boundaries[2] else "Q4"


def _metrics(rows: Sequence[CandidateObservation]) -> dict[str, Any]:
    total = len(rows)
    if not total:
        return {"count": 0, "target_first_rate": ZERO, "stop_first_rate": ZERO,
                "direction_accuracy": ZERO, "net_win_rate": ZERO, "expectancy": ZERO,
                "net_profit": ZERO, "mfe": ZERO, "mae": ZERO}
    labels = [item.label for item in rows]
    ratio = lambda name: Decimal(sum(bool(getattr(item, name)) for item in labels)) / Decimal(total)
    return {
        "count": total,
        "target_first_rate": ratio("target_first"),
        "stop_first_rate": ratio("stop_first"),
        "direction_accuracy": ratio("direction_correct"),
        "net_win_rate": ratio("net_profitable"),
        "expectancy": sum((item.net_pnl for item in labels), ZERO) / Decimal(total),
        "net_profit": sum((item.net_pnl for item in labels), ZERO),
        "mfe": sum((item.mfe for item in labels), ZERO) / Decimal(total),
        "mae": sum((item.mae for item in labels), ZERO) / Decimal(total),
    }


def _slice(rows: Sequence[CandidateObservation], key) -> list[dict[str, Any]]:
    groups: dict[str, list[CandidateObservation]] = defaultdict(list)
    for item in rows:
        groups[str(key(item))].append(item)
    return [{"value": name, **_metrics(values),
             "row_filter": {"dimension_value": name}}
            for name, values in sorted(groups.items())]


def _success_failure(rows: Sequence[CandidateObservation], name: str) -> dict[str, Any]:
    winners = [getattr(item, name) for item in rows if item.label.net_profitable]
    losers = [getattr(item, name) for item in rows if not item.label.net_profitable]
    summarize = lambda values: {"count": len(values), "mean": sum(values, ZERO)/Decimal(len(values)) if values else None,
                                "q25_q50_q75": _quartiles(values) if values else None}
    return {"net_profitable": summarize(winners), "not_net_profitable": summarize(losers)}


def _standardized_difference(rows: Sequence[CandidateObservation], name: str) -> Decimal | None:
    winners = [float(getattr(item, name)) for item in rows if item.label.net_profitable]
    losers = [float(getattr(item, name)) for item in rows if not item.label.net_profitable]
    if len(winners) < 2 or len(losers) < 2:
        return None
    def variance(values: Sequence[float]) -> float:
        mean = fmean(values)
        return sum((item-mean)**2 for item in values)/(len(values)-1)
    pooled = sqrt(((len(winners)-1)*variance(winners)+(len(losers)-1)*variance(losers)) /
                  (len(winners)+len(losers)-2))
    return None if pooled == 0 else Decimal(str((fmean(winners)-fmean(losers))/pooled))


def run_dual_profile_candidate_labels(evidence: Path, manifest_hash: str,
                                      market_facts_manifest_hash: str) -> dict[str, Any]:
    with FileEvidenceStore(evidence) as store:
        refs = {profile.value: CandidateLabelResearch(
            store, manifest_hash, market_facts_manifest_hash, profile).run()
            for profile in (TimeframeProfile.INTRADAY_15M, TimeframeProfile.SWING_4H)}
        index = store.put("dual_profile_candidate_label_index", {
            "schema": "htx-r2-candidate-label-index-v1",
            "manifest_hash": manifest_hash,
            "market_facts_manifest_hash": market_facts_manifest_hash,
            "dataset_role": "ALREADY_SEEN_DEVELOPMENT_DATA_NOT_NEW_OOS",
            "profile_results": refs,
        }, refs=(market_facts_manifest_hash, *refs.values()))
        return {"index_hash": index, "profile_results": refs}
