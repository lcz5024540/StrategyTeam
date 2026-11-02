"""Exit configuration, independent of the process and holding manager."""
from dataclasses import dataclass
from decimal import Decimal as D
from os import environ
from .exits import ProfitProtectionPolicy


@dataclass(frozen=True)
class ExitConfig:
    engine_enabled: bool = False
    engine_live: bool = False
    trailing_atr: D = D('2')
    swing_trailing_atr: D = D('3')
    trailing_activation_r: D = D('1')
    fee_rate: D = D('.0004')
    max_adverse_funding_rate: D = D('.0012')
    flat_bars: int = 3
    flat_budget: D = D('.25')
    flat_budget_rate: D = D('.003')
    policy: ProfitProtectionPolicy = ProfitProtectionPolicy()
    # MAINLINE-A A2 timeout and A1' minimum hold.
    timeout_bars: int | None = 16
    min_hold_bars: int | None = 0

    @classmethod
    def from_env(cls):
        def flag(name): return environ.get(name,'false').lower() == 'true'
        def dec(name, default): return D(environ.get(name,str(default)))
        def optional_int(name, default):
            value = environ.get(name)
            if value is None or value.strip().lower() in ('','none','null'):
                return default
            return int(value)
        default=cls()
        return cls(engine_enabled=flag('HTX_TREND_EXIT_ENGINE_ENABLED'),
            engine_live=flag('HTX_TREND_EXIT_ENGINE_LIVE_ENABLED'),
            trailing_atr=dec('HTX_TREND_TRAILING_ATR_MULTIPLIER',default.trailing_atr),
            swing_trailing_atr=dec('HTX_TREND_SWING_TRAILING_ATR_MULTIPLIER',default.swing_trailing_atr),
            trailing_activation_r=dec('HTX_TREND_TRAILING_ACTIVATION_R',default.trailing_activation_r),
            fee_rate=dec('HTX_TREND_ESTIMATED_TAKER_FEE_RATE',default.fee_rate),
            max_adverse_funding_rate=dec('HTX_TREND_MAX_HOLD_FUNDING_COST_RATE',default.max_adverse_funding_rate),
            flat_bars=int(environ.get('HTX_TREND_FLAT_LOSS_EXIT_CONFIRMATION_BARS','3')),
            policy=ProfitProtectionPolicy(
                dec('HTX_TREND_INTRADAY_PROFIT_ARM_ATR_MULTIPLIER',D('.50')),
                dec('HTX_TREND_INTRADAY_PROFIT_LOCK_ATR_MULTIPLIER',D('.15')),
                dec('HTX_TREND_INTRADAY_PROFIT_PARTIAL_ATR_MULTIPLIER',D('.75')),
                dec('HTX_TREND_INTRADAY_PROFIT_PULLBACK_ATR_MULTIPLIER',D('.40')),
                dec('HTX_TREND_INTRADAY_PROFIT_MIN_NET_FEE_MULTIPLE',D('2')),
                int(environ.get('HTX_TREND_INTRADAY_PROFIT_PROTECTION_BREACH_CONFIRMATION_BARS','2'))),
            timeout_bars=optional_int('HTX_TREND_A2_TIMEOUT_BARS', default.timeout_bars),
            min_hold_bars=optional_int('HTX_TREND_A1_PRIME_MIN_HOLD_BARS', default.min_hold_bars))

