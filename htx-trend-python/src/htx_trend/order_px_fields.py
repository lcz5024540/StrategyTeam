"""Nullable submission metadata; signal and fill prices are never inputs."""
from decimal import Decimal, InvalidOperation


def _positive(value):
    try:
        number = Decimal(str(value))
        return number if number.is_finite() and number > 0 else None
    except (InvalidOperation, ValueError, TypeError):
        return None


def order_px_fields(order_type, *, limit=None, mid=None, last=None):
    """Use only the actual limit or a submission-time market observation."""
    kind = str(order_type).lower()
    candidates = ((limit, 'limit'),) if kind == 'limit' else (
        ((mid, 'mid'), (last, 'last')) if kind in {'market', 'mkt'} else ())
    for value, source in candidates:
        price = _positive(value)
        if price is not None:
            return {'order_px': price, 'order_px_source': source}
    return {'order_px': None, 'order_px_source': None}


def validate_order_px(price, source):
    if price is None and source is None:
        return
    if source not in {'limit', 'mid', 'last'} or _positive(price) is None:
        raise ValueError('order_px requires a positive price and limit/mid/last source')


def mysql_time(value):
    """MySQL DATETIME stores UTC without an offset; unknown stays NULL."""
    from datetime import UTC, datetime
    if value is None:
        return None
    if isinstance(value, str):
        value = datetime.fromisoformat(value.replace('Z', '+00:00'))
    if value.tzinfo is None:
        raise ValueError('submission metadata timestamp must include timezone')
    return value.astimezone(UTC).replace(tzinfo=None)
