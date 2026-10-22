"""Pure offline halt recommendations. No alerts, execution, or PAPER connection."""
from dataclasses import dataclass
import math


@dataclass(frozen=True)
class Health:
    consecutive_failed_heartbeats: int = 0
    fill_failure_rate: float = 0.
    data_delay_seconds: float = 0.


@dataclass(frozen=True)
class HaltThresholds:
    forward_ci_floor_bps: float = 0.
    drawdown_multiple: float = 2.
    cost_relative_deviation: float = .5
    failed_heartbeats: int = 3
    fill_failure_rate: float = .1
    data_delay_seconds: float = 60.


def _finite(*values):
    if not all(math.isfinite(v) for v in values):
        raise ValueError('metrics and thresholds must be finite')


def evaluate_halt(*, forward_net_ci_lower_bps, current_drawdown,
                  reference_drawdown, realized_rt_cost, backtest_rt_cost,
                  health, thresholds=HaltThresholds()):
    """Return should_halt/reasons only; thresholds are offline examples.

    CI <= floor triggers. Drawdown ratio and absolute relative cost deviation
    trigger strictly above limits. Health fields trigger >= limits. Drawdowns
    are nonnegative magnitudes in matching units. Zero reference: any positive
    drawdown/cost triggers; zero/zero is no deviation. Invalid input raises.
    """
    t = thresholds
    _finite(forward_net_ci_lower_bps, current_drawdown, reference_drawdown,
            realized_rt_cost, backtest_rt_cost, health.fill_failure_rate,
            health.data_delay_seconds, t.forward_ci_floor_bps, t.drawdown_multiple,
            t.cost_relative_deviation, t.fill_failure_rate, t.data_delay_seconds)
    if min(current_drawdown, reference_drawdown, realized_rt_cost, backtest_rt_cost,
           health.data_delay_seconds, t.cost_relative_deviation) < 0:
        raise ValueError('magnitudes must be nonnegative')
    if not 0 <= health.fill_failure_rate <= 1 or not 0 < t.fill_failure_rate <= 1:
        raise ValueError('failure rates must be within [0,1], threshold >0')
    if t.drawdown_multiple <= 0 or t.data_delay_seconds <= 0:
        raise ValueError('drawdown and delay thresholds must be positive')
    for value, minimum in ((health.consecutive_failed_heartbeats, 0), (t.failed_heartbeats, 1)):
        if not isinstance(value, int) or isinstance(value, bool) or value < minimum:
            raise ValueError('heartbeat counts must be nonnegative integers; threshold >0')
    reasons = []
    if forward_net_ci_lower_bps <= t.forward_ci_floor_bps:
        reasons.append('FORWARD_NET_CI')
    if current_drawdown > reference_drawdown * t.drawdown_multiple:
        reasons.append('DRAWDOWN_MULTIPLE')
    if abs(realized_rt_cost - backtest_rt_cost) > backtest_rt_cost * t.cost_relative_deviation:
        reasons.append('COST_DEVIATION')
    if health.consecutive_failed_heartbeats >= t.failed_heartbeats:
        reasons.append('PAPER_HEARTBEATS')
    if health.fill_failure_rate >= t.fill_failure_rate:
        reasons.append('PAPER_FILL_FAILURE_RATE')
    if health.data_delay_seconds >= t.data_delay_seconds:
        reasons.append('PAPER_DATA_DELAY')
    return dict(should_halt=bool(reasons), reasons=reasons)
