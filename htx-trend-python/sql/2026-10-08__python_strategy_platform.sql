-- Explicit production migration. Do not execute from a Python worker startup.
CREATE TABLE IF NOT EXISTS strategy_runtime_instance (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  instance_key VARCHAR(128) NOT NULL UNIQUE,
  strategy_family VARCHAR(32) NOT NULL,
  strategy_name VARCHAR(64) NOT NULL,
  account_id BIGINT NULL,
  account_label VARCHAR(32) NULL,
  execution_mode VARCHAR(16) NOT NULL,
  enabled TINYINT(1) NOT NULL DEFAULT 1,
  deployment_unit VARCHAR(128) NOT NULL,
  config_fingerprint VARCHAR(128) NULL,
  started_at DATETIME(3) NULL,
  stopped_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  INDEX idx_strategy_runtime_family (strategy_family, strategy_name, enabled, updated_at),
  INDEX idx_strategy_runtime_account (account_id, account_label, enabled)
);
CREATE TABLE IF NOT EXISTS strategy_runtime_heartbeat (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  instance_key VARCHAR(128) NOT NULL,
  heartbeat_at DATETIME(3) NOT NULL,
  status VARCHAR(32) NOT NULL,
  detail VARCHAR(1024) NULL,
  candidate_count BIGINT NOT NULL DEFAULT 0,
  active_position_count BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX idx_strategy_heartbeat_instance_time (instance_key, heartbeat_at)
);
CREATE TABLE IF NOT EXISTS strategy_capital_allocation (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  account_id BIGINT NOT NULL,
  account_label VARCHAR(32) NOT NULL,
  strategy_family VARCHAR(32) NOT NULL,
  strategy_name VARCHAR(64) NOT NULL,
  enabled TINYINT(1) NOT NULL DEFAULT 1,
  max_notional_usdt DECIMAL(36,18) NOT NULL,
  max_active_positions INT NOT NULL,
  max_daily_loss_usdt DECIMAL(36,18) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_strategy_capital_account_strategy (account_id, strategy_family, strategy_name),
  INDEX idx_strategy_capital_enabled (account_id, account_label, enabled)
);

-- Account-wide hard boundary shared by every Python strategy family.  A
-- strategy allocation limits one worker; this table limits their aggregate.
CREATE TABLE IF NOT EXISTS strategy_account_risk_limit (
  account_id BIGINT PRIMARY KEY,
  account_label VARCHAR(32) NOT NULL,
  enabled TINYINT(1) NOT NULL DEFAULT 1,
  max_notional_usdt DECIMAL(36,18) NOT NULL,
  max_active_positions INT NOT NULL,
  max_daily_loss_usdt DECIMAL(36,18) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);
-- A live executor reserves capacity before any exchange order is sent, then
-- releases it only after terminal order/position reconciliation.
CREATE TABLE IF NOT EXISTS strategy_risk_reservation (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  reservation_key VARCHAR(160) NOT NULL UNIQUE,
  account_id BIGINT NOT NULL,
  account_label VARCHAR(32) NOT NULL,
  strategy_family VARCHAR(32) NOT NULL,
  strategy_name VARCHAR(64) NOT NULL,
  instance_key VARCHAR(128) NOT NULL,
  position_key VARCHAR(128) NULL,
  notional_usdt DECIMAL(36,18) NOT NULL,
  position_count INT NOT NULL DEFAULT 1,
  status VARCHAR(16) NOT NULL,
  reason VARCHAR(512) NULL,
  reserved_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  released_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  INDEX idx_strategy_reservation_account_status (account_id, status, updated_at),
  INDEX idx_strategy_reservation_instance_status (instance_key, status, updated_at)
);
