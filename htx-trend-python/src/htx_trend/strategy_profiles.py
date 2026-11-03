"""Independent, content-addressed 15m and 4h V2 strategy specifications."""
from __future__ import annotations

from dataclasses import asdict, dataclass, replace
from decimal import Decimal
from os import environ

from .java_entry_contract import JavaEntryConfig
from .models import ModelCode
from .research.baseline import CONDITIONS, FEATURES
from .research.contracts import StrategySpec
from .research.store import digest
from .timeframes import TimeframeProfile

STRATEGY_15M_V2 = "HTX_TREND_15M_V2"
STRATEGY_4H_V2 = "HTX_TREND_4H_V2"


@dataclass(frozen=True, slots=True)
class ProfileStrategySpec:
    strategy: StrategySpec
    timeframe_profile: TimeframeProfile
    entry_period: str
    trend_period: str
    confirmation_periods: tuple[str, ...]
    entry_config: JavaEntryConfig
    primary_models: tuple[str, ...]
    confirmation_models: tuple[str, ...]
    exit_version: str = "HTX_TREND_EXIT_V4"

    @property
    def spec_hash(self) -> str:
        return digest(self)

    @property
    def feature_hash(self) -> str:
        return digest(self.strategy.feature_versions)

    @property
    def condition_hash(self) -> str:
        return digest(self.strategy.condition_versions)

    def validate(self) -> None:
        self.timeframe_profile.validate(self.entry_period, self.trend_period)
        expected = version_for_profile(self.timeframe_profile)
        from .realtime import VERSION
        if self.strategy.version != expected and not (self.strategy.version == VERSION and self.timeframe_profile is TimeframeProfile.INTRADAY_15M):
            raise ValueError(f"{self.timeframe_profile.value} requires strategy version {expected}")
        if self.exit_version != "HTX_TREND_EXIT_V4":
            raise ValueError("profile strategy may only reference the formal V4 exit")
        if "entry.profile_contract" not in self.strategy.condition_versions:
            raise ValueError("profile strategy must use entry.profile_contract")
        if "entry.period_15m" in self.strategy.condition_versions:
            raise ValueError("profile strategy cannot retain the legacy 15m-only condition")


def version_for_profile(profile: TimeframeProfile) -> str:
    return STRATEGY_15M_V2 if profile is TimeframeProfile.INTRADAY_15M else STRATEGY_4H_V2


def profile_for_version(version: str) -> TimeframeProfile | None:
    from .realtime import VERSION
    if version in (STRATEGY_15M_V2, VERSION):
        return TimeframeProfile.INTRADAY_15M
    if version == STRATEGY_4H_V2:
        return TimeframeProfile.SWING_4H
    return None


def profile_entry_config(profile: TimeframeProfile, *, read_env: bool = True) -> JavaEntryConfig:
    """Read profile-specific knobs without falling back to another profile's values."""
    defaults = JavaEntryConfig()
    if not read_env:
        return defaults
    prefix = "HTX_TREND_15M_" if profile is TimeframeProfile.INTRADAY_15M else "HTX_TREND_4H_"

    def integer(name: str, value: int) -> int:
        return int(environ.get(prefix + name, str(value)))

    def decimal(name: str, value: Decimal) -> Decimal:
        return Decimal(environ.get(prefix + name, str(value)))

    def flag(name: str, value: bool) -> bool:
        return environ.get(prefix + name, str(value)).strip().lower() in {"1", "true", "yes", "on"}

    return replace(
        defaults,
        fast_ema_period=integer("FAST_EMA_PERIOD", defaults.fast_ema_period),
        medium_ema_period=integer("MEDIUM_EMA_PERIOD", defaults.medium_ema_period),
        slow_ema_period=integer("SLOW_EMA_PERIOD", defaults.slow_ema_period),
        breakout_lookback=integer("BREAKOUT_LOOKBACK", defaults.breakout_lookback),
        momentum_lookback=integer("MOMENTUM_LOOKBACK", defaults.momentum_lookback),
        atr_period=integer("ATR_PERIOD", defaults.atr_period),
        adx_period=integer("ADX_PERIOD", defaults.adx_period),
        volume_lookback=integer("VOLUME_LOOKBACK", defaults.volume_lookback),
        min_atr_pct=decimal("MIN_ATR_PCT", defaults.min_atr_pct),
        min_adx=decimal("MIN_ADX", defaults.min_adx),
        min_momentum_pct=decimal("MIN_MOMENTUM_PCT", defaults.min_momentum_pct),
        min_volume_ratio=decimal("MIN_VOLUME_RATIO", defaults.min_volume_ratio),
        entry_score_threshold=decimal("ENTRY_SCORE_THRESHOLD", defaults.entry_score_threshold),
        min_score_gap=decimal("MIN_SCORE_GAP", defaults.min_score_gap),
        box_window_bars=integer("BOX_WINDOW_BARS", defaults.box_window_bars),
        fib_source_lookback_bars=integer("FIB_SOURCE_LOOKBACK_BARS", defaults.fib_source_lookback_bars),
        combination_require_trending_regime=flag(
            "REQUIRE_TRENDING_REGIME", defaults.combination_require_trending_regime),
    )


def profile_strategy(profile: TimeframeProfile, *, read_env: bool = True, strategy_version: str | None = None) -> ProfileStrategySpec:
    contract = profile.contract
    config = profile_entry_config(profile, read_env=read_env)
    conditions = {item.condition_id: item.version for item in CONDITIONS
                  if item.condition_id != "entry.period_15m"}
    strategy = StrategySpec(
        "HTX_TREND",
        version_for_profile(profile),
        {item.feature_id: item.version for item in FEATURES},
        conditions,
        {**asdict(config), "timeframe_profile": profile.value,
         "entry_period": contract.entry_period, "trend_period": contract.trend_period,
         "confirmation_periods": contract.confirmation_periods,
         "r0001_donchian_research_variants": (42, 84, 126, 168),
         "r0001_threshold_research_variant": Decimal("0.25")},
        ({"from": "SCAN", "to": "CANDIDATE", "when": "profile contract and model conditions pass"},
         {"from": "CANDIDATE", "to": "GATE", "when": "policy selected"},
         {"from": "GATE", "to": "ORDER_INTENT", "when": "all entry and risk gates pass"},
         {"from": "OPEN", "to": "MANAGED", "when": "exchange fill confirmed"},
         {"from": "MANAGED", "to": "EXIT_INTENT", "when": "V4 exit action triggers"}),
        ("data integrity failure", "profile context incomplete", "exchange/account mismatch",
         "risk reservation failure"),
        ("evidence persistence unavailable for new risk", "writer lock lost", "LIVE approval expired"),
    )
    result = ProfileStrategySpec(
        strategy, profile, contract.entry_period, contract.trend_period, contract.confirmation_periods, config,
        tuple(item.value for item in (ModelCode.DONCHIAN_BREAKOUT, ModelCode.FIB_PULLBACK_CONTINUATION,
                                      ModelCode.EMA_MOMENTUM_CONTINUATION)),
        tuple(item.value for item in ModelCode),
    )
    from .realtime import VERSION, MODELS
    if strategy_version == VERSION:
        if profile is not TimeframeProfile.INTRADAY_15M:
            raise ValueError('realtime strategy is 15m only')
        from .realtime.plans import Parameters, GRID
        parameters = Parameters(Decimal(environ.get('HTX_RT_BAND_ATR', '.10')),
                                Decimal(environ.get('HTX_RT_MIN_NET_R', '1.5'))) if read_env else Parameters()
        rt = replace(strategy, version=VERSION,
            feature_versions={'closed_1h_ema20_60':'v1','closed_15m_structure20_32_atr14':'v1',
                              'trade_bbo_2s':'v1'},
            condition_versions={'entry.profile_contract':'v1','rt.two_distinct_trades':'v1',
                                'rt.ioc_cost_risk':'v1','rt.protection_reentry':'linked-v1'},
            parameters={'parameters':asdict(parameters),'preregistered_grid':[asdict(x) for x in GRID]},
            transitions=({'from':'STRUCTURE','to':'TRIGGERED','when':'two trades and fresh BBO'},
                       {'from':'TRIGGERED','to':'ORDER_INTENT','when':'cost/risk/ownership gates pass'},
                       {'from':'OPEN','to':'MANAGED','when':'fill confirmed; v4/linked ownership'}))
        result = replace(result, strategy=rt, primary_models=tuple(sorted(MODELS)), confirmation_models=())
    result.validate()
    return result
