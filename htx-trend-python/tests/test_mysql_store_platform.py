from decimal import Decimal

import pytest

from htx_trend.mysql_store import (
    CapitalAllocation,
    HtxTrendMySqlRepository,
    LedgerEvent,
    PositionIntent,
    RuntimeInstance,
    TradeAccount,
    TrendOrderIntent,
)
from htx_trend.strategy_platform import CapitalRiskCoordinator, DisabledStrategyWorker, ExecutionMode, StrategySupervisor, WorkerHealth


class Cursor:
    def __init__(self, connection): self.connection = connection; self.lastrowid = 1
    def __enter__(self): return self
    def __exit__(self, *_): return None
    def execute(self, query, args=()): self.connection.queries.append((" ".join(query.split()), args)); return 1
    def fetchone(self):
        query, _ = self.connection.queries[-1]
        if "SELECT max_notional_usdt,max_active_positions FROM strategy_account_risk_limit" in query:
            return {"max_notional_usdt": Decimal("200"), "max_active_positions": 2}
        if "FROM strategy_risk_reservation WHERE account_id" in query:
            return {"notional_usdt": Decimal("0"), "position_count": 0, "risk_usdt": Decimal("0")}
        if "FROM htx_trend_factor_snapshot" in query: return {"id": 11}
        if "FROM htx_trend_position WHERE position_key" in query: return {"id": 12}
        if "FROM htx_trend_order WHERE client_order_id" in query: return {"id": 13}
        if "COUNT(*) AS count" in query: return {"count": 1}
        return None
    def fetchall(self): return []


class Connection:
    def __init__(self): self.queries = []; self.commits = 0; self.rollbacks = 0; self.closed = False
    def cursor(self): return Cursor(self)
    def commit(self): self.commits += 1
    def rollback(self): self.rollbacks += 1
    def close(self): self.closed = True


def account() -> TradeAccount:
    return TradeAccount(7, "primary", "PRIMARY", True, False, "https://api.huobi.pro")


def test_mysql_repository_writes_parameterized_execution_records_without_ddl() -> None:
    connection, repository = Connection(), HtxTrendMySqlRepository(Connection())
    repository = HtxTrendMySqlRepository(connection)
    factor_id = repository.record_factor_snapshot(snapshot_key="snapshot", contract_code="BTC-USDT", period="15min", bar_time=1,
                                                   market_regime="LONG", factors={"ema_fast": Decimal("1")})
    repository.record_candidate(contract_code="BTC-USDT", period="15min", bar_time=1, direction="LONG", regime="LONG",
                                score=Decimal("80"), close_price=Decimal("100"), factors={}, reason="candidate", model="DONCHIAN_BREAKOUT",
                                run_id="run", factor_snapshot_id=factor_id, invalidation_price=Decimal("90"), target_low=Decimal("120"),
                                target_high=Decimal("130"), expected_r=Decimal("2"), timeframe_profile="INTRADAY_15M")
    position_id = repository.record_position_intent(PositionIntent("position-key", "run", "BTC-USDT", "LONG", Decimal("0.01"), 2, 3,
        account(), "DONCHIAN_BREAKOUT", factor_id, "signal", "gate", Decimal("90"), Decimal("120"), Decimal("130"), Decimal("2")))
    order_id = repository.record_order_intent(TrendOrderIntent(position_id, "position-key", "run", "BTC-USDT", "OPEN", "LONG", "BUY",
        "LONG", False, "client-order", 2, account(), "DONCHIAN_BREAKOUT", factor_id, "entry"))
    repository.mark_order_accepted(position_id=position_id, leg="OPEN", client_order_id="client-order", exchange_order_id=99,
                                   exchange_client_order_id=None, message="accepted")
    repository.reconcile_order(position_id=position_id, leg="OPEN", client_order_id="client-order", status="FILLED", exchange_order_id=99,
                               exchange_client_order_id=None, exchange_state="filled", filled_contracts=Decimal("2"),
                               average_price=Decimal("100"), fee=Decimal("0.01"), fee_currency="USDT", realized_profit=None, message="filled")
    repository.record_ledger_event(LedgerEvent("event-key", "OPEN_FILL", account(), "TREND", "HTX_TREND_V1", "DONCHIAN_BREAKOUT", "run",
        factor_id, "BTC-USDT", position_id, order_id, "client-order", "LONG", "fill", execution_price_usdt=Decimal("100"),
        filled_contracts=Decimal("2"), contract_size=Decimal("0.01"), amount_usdt=Decimal("2")))
    statements = [query for query, _ in connection.queries]
    assert all("%s" in query for query in statements if query.startswith(("INSERT", "UPDATE")))
    assert all(not query.startswith(("CREATE", "ALTER", "DROP")) for query in statements)
    candidate = next(args for query, args in connection.queries if "htx_trend_candidate_signal" in query)
    assert "timeframe_profile" in next(query for query, _ in connection.queries if "htx_trend_candidate_signal" in query)
    assert candidate[1] == "INTRADAY_15M"
    assert candidate[19] == "python-trend-v1:DONCHIAN_BREAKOUT"
    assert connection.commits >= 7


@pytest.mark.parametrize("timeframe_profile", ["INTRADAY_15M", "SWING_4H"])
def test_candidate_trace_persists_each_timeframe_profile(timeframe_profile: str) -> None:
    connection = Connection()
    repository = HtxTrendMySqlRepository(connection)
    repository.record_candidate(contract_code="BTC-USDT", period="15min" if timeframe_profile == "INTRADAY_15M" else "4hour",
                                bar_time=1, direction="LONG", regime="TRENDING_UP", score=Decimal("80"),
                                close_price=Decimal("100"), factors={}, reason="candidate", model="DONCHIAN_BREAKOUT",
                                run_id="run", factor_snapshot_id=None, invalidation_price=Decimal("90"),
                                target_low=Decimal("120"), target_high=Decimal("130"), expected_r=Decimal("2"),
                                timeframe_profile=timeframe_profile)
    query, params = next((query, params) for query, params in connection.queries
                         if "htx_trend_candidate_signal" in query)
    assert "timeframe_profile" in query
    assert params[1] == timeframe_profile


def test_schema_validation_is_read_only() -> None:
    connection = Connection()
    HtxTrendMySqlRepository(connection).validate_runtime_schema()
    assert any("python_trend_exit_state" in query for query, _ in connection.queries)
    assert all(query.startswith("SELECT") for query, _ in connection.queries)


def test_open_like_and_cooldown_queries_match_account_scope() -> None:
    connection = Connection(); repository = HtxTrendMySqlRepository(connection)
    repository.active_position_count(account_id=999)
    repository.latest_done_trade(account_id=999, contract_code="BTC-USDT")
    open_like_query, open_like_args = connection.queries[-2]
    done_query, done_args = connection.queries[-1]
    assert "source_position_id IS NULL AND account_id=%s" in open_like_query
    assert "account_label <> 'paper'" in open_like_query
    assert "'UNKNOWN'" in open_like_query
    assert open_like_args == (999,)
    assert "source_position_id IS NULL" in done_query and "timeframe_profile=%s" in done_query
    assert done_args == ("BTC-USDT", 999, "INTRADAY_15M")


def test_runtime_lifecycle_is_explicit_and_never_creates_schema() -> None:
    connection = Connection(); repository = HtxTrendMySqlRepository(connection)
    repository.mark_runtime_started("trend-main")
    repository.mark_runtime_stopped("trend-main")
    assert all(query.startswith("UPDATE") for query, _ in connection.queries)


def test_risk_reservation_locks_the_account_boundary_then_releases() -> None:
    connection = Connection(); repository = HtxTrendMySqlRepository(connection)
    repository.reserve_risk(reservation_key="reserve", instance=instance(), position_key="position",
                            notional_usdt=Decimal("250"), risk_usdt=Decimal("1"),
                            max_total_risk_usdt=Decimal("4.5"), max_active_positions=3, reason="entry")
    repository.release_risk("reserve", "terminal position reconciliation")
    queries = [query for query, _ in connection.queries]
    assert "FOR UPDATE" in queries[0]
    assert any("INSERT IGNORE INTO strategy_risk_reservation" in query for query in queries)
    assert any("status='RELEASED'" in query for query in queries)
    assert connection.rollbacks == 0


class AllocationRepository:
    def __init__(self, active=0, strategy_max=100, account_max=200):
        self.active, self.strategy_max, self.account_max = active, strategy_max, account_max
    def capital_allocation(self, **_): return CapitalAllocation(7, "primary", "TREND", "HTX_TREND_V1", Decimal(str(self.strategy_max)), 2, None)
    def account_risk_limit(self, *_):
        from htx_trend.mysql_store import AccountRiskLimit
        return AccountRiskLimit(7, "primary", Decimal(str(self.account_max)), 2, None)
    def active_risk_reservation_totals(self, *_):
        from htx_trend.mysql_store import RiskReservationTotals
        return RiskReservationTotals(Decimal("0"), self.active)


def instance(mode="OBSERVE") -> RuntimeInstance:
    return RuntimeInstance("trend-main", "TREND", "HTX_TREND_V1", 7, "primary", mode, True, "unit", None)


def test_capital_coordinator_enforces_budget_and_capacity() -> None:
    coordinator = CapitalRiskCoordinator(AllocationRepository(active=1))
    assert coordinator.admit(instance(), Decimal("100")).allowed
    assert not coordinator.admit(instance(), Decimal("101")).allowed
    assert not CapitalRiskCoordinator(AllocationRepository(active=2)).admit(instance(), Decimal("10")).allowed


def test_capital_coordinator_does_not_apply_account_total_notional_gate() -> None:
    coordinator = CapitalRiskCoordinator(AllocationRepository(strategy_max=500, account_max=200))
    assert coordinator.admit(instance(), Decimal("300")).allowed


def test_disabled_worker_isolated_and_supervisor_writes_heartbeat() -> None:
    connection = Connection(); repository = HtxTrendMySqlRepository(connection)
    worker = DisabledStrategyWorker(RuntimeInstance("carry", "CARRY_SINGLE", "HTX_CARRY_V1", 7, "primary", "OBSERVE", True, "unit", None), repository)
    result = StrategySupervisor(repository, {"carry": worker}).tick_all()
    assert result["carry"].status == "DISABLED"
    assert any("strategy_runtime_heartbeat" in query for query, _ in connection.queries)


def test_observer_rejects_unapproved_paper_or_live_mode() -> None:
    from htx_trend.strategy_platform import TrendObservationWorker, TrendObserverConfig
    with pytest.raises(ValueError, match="OBSERVE"):
        TrendObservationWorker(instance("PAPER"), HtxTrendMySqlRepository(Connection()), object(), TrendObserverConfig(("BTC-USDT",)))


def test_observer_profiles_from_env_map_to_independent_period_contracts(monkeypatch) -> None:
    from htx_trend.strategy_platform import TrendObserverConfig
    monkeypatch.setenv("HTX_TREND_PROFILES", "INTRADAY_15M,SWING_4H")
    profiles = TrendObserverConfig.profiles_from_env(("BTC-USDT", "ETH-USDT"))
    assert [(item.entry_period, item.trend_period) for item in profiles] == [("15min", "60min"), ("4hour", "1day")]


def test_execution_readers_explicitly_exclude_paper_even_with_live_strategy_name():
    connection = Connection()
    repository = HtxTrendMySqlRepository(connection)
    repository.active_position_count(account_id=7)
    repository.active_direction_position_count(account_id=7, side="LONG")
    repository.active_contract_position(account_id=7, contract_code="BTC-USDT")
    repository.latest_done_trade(account_id=7, contract_code="BTC-USDT")
    repository.pending_orders(account_id=7)
    repository.mirror_position(source_position_id=1, account_id=7)
    from types import SimpleNamespace
    repository.managed_positions(SimpleNamespace(account_id=7, strategy_family="TREND", strategy_name="HTX_TREND_V1"))
    assert len(connection.queries) == 7
    for query, _ in connection.queries:
        assert "account_label <> 'paper'" in query or "account_label='primary'" in query
    assert connection.commits == 0
