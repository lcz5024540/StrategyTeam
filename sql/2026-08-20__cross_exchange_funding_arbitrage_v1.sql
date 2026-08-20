CREATE TABLE IF NOT EXISTS cross_exchange_arb_opportunity (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  opportunity_code VARCHAR(64) NOT NULL UNIQUE,
  symbol VARCHAR(32) NOT NULL,
  long_exchange VARCHAR(16) NOT NULL,
  short_exchange VARCHAR(16) NOT NULL,
  long_funding_rate DECIMAL(28,16) NOT NULL,
  short_funding_rate DECIMAL(28,16) NOT NULL,
  hourly_funding_spread DECIMAL(28,16) NOT NULL,
  estimated_net_yield DECIMAL(28,16) NOT NULL,
  executable_notional DECIMAL(28,8) NOT NULL,
  status VARCHAR(24) NOT NULL,
  rejection_reason VARCHAR(128),
  discovered_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  INDEX idx_ceao_symbol_status (symbol, status),
  INDEX idx_ceao_discovered_at (discovered_at)
);

CREATE TABLE IF NOT EXISTS cross_exchange_arb_position (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  position_code VARCHAR(64) NOT NULL UNIQUE,
  opportunity_code VARCHAR(64) NOT NULL,
  symbol VARCHAR(32) NOT NULL,
  long_exchange VARCHAR(16) NOT NULL,
  short_exchange VARCHAR(16) NOT NULL,
  hedged_quantity DECIMAL(28,12) NOT NULL,
  status VARCHAR(32) NOT NULL,
  opened_at DATETIME(3),
  closed_at DATETIME(3),
  message VARCHAR(512),
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  INDEX idx_ceap_symbol_status (symbol, status)
);

CREATE TABLE IF NOT EXISTS cross_exchange_arb_order (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  position_code VARCHAR(64) NOT NULL,
  client_order_id VARCHAR(96) NOT NULL UNIQUE,
  exchange_name VARCHAR(16) NOT NULL,
  symbol VARCHAR(32) NOT NULL,
  leg_side VARCHAR(8) NOT NULL,
  reduce_only TINYINT(1) NOT NULL DEFAULT 0,
  requested_quantity DECIMAL(28,12) NOT NULL,
  filled_quantity DECIMAL(28,12) NOT NULL DEFAULT 0,
  average_price DECIMAL(28,12),
  status VARCHAR(32) NOT NULL,
  message VARCHAR(512),
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  INDEX idx_ceaord_position (position_code)
);

CREATE TABLE IF NOT EXISTS cross_exchange_funding_settlement (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  position_code VARCHAR(64) NOT NULL,
  exchange_name VARCHAR(16) NOT NULL,
  symbol VARCHAR(32) NOT NULL,
  funding_rate DECIMAL(28,16) NOT NULL,
  expected_amount DECIMAL(28,12),
  actual_amount DECIMAL(28,12),
  settlement_time DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_ceafs_position_exchange_time (position_code, exchange_name, settlement_time)
);

CREATE TABLE IF NOT EXISTS cross_exchange_risk_snapshot (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  position_code VARCHAR(64),
  opportunity_code VARCHAR(64),
  check_stage VARCHAR(32) NOT NULL,
  passed TINYINT(1) NOT NULL,
  reason VARCHAR(256),
  naked_notional DECIMAL(28,8),
  mark_price_deviation DECIMAL(28,16),
  quote_age_ms BIGINT,
  details_json TEXT,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX idx_cers_position_created (position_code, created_at)
);
