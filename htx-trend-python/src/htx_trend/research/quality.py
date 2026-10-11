"""Quality findings are evidence; never silently repair or invent candles."""
from decimal import Decimal
from ..market_data import Candle, period_anchor_seconds, period_seconds


def audit(rows: list[dict], period: str, *, start: int, end: int) -> dict:
    step = period_seconds(period)
    anchor = period_anchor_seconds(period)
    if start >= end or (start-anchor) % step or (end-anchor) % step:
        raise ValueError('quality interval must be nonempty and aligned [start,end)')
    errors, warnings, bars = [], [], []
    for index, raw in enumerate(rows):
        try:
            bar = Candle.from_htx(raw)
            if any(not x.is_finite() for x in (bar.open, bar.high, bar.low, bar.close)):
                raise ValueError('nonfinite OHLC')
            bars.append(bar)
            if min(bar.open, bar.high, bar.low, bar.close) <= 0 or bar.low > min(bar.open, bar.close) or bar.high < max(bar.open, bar.close):
                errors.append({'kind': 'INVALID_OHLC', 'time': bar.open_time})
            if any(x is not None and (not x.is_finite() or x < 0) for x in (bar.volume, bar.amount, bar.turnover)):
                errors.append({'kind': 'INVALID_VOLUME', 'time': bar.open_time})
        except (ValueError, TypeError, ArithmeticError, KeyError):
            errors.append({'kind': 'MALFORMED_BAR', 'index': index})
    times = [b.open_time for b in bars]
    if len(set(times)) != len(times):
        errors.append({'kind': 'DUPLICATE'})
    if times != sorted(times):
        warnings.append({'kind': 'SOURCE_NOT_ASCENDING'})
    actual = set(times)
    expected = set(range(start, end, step))
    missing = sorted(expected - actual)
    outside = sorted(actual - expected)
    if missing:
        errors.append({'kind': 'GAP', 'count': len(missing), 'times': missing})
    if outside:
        errors.append({'kind': 'OUTSIDE_RANGE_OR_UNALIGNED', 'times': outside})
    ordered = sorted(bars, key=lambda x: x.open_time)
    for a, b in zip(ordered, ordered[1:]):
        if a.close > 0 and abs(b.close / a.close - 1) > Decimal('.20'):
            warnings.append({'kind': 'PRICE_JUMP_GT_20_PERCENT', 'time': b.open_time})
        if a.volume is not None and b.volume is not None and a.volume > 0 and b.volume / a.volume > 20:
            warnings.append({'kind': 'VOLUME_SPIKE_GT_20X', 'time': b.open_time})
    return {'schema': 'htx-python-quality-v1', 'start': start, 'end_exclusive': end,
            'period': period, 'expected': len(expected), 'actual': len(bars),
            'integrity': 'PARTIAL' if errors else 'COMPLETE', 'errors': errors, 'warnings': warnings}
