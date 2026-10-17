"""R2 P4 pre-registered 15m single-variable model optimization."""
from __future__ import annotations

from dataclasses import asdict
from decimal import Decimal
from pathlib import Path
from typing import Any, Sequence

from ..models import ModelCode
from ..timeframes import TimeframeProfile
from .experiments import (block_bootstrap_expectancy_lower, permutation_p_value)
from .model_conditions import MODEL_CONDITION_SPECS
from .profile_backtest import ProfileLifecycleResearch, ResearchVariant
from .replay import load_manifest_candles
from .store import FileEvidenceStore, digest

ZERO = Decimal("0")
P4_SEED = 31


def _condition_variant(variant_id: str, model: ModelCode, condition_id: str) -> ResearchVariant:
    version = next(item.version for item in MODEL_CONDITION_SPECS
                   if item.condition_id == condition_id)
    return ResearchVariant(variant_id, (model,), model_condition=condition_id,
                           model_condition_version=version)


P4_VARIANTS = (
    ResearchVariant("P4_DONCHIAN_BASE", (ModelCode.DONCHIAN_BREAKOUT,)),
    _condition_variant("P4_DONCHIAN_BODY_055", ModelCode.DONCHIAN_BREAKOUT,
                       "DONCHIAN_BODY_RATIO_055"),
    _condition_variant("P4_DONCHIAN_BREAKOUT_ATR_010", ModelCode.DONCHIAN_BREAKOUT,
                       "DONCHIAN_BREAKOUT_ATR_010"),
    _condition_variant("P4_DONCHIAN_VOLUME_110", ModelCode.DONCHIAN_BREAKOUT,
                       "DONCHIAN_VOLUME_110"),
    _condition_variant("P4_DONCHIAN_60M_ALIGN", ModelCode.DONCHIAN_BREAKOUT,
                       "DONCHIAN_60M_ALIGN"),
    ResearchVariant("P4_DONCHIAN_LOOKBACK_16", (ModelCode.DONCHIAN_BREAKOUT,),
                    entry_parameters=(("breakout_lookback", 16),)),
    ResearchVariant("P4_DONCHIAN_LOOKBACK_24", (ModelCode.DONCHIAN_BREAKOUT,),
                    entry_parameters=(("breakout_lookback", 24),)),

    ResearchVariant("P4_FIB_BASE", (ModelCode.FIB_PULLBACK_CONTINUATION,)),
    _condition_variant("P4_FIB_60M_ALIGN", ModelCode.FIB_PULLBACK_CONTINUATION,
                       "FIB_60M_ALIGN"),
    _condition_variant("P4_FIB_IMPULSE_ATR_300", ModelCode.FIB_PULLBACK_CONTINUATION,
                       "FIB_IMPULSE_ATR_300"),
    _condition_variant("P4_FIB_DEPTH_CENTER", ModelCode.FIB_PULLBACK_CONTINUATION,
                       "FIB_DEPTH_CENTER"),
    _condition_variant("P4_FIB_VOLUME_100", ModelCode.FIB_PULLBACK_CONTINUATION,
                       "FIB_VOLUME_100"),
    ResearchVariant("P4_FIB_SOURCE_5", (ModelCode.FIB_PULLBACK_CONTINUATION,),
                    entry_parameters=(("fib_source_lookback_bars", 5),)),
    ResearchVariant("P4_FIB_SOURCE_12", (ModelCode.FIB_PULLBACK_CONTINUATION,),
                    entry_parameters=(("fib_source_lookback_bars", 12),)),

    ResearchVariant("P4_EMA_BASE", (ModelCode.EMA_VWAP_MOMENTUM_CONTINUATION,)),
    _condition_variant("P4_EMA_60M_ALIGN", ModelCode.EMA_VWAP_MOMENTUM_CONTINUATION,
                       "EMA_60M_ALIGN"),
    _condition_variant("P4_EMA_4H_NOT_REVERSE", ModelCode.EMA_VWAP_MOMENTUM_CONTINUATION,
                       "EMA_4H_NOT_REVERSE"),
    _condition_variant("P4_EMA_ADX_RISING", ModelCode.EMA_VWAP_MOMENTUM_CONTINUATION,
                       "EMA_ADX_RISING"),
    _condition_variant("P4_EMA_VWAP_RECLAIM", ModelCode.EMA_VWAP_MOMENTUM_CONTINUATION,
                       "EMA_VWAP_RECLAIM"),
    _condition_variant("P4_EMA_TARGET_SPACE_120", ModelCode.EMA_VWAP_MOMENTUM_CONTINUATION,
                       "EMA_TARGET_SPACE_120"),
    ResearchVariant("P4_EMA_FAST_16", (ModelCode.EMA_VWAP_MOMENTUM_CONTINUATION,),
                    entry_parameters=(("fast_ema_period", 16),)),
    ResearchVariant("P4_EMA_FAST_24", (ModelCode.EMA_VWAP_MOMENTUM_CONTINUATION,),
                    entry_parameters=(("fast_ema_period", 24),)),
)

MODEL_BASELINES = {
    ModelCode.DONCHIAN_BREAKOUT.value: "P4_DONCHIAN_BASE",
    ModelCode.FIB_PULLBACK_CONTINUATION.value: "P4_FIB_BASE",
    ModelCode.EMA_VWAP_MOMENTUM_CONTINUATION.value: "P4_EMA_BASE",
}


class IntradayModelOptimization:
    """Select on TRAIN and evaluate once on VALIDATION; never reads TEST."""

    def __init__(self, store: FileEvidenceStore, manifest_hash: str,
                 market_facts_manifest_hash: str) -> None:
        self.store, self.manifest_hash = store, manifest_hash
        self.market_facts_manifest_hash = market_facts_manifest_hash

    def run(self, symbols: Sequence[str] = ("BTC-USDT", "ETH-USDT")) -> str:
        runner = ProfileLifecycleResearch(
            self.store, self.manifest_hash, self.market_facts_manifest_hash,
            TimeframeProfile.INTRADAY_15M)
        plan = {
            "schema": "htx-r2-p4-intraday-model-plan-v2",
            "experiment_id": "EXP-20261018-004",
            "profile": TimeframeProfile.INTRADAY_15M.value,
            "manifest_hash": self.manifest_hash,
            "market_facts_manifest_hash": self.market_facts_manifest_hash,
            "spec_hash": runner.spec.spec_hash,
            "symbols": tuple(symbols),
            "variants": P4_VARIANTS,
            "split": {"TRAIN": (0, 60), "VALIDATION": (60, 80), "TEST": "NOT_CONSUMED"},
            "seed": P4_SEED,
            "search_budget": {"DONCHIAN_BREAKOUT": 7, "FIB_PULLBACK_CONTINUATION": 7,
                              "EMA_VWAP_MOMENTUM_CONTINUATION": 8},
            "mutation_rule": "each non-baseline variant changes exactly one condition or parameter",
            "selection_rule": (
                "TRAIN BASE and CONSERVATIVE net>0, BASE PF>=1.30, >=30 trades and >=30% baseline coverage; "
                "rank by BASE expectancy, PF, net profit; VALIDATION is not used for selection"),
            "validation_rule": (
                ">=30 trades, >=30% coverage, net and expectancy>0, PF>=1.30, conservative net>=0, "
                "drawdown<=10% equity, net win lift>=5pp, permutation p<0.05, bootstrap lower>0, "
                "positive net in >=4/5 chronological windows"),
            "scope": "development only; no TEST, PAPER, LIVE, admission or gate promotion",
        }
        plan_ref = self.store.put("intraday_model_optimization_plan", plan,
                                  refs=(self.manifest_hash, self.market_facts_manifest_hash))
        plan_hash = digest(plan)
        checkpoint_key = f"p4-intraday-lifecycle:{plan_hash}"
        cached = self.store.checkpoint(checkpoint_key)
        lifecycle_ref = cached.get("result_ref") if isinstance(cached, dict) else None
        if lifecycle_ref is not None:
            self.store.verify(lifecycle_ref)
            cached_envelope = self.store.get(lifecycle_ref)
            if cached_envelope["kind"] != "profile_lifecycle_research":
                raise ValueError("P4 lifecycle checkpoint references the wrong artifact kind")
            if not all("candidate_diagnostics" in row
                       for row in cached_envelope["payload"].get("results", ())):
                lifecycle_ref = None
        if lifecycle_ref is None:
            lifecycle_ref = runner.run(symbols=symbols, variants=P4_VARIANTS,
                                       include_test=False, seed=P4_SEED)
            self.store.checkpoint(checkpoint_key, {"result_ref": lifecycle_ref})
        lifecycle = self.store.get(lifecycle_ref)["payload"]
        rows = {(row["variant_id"], row["cost"], row["split"]): row
                for row in lifecycle["results"]}
        validation_times = self._validation_times(symbols)
        model_results = []
        for model in ModelCode:
            variants = [item for item in P4_VARIANTS if item.models == (model,)]
            baseline_id = MODEL_BASELINES[model.value]
            baseline_train = rows[(baseline_id, "BASE", "TRAIN")]
            qualified = [item for item in variants if item.variant_id != baseline_id and
                         _train_qualified(rows, item.variant_id, baseline_train)]
            ranked = sorted(qualified, key=lambda item: _rank(rows[(item.variant_id, "BASE", "TRAIN")]),
                            reverse=True)
            diagnostic = max((item for item in variants if item.variant_id != baseline_id),
                             key=lambda item: _rank(rows[(item.variant_id, "BASE", "TRAIN")]))
            selected = ranked[0] if ranked else diagnostic
            model_results.append(self._evaluate_model(model, baseline_id, selected, bool(ranked),
                                                       rows, validation_times))
        conclusion = ("ELIGIBLE_FOR_P7" if any(item["conclusion"] == "ELIGIBLE_FOR_P7"
                                                for item in model_results)
                      else "REJECT")
        return self.store.put("intraday_model_optimization_result", {
            "schema": "htx-r2-p4-intraday-model-result-v1",
            "profile": TimeframeProfile.INTRADAY_15M.value,
            "strategy_version": runner.spec.strategy.version,
            "spec_hash": runner.spec.spec_hash,
            "plan_ref": plan_ref,
            "plan_hash": plan_hash,
            "lifecycle_result_ref": lifecycle_ref,
            "test_segment": "NOT_READ",
            "model_results": model_results,
            "conclusion": conclusion,
            "decision": "no condition or parameter promoted; P4 can only nominate P7 research",
        }, refs=(plan_ref, lifecycle_ref))

    def _validation_times(self, symbols: Sequence[str]) -> tuple[int, ...]:
        times = set()
        for symbol in symbols:
            candles = load_manifest_candles(self.store, self.manifest_hash,
                                            symbol=symbol, period="15min")
            start, end = len(candles)*60//100, len(candles)*80//100
            times.update(item.open_time for item in candles[start:end])
        return tuple(sorted(times))

    @staticmethod
    def _evaluate_model(model: ModelCode, baseline_id: str, selected: ResearchVariant,
                        train_qualified: bool, rows: dict, validation_times: Sequence[int]) -> dict[str, Any]:
        base_train = rows[(baseline_id, "BASE", "TRAIN")]
        selected_train = rows[(selected.variant_id, "BASE", "TRAIN")]
        base_validation = rows[(baseline_id, "BASE", "VALIDATION")]
        selected_validation = rows[(selected.variant_id, "BASE", "VALIDATION")]
        conservative = rows[(selected.variant_id, "CONSERVATIVE", "VALIDATION")]
        base_metrics, metrics = base_validation["metrics"], selected_validation["metrics"]
        values = [Decimal(str(item["net_pnl"])) for item in selected_validation["trade_ledger"]]
        if len(values) >= 5:
            p_value = permutation_p_value(values, seed=P4_SEED)
            bootstrap = block_bootstrap_expectancy_lower(
                values, block_size=min(5, len(values)), seed=P4_SEED)
        else:
            p_value, bootstrap = Decimal("1"), ZERO
        windows = _temporal_windows(base_validation["trade_ledger"],
                                    selected_validation["trade_ledger"], validation_times)
        positive_windows = sum(item["selected_net_profit"] > ZERO for item in windows)
        trades = int(metrics["trades"])
        baseline_trades = int(base_metrics["trades"])
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
            (coverage >= Decimal("0.30"), "VALIDATION coverage below 30% of model baseline"),
            (Decimal(metrics["net_profit"]) > ZERO, "VALIDATION BASE net profit is not positive"),
            (expectancy > ZERO, "VALIDATION BASE expectancy is not positive"),
            (pf is not None and Decimal(pf) >= Decimal("1.30"), "VALIDATION BASE PF below 1.30"),
            (Decimal(conservative["metrics"]["net_profit"]) >= ZERO,
             "VALIDATION CONSERVATIVE net profit is negative"),
            (Decimal(metrics["max_drawdown"]) <= Decimal("1000"), "VALIDATION drawdown exceeds 10%"),
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
            "baseline_variant": baseline_id,
            "selected_by_train": selected.variant_id if train_qualified else None,
            "diagnostic_top_by_train": selected.variant_id,
            "diagnostic_variant": asdict(selected),
            "train_qualified": train_qualified,
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
            "conclusion": conclusion,
            "reasons": tuple(reasons),
        }


def _train_qualified(rows: dict, variant_id: str, baseline: dict) -> bool:
    base = rows[(variant_id, "BASE", "TRAIN")]["metrics"]
    conservative = rows[(variant_id, "CONSERVATIVE", "TRAIN")]["metrics"]
    trades, baseline_trades = int(base["trades"]), int(baseline["metrics"]["trades"])
    coverage = Decimal(trades)/Decimal(baseline_trades) if baseline_trades else ZERO
    expectancy = Decimal(base["net_profit"])/Decimal(trades) if trades else ZERO
    return (trades >= 30 and coverage >= Decimal("0.30") and Decimal(base["net_profit"]) > ZERO
            and expectancy > ZERO and base["profit_factor"] is not None
            and Decimal(base["profit_factor"]) >= Decimal("1.30")
            and Decimal(conservative["net_profit"]) >= ZERO)


def _rank(row: dict) -> tuple[Decimal, Decimal, Decimal, int]:
    metrics = row["metrics"]
    trades = int(metrics["trades"])
    expectancy = Decimal(metrics["net_profit"])/Decimal(trades) if trades else Decimal("-Infinity")
    pf = Decimal(metrics["profit_factor"]) if metrics["profit_factor"] is not None else Decimal("Infinity")
    return expectancy, pf, Decimal(metrics["net_profit"]), trades


def _temporal_windows(baseline: Sequence[dict], selected: Sequence[dict],
                      times: Sequence[int]) -> list[dict[str, Any]]:
    output = []
    for number in range(5):
        values = times[len(times)*number//5:len(times)*(number+1)//5]
        start, end = (values[0], values[-1]+900) if values else (0, 0)
        base_net = sum((Decimal(str(item["net_pnl"])) for item in baseline
                        if start <= int(item["entry_time"]) < end), ZERO)
        selected_net = sum((Decimal(str(item["net_pnl"])) for item in selected
                            if start <= int(item["entry_time"]) < end), ZERO)
        output.append({"window": number+1, "start": start, "end_exclusive": end,
                       "baseline_net_profit": base_net,
                       "selected_net_profit": selected_net,
                       "net_profit_lift": selected_net-base_net})
    return output


def run_intraday_model_optimization(evidence: Path, manifest_hash: str,
                                    market_facts_manifest_hash: str) -> dict[str, str]:
    with FileEvidenceStore(evidence) as store:
        result = IntradayModelOptimization(
            store, manifest_hash, market_facts_manifest_hash).run()
        return {"result_hash": result}
