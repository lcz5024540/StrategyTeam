from dataclasses import replace
from decimal import Decimal

import pytest

from htx_trend.java_entry_contract import JavaEntryConfig, evaluate, candidates
from htx_trend.market_data import Candle
from htx_trend.research.baseline import REGISTRY, baseline_strategy, trace_entry
from htx_trend.research.contracts import ConditionStatus, DecisionContext
from htx_trend.research.features import donchian, performance, regression_slope


def candles(count: int = 130) -> tuple[Candle, ...]:
    return tuple(Candle(i * 900, Decimal(100 + i), Decimal(102 + i), Decimal(99 + i),
                        Decimal(101 + i), Decimal("10"), Decimal("20"), Decimal("2000"), 4)
                 for i in range(count))


def test_donchian_excludes_current_bar_and_slope_is_explicit() -> None:
    values = candles(22)
    high, low = donchian(values, 20, lag=1)
    assert high == values[-2].high
    assert low == values[-21].low
    assert regression_slope(tuple(Decimal(i) for i in range(10)), 10) == Decimal("1")


def test_profit_factor_does_not_invent_a_denominator() -> None:
    result = performance(({"net_pnl": "2", "fees": ".1"}, {"net_pnl": "1", "fees": ".1"}))
    assert result.profit_factor is None
    assert result.net_profit == Decimal("3")


def test_safety_condition_cannot_be_changed_by_experiment() -> None:
    base = baseline_strategy()
    with pytest.raises(ValueError, match="safety condition"):
        REGISTRY.apply_experiment(base, experiment_id="EXP-001",
                                  condition_versions={"runtime.writer_guard": "legacy-v1"})


def test_baseline_trace_records_observations_and_skipped_path_conditions() -> None:
    values = candles()
    config = JavaEntryConfig()
    signal = evaluate(values, config)
    output = candidates(values, signal, config, "15min")
    context = DecisionContext("decision-1", "HTX_TREND_V1", "OBSERVE", "2026-10-11T00:00:00+00:00",
                              {"contract_code": "BTC-USDT"}, {}, {}, ("a" * 64,))
    trace = trace_entry(context, values, "15min", signal, output, config)
    results = {item.condition_id: item for item in trace.condition_results}
    assert results["signal.vwap_observation"].usage.value == "RECORD"
    assert results["runtime.writer_guard"].status is ConditionStatus.SKIPPED
    assert trace.strategy_spec_hash == baseline_strategy(config).spec_hash
