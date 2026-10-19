"""Opt-in business candle delivery; no DDL, account access, or worker integration."""
from contextlib import contextmanager
from datetime import datetime, timezone
from zoneinfo import ZoneInfo
from typing import Iterable

from .market_data import Candle, period_seconds
from .mysql_store import MySqlSettings, connect

BUSINESS_KLINE_SYMBOLS = ('BTC-USDT', 'ETH-USDT')
BUSINESS_KLINE_PERIODS = ('15min', '60min', '4hour', '1day')
# Java LocalDateTime and this legacy DATETIME column use Shanghai wall time.
BUSINESS_DB_TIMEZONE = ZoneInfo('Asia/Shanghai')
COLUMNS = ('candle_hash', 'contract_code', 'period', 'bar_time', 'open_price',
           'high_price', 'low_price', 'close_price', 'amount', 'volume',
           'turnover', 'trade_count', 'source_kind', 'captured_at')
INSERT_SQL = ('INSERT IGNORE INTO htx_trend_runtime_kline_candle (' + ','.join(COLUMNS)
              + ') VALUES (' + ','.join(['%s'] * len(COLUMNS)) + ')')


class MySqlBusinessKlineSink:
    def __init__(self, connection):
        self.connection = connection

    def write(self, candles: Iterable[Candle], *, contract_code: str, period: str,
              captured_at: datetime) -> dict:
        if contract_code not in BUSINESS_KLINE_SYMBOLS or period not in BUSINESS_KLINE_PERIODS:
            raise ValueError('outside locked business K-line scope')
        if captured_at.tzinfo is None:
            raise ValueError('captured_at requires timezone')
        captured = captured_at.astimezone(BUSINESS_DB_TIMEZONE).replace(tzinfo=None)
        attempted = inserted = 0
        try:
            with self.connection.cursor() as cursor:
                for bar in candles:
                    attempted += 1
                    inserted += cursor.execute(INSERT_SQL, (
                        bar.content_hash, contract_code, period, bar.open_time,
                        bar.open, bar.high, bar.low, bar.close, bar.amount, bar.volume,
                        bar.turnover, bar.trade_count, 'HTX_PUBLIC_RUNTIME', captured))
            self.connection.commit()
        except Exception:
            self.connection.rollback()
            raise
        return {'attempted': attempted, 'inserted': inserted, 'ignored': attempted - inserted}


@contextmanager
def business_sink(enabled: bool):
    """Default-off CLI factory; connection lifetime belongs to this context."""
    if not enabled:
        yield None
        return
    connection = connect(MySqlSettings.from_env('HTX_DB_'))
    try:
        yield MySqlBusinessKlineSink(connection)
    finally:
        connection.close()


def business_kline_health(rows: Iterable[dict], *, now: datetime) -> list[dict]:
    """Pure aggregation; naive SQL DATETIME values are Shanghai wall time."""
    if now.tzinfo is None:
        raise ValueError('health clock requires timezone')
    maxima = {}
    for row in rows:
        key = (row['contract_code'], row['period'])
        if key[0] not in BUSINESS_KLINE_SYMBOLS or key[1] not in BUSINESS_KLINE_PERIODS:
            continue
        captured = row['captured_at']
        if captured.tzinfo is None:
            captured = captured.replace(tzinfo=BUSINESS_DB_TIMEZONE)
        bar, capture = maxima.get(key, (float('-inf'), float('-inf')))
        maxima[key] = max(bar, row['bar_time']), max(capture, captured.timestamp())
    result = []
    for symbol in BUSINESS_KLINE_SYMBOLS:
        for period in BUSINESS_KLINE_PERIODS:
            limits = (2 * period_seconds(period) + 120, period_seconds(period) + 120)
            latest = maxima.get((symbol, period))
            lags = None if latest is None else tuple(now.timestamp() - value for value in latest)
            status = ('MISSING' if lags is None else 'INVALID_FUTURE' if min(lags) < 0 else
                      'HEALTHY' if all(lag <= limit for lag, limit in zip(lags, limits)) else 'STALE')
            result.append({'contract_code': symbol, 'period': period, 'status': status,
                           'bar_lag_seconds': None if lags is None else lags[0],
                           'capture_lag_seconds': None if lags is None else lags[1]})
    return result
