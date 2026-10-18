"""R2 P6 paired exit experiments over frozen P4/P5 entry ledgers."""
from __future__ import annotations

from dataclasses import asdict
from decimal import Decimal
from pathlib import Path
from typing import Any, Sequence

from ..market_data import Candle, period_seconds
from ..models import ModelCode, TrendSide
from ..timeframes import TimeframeProfile
from .experiments import (DEFAULT_COST_SCENARIOS, block_bootstrap_expectancy_lower,
                          permutation_p_value)
from .exit_conditions import (EXIT_CONDITIONS, EXIT_CONDITION_SPECS, ConditionStatus,
                              ExitConditionContext, ResearchExitAction, evaluate_exit_condition)
from .features import feature_vector, performance
from .model_conditions import required_periods
from .model_optimization import MODEL_BASELINES, P4_VARIANTS, _temporal_windows
from .profile_backtest import ProfileLifecycleResearch, ResearchVariant
from .replay import load_manifest_candles
from .store import FileEvidenceStore, digest
from .swing_model_optimization import P5_DECISION_WINDOW, P5_MODEL_BASELINES, P5_VARIANTS

ZERO = Decimal("0")
P6_SEED = 61
BASE_FEE_RATE = Decimal("0.0004")


class ExitOptimization:
    """Change one exit condition while holding every parent BASE entry fixed."""

    def __init__(self, store: FileEvidenceStore, manifest_hash: str,
                 market_facts_manifest_hash: str, lifecycle_result_hash: str,
                 profile: TimeframeProfile) -> None:
        self.store, self.manifest_hash = store, manifest_hash
        self.market_facts_manifest_hash = market_facts_manifest_hash
        self.lifecycle_result_hash, self.profile = lifecycle_result_hash, profile
        self.parent = store.get(lifecycle_result_hash)["payload"]
        if self.parent.get("test_segment") != "NOT_READ":
            raise ValueError("P6 requires a parent lifecycle that did not read TEST")
        if self.parent.get("profile") != profile.value:
            raise ValueError("P6 parent lifecycle profile mismatch")
        self.variants = P4_VARIANTS if profile is TimeframeProfile.INTRADAY_15M else P5_VARIANTS
        self.baselines = MODEL_BASELINES if profile is TimeframeProfile.INTRADAY_15M else P5_MODEL_BASELINES
        self.window = 160 if profile is TimeframeProfile.INTRADAY_15M else P5_DECISION_WINDOW
        self.runner = ProfileLifecycleResearch(store, manifest_hash, market_facts_manifest_hash,
                                               profile, window=self.window)
        self._funding_cache: dict[tuple[str, str, int, int, int], Any] = {}
        self._decision_cache: dict[tuple[str, str], Any] = {}

    def run(self, symbols: Sequence[str] = ("BTC-USDT", "ETH-USDT")) -> str:
        plan = {
            "schema": "htx-r2-p6-exit-plan-v1",
            "experiment_id": f"EXP-20261018-006-{self.profile.value}",
            "profile": self.profile.value,
            "manifest_hash": self.manifest_hash,
            "market_facts_manifest_hash": self.market_facts_manifest_hash,
            "parent_lifecycle_result_hash": self.lifecycle_result_hash,
            "entry_freeze": "BASE parent ledger signal_time, entry_time, quantity and model",
            "conditions": EXIT_CONDITION_SPECS,
            "costs": DEFAULT_COST_SCENARIOS,
            "split": {"TRAIN": (0, 60), "VALIDATION": (60, 80), "TEST": "NOT_READ"},
            "seed": P6_SEED,
            "selection_rule": (
                "within each model select on TRAIN only: >=30 paired trades, condition triggers, "
                "BASE net and expectancy lift>0, CONSERVATIVE net lift>=0; rank by BASE net lift"),
            "validation_rule": (
                ">=30 paired trades, selected net and expectancy>0, PF>=1.30, conservative net>=0, "
                "drawdown<=10%, paired permutation p<0.05, paired bootstrap lift lower>0, "
                "positive net lift in >=4/5 windows"),
            "safety": "hard stop and original baseline exit retain priority; condition can only shorten a trade",
            "scope": "research only; no TEST, PAPER, LIVE, admission, formal V4 mutation or order path",
        }
        plan_ref = self.store.put("exit_optimization_plan", plan,
                                  refs=(self.manifest_hash, self.market_facts_manifest_hash,
                                        self.lifecycle_result_hash))
        series = {symbol: load_manifest_candles(self.store, self.manifest_hash, symbol=symbol,
                                                period=self.runner.spec.entry_period)
                  for symbol in symbols}
        rows = {(row["variant_id"], row["cost"], row["split"]): row
                for row in self.parent["results"]}
        results: list[dict[str, Any]] = []
        model_evaluations: list[dict[str, Any]] = []
        for model in ModelCode:
            baseline_id = self.baselines[model.value]
            variant = next(item for item in self.variants if item.variant_id == baseline_id)
            replay = self._prepare_replay(series, variant, symbols)
            for split in ("TRAIN", "VALIDATION"):
                frozen = rows[(baseline_id, "BASE", split)]["trade_ledger"]
                for cost in DEFAULT_COST_SCENARIOS:
                    baseline = [self._baseline_cost_trade(item, cost.name, cost.fee_rate,
                                                          cost.slippage_rate, series[item["symbol"]])
                                for item in frozen]
                    results.append(self._row(model, "BASELINE_EXIT", None, cost.name, split,
                                             baseline, frozen_count=len(frozen)))
                    for spec in EXIT_CONDITION_SPECS:
                        trades = [self._replay_trade(item, spec.condition_id, cost.name,
                                                     cost.fee_rate, cost.slippage_rate,
                                                     series[item["symbol"]],
                                                     replay[item["symbol"]]) for item in frozen]
                        results.append(self._row(model, spec.condition_id, spec.version, cost.name,
                                                 split, trades, frozen_count=len(frozen)))
            model_evaluations.append(self._evaluate_model(model, results, series))
        conclusion = ("ELIGIBLE_FOR_P7" if any(item["conclusion"] == "ELIGIBLE_FOR_P7"
                                                for item in model_evaluations)
                      else "INSUFFICIENT_EVIDENCE" if all(
                          item["conclusion"] == "INSUFFICIENT_EVIDENCE" for item in model_evaluations)
                      else "REJECT")
        return self.store.put("exit_optimization_result", {
            "schema": "htx-r2-p6-exit-result-v1",
            "profile": self.profile.value,
            "strategy_version": self.runner.spec.strategy.version,
            "spec_hash": self.runner.spec.spec_hash,
            "plan_ref": plan_ref,
            "plan_hash": digest(plan),
            "parent_lifecycle_result_hash": self.lifecycle_result_hash,
            "test_segment": "NOT_READ",
            "results": results,
            "model_results": model_evaluations,
            "conclusion": conclusion,
            "decision": "P6 may nominate research combinations for P7 only; formal V4 remains unchanged",
        }, refs=(plan_ref, self.lifecycle_result_hash))

    def _prepare_replay(self, series: dict[str, Sequence[Candle]], variant: ResearchVariant,
                        symbols: Sequence[str]) -> dict[str, dict[str, Any]]:
        periods = required_periods(variant.model_condition)
        output = {}
        config = self.runner._variant_config(variant)
        config_hash = digest(config)
        for symbol in symbols:
            candles = series[symbol]
            context = {period: load_manifest_candles(self.store, self.manifest_hash,
                                                     symbol=symbol, period=period)
                       for period in periods}
            decisions = self._decision_cache.get((symbol, config_hash))
            if decisions is None:
                decisions = self.runner._decisions(symbol, candles, config)
                self._decision_cache[(symbol, config_hash)] = decisions
            output[symbol] = {"decisions": decisions,
                              "context": context,
                              "index": {item.open_time: number for number, item in enumerate(candles)},
                              "variant": variant, "features": {}}
        return output

    def _replay_trade(self, source: dict, condition_id: str, cost_name: str, fee_rate: Decimal,
                      slippage: Decimal, candles: Sequence[Candle], replay: dict) -> dict:
        signal_index = replay["index"].get(int(source["signal_time"]))
        entry_index = replay["index"].get(int(source["entry_time"]))
        exit_index = replay["index"].get(int(source["exit_time"]))
        if None in (signal_index, entry_index, exit_index):
            raise ValueError("frozen entry references a candle outside the manifest")
        decision = replay["decisions"][signal_index]
        if decision is None:
            raise ValueError("frozen signal has no same-source decision")
        candidate = self.runner._select(decision, candles, signal_index, replay["variant"],
                                        context=replay["context"])
        if candidate is None or candidate.model.value != source["model"]:
            raise ValueError("frozen entry no longer resolves to its original model candidate")
        quantity = int(source["quantity"])
        contract_size = self.runner.market_facts.contract(source["symbol"]).contract_size
        multiplier = contract_size * quantity
        adverse = Decimal("1") + slippage if candidate.side is TrendSide.LONG else Decimal("1") - slippage
        entry = candles[entry_index].open * adverse
        entry_fee = entry * multiplier * fee_rate
        highest = lowest = entry
        protection = None
        decay_streak = 0
        acted = False
        trace: list[dict] = []
        for index in range(entry_index, exit_index + 1):
            candle = candles[index]
            # The parent hard stop/target/formal exit owns this timestamp.  A
            # research condition never overrides an equal-priority baseline exit.
            if candle.open_time >= int(source["exit_time"]):
                break
            highest, lowest = max(highest, candle.high), min(lowest, candle.low)
            if protection is not None:
                breached = candle.low <= protection if candidate.side is TrendSide.LONG else candle.high >= protection
                if breached:
                    price = min(candle.open, protection) if candidate.side is TrendSide.LONG else max(candle.open, protection)
                    return self._early_trade(source, entry, price, candle.open_time, fee_rate,
                                             contract_size, highest, lowest, candles,
                                             cost_name, condition_id,
                                             f"{condition_id}:PROTECTION_BREACH", trace)
            current = replay["decisions"][index]
            if current is None or current.signal.indicators is None:
                trace.append({"condition_id": condition_id,
                              "version": EXIT_CONDITIONS[condition_id].version,
                              "status": ConditionStatus.UNKNOWN.value,
                              "actual": {"bar_time": candle.open_time},
                              "threshold": {}, "reason": "same-source indicators unavailable",
                              "action": ResearchExitAction.HOLD.value,
                              "state_before": "OPEN", "state_after": "OPEN",
                              "protection_price": None, "next_decay_streak": decay_streak})
                continue
            factors = current.signal.indicators
            feature = replay["features"].get(index)
            if feature is None and index >= 120:
                feature = feature_vector(candles[index-120:index+1])
                replay["features"][index] = feature
            favorable = highest-entry if candidate.side is TrendSide.LONG else entry-lowest
            risk = abs(entry-candidate.invalidation_price)
            max_r = favorable/risk if risk > ZERO else ZERO
            gross = ((candle.close-entry) if candidate.side is TrendSide.LONG else (entry-candle.close))*multiplier
            close_fee = candle.close*multiplier*fee_rate
            funding = self._funding_charge(source["symbol"], candidate.side, quantity,
                                           int(source["entry_time"]), candle.open_time, candles)
            if funding.evidence_status != "COMPLETE" or funding.amount is None:
                raise ValueError(f"funding evidence unavailable: {funding.evidence_status}")
            context = ExitConditionContext(
                candidate, index-entry_index+1, entry, candle.close, factors.atr, factors.adx,
                factors.roc, factors.ema_fast, factors.ema_slow,
                None if feature is None else feature.close_slope20,
                None if feature is None else feature.ema20_slope10,
                gross-entry_fee-close_fee-funding.amount,
                favorable*multiplier-entry_fee-close_fee, close_fee, multiplier, max_r,
                current.signal.side, decay_streak, self.profile.value)
            result = evaluate_exit_condition(condition_id, context)
            decay_streak = result.next_decay_streak
            trace.append(asdict(result))
            if result.action is ResearchExitAction.ARM_PROTECTION:
                acted = True
                protection = _tighten(candidate.side, protection, result.protection_price)
            elif result.action is ResearchExitAction.FULL_CLOSE:
                return self._early_trade(source, entry, candle.close, candle.open_time, fee_rate,
                                         contract_size, highest, lowest, candles, cost_name,
                                         condition_id, condition_id, trace)
        trace.append({"condition_id": condition_id,
                      "version": EXIT_CONDITIONS[condition_id].version,
                      "status": ConditionStatus.SKIPPED.value,
                      "actual": {"baseline_exit_reason": source["exit_reason"],
                                 "baseline_exit_time": source["exit_time"]},
                      "threshold": {"priority": "IMMUTABLE_BASELINE_EXIT"},
                      "action": ResearchExitAction.HOLD.value,
                      "state_before": "OPEN", "state_after": "CLOSED_BY_BASELINE",
                      "reason": "original hard stop, target or formal exit retained priority",
                      "protection_price": protection, "next_decay_streak": decay_streak})
        baseline = self._baseline_cost_trade(source, cost_name, fee_rate, slippage, candles)
        baseline["condition_trace"] = trace
        baseline["condition_triggered"] = acted
        baseline["exit_variant_id"] = condition_id
        return baseline

    def _early_trade(self, source: dict, entry: Decimal, exit_price: Decimal, exit_time: int,
                     fee_rate: Decimal, contract_size: Decimal, highest: Decimal, lowest: Decimal,
                     candles: Sequence[Candle], cost_name: str, exit_variant_id: str,
                     reason: str, trace: list[dict]) -> dict:
        quantity = int(source["quantity"])
        multiplier = contract_size*quantity
        side = TrendSide(source["side"])
        gross = ((exit_price-entry) if side is TrendSide.LONG else (entry-exit_price))*multiplier
        fees = (entry+exit_price)*multiplier*fee_rate
        funding = self._funding_charge(source["symbol"], side, quantity,
                                       int(source["entry_time"]), exit_time, candles)
        if funding.evidence_status != "COMPLETE" or funding.amount is None:
            raise ValueError(f"funding evidence unavailable: {funding.evidence_status}")
        mfe = (highest-entry if side is TrendSide.LONG else entry-lowest)*multiplier
        mae = (entry-lowest if side is TrendSide.LONG else highest-entry)*multiplier
        result = dict(source)
        result.update(cost=cost_name, entry_variant_id=source["variant_id"],
                      exit_variant_id=exit_variant_id,
                      entry_price=entry, exit_price=exit_price, exit_time=exit_time,
                      gross_pnl=gross, fees=fees, funding=funding.amount,
                      funding_event_count=funding.event_count,
                      net_pnl=gross-fees-funding.amount, mfe=mfe, mae=mae,
                      exit_reason=reason, condition_trace=trace, condition_triggered=True)
        return result

    def _funding_charge(self, symbol: str, side: TrendSide, quantity: int,
                        entry_time: int, exit_time: int, candles: Sequence[Candle]):
        key = (symbol, side.value, quantity, entry_time, exit_time)
        cached = self._funding_cache.get(key)
        if cached is None:
            cached = self.runner.market_facts.charge(
                symbol, side, quantity=quantity, entry_time=entry_time, exit_time=exit_time,
                candles=candles, period=self.runner.spec.entry_period)
            self._funding_cache[key] = cached
        return cached

    def _baseline_cost_trade(self, source: dict, cost_name: str, fee_rate: Decimal,
                             slippage: Decimal, candles: Sequence[Candle]) -> dict:
        result = dict(source)
        side = TrendSide(source["side"])
        entry_candle = next(item for item in candles if item.open_time == int(source["entry_time"]))
        adverse = Decimal("1") + slippage if side is TrendSide.LONG else Decimal("1") - slippage
        new_entry = entry_candle.open*adverse
        old_entry = Decimal(str(source["entry_price"]))
        multiplier = (self.runner.market_facts.contract(source["symbol"]).contract_size
                      * int(source["quantity"]))
        # Preserve parent partial-close accounting.  Entry slippage adjusts the
        # frozen gross by its effective multiplier and fees scale by scenario.
        gross = Decimal(str(source["gross_pnl"]))
        if multiplier:
            gross += (old_entry-new_entry)*multiplier if side is TrendSide.LONG else (new_entry-old_entry)*multiplier
        fees = (Decimal(str(source["fees"]))*fee_rate/BASE_FEE_RATE
                if BASE_FEE_RATE else ZERO)
        funding = Decimal(str(source["funding"]))
        result.update(cost=cost_name, entry_variant_id=source["variant_id"],
                      exit_variant_id="BASELINE_EXIT", entry_price=new_entry,
                      gross_pnl=gross, fees=fees,
                      net_pnl=gross-fees-funding, condition_trace=[], condition_triggered=False)
        return result

    @staticmethod
    def _row(model: ModelCode, variant_id: str, version: str | None, cost: str, split: str,
             trades: list[dict], *, frozen_count: int) -> dict:
        metrics = performance(trades)
        return {"model": model.value, "variant_id": variant_id, "condition_version": version,
                "cost": cost, "split": split, "frozen_entries": frozen_count,
                "paired_trades": len(trades), "condition_triggered": sum(
                    bool(item.get("condition_triggered")) for item in trades),
                "condition_status_counts": _status_counts(trades),
                "metrics": asdict(metrics), "trade_ledger": trades}

    def _evaluate_model(self, model: ModelCode, result_rows: list[dict],
                        series: dict[str, Sequence[Candle]]) -> dict:
        rows = {(row["model"], row["variant_id"], row["cost"], row["split"]): row
                for row in result_rows}
        baseline_train = rows[(model.value, "BASELINE_EXIT", "BASE", "TRAIN")]
        candidates = []
        for spec in EXIT_CONDITION_SPECS:
            base = rows[(model.value, spec.condition_id, "BASE", "TRAIN")]
            conservative = rows[(model.value, spec.condition_id, "CONSERVATIVE", "TRAIN")]
            baseline_conservative = rows[(model.value, "BASELINE_EXIT", "CONSERVATIVE", "TRAIN")]
            net_lift = Decimal(base["metrics"]["net_profit"])-Decimal(baseline_train["metrics"]["net_profit"])
            conservative_lift = (Decimal(conservative["metrics"]["net_profit"])
                                 - Decimal(baseline_conservative["metrics"]["net_profit"]))
            expectancy_lift = net_lift/Decimal(base["paired_trades"]) if base["paired_trades"] else ZERO
            qualified = (base["paired_trades"] >= 30 and base["condition_triggered"] > 0
                         and net_lift > ZERO and expectancy_lift > ZERO and conservative_lift >= ZERO)
            candidates.append((qualified, net_lift, spec.condition_id))
        qualified = sorted((item for item in candidates if item[0]), reverse=True)
        diagnostic = max(candidates, key=lambda item: item[1])
        selected_id = qualified[0][2] if qualified else diagnostic[2]
        return self._validation(model, selected_id, bool(qualified), rows, series)

    def _validation(self, model: ModelCode, selected_id: str, train_qualified: bool,
                    rows: dict, series: dict[str, Sequence[Candle]]) -> dict:
        baseline = rows[(model.value, "BASELINE_EXIT", "BASE", "VALIDATION")]
        selected = rows[(model.value, selected_id, "BASE", "VALIDATION")]
        conservative = rows[(model.value, selected_id, "CONSERVATIVE", "VALIDATION")]
        base_values = {(_trade_key(item)): Decimal(str(item["net_pnl"]))
                       for item in baseline["trade_ledger"]}
        selected_values = {(_trade_key(item)): Decimal(str(item["net_pnl"]))
                           for item in selected["trade_ledger"]}
        deltas = [selected_values[key]-base_values[key] for key in sorted(base_values)]
        if len(deltas) >= 5:
            p_value = permutation_p_value(deltas, seed=P6_SEED)
            bootstrap = block_bootstrap_expectancy_lower(
                deltas, block_size=min(5, len(deltas)), seed=P6_SEED)
        else:
            p_value, bootstrap = Decimal("1"), ZERO
        times = sorted({item.open_time for candles in series.values()
                        for index, item in enumerate(candles)
                        if len(candles)*60//100 <= index < len(candles)*80//100})
        windows = _temporal_windows(baseline["trade_ledger"], selected["trade_ledger"], times,
                                    step_seconds=period_seconds(self.runner.spec.entry_period))
        positive_windows = sum(Decimal(item["net_profit_lift"]) > ZERO for item in windows)
        metrics = selected["metrics"]
        trades = int(metrics["trades"])
        expectancy = Decimal(metrics["net_profit"])/Decimal(trades) if trades else ZERO
        reasons = []
        if not train_qualified:
            reasons.append("no exit condition passed the frozen TRAIN paired-lift rule")
        checks = (
            (trades >= 30, "VALIDATION paired trades below 30"),
            (selected["condition_triggered"] > 0, "selected condition never changed an exit"),
            (Decimal(metrics["net_profit"]) > ZERO, "VALIDATION BASE net profit is not positive"),
            (expectancy > ZERO, "VALIDATION BASE expectancy is not positive"),
            (metrics["profit_factor"] is not None and Decimal(metrics["profit_factor"]) >= Decimal("1.30"),
             "VALIDATION BASE PF below 1.30"),
            (Decimal(conservative["metrics"]["net_profit"]) >= ZERO,
             "VALIDATION CONSERVATIVE net profit is negative"),
            (Decimal(metrics["max_drawdown"]) <= Decimal("1000"),
             "VALIDATION drawdown exceeds 10%"),
            (p_value < Decimal("0.05"), "paired permutation p-value is not below 0.05"),
            (bootstrap > ZERO, "paired bootstrap expectancy-lift lower bound is not positive"),
            (positive_windows >= 4, "fewer than 4/5 validation windows have positive net lift"),
        )
        reasons.extend(message for passed, message in checks if not passed)
        conclusion = ("ELIGIBLE_FOR_P7" if not reasons else
                      "INSUFFICIENT_EVIDENCE" if trades < 30 else "REJECT")
        return {"model": model.value, "selected_by_train": selected_id if train_qualified else None,
                "diagnostic_top_by_train": selected_id, "train_qualified": train_qualified,
                "validation_baseline": baseline["metrics"], "validation_selected": metrics,
                "validation_conservative": conservative["metrics"],
                "condition_triggered": selected["condition_triggered"],
                "paired_net_lift": Decimal(metrics["net_profit"])-Decimal(baseline["metrics"]["net_profit"]),
                "paired_permutation_p": p_value,
                "paired_bootstrap_expectancy_lift_lower": bootstrap,
                "temporal_windows": windows, "positive_lift_windows": positive_windows,
                "conclusion": conclusion, "reasons": tuple(reasons)}


def _trade_key(item: dict) -> tuple[str, int, str]:
    return item["symbol"], int(item["signal_time"]), item["model"]


def _status_counts(trades: Sequence[dict]) -> dict[str, int]:
    counts = {item.value: 0 for item in ConditionStatus}
    for trade in trades:
        for event in trade.get("condition_trace", ()):
            status = str(event["status"])
            counts[status] = counts.get(status, 0)+1
    return counts


def _tighten(side: TrendSide, old: Decimal | None, new: Decimal | None) -> Decimal | None:
    if new is None:
        return old
    if old is None:
        return new
    return max(old, new) if side is TrendSide.LONG else min(old, new)


def run_exit_optimization(evidence: Path, manifest_hash: str,
                          market_facts_manifest_hash: str, lifecycle_result_hash: str,
                          profile: TimeframeProfile) -> dict[str, str]:
    with FileEvidenceStore(evidence) as store:
        result = ExitOptimization(store, manifest_hash, market_facts_manifest_hash,
                                  lifecycle_result_hash, profile).run()
        return {"result_hash": result}
