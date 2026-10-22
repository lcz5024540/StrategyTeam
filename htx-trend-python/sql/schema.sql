-- Execute explicitly before a SQLite-backed Python paper/gray runtime is started.
-- The strategy runtime never applies this file automatically.
CREATE TABLE IF NOT EXISTS trend_account (
  account_id TEXT PRIMARY KEY, label TEXT NOT NULL, execution_role TEXT NOT NULL,
  created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS trend_position (
  position_id TEXT PRIMARY KEY, account_id TEXT NOT NULL, contract_code TEXT NOT NULL,
  side TEXT NOT NULL, quantity INTEGER NOT NULL, entry_price TEXT NOT NULL,
  realized_pnl TEXT NOT NULL DEFAULT '0', status TEXT NOT NULL, opened_at TEXT NOT NULL,
  closed_at TEXT, parent_position_id TEXT, strategy_run_id TEXT,
  FOREIGN KEY(account_id) REFERENCES trend_account(account_id)
);
CREATE TABLE IF NOT EXISTS trend_order (
  order_id TEXT PRIMARY KEY, account_id TEXT NOT NULL, position_id TEXT, contract_code TEXT NOT NULL,
  side TEXT NOT NULL, position_side TEXT NOT NULL, order_type TEXT NOT NULL, quantity INTEGER NOT NULL,
  limit_price TEXT, status TEXT NOT NULL, client_order_id TEXT NOT NULL UNIQUE,
  parent_order_id TEXT, strategy_run_id TEXT, created_at TEXT NOT NULL, filled_at TEXT,
  signal_px TEXT,
  signal_ts TEXT,
  order_px TEXT,
  order_px_source TEXT CHECK (order_px_source IN ('limit','mid','last')),
  order_ts TEXT,
  cancel_count INTEGER,
  amend_count INTEGER,
  FOREIGN KEY(account_id) REFERENCES trend_account(account_id)
);
CREATE TABLE IF NOT EXISTS trend_fill (
  fill_id TEXT PRIMARY KEY, order_id TEXT NOT NULL, account_id TEXT NOT NULL,
  quantity INTEGER NOT NULL, price TEXT NOT NULL, fee TEXT NOT NULL, filled_at TEXT NOT NULL,
  FOREIGN KEY(order_id) REFERENCES trend_order(order_id),
  FOREIGN KEY(account_id) REFERENCES trend_account(account_id)
);
CREATE TABLE IF NOT EXISTS trend_position_context (
  position_id TEXT PRIMARY KEY, context_json TEXT NOT NULL, updated_at TEXT NOT NULL,
  FOREIGN KEY(position_id) REFERENCES trend_position(position_id)
);
CREATE TABLE IF NOT EXISTS trend_decision (
  decision_id TEXT PRIMARY KEY, account_id TEXT NOT NULL, position_id TEXT,
  run_id TEXT NOT NULL, decision_kind TEXT NOT NULL, payload_json TEXT NOT NULL, recorded_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_trend_position_account_status ON trend_position(account_id, status);
CREATE INDEX IF NOT EXISTS idx_trend_order_account_created ON trend_order(account_id, created_at);
