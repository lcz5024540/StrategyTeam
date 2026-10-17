"""No-lookahead multi-timeframe regime evidence for profile strategies."""
from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal
from enum import StrEnum
from typing import Mapping, Sequence

from ..market_data import Candle, period_seconds
from ..timeframes import TimeframeProfile
from .features import FeatureVector, feature_vector
from .store import digest


class RegimeStatus(StrEnum):
    PASS = "PASS"
    UNKNOWN = "UNKNOWN"


class TrendRegime(StrEnum):
    UP = "UP"
    DOWN = "DOWN"
    MIXED = "MIXED"


class VolatilityRegime(StrEnum):
    LOW = "LOW"
    NORMAL = "NORMAL"
    HIGH = "HIGH"
    UNKNOWN = "UNKNOWN"


class LiquidityRegime(StrEnum):
    LIQUID = "LIQUID"
    THIN = "THIN"
    UNKNOWN = "UNKNOWN"


@dataclass(frozen=True, slots=True)
class RegimeSnapshot:
    timeframe_profile: str
    as_of_epoch: int
    status: RegimeStatus
    trend: TrendRegime
    volatility: VolatilityRegime
    liquidity: LiquidityRegime
    trend_period: str
    available_periods: tuple[str, ...]
    missing_periods: tuple[str, ...]
    ema_stack: str | None
    close_slope: Decimal | None
    ema_slope: Decimal | None
    adx: Decimal | None
    roc: Decimal | None
    atr_pct: Decimal | None
    amount_ratio: Decimal | None
    spread_rate: Decimal | None
    executable_depth_contracts: Decimal | None
    reason: str

    @property
    def snapshot_hash(self) -> str:
        return digest(self)


def profile_regime(
    profile: TimeframeProfile,
    windows: Mapping[str, Sequence[Candle]],
    *,
    as_of_epoch: int,
    spread_rate: Decimal | None = None,
    executable_depth_contracts: Decimal | None = None,
    atr_pct_low: Decimal | None = None,
    atr_pct_high: Decimal | None = None,
    max_spread_rate: Decimal = Decimal("0.0010"),
    min_depth_contracts: Decimal = Decimal("1"),
) -> RegimeSnapshot:
    contract = profile.contract
    required = tuple(dict.fromkeys((contract.entry_period, contract.trend_period, *contract.confirmation_periods)))
    available: list[str] = []
    missing: list[str] = []
    features: dict[str, FeatureVector] = {}
    for period in required:
        candles = tuple(sorted(windows.get(period, ()), key=lambda item: item.open_time))
        complete = bool(candles) and all(
            item.open_time + period_seconds(period) <= as_of_epoch for item in candles
        )
        if len(candles) < 121 or not complete:
            missing.append(period)
            continue
        features[period] = feature_vector(candles)
        available.append(period)
    if missing:
        return RegimeSnapshot(
            profile.value, as_of_epoch, RegimeStatus.UNKNOWN, TrendRegime.MIXED,
            VolatilityRegime.UNKNOWN, LiquidityRegime.UNKNOWN, contract.trend_period,
            tuple(available), tuple(missing), None, None, None, None, None, None, None,
            spread_rate, executable_depth_contracts,
            "required profile context is missing, incomplete, or contains an unclosed candle",
        )
    factor = features[contract.trend_period]
    bullish = factor.ema20 > factor.ema60 > factor.ema120 and factor.close_slope20 > 0 and factor.ema20_slope10 > 0
    bearish = factor.ema20 < factor.ema60 < factor.ema120 and factor.close_slope20 < 0 and factor.ema20_slope10 < 0
    trend = TrendRegime.UP if bullish else TrendRegime.DOWN if bearish else TrendRegime.MIXED
    stack = "BULLISH" if bullish else "BEARISH" if bearish else "MIXED"
    atr_pct = factor.atr14 / factor.ema20 if factor.ema20 else None
    if atr_pct is None or atr_pct_low is None or atr_pct_high is None:
        volatility = VolatilityRegime.UNKNOWN
    elif atr_pct < atr_pct_low:
        volatility = VolatilityRegime.LOW
    elif atr_pct > atr_pct_high:
        volatility = VolatilityRegime.HIGH
    else:
        volatility = VolatilityRegime.NORMAL
    if spread_rate is None or executable_depth_contracts is None:
        liquidity = LiquidityRegime.UNKNOWN
    elif spread_rate <= max_spread_rate and executable_depth_contracts >= min_depth_contracts:
        liquidity = LiquidityRegime.LIQUID
    else:
        liquidity = LiquidityRegime.THIN
    return RegimeSnapshot(
        profile.value, as_of_epoch, RegimeStatus.PASS, trend, volatility, liquidity,
        contract.trend_period, tuple(available), (), stack, factor.close_slope20,
        factor.ema20_slope10, factor.adx14, factor.roc12, atr_pct, factor.amount_ratio20,
        spread_rate, executable_depth_contracts, "complete closed-bar profile context",
    )
