"""R2 P5 pre-registered 4h model optimization over expanded 4h/1d evidence."""
from __future__ import annotations

from dataclasses import asdict
from decimal import Decimal
from pathlib import Path
from typing import Any, Sequence

from ..market_data import period_seconds
from ..models import ModelCode
from ..timeframes import TimeframeProfile
from .experiments import block_bootstrap_expectancy_lower, permutation_p_value
from .model_conditions import MODEL_CONDITION_SPECS
from .model_optimization import _rank, _temporal_windows, _train_qualified
from .profile_backtest import ProfileLifecycleResearch, ResearchVariant
from .replay import load_manifest_candles
from .store import FileEvidenceStore, digest

ZERO = Decimal("0")
P5_SEED = 47
P5_DECISION_WINDOW = 200


def _condition(model: ModelCode) -> str:
    return {
        ModelCode.DONCHIAN_BREAKOUT: "DONCHIAN_1D_TREND",
        ModelCode.FIB_PULLBACK_CONTINUATION: "FIB_1D_TREND",
        ModelCode.EMA_VWAP_MOMENTUM_CONTINUATION: "EMA_1D_TREND",
    }[model]


def _daily_variant(variant_id: str, model: ModelCode, *,
                   confirmation: str | None = None,
                   condition_version: str | None = None,
                   entry_parameters: tuple[tuple[str, int | Decimal | bool], ...] = ()) -> ResearchVariant:
    condition_id = _condition(model)
    version = next(item.version for item in MODEL_CONDITION_SPECS
                   if item.condition_id == condition_id)
    return ResearchVariant(
        variant_id, (model,), confirmation=confirmation,
        condition_version=condition_version, entry_parameters=entry_parameters,
        model_condition=condition_id, model_condition_version=version)


P5_VARIANTS = (
    # Legacy rows quantify the effect of introducing the fixed 1d contract. They
    # are diagnostics, not selectable P5 candidates.
    ResearchVariant("P5_DONCHIAN_LEGACY", (ModelCode.DONCHIAN_BREAKOUT,)),
    _daily_variant("P5_DONCHIAN_BASE", ModelCode.DONCHIAN_BREAKOUT),
    *(_daily_variant(f"P5_DONCHIAN_LOOKBACK_{lookback}", ModelCode.DONCHIAN_BREAKOUT,
                     entry_parameters=(("breakout_lookback", lookback),))
      for lookback in (42, 84, 126, 168)),

    ResearchVariant("P5_FIB_LEGACY", (ModelCode.FIB_PULLBACK_CONTINUATION,)),
    _daily_variant("P5_FIB_BASE", ModelCode.FIB_PULLBACK_CONTINUATION),
    _daily_variant("P5_FIB_MACD", ModelCode.FIB_PULLBACK_CONTINUATION,
                   confirmation="MACD", condition_version="r2-p3-v1"),
    _daily_variant("P5_FIB_SOURCE_5", ModelCode.FIB_PULLBACK_CONTINUATION,
                   entry_parameters=(("fib_source_lookback_bars", 5),)),
    _daily_variant("P5_FIB_SOURCE_12", ModelCode.FIB_PULLBACK_CONTINUATION,
                   entry_parameters=(("fib_source_lookback_bars", 12),)),

    ResearchVariant("P5_EMA_LEGACY", (ModelCode.EMA_VWAP_MOMENTUM_CONTINUATION,)),
    _daily_variant("P5_EMA_BASE", ModelCode.EMA_VWAP_MOMENTUM_CONTINUATION),
    _daily_variant("P5_EMA_PRICE_SLOPE", ModelCode.EMA_VWAP_MOMENTUM_CONTINUATION,
                   confirmation="PRICE_SLOPE", condition_version="r2-p3-v2"),
    _daily_variant("P5_EMA_FAST_16", ModelCode.EMA_VWAP_MOMENTUM_CONTINUATION,
                   entry_parameters=(("fast_ema_period", 16),)),
    _daily_variant("P5_EMA_FAST_24", ModelCode.EMA_VWAP_MOMENTUM_CONTINUATION,
                   entry_parameters=(("fast_ema_period", 24),)),
    _daily_variant("P5_EMA_MOMENTUM_9", ModelCode.EMA_VWAP_MOMENTUM_CONTINUATION,
                   entry_parameters=(("momentum_lookback", 9),)),
    _daily_variant("P5_EMA_MOMENTUM_18", ModelCode.EMA_VWAP_MOMENTUM_CONTINUATION,
                   entry_parameters=(("momentum_lookback", 18),)),
)

P5_MODEL_BASELINES = {
    ModelCode.DONCHIAN_BREAKOUT.value: "P5_DONCHIAN_BASE",
    ModelCode.FIB_PULLBACK_CONTINUATION.value: "P5_FIB_BASE",
    ModelCode.EMA_VWAP_MOMENTUM_CONTINUATION.value: "P5_EMA_BASE",
}

P5_LEGACY_BASELINES = {
    ModelCode.DONCHIAN_BREAKOUT.value: "P5_DONCHIAN_LEGACY",
    ModelCode.FIB_PULLBACK_CONTINUATION.value: "P5_FIB_LEGACY",
    ModelCode.EMA_VWAP_MOMENTUM_CONTINUATION.value: "P5_EMA_LEGACY",
}


class SwingModelOptimization:
    """Select 4h variants on TRAIN and evaluate once on VALIDATION; never read TEST."""

    def __init__(self, store: FileEvidenceStore, manifest_hash: str,
                 market_facts_manifest_hash: str) -> None:
        self.store, self.manifest_hash = store, manifest_hash
        self.market_facts_manifest_hash = market_facts_manifest_hash

    def run(self, symbols: Sequence[str] = ("BTC-USDT", "ETH-USDT")) -> str:
        runner = ProfileLifecycleResearch(
            self.store, self.manifest_hash, self.market_facts_manifest_hash,
            TimeframeProfile.SWING_4H, window=P5_DECISION_WINDOW)
        dataset = self._dataset_summary(symbols)
        plan = {
            "schema": "htx-r2-p5-swing-model-plan-v1",
            "experiment_id": "EXP-20261018-005",
            "profile": TimeframeProfile.SWING_4H.value,
            "manifest_hash": self.manifest_hash,
            "market_facts_manifest_hash": self.market_facts_manifest_hash,
            "spec_hash": runner.spec.spec_hash,
            "symbols": tuple(symbols),
            "dataset": dataset,
            "variants": P5_VARIANTS,
            "split": {"TRAIN": (0, 60), "VALIDATION": (60, 80), "TEST": "NOT_CONSUMED"},
            "seed": P5_SEED,
            "decision_window": P5_DECISION_WINDOW,
            "search_budget": {"DONCHIAN_BREAKOUT": 5, "FIB_PULLBACK_CONTINUATION": 4,
                              "EMA_VWAP_MOMENTUM_CONTINUATION": 6},
            "fixed_context": (
                "each selectable variant requires closed 1d EMA20/60/120 stack, "
                "OLS close slope20, ADX14>=16 and side-aligned ROC12; missing context is UNKNOWN"),
            "eligible_p3_hypotheses": ("FIB+MACD", "EMA_VWAP+PRICE_SLOPE"),
            "mutation_rule": (
                "relative to each model's 1d-context baseline, each candidate changes exactly one "
                "P3-qualified confirmation or one pre-registered entry parameter"),
            "selection_rule": (
                "TRAIN BASE and CONSERVATIVE net>0, BASE PF>=1.30, >=30 trades and >=30% baseline coverage; "
                "rank by BASE expectancy, PF, net profit; VALIDATION is not used for selection"),
            "validation_rule": (
                ">=30 trades, >=30% coverage, net and expectancy>0, PF>=1.30, conservative net>=0, "
                "drawdown<=10% equity, net win lift>=5pp, permutation p<0.05, bootstrap lower>0, "
                "positive net in >=4/5 chronological windows"),
            "scope": "development only; no TEST, PAPER, LIVE, admission or gate promotion",
        }
        plan_ref = self.store.put("swing_model_optimization_plan", plan,
                                  refs=(self.manifest_hash, self.market_facts_manifest_hash))
        plan_hash = digest(plan)
        checkpoint_key = f"p5-swing-lifecycle:{plan_hash}"
        cached = self.store.checkpoint(checkpoint_key)
        lifecycle_ref = cached.get("result_ref") if isinstance(cached, dict) else None
        if lifecycle_ref is not None:
            self.store.verify(lifecycle_ref)
            cached_envelope = self.store.get(lifecycle_ref)
            if cached_envelope["kind"] != "profile_lifecycle_research":
                raise ValueError("P5 lifecycle checkpoint references the wrong artifact kind")
            diagnostics = (row.get("candidate_diagnostics", {})
                           for row in cached_envelope["payload"].get("results", ()))
            if not all("unknown" in item for item in diagnostics):
                lifecycle_ref = None
        if lifecycle_ref is None:
            lifecycle_ref = runner.run(symbols=symbols, variants=P5_VARIANTS,
                                       include_test=False, seed=P5_SEED)
            self.store.checkpoint(checkpoint_key, {"result_ref": lifecycle_ref})
        lifecycle = self.store.get(lifecycle_ref)["payload"]
        rows = {(row["variant_id"], row["cost"], row["split"]): row
                for row in lifecycle["results"]}
        validation_times = self._validation_times(symbols)
        model_results = []
        for model in ModelCode:
            variants = [item for item in P5_VARIANTS if item.models == (model,)]
            baseline_id = P5_MODEL_BASELINES[model.value]
            legacy_id = P5_LEGACY_BASELINES[model.value]
            baseline_train = rows[(baseline_id, "BASE", "TRAIN")]
            candidates = [item for item in variants
                          if item.variant_id not in {baseline_id, legacy_id}]
            qualified = [item for item in candidates
                         if _train_qualified(rows, item.variant_id, baseline_train)]
            ranked = sorted(qualified,
                            key=lambda item: _rank(rows[(item.variant_id, "BASE", "TRAIN")]),
                            reverse=True)
            diagnostic = max(candidates,
                             key=lambda item: _rank(rows[(item.variant_id, "BASE", "TRAIN")]))
            selected = ranked[0] if ranked else diagnostic
            model_results.append(self._evaluate_model(
                model, legacy_id, baseline_id, selected, bool(ranked), rows, validation_times))
        if any(item["conclusion"] == "ELIGIBLE_FOR_P7" for item in model_results):
            conclusion = "ELIGIBLE_FOR_P7"
        elif any(item["conclusion"] == "REJECT" for item in model_results):
            conclusion = "REJECT"
        else:
            conclusion = "INSUFFICIENT_EVIDENCE"
        return self.store.put("swing_model_optimization_result", {
            "schema": "htx-r2-p5-swing-model-result-v1",
            "profile": TimeframeProfile.SWING_4H.value,
            "strategy_version": runner.spec.strategy.version,
            "spec_hash": runner.spec.spec_hash,
            "plan_ref": plan_ref,
            "plan_hash": plan_hash,
            "lifecycle_result_ref": lifecycle_ref,
            "dataset": dataset,
            "test_segment": "NOT_READ",
            "model_results": model_results,
            "conclusion": conclusion,
            "decision": "P5 can only nominate P7 research; no condition, parameter or gate is promoted",
        }, refs=(plan_ref, lifecycle_ref))

    def _dataset_summary(self, symbols: Sequence[str]) -> dict[str, Any]:
        manifest = self.store.get(self.manifest_hash)["payload"]
        if manifest.get("integrity") != "COMPLETE" or "4hour" not in manifest.get("periods", ()) \
                or "1day" not in manifest.get("periods", ()):
            raise ValueError("P5 requires a COMPLETE 4hour+1day dataset")
        counts = {symbol: {
            period: len(load_manifest_candles(
                self.store, self.manifest_hash, symbol=symbol, period=period))
            for period in ("4hour", "1day")} for symbol in symbols}
        return {"start": manifest["start"], "end_exclusive": manifest["end_exclusive"],
                "counts": counts, "prior_six_month_4h_bars_per_symbol": 1098,
                "expansion_factor": Decimal(min(item["4hour"] for item in counts.values())) / Decimal(1098)}

    def _validation_times(self, symbols: Sequence[str]) -> tuple[int, ...]:
        times = set()
        for symbol in symbols:
            candles = load_manifest_candles(self.store, self.manifest_hash,
                                            symbol=symbol, period="4hour")
            start, end = len(candles)*60//100, len(candles)*80//100
            times.update(item.open_time for item in candles[start:end])
        return tuple(sorted(times))

    @staticmethod
    def _evaluate_model(model: ModelCode, legacy_id: str, baseline_id: str,
                        selected: ResearchVariant, train_qualified: bool, rows: dict,
                        validation_times: Sequence[int]) -> dict[str, Any]:
        legacy_validation = rows[(legacy_id, "BASE", "VALIDATION")]
        base_train = rows[(baseline_id, "BASE", "TRAIN")]
        selected_train = rows[(selected.variant_id, "BASE", "TRAIN")]
        base_validation = rows[(baseline_id, "BASE", "VALIDATION")]
        selected_validation = rows[(selected.variant_id, "BASE", "VALIDATION")]
        conservative = rows[(selected.variant_id, "CONSERVATIVE", "VALIDATION")]
        base_metrics, metrics = base_validation["metrics"], selected_validation["metrics"]
        values = [Decimal(str(item["net_pnl"])) for item in selected_validation["trade_ledger"]]
        if len(values) >= 5:
            p_value = permutation_p_value(values, seed=P5_SEED)
            bootstrap = block_bootstrap_expectancy_lower(
                values, block_size=min(5, len(values)), seed=P5_SEED)
        else:
            p_value, bootstrap = Decimal("1"), ZERO
        windows = _temporal_windows(
            base_validation["trade_ledger"], selected_validation["trade_ledger"],
            validation_times, step_seconds=period_seconds("4hour"))
        positive_windows = sum(item["selected_net_profit"] > ZERO for item in windows)
        trades, baseline_trades = int(metrics["trades"]), int(base_metrics["trades"])
        coverage = Decimal(trades)/Decimal(baseline_trades) if baseline_trades else ZERO
        expectancy = Decimal(metrics["net_profit"])/Decimal(trades) if trades else ZERO
        baseline_expectancy = (Decimal(base_metrics["net_profit"])/Decimal(baseline_trades)
                               if baseline_trades else ZERO)
        win_lift = Decimal(metrics["win_rate"])-Decimal(base_metrics["win_rate"])
        pf = metrics["profit_factor"]
        reasons = []
        if not train_qualified:
            reasons.append("no non-baseline variant passed the frozen TRAIN selection rule")
        checks = (
            (trades >= 30, "VALIDATION completed trades below 30"),
            (coverage >= Decimal("0.30"), "VALIDATION coverage below 30% of 1d-context baseline"),
            (Decimal(metrics["net_profit"]) > ZERO, "VALIDATION BASE net profit is not positive"),
            (expectancy > ZERO, "VALIDATION BASE expectancy is not positive"),
            (pf is not None and Decimal(pf) >= Decimal("1.30"), "VALIDATION BASE PF below 1.30"),
            (Decimal(conservative["metrics"]["net_profit"]) >= ZERO,
             "VALIDATION CONSERVATIVE net profit is negative"),
            (Decimal(metrics["max_drawdown"]) <= Decimal("1000"),
             "VALIDATION drawdown exceeds 10%"),
            (win_lift >= Decimal("0.05"), "VALIDATION net win rate lift below 5pp"),
            (p_value < Decimal("0.05"), "VALIDATION permutation p-value is not below 0.05"),
            (bootstrap > ZERO, "VALIDATION bootstrap expectancy lower bound is not positive"),
            (positive_windows >= 4, "fewer than 4/5 validation windows have positive net profit"),
        )
        reasons.extend(message for passed, message in checks if not passed)
        conclusion = ("ELIGIBLE_FOR_P7" if not reasons else
                      "INSUFFICIENT_EVIDENCE" if trades < 30 else "REJECT")
        return {
            "model": model.value,
            "legacy_variant": legacy_id,
            "baseline_variant": baseline_id,
            "selected_by_train": selected.variant_id if train_qualified else None,
            "diagnostic_top_by_train": selected.variant_id,
            "diagnostic_variant": asdict(selected),
            "train_qualified": train_qualified,
            "validation_legacy": legacy_validation["metrics"],
            "train_baseline": base_train["metrics"],
            "train_selected": selected_train["metrics"],
            "validation_baseline": base_metrics,
            "validation_selected": metrics,
            "validation_conservative": conservative["metrics"],
            "validation_coverage": coverage,
            "validation_expectancy": expectancy,
            "validation_expectancy_lift": expectancy-baseline_expectancy,
            "validation_net_win_rate_lift": win_lift,
            "permutation_p": p_value,
            "bootstrap_expectancy_lower": bootstrap,
            "temporal_windows": windows,
            "positive_net_windows": positive_windows,
            "candidate_diagnostics": selected_validation["candidate_diagnostics"],
            "conclusion": conclusion,
            "reasons": tuple(reasons),
        }


def run_swing_model_optimization(evidence: Path, manifest_hash: str,
                                 market_facts_manifest_hash: str) -> dict[str, str]:
    with FileEvidenceStore(evidence) as store:
        result = SwingModelOptimization(
            store, manifest_hash, market_facts_manifest_hash).run()
        return {"result_hash": result}
