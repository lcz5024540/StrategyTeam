"""Research timestamps: 2019-01-01 UTC through now(UTC)+1 day."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

import pytest

from htx_trend.research.adapters.htx.market_data import (
    datetime_from_epoch,
    parse_htx_funding_time,
    require_research_timestamp,
)


def test_year_58747_value_raises():
    """Millisecond epoch read as seconds is year 58747 and must be rejected."""
    unscaled_ms = 1_791_763_200_000
    with pytest.raises(ValueError, match="58747|58748"):
        require_research_timestamp(datetime_from_epoch(unscaled_ms))


def test_timestamp_two_days_in_future_raises():
    now = datetime(2026, 10, 11, 12, 0, tzinfo=UTC)
    future = now + timedelta(days=2)
    with pytest.raises(ValueError, match="out of range"):
        require_research_timestamp(future, clock=lambda: now)


def test_2027_timestamp_passes_when_clock_is_2027_june():
    clock = lambda: datetime(2027, 6, 15, tzinfo=UTC)
    value = datetime(2027, 3, 1, 8, 0, tzinfo=UTC)
    assert require_research_timestamp(value, clock=clock) == value
    assert parse_htx_funding_time(int(value.timestamp()), clock=clock) == value


def test_before_2019_01_01_utc_raises():
    now = datetime(2026, 10, 11, tzinfo=UTC)
    with pytest.raises(ValueError, match="out of range"):
        require_research_timestamp(
            datetime(2018, 12, 31, 23, 59, 59, tzinfo=UTC),
            clock=lambda: now,
        )
    assert require_research_timestamp(
        datetime(2019, 1, 1, tzinfo=UTC),
        clock=lambda: now,
    ) == datetime(2019, 1, 1, tzinfo=UTC)
