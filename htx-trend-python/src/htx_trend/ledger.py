"""Account-separated, append-only ledger for Python paper and gray execution.

SQLite schema initialization is explicit.  Constructing ``TrendLedger`` only
opens an existing database; it never executes DDL or migrations at service
startup.
"""
from __future__ import annotations

from .order_px_fields import validate_order_px

from dataclasses import dataclass
from datetime import UTC, datetime
from decimal import Decimal
from pathlib import Path
import json
import sqlite3
import uuid


@dataclass(frozen=True, slots=True)
class Account:
    account_id: str
    label: str
    execution_role: str  # PRIMARY, MIRROR, PAPER


@dataclass(frozen=True, slots=True)
class Position:
    position_id: str
    account_id: str
    contract_code: str
    side: str
    quantity: int
    entry_price: Decimal
    realized_pnl: Decimal
    status: str
    opened_at: datetime
    closed_at: datetime | None = None
    parent_position_id: str | None = None
    strategy_run_id: str | None = None


@dataclass(frozen=True, slots=True)
class Order:
    order_id: str
    account_id: str
    position_id: str | None
    contract_code: str
    side: str
    position_side: str
    order_type: str
    quantity: int
    client_order_id: str
    status: str
    created_at: datetime
    limit_price: Decimal | None = None
    parent_order_id: str | None = None
    strategy_run_id: str | None = None
    filled_at: datetime | None = None

    signal_px: Decimal | None = None
    signal_ts: datetime | None = None
    order_px: Decimal | None = None
    order_px_source: str | None = None
    order_ts: datetime | None = None
    cancel_count: int | None = None
    amend_count: int | None = None


@dataclass(frozen=True, slots=True)
class Fill:
    fill_id: str
    order_id: str
    account_id: str
    quantity: int
    price: Decimal
    fee: Decimal
    filled_at: datetime


class TrendLedger:
    def __init__(self, database_path: Path) -> None:
        self._path = database_path
        self._connection = sqlite3.connect(database_path)
        self._connection.row_factory = sqlite3.Row

    @staticmethod
    def initialize(database_path: Path, schema_path: Path) -> None:
        """Explicit operator action; never call from the runtime constructor."""
        connection = sqlite3.connect(database_path)
        try:
            connection.executescript(schema_path.read_text(encoding="utf-8"))
            connection.commit()
        finally:
            connection.close()

    def close(self) -> None:
        self._connection.close()

    def account(self, account: Account) -> None:
        self._connection.execute("INSERT OR IGNORE INTO trend_account(account_id,label,execution_role,created_at) VALUES(?,?,?,?)",
                                 (account.account_id, account.label, account.execution_role, _time(datetime.now(UTC))))
        self._connection.commit()

    def open_position(self, position: Position) -> None:
        self._connection.execute("INSERT INTO trend_position VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", (
            position.position_id, position.account_id, position.contract_code, position.side, position.quantity,
            str(position.entry_price), str(position.realized_pnl), position.status, _time(position.opened_at),
            _time(position.closed_at) if position.closed_at else None, position.parent_position_id, position.strategy_run_id))
        self._connection.commit()

    def record_order(self, order: Order) -> None:
        validate_order_px(order.order_px, order.order_px_source)
        self._connection.execute("""INSERT INTO trend_order (
            order_id,account_id,position_id,contract_code,side,position_side,order_type,quantity,
            limit_price,status,client_order_id,parent_order_id,strategy_run_id,created_at,filled_at,
            signal_px,signal_ts,order_px,order_px_source,order_ts,cancel_count,amend_count)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", (
            order.order_id, order.account_id, order.position_id, order.contract_code, order.side, order.position_side,
            order.order_type, order.quantity, str(order.limit_price) if order.limit_price else None, order.status,
            order.client_order_id, order.parent_order_id, order.strategy_run_id, _time(order.created_at),
            _time(order.filled_at) if order.filled_at else None,
            str(order.signal_px) if order.signal_px is not None else None,
            _time(order.signal_ts) if order.signal_ts else None,
            str(order.order_px) if order.order_px is not None else None, order.order_px_source,
            _time(order.order_ts) if order.order_ts else None, order.cancel_count, order.amend_count))
        self._connection.commit()

    def record_fill(self, fill: Fill, *, order_status: str = "FILLED") -> None:
        self._connection.execute("INSERT INTO trend_fill VALUES(?,?,?,?,?,?,?)", (fill.fill_id, fill.order_id,
                                 fill.account_id, fill.quantity, str(fill.price), str(fill.fee), _time(fill.filled_at)))
        self._connection.execute("UPDATE trend_order SET status=?,filled_at=? WHERE order_id=?",
                                 (order_status, _time(fill.filled_at), fill.order_id))
        self._connection.commit()

    def update_order_status(self, order_id: str, status: str, when: datetime | None = None) -> None:
        self._connection.execute("UPDATE trend_order SET status=?,filled_at=? WHERE order_id=?",
                                 (status, _time(when) if when else None, order_id))
        self._connection.commit()

    def close_position(self, position_id: str, realized_pnl: Decimal, when: datetime) -> None:
        self._connection.execute("UPDATE trend_position SET status='CLOSED',realized_pnl=?,closed_at=? WHERE position_id=?",
                                 (str(realized_pnl), _time(when), position_id))
        self._connection.commit()

    def reduce_position(self, position_id: str, remaining_quantity: int, realized_pnl: Decimal, when: datetime) -> None:
        if remaining_quantity < 0:
            raise ValueError("remaining quantity cannot be negative")
        if remaining_quantity == 0:
            self.close_position(position_id, realized_pnl, when)
            return
        self._connection.execute("UPDATE trend_position SET quantity=?,realized_pnl=? WHERE position_id=? AND status='OPEN'",
                                 (remaining_quantity, str(realized_pnl), position_id))
        self._connection.commit()

    def save_position_context(self, position_id: str, context: dict) -> None:
        self._connection.execute("INSERT INTO trend_position_context(position_id,context_json,updated_at) VALUES(?,?,?) "
                                 "ON CONFLICT(position_id) DO UPDATE SET context_json=excluded.context_json,updated_at=excluded.updated_at",
                                 (position_id, json.dumps(context, default=str, sort_keys=True), _time(datetime.now(UTC))))
        self._connection.commit()

    def position_context(self, position_id: str) -> dict:
        row = self._connection.execute("SELECT context_json FROM trend_position_context WHERE position_id=?", (position_id,)).fetchone()
        if row is None: raise KeyError(f"missing position context {position_id}")
        return json.loads(row["context_json"])

    def position(self, position_id: str) -> Position:
        row = self._connection.execute("SELECT * FROM trend_position WHERE position_id=?", (position_id,)).fetchone()
        if row is None: raise KeyError(f"missing position {position_id}")
        return _position(row)

    def mirrored_positions(self, parent_position_id: str) -> list[Position]:
        rows = self._connection.execute("SELECT * FROM trend_position WHERE parent_position_id=? AND status='OPEN'", (parent_position_id,)).fetchall()
        return [_position(row) for row in rows]

    def decision(self, account_id: str, run_id: str, decision_kind: str, payload: dict, *, position_id: str | None = None) -> None:
        self._connection.execute("INSERT INTO trend_decision VALUES(?,?,?,?,?,?,?)", (str(uuid.uuid4()), account_id,
                                 position_id, run_id, decision_kind, json.dumps(payload, default=str, sort_keys=True), _time(datetime.now(UTC))))
        self._connection.commit()

    def active_positions(self, account_id: str) -> list[Position]:
        rows = self._connection.execute("SELECT * FROM trend_position WHERE account_id=? AND status='OPEN'", (account_id,)).fetchall()
        return [_position(row) for row in rows]


def _time(value: datetime) -> str: return value.astimezone(UTC).isoformat()
def _position(row: sqlite3.Row) -> Position:
    return Position(row["position_id"], row["account_id"], row["contract_code"], row["side"], row["quantity"],
                    Decimal(row["entry_price"]), Decimal(row["realized_pnl"]), row["status"], datetime.fromisoformat(row["opened_at"]),
                    datetime.fromisoformat(row["closed_at"]) if row["closed_at"] else None, row["parent_position_id"], row["strategy_run_id"])
