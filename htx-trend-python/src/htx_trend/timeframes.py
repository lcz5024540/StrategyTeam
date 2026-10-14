"""Explicit timeframe contracts for independent entry and exit lifecycles."""
from __future__ import annotations

from dataclasses import dataclass
from enum import Enum


@dataclass(frozen=True, slots=True)
class TimeframeContract:
    entry_period: str
    trend_period: str
    confirmation_periods: tuple[str, ...]


class TimeframeProfile(str, Enum):
    INTRADAY_15M = "INTRADAY_15M"
    SWING_4H = "SWING_4H"

    @property
    def contract(self) -> TimeframeContract:
        if self is TimeframeProfile.INTRADAY_15M:
            return TimeframeContract("15min", "60min", ("30min", "60min", "4hour", "1day"))
        return TimeframeContract("4hour", "1day", ("1day",))

    @classmethod
    def for_period(cls, period: str) -> "TimeframeProfile":
        for profile in cls:
            if profile.contract.entry_period == period:
                return profile
        raise ValueError(f"unsupported trend entry period: {period}")

    def validate(self, entry_period: str, trend_period: str) -> None:
        contract = self.contract
        if (entry_period, trend_period) != (contract.entry_period, contract.trend_period):
            raise ValueError(
                f"{self.value} requires entry={contract.entry_period},trend={contract.trend_period}; "
                f"got entry={entry_period},trend={trend_period}"
            )
