"""Same-source profile research over an immutable HTX dataset.

The runner deliberately keeps historical simulation separate from historical
decision reproduction.  OHLCV does not contain an order book, account state or
funding history, so those facts are frozen as named execution assumptions and
are never presented as observed exchange facts.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass, replace
from datetime import UTC, datetime
from decimal import Decimal, ROUND_DOWN
from pathlib import Path
from typing import Iterable, Sequence

from ..execution import ExecutionConfig, ExecutionFacts, evaluate_gate
from ..exits import ExitAction, ExitInput, decide
from ..market_data import Candle, ContractSpec, FundingRate, TopOfBook, period_seconds
from ..models import ModelCandidate, ModelCode, TrendSide
from ..strategy_admission import (DecisionSurface, ResearchStage, StageEvidence, StageStatus,
                                  build_artifact)
from ..strategy_decision import HtxStrategyDecisionEngine, StrategyDecision
from ..strategy_profiles import ProfileStrategySpec, profile_strategy
from ..timeframes import TimeframeProfile
from .experiments import (DEFAULT_COST_SCENARIOS, ResearchConclusion, block_bootstrap_expectancy_lower,
                          economic_gate, permutation_p_value, walk_forward_bounds)
from .features import performance
from .market_facts import FrozenMarketFacts
from .replay import load_manifest_candles
from .store import FileEvidenceStore, digest

ZERO = Decimal("0")
ONE = Decimal("1")


@dataclass(frozen=True, slots=True)
class ResearchVariant:
    variant_id: str
    models: tuple[ModelCode, ...] = tuple(ModelCode)
    confirmation: str | None = None
    minimum_score: Decimal | None = None


DEFAULT_VARIANTS = (
    ResearchVariant("BASELINE"),
    ResearchVariant("MODEL_DONCHIAN", (ModelCode.DONCHIAN_BREAKOUT,)),
    ResearchVariant("MODEL_FIB", (ModelCode.FIB_PULLBACK_CONTINUATION,)),
    ResearchVariant("MODEL_EMA_VWAP", (ModelCode.EMA_VWAP_MOMENTUM_CONTINUATION,)),
    ResearchVariant("ADD_RSI", confirmation="RSI"),
    ResearchVariant("ADD_MACD", confirmation="MACD"),
    ResearchVariant("ADD_PRICE_SLOPE", confirmation="PRICE_SLOPE"),
    ResearchVariant("ADD_EMA_SLOPE", confirmation="EMA_SLOPE"),
    ResearchVariant("ADD_REGIME", confirmation="REGIME"),
    ResearchVariant("SENSITIVITY_SCORE_65", minimum_score=Decimal("65")),
    ResearchVariant("SENSITIVITY_SCORE_70", minimum_score=Decimal("70")),
)


@dataclass(frozen=True, slots=True)
class SimulatedTrade:
    profile: str
    symbol: str
    variant_id: str
    cost: str
    model: str
    side: str
    signal_time: int
    entry_time: int
    exit_time: int
    quantity: int
    entry_price: Decimal
    exit_price: Decimal
    gross_pnl: Decimal
    fees: Decimal
    funding: Decimal
    funding_event_count: int
    funding_evidence_status: str
    net_pnl: Decimal
    mfe: Decimal
    mae: Decimal
    exit_reason: str


def provisional_backtest_admission(spec: ProfileStrategySpec, manifest_hash: str):
    """Authorize only BACKTEST after stages 1-7 have explicit frozen evidence."""
    evidence = digest({"purpose": "provisional same-source research", "spec_hash": spec.spec_hash,
                       "manifest_hash": manifest_hash})
    stages = tuple(StageEvidence(stage, StageStatus.PASS, f"cas:{evidence}", evidence,
                                 "frozen prerequisite for research simulation")
                   for stage in ResearchStage if stage <= ResearchStage.COST_EXECUTION)
    return build_artifact(
        strategy_id="HTX_TREND", strategy_version=spec.strategy.version,
        experiment_id="EXP-20261017-001", source_commit="working-tree-research",
        policy_hash=digest({"models": spec.primary_models}), config_hash=spec.spec_hash,
        dataset_manifest_hash=manifest_hash, stages=stages,
        allowed_surfaces=(DecisionSurface.BACKTEST,),
        failure_conditions=spec.strategy.failure_conditions,
        pause_conditions=spec.strategy.pause_conditions,
    )


class ProfileLifecycleResearch:
    """Run the shared entry core, Gate, sizing and exit kernel on frozen bars."""

    def __init__(self, store: FileEvidenceStore, manifest_hash: str, market_facts_manifest_hash: str,
                 profile: TimeframeProfile,
                 *, window: int = 160, account_equity: Decimal = Decimal("10000")) -> None:
        self.store, self.manifest_hash, self.profile = store, manifest_hash, profile
        self.market_facts = FrozenMarketFacts.load(store, market_facts_manifest_hash)
        self.window, self.account_equity = window, account_equity
        self.spec = profile_strategy(profile, read_env=False)
        admission = provisional_backtest_admission(self.spec, manifest_hash)
        self.engine = HtxStrategyDecisionEngine(strategy_version=self.spec.strategy.version,
                                                config=self.spec.entry_config, admission=admission)

    def run(self, symbols: Sequence[str] = ("BTC-USDT", "ETH-USDT"),
            variants: Sequence[ResearchVariant] = DEFAULT_VARIANTS,
            *, include_test: bool = True, seed: int = 17) -> str:
        plan_hash = digest({"manifest_hash": self.manifest_hash, "spec_hash": self.spec.spec_hash,
                            "market_facts_manifest_hash": self.market_facts.manifest_hash,
                            "symbols": tuple(symbols), "variants": tuple(variants), "seed": seed,
                            "split": (60, 20, 20), "costs": DEFAULT_COST_SCENARIOS})
        if include_test:
            checkpoint = f"profile-test-consumed:{self.manifest_hash}:{self.profile.value}"
            consumed = self.store.checkpoint(checkpoint)
            if consumed not in (None, {"plan_hash": plan_hash}):
                raise ValueError("profile test segment was already consumed by another frozen plan")
            self.store.checkpoint(checkpoint, {"plan_hash": plan_hash})
        series = {symbol: load_manifest_candles(self.store, self.manifest_hash, symbol=symbol,
                                                period=self.spec.entry_period) for symbol in symbols}
        decisions = {symbol: self._decisions(symbol, candles) for symbol, candles in series.items()}
        result_rows: list[dict] = []
        test_values: dict[tuple[str, str], list[Decimal]] = {}
        for variant in variants:
            for cost in DEFAULT_COST_SCENARIOS:
                by_split: dict[str, list[SimulatedTrade]] = {}
                for split in (("TRAIN", 0, 60), ("VALIDATION", 60, 80), ("TEST", 80, 100)):
                    if split[0] == "TEST" and not include_test:
                        continue
                    trades: list[SimulatedTrade] = []
                    for symbol, candles in series.items():
                        start, end = len(candles) * split[1] // 100, len(candles) * split[2] // 100
                        trades.extend(self._simulate(symbol, candles, decisions[symbol], start, end,
                                                     variant, cost.name, cost.fee_rate,
                                                     cost.slippage_rate))
                    trades.sort(key=lambda item: (item.exit_time, item.symbol, item.entry_time))
                    by_split[split[0]] = trades
                    metrics = performance(tuple({"net_pnl": item.net_pnl, "fees": item.fees,
                                                 "mfe": item.mfe, "mae": item.mae} for item in trades))
                    result_rows.append({"variant_id": variant.variant_id, "cost": cost.name,
                                        "split": split[0], "metrics": asdict(metrics),
                                        "trades": len(trades),
                                        "trade_ledger": [asdict(item) for item in trades]})
                    if split[0] == "TEST":
                        test_values[(variant.variant_id, cost.name)] = [item.net_pnl for item in trades]
        conclusions = self._conclusions(result_rows, test_values, series, variants, seed)
        walk_forward = self._walk_forward_summary(series, decisions, variants[0], seed)
        payload = {
            "result_kind": "STRATEGY_SIMULATION",
            "profile": self.profile.value,
            "strategy_version": self.spec.strategy.version,
            "spec_hash": self.spec.spec_hash,
            "manifest_hash": self.manifest_hash,
            "market_facts_manifest_hash": self.market_facts.manifest_hash,
            "plan_hash": plan_hash,
            "symbols": list(symbols),
            "window": self.window,
            "split": [60, 20, 20],
            "execution_assumptions": {
                "signal": "closed bar", "entry": "next bar open plus directional slippage",
                "book": "synthetic around next open because frozen OHLCV has no L2 book",
                "funding": "HTX historical events only; realized_rate when present, otherwise reported historical funding_rate",
                "capacity": "at most 1% of candle volume", "intrabar": "stop before target",
                "positioning": "one position per symbol/profile; no cross-segment carry",
                "contract_size": "HTX point-in-time contract fact bound by market_facts_manifest_hash",
            },
            "variants": [asdict(item) for item in variants],
            "results": result_rows,
            "conclusions": conclusions,
            "walk_forward": walk_forward,
        }
        return self.store.put("profile_lifecycle_research", payload,
                              refs=(self.manifest_hash, self.market_facts.manifest_hash))

    def _decisions(self, symbol: str, candles: Sequence[Candle]) -> tuple[StrategyDecision | None, ...]:
        rows: list[StrategyDecision | None] = [None] * len(candles)
        for end in range(self.window, len(candles) + 1):
            rows[end - 1] = self.engine.decide(contract_code=symbol, period=self.spec.entry_period,
                                               candles=candles[end-self.window:end],
                                               surface=DecisionSurface.BACKTEST)
        return tuple(rows)

    def _simulate(self, symbol: str, candles: Sequence[Candle], decisions: Sequence[StrategyDecision | None],
                  start: int, end: int, variant: ResearchVariant, cost_name: str,
                  fee_rate: Decimal, slippage: Decimal) -> list[SimulatedTrade]:
        start = max(start, self.window)
        if end - start < 2:
            return []
        trades: list[SimulatedTrade] = []
        position: dict | None = None
        prior = None
        for index in range(start, end):
            candle = candles[index]
            decision = decisions[index]
            if position is not None:
                closed = self._manage(position, candle, decision, fee_rate, cost_name,
                                      variant.variant_id, symbol, candles)
                if closed is not None:
                    trades.append(closed)
                    prior = closed
                    position = None
            if position is not None or index == 0:
                continue
            signal = decisions[index - 1]
            if signal is None:
                continue
            candidate = self._select(signal, candles, index - 1, variant)
            if candidate is None:
                continue
            factors = signal.signal.indicators
            if factors is None:
                continue
            adverse = ONE + slippage if candidate.side is TrendSide.LONG else ONE - slippage
            fill = candle.open * adverse
            half_spread = min(Decimal("0.00025"), slippage / Decimal("2"))
            book = TopOfBook(fill * (ONE - half_spread), fill * (ONE + half_spread),
                             Decimal("1000000"), Decimal("1000000"))
            config = ExecutionConfig(min_score=self.spec.entry_config.entry_score_threshold,
                                     fee_rate=fee_rate, signal_freshness_seconds=0,
                                     target_notional=Decimal("100"), min_notional=Decimal("1"),
                                     max_notional=Decimal("100"), risk_per_trade=self.account_equity * Decimal("0.005"),
                                     hard_risk_limits_enabled=True, account_equity_usdt=self.account_equity,
                                     max_risk_per_trade_usdt=self.account_equity * Decimal("0.005"))
            facts = ExecutionFacts(actual_taker_fee_rate=fee_rate, executable_depth_contracts=Decimal("1000000"),
                                   account_equity_usdt=self.account_equity, available_margin_usdt=self.account_equity,
                                   leverage=Decimal("5"), active_position_count=0, max_active_positions=3,
                                   same_direction_position_count=0)
            contract_fact = self.market_facts.contract(symbol)
            funding_fact = self.market_facts.rate_at(symbol, candidate.bar_time)
            gate = evaluate_gate(candidate, period=self.spec.entry_period,
                                 now_epoch=candidate.bar_time + period_seconds(self.spec.entry_period),
                                 atr=factors.atr, order_book=book,
                                 contract=ContractSpec(symbol, contract_fact.contract_size,
                                                       contract_fact.price_tick, contract_fact.contract_status),
                                 funding=FundingRate(symbol, funding_fact.effective_rate,
                                                     funding_fact.funding_time),
                                 recent_candles=candles[max(0,index-20):index],
                                 prior_trade=None, execution_facts=facts, momentum_quality=signal.signal.score,
                                 ema_fast=factors.ema_fast, ema_slow=factors.ema_slow, adx=factors.adx, config=config)
            if not gate.allowed or gate.size_plan is None or gate.price_plan is None:
                continue
            volume = candle.volume or ZERO
            capacity = int((volume * Decimal("0.01")).to_integral_value(rounding=ROUND_DOWN))
            quantity = min(gate.size_plan.contracts, capacity)
            if quantity < 1:
                continue
            entry_fee = fill * contract_fact.contract_size * quantity * fee_rate
            position = {"candidate": candidate, "quantity": quantity, "initial_quantity": quantity,
                        "entry": fill, "signal_time": candidate.bar_time, "entry_time": candle.open_time,
                        "stop": candidate.invalidation_price, "target": candidate.target_low,
                        "highest": fill, "lowest": fill, "mfe": ZERO, "mae": ZERO,
                        "fees": entry_fee, "funding": ZERO, "gross": ZERO, "bars": 0,
                        "contract_size": contract_fact.contract_size,
                        "funding_events": 0, "funding_status": "COMPLETE",
                        "last_funding_cutoff": candle.open_time,
                        "protection": None, "armed": None, "arm_reason": None, "max_r": ZERO,
                        "breach": 0, "flat": 0}
            # The entry candle can hit the stop; manage it after entry without a second signal evaluation.
            closed = self._manage(position, candle, decision, fee_rate, cost_name,
                                  variant.variant_id, symbol, candles)
            if closed is not None:
                trades.append(closed); prior = closed; position = None
        if position is not None:
            trades.append(self._close(position, candles[end-1].close, candles[end-1].open_time,
                                      "SEGMENT_END", fee_rate, cost_name, variant.variant_id, symbol))
        return trades

    def _manage(self, p: dict, candle: Candle, decision: StrategyDecision | None, fee_rate: Decimal,
                cost_name: str, variant_id: str, symbol: str,
                candles: Sequence[Candle]) -> SimulatedTrade | None:
        candidate: ModelCandidate = p["candidate"]
        side = candidate.side
        p["bars"] += 1
        p["highest"], p["lowest"] = max(p["highest"], candle.high), min(p["lowest"], candle.low)
        favorable = p["highest"] - p["entry"] if side is TrendSide.LONG else p["entry"] - p["lowest"]
        adverse = p["entry"] - p["lowest"] if side is TrendSide.LONG else p["highest"] - p["entry"]
        multiplier = p["contract_size"] * p["quantity"]
        p["mfe"], p["mae"] = max(p["mfe"], favorable * multiplier), max(p["mae"], adverse * multiplier)
        charge = self.market_facts.charge(
            symbol, side, quantity=p["quantity"], entry_time=p["last_funding_cutoff"],
            exit_time=candle.open_time, candles=candles, period=self.spec.entry_period)
        if charge.evidence_status != "COMPLETE" or charge.amount is None:
            raise ValueError(f"funding evidence unavailable: {charge.evidence_status}")
        p["funding"] += charge.amount
        p["funding_events"] += charge.event_count
        p["last_funding_cutoff"] = candle.open_time
        stop_hit = candle.low <= p["stop"] if side is TrendSide.LONG else candle.high >= p["stop"]
        target_hit = candle.high >= p["target"] if side is TrendSide.LONG else candle.low <= p["target"]
        if stop_hit:
            price = min(candle.open, p["stop"]) if side is TrendSide.LONG else max(candle.open, p["stop"])
            return self._close(p, price, candle.open_time, "HARD_STOP", fee_rate, cost_name, variant_id, symbol)
        if target_hit:
            return self._close(p, p["target"], candle.open_time, "STRUCTURE_TARGET", fee_rate,
                               cost_name, variant_id, symbol)
        if decision is None or decision.signal.indicators is None:
            return None
        factors = decision.signal.indicators
        pnl = ((candle.close - p["entry"]) if side is TrendSide.LONG else (p["entry"] - candle.close)) * multiplier
        close_fee = candle.close * multiplier * fee_rate
        p["flat"] = p["flat"] + 1 if decision.signal.side is TrendSide.FLAT else 0
        if p["protection"] is not None:
            crossed = candle.close <= p["protection"] if side is TrendSide.LONG else candle.close >= p["protection"]
            p["breach"] = p["breach"] + 1 if crossed else 0
        exit_decision = decide(ExitInput(
            candidate.model, side, p["quantity"], p["contract_size"], p["entry"], p["stop"],
            candidate.invalidation_price, p["target"], candle.close, p["protection"], p["highest"], p["lowest"],
            factors.atr, decision.signal.side, factors.ema_fast, factors.ema_slow, 0, False,
            pnl - p["fees"] - close_fee - p["funding"], p["fees"], close_fee,
            p["armed"], p["arm_reason"], p["max_r"], datetime.fromtimestamp(candle.open_time, UTC),
            flat_streak=p["flat"], protection_breach_bars=p["breach"], observed_mfe_net_pnl=p["mfe"]-p["fees"]-close_fee))
        p["protection"], p["armed"], p["arm_reason"], p["max_r"] = (
            exit_decision.protection_price, exit_decision.trailing_armed_at,
            exit_decision.trailing_arm_reason, exit_decision.max_favorable_r)
        if exit_decision.action is ExitAction.FULL_CLOSE:
            return self._close(p, candle.close, candle.open_time, exit_decision.reason, fee_rate,
                               cost_name, variant_id, symbol)
        # Partial closes are booked into the same lifecycle; only the final close
        # increments the trade count.
        if exit_decision.action is ExitAction.PARTIAL_CLOSE and exit_decision.target_remaining_contracts is not None:
            closed = p["quantity"] - exit_decision.target_remaining_contracts
            if closed > 0:
                part = p["contract_size"] * closed
                p["gross"] += ((candle.close-p["entry"]) if side is TrendSide.LONG else (p["entry"]-candle.close)) * part
                p["fees"] += candle.close * part * fee_rate
                p["quantity"] = exit_decision.target_remaining_contracts
        return None

    def _close(self, p: dict, price: Decimal, time: int, reason: str, fee_rate: Decimal,
               cost_name: str, variant_id: str, symbol: str) -> SimulatedTrade:
        candidate: ModelCandidate = p["candidate"]
        multiplier = p["contract_size"] * p["quantity"]
        gross = p["gross"] + ((price-p["entry"]) if candidate.side is TrendSide.LONG else (p["entry"]-price)) * multiplier
        fees = p["fees"] + price * multiplier * fee_rate
        return SimulatedTrade(self.profile.value, symbol, variant_id, cost_name, candidate.model.value,
                              candidate.side.value, p["signal_time"], p["entry_time"], time,
                              p["initial_quantity"], p["entry"], price, gross, fees, p["funding"],
                              p["funding_events"], p["funding_status"],
                              gross-fees-p["funding"], p["mfe"], p["mae"], reason)

    @staticmethod
    def _select(decision: StrategyDecision, candles: Sequence[Candle], index: int,
                variant: ResearchVariant) -> ModelCandidate | None:
        candidates = [item for item in decision.candidates if item.model in variant.models]
        factors = decision.signal.indicators
        if not candidates or factors is None:
            return None
        if variant.minimum_score is not None:
            candidates = [item for item in candidates if item.score >= variant.minimum_score]
            if not candidates:
                return None
        side = candidates[0].side
        if variant.confirmation == "RSI" and not ((side is TrendSide.LONG and factors.rsi >= 50) or (side is TrendSide.SHORT and factors.rsi <= 50)):
            return None
        if variant.confirmation == "MACD" and not ((side is TrendSide.LONG and factors.macd_histogram > ZERO) or (side is TrendSide.SHORT and factors.macd_histogram < ZERO)):
            return None
        if variant.confirmation == "REGIME" and decision.signal.regime == "RANGE":
            return None
        if variant.confirmation in {"PRICE_SLOPE", "EMA_SLOPE"} and index < 2:
            return None
        if variant.confirmation == "PRICE_SLOPE":
            slope = candles[index].close - candles[index-2].close
            if not ((side is TrendSide.LONG and slope > ZERO) or (side is TrendSide.SHORT and slope < ZERO)):
                return None
        if variant.confirmation == "EMA_SLOPE":
            # EMA ordering is a point-in-time, no-lookahead proxy for slope direction.
            slope = factors.ema_fast - factors.ema_medium
            if not ((side is TrendSide.LONG and slope > ZERO) or (side is TrendSide.SHORT and slope < ZERO)):
                return None
        return max(candidates, key=lambda item: (item.score, item.expected_r, item.model.value))

    def _conclusions(self, rows: list[dict], values: dict[tuple[str,str], list[Decimal]],
                     series: dict[str, Sequence[Candle]], variants: Sequence[ResearchVariant], seed: int) -> list[dict]:
        conclusions = []
        days = max(1, (max(c[-1].open_time for c in series.values()) - min(c[int(len(c)*.8)].open_time for c in series.values())) // 86400 + 1)
        for variant in variants:
            base_values = values.get((variant.variant_id, "BASE"), [])
            conservative_values = values.get((variant.variant_id, "CONSERVATIVE"), [])
            base = performance(tuple({"net_pnl": x, "fees": ZERO, "mfe": ZERO, "mae": ZERO} for x in base_values))
            conservative = performance(tuple({"net_pnl": x, "fees": ZERO, "mfe": ZERO, "mae": ZERO} for x in conservative_values))
            if len(base_values) < 5:
                p_value, lower = ONE, ZERO
            else:
                p_value = permutation_p_value(base_values, seed=seed)
                lower = block_bootstrap_expectancy_lower(base_values, block_size=min(5, len(base_values)), seed=seed)
            gate = economic_gate(base, conservative, account_equity=self.account_equity,
                                 permutation_p=p_value, bootstrap_expectancy_lower=lower,
                                 combined_trades_per_day=Decimal(len(base_values))/Decimal(days))
            conclusions.append({"variant_id": variant.variant_id, "conclusion": gate.conclusion.value,
                                "reasons": gate.reasons, "permutation_p": p_value,
                                "bootstrap_expectancy_lower": lower,
                                "test_trades_per_day": gate.combined_trades_per_day})
        return conclusions

    def _walk_forward_summary(self, series: dict[str, Sequence[Candle]], decisions: dict[str, Sequence[StrategyDecision | None]],
                              variant: ResearchVariant, seed: int) -> dict:
        size = min(len(item) for item in series.values())
        train, validate = max(self.window+1, size//2), max(1, size//10)
        windows = walk_forward_bounds(size, train=train, validate=validate, step=validate)
        results = []
        base = next(item for item in DEFAULT_COST_SCENARIOS if item.name == "BASE")
        for number, bounds in enumerate(windows):
            values: list[Decimal] = []
            for symbol, candles in series.items():
                start, end = bounds["VALIDATION"]
                values.extend(item.net_pnl for item in self._simulate(symbol, candles, decisions[symbol], start, end,
                              variant, base.name, base.fee_rate, base.slippage_rate))
            results.append({"window": number, "bounds": bounds, "trades": len(values),
                            "net_profit": sum(values, ZERO)})
        return {"seed": seed, "windows": results}


def run_dual_profile_research(evidence: Path, manifest_hash: str, market_facts_manifest_hash: str,
                              *, include_test: bool = True) -> dict:
    with FileEvidenceStore(evidence) as store:
        refs = {}
        for profile in (TimeframeProfile.INTRADAY_15M, TimeframeProfile.SWING_4H):
            refs[profile.value] = ProfileLifecycleResearch(
                store, manifest_hash, market_facts_manifest_hash, profile).run(include_test=include_test)
        payloads = {name: store.get(ref)["payload"] for name, ref in refs.items()}
        baseline = {name: next(item for item in payload["conclusions"]
                               if item["variant_id"] == "BASELINE")
                    for name, payload in payloads.items()}
        combined_rate = sum((Decimal(item["test_trades_per_day"]) for item in baseline.values()), ZERO)
        if any(item["conclusion"] == ResearchConclusion.REJECT.value for item in baseline.values()):
            combined_conclusion = ResearchConclusion.REJECT.value
        elif any(item["conclusion"] == ResearchConclusion.INSUFFICIENT_EVIDENCE.value for item in baseline.values()):
            combined_conclusion = ResearchConclusion.INSUFFICIENT_EVIDENCE.value
        else:
            combined_conclusion = (ResearchConclusion.PASS.value if combined_rate >= Decimal("5")
                                   else ResearchConclusion.PASS_PROFITABILITY_FREQUENCY_GAP.value)
        combined = store.put("dual_profile_research_index", {
            "manifest_hash": manifest_hash, "profile_results": refs,
            "market_facts_manifest_hash": market_facts_manifest_hash,
            "test_consumed": include_test,
            "combined_baseline_trades_per_day": combined_rate,
            "combined_conclusion": combined_conclusion,
            "combination_rule": "frequency is evaluated only after both profile economic gates",
        }, refs=(market_facts_manifest_hash, *refs.values()))
        return {"index_hash": combined, "profile_results": refs}
