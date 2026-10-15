"""MySQL repository compatible with the existing Java HTX trend ledger.

No DDL is executed here.  This repository uses parameterized SQL and writes the
same tables consumed by the current dashboard, so Java and Python must never
be live writers for the same account/strategy instance simultaneously.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime
from decimal import Decimal
from os import environ
from typing import Any, Protocol
import hashlib
import json
import logging


class Cursor(Protocol):
    lastrowid: int
    def execute(self, query: str, args: tuple[Any, ...] = ...) -> int: ...
    def fetchone(self) -> dict[str, Any] | None: ...
    def fetchall(self) -> list[dict[str, Any]]: ...
    def __enter__(self) -> "Cursor": ...
    def __exit__(self, *_: object) -> None: ...

class Connection(Protocol):
    def cursor(self) -> Cursor: ...
    def commit(self) -> None: ...
    def rollback(self) -> None: ...
    def close(self) -> None: ...

@dataclass(frozen=True, slots=True)
class MySqlSettings:
    host: str
    port: int
    database: str
    user: str
    password: str

    @classmethod
    def from_env(cls, prefix: str = "COINSTRATEGY_DB_") -> "MySqlSettings":
        required = {name: environ.get(prefix + name) for name in ("HOST", "DATABASE", "USER", "PASSWORD")}
        missing = [name for name, value in required.items() if not value]
        if missing: raise ValueError(f"missing database environment variables: {', '.join(prefix + name for name in missing)}")
        return cls(required["HOST"] or "", int(environ.get(prefix + "PORT", "3306")), required["DATABASE"] or "",
                   required["USER"] or "", required["PASSWORD"] or "")


def connect(settings: MySqlSettings) -> Connection:
    """Open a MySQL connection only when an operator starts a worker."""
    try:
        import pymysql
    except ImportError as error:
        raise RuntimeError("install coinstrategy-htx-trend to use MySQL storage") from error
    return pymysql.connect(host=settings.host, port=settings.port, user=settings.user, password=settings.password,
                           database=settings.database, charset="utf8mb4", autocommit=False,
                           cursorclass=pymysql.cursors.DictCursor)


@dataclass(frozen=True, slots=True)
class TradeAccount:
    account_id: int
    account_label: str
    role: str
    trend_enabled: bool
    mirror_enabled: bool
    base_url: str


@dataclass(frozen=True, slots=True)
class RuntimeInstance:
    """One independently deployable strategy process registered by an operator."""
    instance_key: str
    strategy_family: str
    strategy_name: str
    account_id: int | None
    account_label: str | None
    execution_mode: str
    enabled: bool
    deployment_unit: str
    config_fingerprint: str | None


@dataclass(frozen=True, slots=True)
class AccountCredentialRecord:
    account_id: int
    account_label: str
    api_key_cipher: str
    secret_key_cipher: str
    base_url: str


@dataclass(frozen=True, slots=True)
class CapitalAllocation:
    account_id: int
    account_label: str
    strategy_family: str
    strategy_name: str
    max_notional_usdt: Decimal
    max_active_positions: int
    max_daily_loss_usdt: Decimal | None


@dataclass(frozen=True, slots=True)
class AccountRiskLimit:
    account_id: int
    account_label: str
    max_notional_usdt: Decimal
    max_active_positions: int
    max_daily_loss_usdt: Decimal | None


@dataclass(frozen=True, slots=True)
class RiskReservationTotals:
    notional_usdt: Decimal
    position_count: int


@dataclass(frozen=True, slots=True)
class TrendBreadthStats:
    """The current per-contract signal view used by Java market breadth."""
    total_count: int
    long_context_count: int
    short_context_count: int
    long_signal_count: int
    short_signal_count: int


@dataclass(frozen=True, slots=True)
class StrategyPolicyRecord:
    policy_code: str
    policy_version: str
    primary_model: str
    confirmation_models: str
    base_notional_usdt: Decimal
    max_notional_usdt: Decimal
    score_threshold: Decimal
    box_window_bars: int


@dataclass(frozen=True, slots=True)
class PositionIntent:
    """The minimum immutable identity needed before a live trend order is sent."""
    position_key: str
    run_id: str
    contract_code: str
    side: str
    contract_size: Decimal
    contract_volume: int
    leverage: int
    account: TradeAccount
    primary_model: str
    factor_snapshot_id: int | None
    entry_signal: str
    entry_gate_reason: str
    invalidation_price: Decimal | None
    target_zone_low: Decimal | None
    target_zone_high: Decimal | None
    expected_r_multiple: Decimal | None
    timeframe_profile: str = "INTRADAY_15M"
    market_regime: str | None = None
    strategy_family: str = "TREND"
    strategy_name: str = "HTX_TREND_V1"
    source_position_id: int | None = None
    confirmation_models: str | None = None
    policy_version: str | None = None
    entry_hypothesis: str | None = None
    sizing_multiplier: Decimal | None = None
    sizing_reason: str | None = None
    entry_fee_rate: Decimal | None = None
    estimated_entry_fee: Decimal | None = None
    estimated_close_fee: Decimal | None = None
    expected_net_after_fees: Decimal | None = None


@dataclass(frozen=True, slots=True)
class TrendOrderIntent:
    position_id: int
    position_key: str
    run_id: str
    contract_code: str
    leg: str
    trend_side: str
    order_side: str
    position_side: str
    reduce_only: bool
    client_order_id: str
    request_volume: int
    account: TradeAccount
    primary_model: str
    factor_snapshot_id: int | None
    reason: str
    timeframe_profile: str = "INTRADAY_15M"
    strategy_family: str = "TREND"
    strategy_name: str = "HTX_TREND_V1"
    sizing_multiplier: Decimal | None = None
    sizing_reason: str | None = None
    entry_gate_reason: str | None = None
    fee_rate_used: Decimal | None = None
    estimated_open_fee: Decimal | None = None
    estimated_close_fee: Decimal | None = None


@dataclass(frozen=True, slots=True)
class LedgerEvent:
    """Append-only accounting projection; it must never overwrite prior economics."""
    event_key: str
    event_type: str
    account: TradeAccount
    strategy_family: str
    strategy_name: str
    model_name: str
    run_id: str | None
    factor_snapshot_id: int | None
    contract_code: str | None
    position_id: int | None
    order_id: int | None
    client_order_id: str | None
    side: str | None
    reason: str | None
    execution_price_usdt: Decimal | None = None
    entry_price_usdt: Decimal | None = None
    close_price_usdt: Decimal | None = None
    filled_contracts: Decimal | None = None
    contract_size: Decimal | None = None
    amount_usdt: Decimal | None = None
    fee_usdt: Decimal = Decimal("0")
    funding_fee_usdt: Decimal = Decimal("0")
    gross_pnl_usdt: Decimal | None = None
    net_pnl_after_fee: Decimal | None = None


class HtxTrendMySqlRepository:
    def __init__(self, connection: Connection) -> None:
        self._connection = connection
        self._in_transaction = False
    def close(self) -> None: self._connection.close()

    def validate_runtime_schema(self) -> None:
        """Fail before a worker scans when its explicitly provisioned tables are absent.

        This only reads the database.  In particular it never treats a missing
        table as permission to create one during service start.
        """
        for table in ("strategy_runtime_instance", "strategy_runtime_heartbeat", "strategy_capital_allocation",
                      "htx_trade_account", "htx_trend_signal", "htx_trend_candidate_signal", "htx_trend_factor_snapshot",
                      "htx_trend_position", "htx_trend_order", "htx_trend_exit_decision", "coin_strategy_ledger_event",
                      "strategy_account_risk_limit", "strategy_risk_reservation", "htx_trend_structure_lifecycle", "python_trend_exit_state"):
            self._one(f"SELECT 1 AS present FROM {table} LIMIT 1", ())

    def enabled_trend_accounts(self) -> list[TradeAccount]:
        rows = self._all("SELECT id,account_label,account_role,trend_enabled,mirror_enabled,base_url FROM htx_trade_account "
                         "WHERE enabled=1 AND trend_enabled=1 ORDER BY sort_order,id")
        return [TradeAccount(int(row["id"]), row["account_label"], row["account_role"], bool(row["trend_enabled"]),
                             bool(row["mirror_enabled"]), row["base_url"]) for row in rows]

    def trade_account(self, account_id: int) -> TradeAccount | None:
        row = self._one("""SELECT id,account_label,account_role,trend_enabled,mirror_enabled,base_url FROM htx_trade_account
          WHERE id=%s AND enabled=1 AND trend_enabled=1""", (account_id,))
        return None if row is None else TradeAccount(int(row["id"]), str(row["account_label"]), str(row["account_role"]),
                                                     bool(row["trend_enabled"]), bool(row["mirror_enabled"]), str(row["base_url"]))

    def account_credentials(self, account_id: int) -> AccountCredentialRecord | None:
        row = self._one("""SELECT id,account_label,api_key_cipher,secret_key_cipher,base_url FROM htx_trade_account
          WHERE id=%s AND enabled=1 AND trend_enabled=1""", (account_id,))
        if row is None or not row.get("api_key_cipher") or not row.get("secret_key_cipher"):
            return None
        return AccountCredentialRecord(int(row["id"]), str(row["account_label"]), str(row["api_key_cipher"]),
                                       str(row["secret_key_cipher"]), str(row["base_url"]))

    def enabled_strategy_policies(self) -> list[StrategyPolicyRecord]:
        rows = self._all("""SELECT policy_code,policy_version,primary_model,confirmation_models,base_notional_usdt,
          max_notional_usdt,score_threshold,box_window_bars FROM htx_trend_strategy_policy
          WHERE enabled=1 ORDER BY updated_at DESC,id DESC""", ())
        return [StrategyPolicyRecord(str(row["policy_code"]), str(row["policy_version"]),
                str(row["primary_model"]), str(row["confirmation_models"]),
                Decimal(str(row["base_notional_usdt"])), Decimal(str(row["max_notional_usdt"])),
                Decimal(str(row["score_threshold"])), int(row["box_window_bars"])) for row in rows]

    def latest_breadth_stats(self, since: datetime) -> TrendBreadthStats:
        """Port ``HtxTrendSignalMapper.latestBreadthStats`` without runtime DDL.

        The query deliberately selects the latest persisted signal for each
        contract in the same lookback window.  It must not use the worker's
        in-memory scan: Java reads the ledger before it starts the next scan.
        """
        row = self._one("""SELECT COUNT(*) AS total_count,
          COALESCE(SUM(CASE WHEN latest.trend_signal = 'LONG'
              OR latest.reason LIKE '%%trend=LONG%%' OR latest.reason LIKE '%%uptrend%%'
              THEN 1 ELSE 0 END), 0) AS long_context_count,
          COALESCE(SUM(CASE WHEN latest.trend_signal = 'SHORT'
              OR latest.reason LIKE '%%trend=SHORT%%' OR latest.reason LIKE '%%downtrend%%'
              THEN 1 ELSE 0 END), 0) AS short_context_count,
          COALESCE(SUM(CASE WHEN latest.trend_signal = 'LONG' THEN 1 ELSE 0 END), 0) AS long_signal_count,
          COALESCE(SUM(CASE WHEN latest.trend_signal = 'SHORT' THEN 1 ELSE 0 END), 0) AS short_signal_count
          FROM (
            SELECT s.trend_signal, s.reason FROM htx_trend_signal s
            JOIN (
              SELECT contract_code, MAX(id) AS max_id FROM htx_trend_signal
              WHERE created_at >= %s GROUP BY contract_code
            ) current_signals ON current_signals.max_id = s.id
          ) latest""", (since,)) or {}
        return TrendBreadthStats(
            int(row.get("total_count") or 0),
            int(row.get("long_context_count") or 0),
            int(row.get("short_context_count") or 0),
            int(row.get("long_signal_count") or 0),
            int(row.get("short_signal_count") or 0),
        )

    def runtime_instance(self, instance_key: str) -> RuntimeInstance | None:
        row = self._one("""SELECT instance_key,strategy_family,strategy_name,account_id,account_label,execution_mode,
            enabled,deployment_unit,config_fingerprint FROM strategy_runtime_instance WHERE instance_key=%s""", (instance_key,))
        if row is None:
            return None
        return RuntimeInstance(row["instance_key"], row["strategy_family"], row["strategy_name"], row["account_id"],
                               row["account_label"], row["execution_mode"], bool(row["enabled"]), row["deployment_unit"],
                               row["config_fingerprint"])

    def mark_runtime_started(self, instance_key: str) -> None:
        self._write("""UPDATE strategy_runtime_instance SET started_at=CURRENT_TIMESTAMP(3),stopped_at=NULL,
          updated_at=CURRENT_TIMESTAMP(3) WHERE instance_key=%s""", (instance_key,))

    def mark_runtime_stopped(self, instance_key: str) -> None:
        self._write("UPDATE strategy_runtime_instance SET stopped_at=CURRENT_TIMESTAMP(3),updated_at=CURRENT_TIMESTAMP(3) WHERE instance_key=%s",
                    (instance_key,))

    def capital_allocation(self, *, account_id: int, strategy_family: str, strategy_name: str) -> CapitalAllocation | None:
        row = self._one("""SELECT account_id,account_label,strategy_family,strategy_name,max_notional_usdt,max_active_positions,
            max_daily_loss_usdt FROM strategy_capital_allocation WHERE account_id=%s AND strategy_family=%s
            AND strategy_name=%s AND enabled=1""", (account_id, strategy_family, strategy_name))
        if row is None:
            return None
        return CapitalAllocation(int(row["account_id"]), row["account_label"], row["strategy_family"], row["strategy_name"],
                                 Decimal(str(row["max_notional_usdt"])), int(row["max_active_positions"]),
                                 None if row["max_daily_loss_usdt"] is None else Decimal(str(row["max_daily_loss_usdt"])))

    def account_risk_limit(self, account_id: int) -> AccountRiskLimit | None:
        row = self._one("""SELECT account_id,account_label,max_notional_usdt,max_active_positions,max_daily_loss_usdt
            FROM strategy_account_risk_limit WHERE account_id=%s AND enabled=1""", (account_id,))
        if row is None:
            return None
        return AccountRiskLimit(int(row["account_id"]), row["account_label"], Decimal(str(row["max_notional_usdt"])),
                                int(row["max_active_positions"]), None if row["max_daily_loss_usdt"] is None
                                else Decimal(str(row["max_daily_loss_usdt"])))

    def active_risk_reservation_totals(self, account_id: int) -> RiskReservationTotals:
        row = self._one("""SELECT COALESCE(SUM(notional_usdt),0) AS notional_usdt,COALESCE(SUM(position_count),0) AS position_count
            FROM strategy_risk_reservation WHERE account_id=%s AND status='RESERVED'""", (account_id,))
        return RiskReservationTotals(Decimal(str(row["notional_usdt"])), int(row["position_count"])) if row else RiskReservationTotals(Decimal("0"), 0)

    def reserve_risk(self, *, reservation_key: str, instance: RuntimeInstance, position_key: str | None,
                     notional_usdt: Decimal, reason: str) -> None:
        """Atomically reserve an account-level budget before an exchange mutation.

        The account limit row is locked first, serializing reservations made by
        trend and arbitrage workers for the same account.  A missing global
        limit is a denial, never an implicit unlimited budget.
        """
        if instance.account_id is None or instance.account_label is None:
            raise ValueError("cannot reserve risk without an account-bound instance")
        try:
            with self._connection.cursor() as cursor:
                cursor.execute("SELECT max_notional_usdt,max_active_positions FROM strategy_account_risk_limit WHERE account_id=%s AND enabled=1 FOR UPDATE", (instance.account_id,))
                limit = cursor.fetchone()
                if limit is None:
                    raise ValueError("no enabled account risk limit")
                cursor.execute("SELECT reservation_key FROM strategy_risk_reservation WHERE reservation_key=%s AND status='RESERVED'", (reservation_key,))
                if cursor.fetchone() is not None:
                    self._connection.commit()
                    return
                cursor.execute("""SELECT COALESCE(SUM(notional_usdt),0) AS notional_usdt,COALESCE(SUM(position_count),0) AS position_count
                  FROM strategy_risk_reservation WHERE account_id=%s AND status='RESERVED'""", (instance.account_id,))
                used = cursor.fetchone() or {"notional_usdt": 0, "position_count": 0}
                if Decimal(str(used["notional_usdt"])) + notional_usdt > Decimal(str(limit["max_notional_usdt"])):
                    raise ValueError("account risk notional limit exceeded")
                if int(used["position_count"]) + 1 > int(limit["max_active_positions"]):
                    raise ValueError("account risk position limit exceeded")
                cursor.execute("""INSERT IGNORE INTO strategy_risk_reservation(reservation_key,account_id,account_label,strategy_family,
                  strategy_name,instance_key,position_key,notional_usdt,position_count,status,reason)
                  VALUES(%s,%s,%s,%s,%s,%s,%s,%s,1,'RESERVED',%s)""",
                  (reservation_key,instance.account_id,instance.account_label,instance.strategy_family,instance.strategy_name,
                   instance.instance_key,position_key,notional_usdt,_clip(reason,512)))
            self._connection.commit()
        except Exception:
            self._connection.rollback()
            raise

    def release_risk(self, reservation_key: str, reason: str) -> None:
        self._write("""UPDATE strategy_risk_reservation SET status='RELEASED',released_at=CURRENT_TIMESTAMP(3),reason=%s,
          updated_at=CURRENT_TIMESTAMP(3) WHERE reservation_key=%s AND status='RESERVED'""", (_clip(reason,512), reservation_key))

    def active_position_count(self, *, account_id: int) -> int:
        """Literal Java ``HtxTrendPositionMapper.countOpenLike`` scope.

        Java counts only primary, non-mirrored positions and includes UNKNOWN
        reconciliation rows.  A missing UNKNOWN here creates a false free slot
        after an exchange/ledger ambiguity.
        """
        row = self._one("""SELECT COUNT(*) AS count FROM htx_trend_position
            WHERE source_position_id IS NULL AND account_label='primary'
            AND status IN ('INTENT','ENTRY_SENT','ACTIVE','PARTIAL_CLOSE_SENT','CLOSE_SENT','UNKNOWN')""", ())
        return int(row["count"]) if row else 0

    def active_contract_position(self, *, account_id: int, contract_code: str) -> dict[str, Any] | None:
        """Account-level mutex: an exchange contract belongs to one profile until terminal."""
        return self._one("""SELECT id,contract_code,timeframe_profile,status FROM htx_trend_position
          WHERE account_id=%s AND contract_code=%s AND source_position_id IS NULL
          AND status IN ('INTENT','ENTRY_SENT','ACTIVE','PARTIAL_CLOSE_SENT','CLOSE_SENT','UNKNOWN')
          ORDER BY id LIMIT 1""", (account_id, contract_code))

    def latest_done_trade(self, *, account_id: int, contract_code: str,
                          timeframe_profile: str = "INTRADAY_15M") -> dict[str, Any] | None:
        """Profile-local cooldown; a 15m close cannot cool down a 4h lifecycle."""
        return self._one("""SELECT id,contract_code,updated_at,realized_profit,entry_fee,close_fee,message
          FROM htx_trend_position WHERE contract_code=%s AND source_position_id IS NULL
          AND account_id=%s AND timeframe_profile=%s AND status='DONE'
          ORDER BY updated_at DESC,id DESC LIMIT 1""", (contract_code, account_id, timeframe_profile))

    def claim_entry_cycle(self, *, structure_key: str, contract_code: str, timeframe: str,
                          direction: str, primary_model: str, breakout_bar_time: int | None,
                          source_run_id: str) -> bool:
        """Persist Java's atomic entry-cycle claim before the position intent.

        Only the DB unique key decides ownership, so restarts and concurrent
        workers cannot submit the same Fibonacci entry cycle twice.
        """
        try:
            with self._connection.cursor() as cursor:
                inserted = cursor.execute("""INSERT IGNORE INTO htx_trend_structure_lifecycle
                  (structure_key,contract_code,timeframe,direction,primary_model,breakout_bar_time,state,source_run_id)
                  VALUES(%s,%s,%s,%s,%s,%s,'DISCOVERED',%s)""",
                  (structure_key, contract_code, timeframe, direction, primary_model, breakout_bar_time, source_run_id))
                if inserted == 1:
                    self._connection.commit()
                    return True
                cursor.execute("SELECT state FROM htx_trend_structure_lifecycle WHERE structure_key=%s FOR UPDATE", (structure_key,))
                existing = cursor.fetchone()
                if existing is not None and existing["state"] == "ABANDONED":
                    reclaimed = cursor.execute("""UPDATE htx_trend_structure_lifecycle SET state='DISCOVERED',source_run_id=%s,
                      entry_position_id=NULL,terminal_reason=NULL,updated_at=CURRENT_TIMESTAMP(3) WHERE structure_key=%s AND state='ABANDONED'""",
                      (source_run_id, structure_key))
                    self._connection.commit()
                    return reclaimed == 1
            self._connection.commit()
            return False
        except Exception:
            self._connection.rollback()
            raise

    def attach_entry_cycle_intent(self, *, structure_key: str, source_run_id: str, position_id: int) -> None:
        self._write("""UPDATE htx_trend_structure_lifecycle SET state='INTENT',entry_position_id=%s,source_run_id=%s,
          updated_at=CURRENT_TIMESTAMP(3) WHERE structure_key=%s""", (position_id, source_run_id, structure_key))

    def abandon_entry_cycle(self, *, structure_key: str, reason: str) -> None:
        self._write("""UPDATE htx_trend_structure_lifecycle SET state='ABANDONED',terminal_reason=%s,
          updated_at=CURRENT_TIMESTAMP(3) WHERE structure_key=%s AND state IN ('DISCOVERED','INTENT')""",
          (_clip(reason, 512), structure_key))

    def mark_entry_cycle_active(self, *, position_id: int) -> None:
        self._write("""UPDATE htx_trend_structure_lifecycle SET state='ACTIVE',updated_at=CURRENT_TIMESTAMP(3)
          WHERE entry_position_id=%s AND state='INTENT'""", (position_id,))

    def mark_entry_cycle_terminal(self, *, position_id: int, reason: str) -> None:
        # Literal HtxTrendStructureLifecycleService.terminalState mapping.
        normalized = (reason or "").upper()
        state = ("INVALIDATED" if "MODEL_INVALIDATION" in normalized or "INITIAL_STOP" in normalized
                 else "PROFIT_PROTECTED" if "PROFIT" in normalized or "TRAILING" in normalized or "TARGET" in normalized
                 else "EXHAUSTED")
        self._write("""UPDATE htx_trend_structure_lifecycle SET state=%s,terminal_reason=%s,
          updated_at=CURRENT_TIMESTAMP(3) WHERE entry_position_id=%s AND state IN ('INTENT','ACTIVE')""",
          (state, _clip(reason, 512), position_id))

    def record_position_intent(self, intent: PositionIntent) -> int:
        values = ",".join(["%s"] * 4 + ["'INTENT'"] + ["%s"] * 29)
        self._write(f"""INSERT IGNORE INTO htx_trend_position(source_position_id,position_key,run_id,contract_code,status,side,
          contract_size,contract_volume,remaining_contract_volume,leverage,entry_signal,account_id,account_label,strategy_family,
          strategy_name,model_name,entry_gate_reason,primary_model,confirmation_models,policy_version,timeframe_profile,market_regime,
          factor_snapshot_id,entry_hypothesis,invalidation_price,target_zone_low,target_zone_high,expected_r_multiple,
          sizing_multiplier,sizing_reason,entry_fee_rate,estimated_entry_fee,estimated_close_fee,expected_net_after_fees)
          VALUES({values})""",
          (intent.source_position_id,intent.position_key,intent.run_id,intent.contract_code,intent.side,intent.contract_size,
           intent.contract_volume,0,intent.leverage,_clip(intent.entry_signal,512),intent.account.account_id,
           intent.account.account_label,intent.strategy_family,intent.strategy_name,intent.primary_model,
           _clip(intent.entry_gate_reason,1024),intent.primary_model,intent.confirmation_models,intent.policy_version,
           intent.timeframe_profile,intent.market_regime,intent.factor_snapshot_id,_clip(intent.entry_hypothesis,1024) if intent.entry_hypothesis else None,
           intent.invalidation_price,intent.target_zone_low,intent.target_zone_high,intent.expected_r_multiple,
           intent.sizing_multiplier,_clip(intent.sizing_reason,512) if intent.sizing_reason else None,intent.entry_fee_rate,
           intent.estimated_entry_fee,intent.estimated_close_fee,intent.expected_net_after_fees))
        row = self._one("SELECT id FROM htx_trend_position WHERE position_key=%s", (intent.position_key,))
        if row is None:
            raise RuntimeError("position intent was not persisted")
        return int(row["id"])

    def record_order_intent(self, intent: TrendOrderIntent) -> int:
        values = ",".join(["%s"] * 11 + ["'INTENT'"] + ["%s"] * 14)
        self._write(f"""INSERT IGNORE INTO htx_trend_order(position_id,position_key,run_id,contract_code,leg,trend_side,order_side,
          position_side,reduce_only,client_order_id,request_volume,status,reason,account_id,account_label,strategy_family,
          strategy_name,model_name,factor_snapshot_id,timeframe_profile,fee_rate_used,estimated_open_fee,estimated_close_fee,
          sizing_multiplier,sizing_reason,entry_gate_reason)
          VALUES({values})""",
          (intent.position_id,intent.position_key,intent.run_id,intent.contract_code,intent.leg,intent.trend_side,intent.order_side,
           intent.position_side,intent.reduce_only,intent.client_order_id,intent.request_volume,_clip(intent.reason,512),
           intent.account.account_id,intent.account.account_label,intent.strategy_family,intent.strategy_name,intent.primary_model,
           intent.factor_snapshot_id,intent.timeframe_profile,intent.fee_rate_used,intent.estimated_open_fee,intent.estimated_close_fee,
           intent.sizing_multiplier,_clip(intent.sizing_reason,512) if intent.sizing_reason else None,
           _clip(intent.entry_gate_reason,1024) if intent.entry_gate_reason else None))
        row = self._one("SELECT id FROM htx_trend_order WHERE client_order_id=%s", (intent.client_order_id,))
        if row is None:
            raise RuntimeError("order intent was not persisted")
        return int(row["id"])

    def mark_order_accepted(self, *, position_id: int, leg: str, client_order_id: str, exchange_order_id: int | None,
                            exchange_client_order_id: int | None, message: str) -> None:
        self._write("""UPDATE htx_trend_order SET status='ACCEPTED',exchange_order_id=%s,exchange_client_order_id=%s,message=%s,
          updated_at=CURRENT_TIMESTAMP(3) WHERE position_id=%s AND leg=%s AND client_order_id=%s""",
          (exchange_order_id,exchange_client_order_id,_clip(message,512),position_id,leg,client_order_id))

    def mark_order_rejected(self, *, position_id: int, leg: str, client_order_id: str, message: str) -> None:
        self._write("""UPDATE htx_trend_order SET status='REJECTED',message=%s,updated_at=CURRENT_TIMESTAMP(3)
          WHERE position_id=%s AND leg=%s AND client_order_id=%s""", (_clip(message,512),position_id,leg,client_order_id))

    def mark_order_unknown(self, *, position_id: int, leg: str, client_order_id: str, message: str) -> None:
        self._write("UPDATE htx_trend_order SET status='UNKNOWN',message=%s,updated_at=CURRENT_TIMESTAMP(3) "
                    "WHERE position_id=%s AND leg=%s AND client_order_id=%s",
                    (_clip(message,512),position_id,leg,client_order_id))

    def apply_exchange_order(self, **values: Any) -> None:
        from .order_accounting import apply_exchange_order
        apply_exchange_order(self, **values)

    def mark_position_status(self, *, position_id: int, status: str, message: str) -> None:
        self._write("UPDATE htx_trend_position SET status=%s,message=%s,updated_at=CURRENT_TIMESTAMP(3) WHERE id=%s",
                    (status,_clip(message,1024),position_id))

    def pending_orders(self, *, account_id: int, strategy_family: str = "TREND", strategy_name: str = "HTX_TREND_V1") -> list[dict[str, Any]]:
        """Recovery input only; no worker changes a row without exchange confirmation."""
        return self._all("""SELECT o.id,o.position_id,o.position_key,o.run_id,o.contract_code,o.leg,o.trend_side,o.order_side,
          o.position_side,o.reduce_only,o.client_order_id,o.exchange_order_id,o.request_volume,o.status,o.account_id,
          o.account_label,o.strategy_family,o.strategy_name,o.model_name,o.factor_snapshot_id,o.timeframe_profile
          ,p.contract_size FROM htx_trend_order o JOIN htx_trend_position p ON p.id=o.position_id
          WHERE o.account_id=%s AND o.strategy_family=%s AND o.strategy_name=%s
          AND o.status IN ('INTENT','ACCEPTED','PARTIALLY_FILLED','UNKNOWN')
          ORDER BY o.created_at""", (account_id,strategy_family,strategy_name))

    def mirror_position(self, *, source_position_id: int, account_id: int) -> dict[str, Any] | None:
        return self._one("""SELECT id,position_key,contract_code,side,remaining_contract_volume,contract_size,status
          FROM htx_trend_position WHERE source_position_id=%s AND account_id=%s
          AND status IN ('INTENT','ENTRY_SENT','ACTIVE','PARTIAL_CLOSE_SENT','CLOSE_SENT') ORDER BY id DESC LIMIT 1""",
                         (source_position_id,account_id))

    def reconcile_order(self, *, position_id: int, leg: str, client_order_id: str, status: str, exchange_order_id: int | None,
                        exchange_client_order_id: int | None, exchange_state: str | None, filled_contracts: Decimal,
                        average_price: Decimal | None, fee: Decimal, fee_currency: str | None, realized_profit: Decimal | None,
                        message: str) -> None:
        self._write("""UPDATE htx_trend_order SET status=%s,exchange_order_id=%s,exchange_client_order_id=%s,exchange_state=%s,
          filled_contracts=%s,average_price=%s,fee=%s,fee_currency=%s,realized_profit=%s,message=%s,updated_at=CURRENT_TIMESTAMP(3)
          WHERE position_id=%s AND leg=%s AND client_order_id=%s""",
          (status,exchange_order_id,exchange_client_order_id,exchange_state,filled_contracts,average_price,fee,fee_currency,
           realized_profit,_clip(message,512),position_id,leg,client_order_id))

    def record_ledger_event(self, event: LedgerEvent) -> None:
        self._write("""INSERT IGNORE INTO coin_strategy_ledger_event(event_key,event_type,account_id,account_label,exchange,market_type,
          strategy_family,strategy_name,model_name,run_id,factor_snapshot_id,symbol,contract_code,position_id,order_id,client_order_id,
          side,execution_price_usdt,entry_price_usdt,close_price_usdt,filled_contracts,contract_size,amount_usdt,fee_usdt,
          funding_fee_usdt,gross_pnl_usdt,net_pnl_after_fee,reason)
          VALUES(%s,%s,%s,%s,'HTX','USDT_SWAP',%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)""",
          (event.event_key,event.event_type,event.account.account_id,event.account.account_label,event.strategy_family,
           event.strategy_name,event.model_name,event.run_id,event.factor_snapshot_id,_symbol(event.contract_code),event.contract_code,
           event.position_id,event.order_id,event.client_order_id,event.side,event.execution_price_usdt,event.entry_price_usdt,
           event.close_price_usdt,event.filled_contracts,event.contract_size,event.amount_usdt,event.fee_usdt,event.funding_fee_usdt,
           event.gross_pnl_usdt,event.net_pnl_after_fee,_clip(event.reason,1024) if event.reason else None))

    def record_signal(self, *, contract_code: str, period: str, bar_time: int, signal: str, close_price: Decimal,
                      ema_fast: Decimal, ema_slow: Decimal, breakout_high: Decimal, breakout_low: Decimal, atr: Decimal,
                      atr_pct: Decimal, adx: Decimal, spread_rate: Decimal | None, funding_rate: Decimal | None, reason: str) -> None:
        self._write("""INSERT INTO htx_trend_signal(contract_code,period,bar_time,trend_signal,close_price,ema_fast,ema_slow,
            breakout_high,breakout_low,atr,atr_pct,adx,spread_rate,funding_rate,reason)
            VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)""",
            (contract_code,period,bar_time,signal,close_price,ema_fast,ema_slow,breakout_high,breakout_low,atr,atr_pct,adx,
             spread_rate,funding_rate,_clip(reason,512)))

    def record_factor_snapshot(self, *, snapshot_key: str, contract_code: str, period: str, bar_time: int,
                               market_regime: str, factors: dict[str, Any]) -> int:
        self._write("INSERT IGNORE INTO htx_trend_factor_snapshot(snapshot_key,contract_code,period,bar_time,market_regime,factors_json) VALUES(%s,%s,%s,%s,%s,%s)",
                    (snapshot_key,contract_code,period,bar_time,market_regime,json.dumps(factors,default=str,sort_keys=True)))
        row = self._one("SELECT id FROM htx_trend_factor_snapshot WHERE snapshot_key=%s", (snapshot_key,))
        if row is None: raise RuntimeError("factor snapshot was not persisted")
        return int(row["id"])

    def record_candidate(self, *, contract_code: str, period: str, bar_time: int, direction: str, regime: str,
                         score: Decimal, close_price: Decimal, factors: dict[str, Decimal], reason: str, model: str,
                         run_id: str, factor_snapshot_id: int | None, invalidation_price: Decimal, target_low: Decimal,
                         target_high: Decimal, expected_r: Decimal, shadow: bool = True,
                         confirmation_models: str | None = None, policy_version: str | None = None,
                         gate_result: str | None = None, gate_reason: str | None = None,
                         sizing_multiplier: Decimal | None = None, planned_notional_usdt: Decimal | None = None) -> None:
        self._write("""INSERT INTO htx_trend_candidate_signal(contract_code,period,bar_time,direction,regime,signal_score,close_price,
          ema_fast,ema_medium,ema_slow,atr,atr_pct,adx,momentum_pct,entry_reason,shadow,model_version,primary_model,confirmation_models,
          policy_version,run_id,factor_snapshot_id,invalidation_price,target_zone_low,target_zone_high,expected_r_multiple,gate_result,
          gate_reason,sizing_multiplier,planned_notional_usdt)
          VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
          ON DUPLICATE KEY UPDATE signal_score=VALUES(signal_score),entry_reason=VALUES(entry_reason),confirmation_models=VALUES(confirmation_models),
          policy_version=VALUES(policy_version),run_id=VALUES(run_id),factor_snapshot_id=VALUES(factor_snapshot_id),
          invalidation_price=VALUES(invalidation_price),target_zone_low=VALUES(target_zone_low),target_zone_high=VALUES(target_zone_high),
          expected_r_multiple=VALUES(expected_r_multiple),shadow=VALUES(shadow),gate_result=VALUES(gate_result),gate_reason=VALUES(gate_reason),
          sizing_multiplier=VALUES(sizing_multiplier),planned_notional_usdt=VALUES(planned_notional_usdt)""",
          (contract_code,period,bar_time,direction,regime,score,close_price,factors.get("ema_fast"),factors.get("ema_medium"),
           factors.get("ema_slow"),factors.get("atr"),factors.get("atr_pct"),factors.get("adx"),factors.get("roc"),
           _clip(reason,512),shadow,f"python-trend-v1:{model}",model,confirmation_models,policy_version,run_id,factor_snapshot_id,
           invalidation_price,target_low,target_high,expected_r,gate_result,_clip(gate_reason,1024) if gate_reason else None,
           sizing_multiplier,planned_notional_usdt))

    def mark_candidate_execution_gate(self, run_id: str, contract_code: str, primary_model: str, result: str, reason: str) -> None:
        self._write("""UPDATE htx_trend_candidate_signal SET execution_gate_result=%s,execution_gate_reason=%s,
          execution_evaluated_at=CURRENT_TIMESTAMP(3) WHERE run_id=%s AND contract_code=%s AND primary_model=%s""",
                    (result,_clip(reason,1024),run_id,contract_code,primary_model))

    def record_exit_decision(self, *, decision_key: str, position_id: int, run_id: str, account: TradeAccount,
                             contract_code: str, side: str, primary_model: str, engine_version: str, exit_phase: str,
                             suggested_action: str, executed_action: str, reference_price: Decimal, protection_price: Decimal | None,
                             current_net_pnl: Decimal, max_favorable_r: Decimal, target_remaining: int | None,
                             evidence: dict[str, Any], block_reason: str | None = None) -> None:
        self._write("""INSERT IGNORE INTO htx_trend_exit_decision(decision_key,position_id,run_id,account_id,account_label,contract_code,side,
          primary_model,engine_version,exit_phase,suggested_action,executed_action,execution_block_reason,reference_price,
          protection_price,current_net_pnl,max_favorable_r,target_remaining_contracts,evidence_json)
          VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)""",
          (decision_key,position_id,run_id,account.account_id,account.account_label,contract_code,side,primary_model,engine_version,
           exit_phase,suggested_action,executed_action,_clip(block_reason,512) if block_reason else None,reference_price,
           protection_price,current_net_pnl,max_favorable_r,target_remaining,json.dumps(evidence,default=str,sort_keys=True)))

    def managed_positions(self, instance: RuntimeInstance) -> list[dict[str, Any]]:
        return self._all("""SELECT * FROM htx_trend_position WHERE account_id=%s AND strategy_family=%s
          AND strategy_name=%s AND source_position_id IS NULL
          AND status IN ('ACTIVE','ENTRY_SENT','CLOSE_SENT','PARTIAL_CLOSE_SENT','UNKNOWN') ORDER BY id""",
          (instance.account_id,instance.strategy_family,instance.strategy_name))

    def exit_state(self, position_id: int) -> dict[str, Any]:
        row = self._one("SELECT state_json FROM python_trend_exit_state WHERE position_id=%s", (position_id,))
        return json.loads(row['state_json']) if row else {}

    def save_exit_state(self, position_id: int, state: dict[str, Any], decision) -> None:
        self._write("""INSERT INTO python_trend_exit_state(position_id,state_json) VALUES(%s,%s)
          ON DUPLICATE KEY UPDATE state_json=VALUES(state_json),updated_at=CURRENT_TIMESTAMP(3)""",
          (position_id,json.dumps(state,default=str,sort_keys=True)))
        self._write("""UPDATE htx_trend_position SET highest_price=%s,lowest_price=%s,profit_protection_price=%s,
          trailing_armed_at=%s,trailing_arm_reason=%s,max_favorable_r=%s,exit_phase=%s WHERE id=%s""",
          (state['highest'],state['lowest'],decision.protection_price,decision.trailing_armed_at,
           decision.trailing_arm_reason,decision.max_favorable_r,decision.phase.value,position_id))

    def record_entry_diagnosis(self, *, instance_key: str, run_id: str, contract_code: str,
                               period: str, bar_time: int, timeframe_profile: str | None = None,
                               signal_side: str | None = None, signal_score: Decimal | None = None,
                               score_gap: Decimal | None = None, regime: str | None = None,
                               market_regime_source: str = "UNCLASSIFIED", stage: str | None = None,
                               reject_code: str | None = None, raw_candidate_count: int = 0,
                               policy_selected_count: int = 0, flags: dict | None = None,
                               decision_hash: str | None = None, package_version: str | None = None) -> bool:
        """One row per instance/contract/bar; retain the first rejection on retries.

        Successful scans can later acquire a queue/EXEC rejection. A rejection
        already recorded is immutable, including across process restarts.
        Missing DDL (or any diagnostic write failure) degrades to a safe log.
        """
        compact_flags = dict(flags or {})
        encoded = json.dumps(compact_flags, default=str, sort_keys=True, separators=(",", ":"))
        # Keep valid JSON rather than slicing in the middle of a string/value.
        while len(encoded) > 512 and compact_flags:
            compact_flags.pop(next(reversed(compact_flags)))
            encoded = json.dumps({**compact_flags, "truncated": True}, default=str, sort_keys=True, separators=(",", ":"))
        columns = ("instance_key", "run_id", "contract_code", "period", "bar_time", "timeframe_profile",
                   "signal_side", "signal_score", "score_gap", "regime", "market_regime_source", "stage",
                   "raw_candidate_count", "policy_selected_count", "flags_json", "decision_hash", "package_version", "reject_code")
        params = (instance_key, run_id, contract_code, period, bar_time, timeframe_profile, signal_side,
                  signal_score, score_gap, regime, market_regime_source, stage, raw_candidate_count,
                  policy_selected_count, encoded, decision_hash, package_version, reject_code)
        # reject_code must be assigned last: MySQL evaluates assignments left to right.
        updates = ",".join(f"{name}=IF(reject_code IS NULL,VALUES({name}),{name})" for name in columns[5:])
        query = ("INSERT INTO htx_trend_entry_diagnosis (" + ",".join(columns) + ") VALUES ("
                 + ",".join(["%s"] * len(columns)) + ") ON DUPLICATE KEY UPDATE " + updates)
        try:
            self._write(query, params)
        except Exception as error:
            logging.getLogger(__name__).warning("entry diagnosis write unavailable: %s", type(error).__name__)
            return False
        return True

    def heartbeat(self, *, instance_key: str, status: str, detail: str, candidate_count: int, active_position_count: int) -> None:
        self._write("INSERT INTO strategy_runtime_heartbeat(instance_key,heartbeat_at,status,detail,candidate_count,active_position_count) VALUES(%s,CURRENT_TIMESTAMP(3),%s,%s,%s,%s)",
                    (instance_key,status,_clip(detail,1024),candidate_count,active_position_count))

    def _write(self, query: str, params: tuple[Any, ...]) -> None:
        with self._connection.cursor() as cursor: cursor.execute(query, params)
        if not self._in_transaction:
            self._connection.commit()
    def _one(self, query: str, params: tuple[Any, ...]) -> dict[str, Any] | None:
        with self._connection.cursor() as cursor: cursor.execute(query, params); return cursor.fetchone()
    def _all(self, query: str, params: tuple[Any, ...] = ()) -> list[dict[str, Any]]:
        with self._connection.cursor() as cursor: cursor.execute(query, params); return cursor.fetchall()

def _clip(value: str, maximum: int) -> str: return value[:maximum]
def _symbol(contract_code: str | None) -> str | None:
    return contract_code.replace("-", "").lower() if contract_code else None
def decision_key(position_id: int, run_id: str, action: str) -> str:
    return hashlib.sha256(f"python-exit-v1:{position_id}:{run_id}:{action}".encode()).hexdigest()[:160]
