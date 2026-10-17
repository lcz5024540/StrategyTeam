from dataclasses import replace
from datetime import UTC, datetime, timedelta
from decimal import Decimal

import pytest

from htx_trend.live_approval import EXIT_V4, LegacyLiveWaiver, LiveApproval, LiveRiskPolicy
from htx_trend.live_worker import LiveTrendWorkerConfig
from htx_trend.market_data import Candle
from htx_trend.models import TrendSide
from htx_trend.research.experiments import ResearchConclusion, economic_gate
from htx_trend.research.features import performance
from htx_trend.research.lifecycle import BacktestIntent, ExecutionAssumptions, LifecycleBacktestRun
from htx_trend.research.market_facts import (ContractMarketFact, FrozenMarketFacts,
                                             HistoricalFundingEvent)
from htx_trend.research.regime import RegimeStatus, profile_regime
from htx_trend.strategy_profiles import STRATEGY_15M_V2, STRATEGY_4H_V2, profile_strategy
from htx_trend.timeframes import TimeframeProfile


SHA = "a" * 64


def _waiver(*, expires_in_days: int = 45) -> LegacyLiveWaiver:
    issued = datetime(2026, 10, 17, tzinfo=UTC)
    return LegacyLiveWaiver(
        "HTX_TREND_V1", "86529039", SHA, SHA, 7,
        ("INTRADAY_15M", "SWING_4H"), LiveRiskPolicy(), EXIT_V4,
        issued.isoformat(), (issued + timedelta(days=expires_in_days)).isoformat(),
    ).sealed()


def _candles(period_seconds: int, count: int = 130) -> tuple[Candle, ...]:
    return tuple(
        Candle(i * period_seconds, Decimal(100 + i), Decimal(102 + i), Decimal(99 + i),
               Decimal(101 + i), amount=Decimal(100 + i), volume=Decimal("1000"))
        for i in range(count)
    )


def test_legacy_waiver_is_sealed_tamper_evident_and_capped_at_45_days() -> None:
    waiver = _waiver()
    waiver.validate(now=datetime(2026, 10, 18, tzinfo=UTC))
    with pytest.raises(ValueError, match="modified"):
        replace(waiver, account_id=8).validate(
            now=datetime(2026, 10, 18, tzinfo=UTC))
    with pytest.raises(ValueError, match="at most 45"):
        _waiver(expires_in_days=46).validate(now=datetime(2026, 10, 18, tzinfo=UTC))


def test_live_approval_binds_strategy_to_profile_and_v4() -> None:
    issued = datetime(2026, 10, 17, tzinfo=UTC)
    approval = LiveApproval(
        STRATEGY_15M_V2, "INTRADAY_15M", "commit", SHA, SHA, SHA, SHA, 7,
        LiveRiskPolicy(), EXIT_V4, issued.isoformat(), (issued + timedelta(days=30)).isoformat(),
    ).sealed()
    approval.validate(now=issued + timedelta(days=1))
    bad = LiveApproval(
        STRATEGY_4H_V2, "INTRADAY_15M", "commit", SHA, SHA, SHA, SHA, 7,
        LiveRiskPolicy(), EXIT_V4, issued.isoformat(), (issued + timedelta(days=30)).isoformat(),
    ).sealed()
    with pytest.raises(ValueError, match="does not match"):
        bad.validate(now=issued + timedelta(days=1))


def test_v2_specs_and_runtime_profiles_are_strictly_separate(monkeypatch) -> None:
    monkeypatch.setenv("HTX_TREND_ENABLED", "true")
    monkeypatch.setenv("HTX_TREND_ENTRY_ENABLED", "true")
    monkeypatch.setenv("HTX_TREND_15M_FAST_EMA_PERIOD", "21")
    monkeypatch.setenv("HTX_TREND_4H_FAST_EMA_PERIOD", "34")
    monkeypatch.setenv("HTX_TREND_INTRADAY_15M_LIVE_ENABLED", "true")
    fifteen = profile_strategy(TimeframeProfile.INTRADAY_15M)
    four_hour = profile_strategy(TimeframeProfile.SWING_4H)
    assert fifteen.entry_config.fast_ema_period == 21
    assert four_hour.entry_config.fast_ema_period == 34
    assert fifteen.spec_hash != four_hour.spec_hash
    assert "entry.period_15m" not in fifteen.strategy.condition_versions
    runtime = LiveTrendWorkerConfig.profiles_from_env(strategy_version=STRATEGY_15M_V2)
    assert len(runtime) == 1 and runtime[0].entry_period == "15min"
    assert runtime[0].entry_enabled


def test_regime_requires_every_closed_profile_period_and_has_no_future_leakage() -> None:
    profile = TimeframeProfile.INTRADAY_15M
    windows = {
        "15min": _candles(900), "30min": _candles(1800), "60min": _candles(3600),
        "4hour": _candles(14400), "1day": _candles(86400),
    }
    as_of = 130 * 86400
    snapshot = profile_regime(profile, windows, as_of_epoch=as_of,
                              spread_rate=Decimal("0.0005"), executable_depth_contracts=Decimal("5"))
    assert snapshot.status is RegimeStatus.PASS
    missing = profile_regime(profile, {**windows, "1day": ()}, as_of_epoch=as_of)
    assert missing.status is RegimeStatus.UNKNOWN and "1day" in missing.missing_periods
    future = Candle(as_of, Decimal(1), Decimal(2), Decimal("0.5"), Decimal(1))
    leaked = profile_regime(profile, {**windows, "60min": windows["60min"] + (future,)}, as_of_epoch=as_of)
    assert leaked.status is RegimeStatus.UNKNOWN


def test_lifecycle_uses_next_bar_capacity_costs_and_adverse_intrabar_order() -> None:
    candles = (
        Candle(0, Decimal(100), Decimal(101), Decimal(99), Decimal(100), volume=Decimal(1000)),
        Candle(900, Decimal(101), Decimal(107), Decimal(96), Decimal(100), volume=Decimal(100)),
        Candle(1800, Decimal(100), Decimal(110), Decimal(90), Decimal(101), volume=Decimal(1000)),
    )
    intent = BacktestIntent("i1", 0, "INTRADAY_15M", "DONCHIAN", TrendSide.LONG, 5,
                            Decimal(1), Decimal(95), Decimal(108), contract_code="BTC-USDT")
    facts = FrozenMarketFacts(SHA, 0, 2701,
        {"BTC-USDT": ContractMarketFact("BTC-USDT", Decimal(1), Decimal(".1"), 1, 8, 1, 0, SHA)},
        {"BTC-USDT": (HistoricalFundingEvent("BTC-USDT", 1800, Decimal(".0001"), None,
                                             Decimal(".0001"), "FUNDING_RATE", "USDT", None, SHA),)})
    result = LifecycleBacktestRun(facts, ExecutionAssumptions(max_volume_participation=Decimal("0.01"))).run(
        candles, (intent,))
    trade = result.trades[0]
    assert trade.entry_time == 900
    assert trade.filled_quantity == 1 and trade.partial_fill
    assert trade.exit_reason == "STOP" and trade.exit_price == Decimal(95)
    assert trade.fees > 0 and trade.funding > 0
    assert result.result_kind == "STRATEGY_SIMULATION"


def test_economic_gate_keeps_profitability_ahead_of_frequency() -> None:
    base = performance([{"net_pnl": Decimal("2")}] * 30 + [{"net_pnl": Decimal("-1")}] * 10)
    conservative = performance([{"net_pnl": Decimal("1")}] * 40)
    gap = economic_gate(base, conservative, account_equity=Decimal(1000),
                        permutation_p=Decimal("0.01"), bootstrap_expectancy_lower=Decimal("0.1"),
                        combined_trades_per_day=Decimal("4.9"))
    assert gap.conclusion is ResearchConclusion.PASS_PROFITABILITY_FREQUENCY_GAP
    failed = economic_gate(base, conservative, account_equity=Decimal(1000),
                           permutation_p=Decimal("0.05"), bootstrap_expectancy_lower=Decimal("0.1"),
                           combined_trades_per_day=Decimal("8"))
    assert failed.conclusion is ResearchConclusion.REJECT
