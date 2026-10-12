from datetime import UTC, datetime
from decimal import Decimal

import pymysql

from htx_trend.live_worker import LiveTrendWorker, LiveTrendWorkerConfig
from htx_trend.mysql_store import HtxTrendMySqlRepository, RuntimeInstance
from htx_trend.strategy_context import MarketRegime


class _Cursor:
    def __init__(self, connection):
        self.connection = connection

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return None

    def execute(self, query, args=()):
        self.connection.queries.append((" ".join(query.split()), args))

    def fetchone(self):
        return {"total_count": 30, "long_context_count": 23, "short_context_count": 2,
                "long_signal_count": 20, "short_signal_count": 2}

    def fetchall(self):
        return []


class _Connection:
    def __init__(self):
        self.queries = []

    def cursor(self):
        return _Cursor(self)

    def close(self):
        return None


def test_repository_reads_java_latest_signal_per_contract_breadth_query() -> None:
    connection = _Connection()
    stats = HtxTrendMySqlRepository(connection).latest_breadth_stats(datetime(2026, 10, 8, tzinfo=UTC))
    assert stats.total_count == 30
    assert stats.long_context_count == 23
    query, params = connection.queries[-1]
    assert "SELECT contract_code, MAX(id) AS max_id" in query
    assert "latest.trend_signal = 'LONG'" in query
    assert "LIKE '%%trend=LONG%%'" in query
    assert "LIKE '%%uptrend%%'" in query
    assert "LIKE '%%trend=SHORT%%'" in query
    assert "LIKE '%%downtrend%%'" in query
    assert params == (datetime(2026, 10, 8, tzinfo=UTC),)


def test_latest_breadth_stats_query_mogrifies_under_pymysql() -> None:
    """PyMySQL uses %-formatting; literal LIKE wildcards must be doubled in the SQL string."""
    connection = _Connection()
    since = datetime(2026, 10, 8, 12, 0, tzinfo=UTC)
    HtxTrendMySqlRepository(connection).latest_breadth_stats(since)
    query, params = connection.queries[-1]
    escaped = tuple(pymysql.converters.escape_item(arg, "utf8mb4") for arg in params)
    mogrified = query % escaped
    assert "%trend=LONG%" in mogrified
    assert "%uptrend%" in mogrified
    assert "%trend=SHORT%" in mogrified
    assert "%downtrend%" in mogrified


def test_market_breadth_context_uses_java_minimum_symbol_and_opposite_ratio_rules() -> None:
    context = LiveTrendWorker.market_context_from_breadth(
        total_count=30,
        long_context_count=23,
        short_context_count=2,
        anchor_regime=MarketRegime.BROAD_UP,
        config=LiveTrendWorkerConfig(("BTC-USDT",)),
    )
    assert context.regime is MarketRegime.BROAD_UP
    assert context.breadth_side.value == "LONG"
    assert context.breadth_ratio == Decimal(23) / Decimal(30)
