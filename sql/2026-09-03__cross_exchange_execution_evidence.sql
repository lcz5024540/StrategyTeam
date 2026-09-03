ALTER TABLE cross_exchange_arb_order
  ADD COLUMN best_price DECIMAL(28,12) NULL AFTER fee_amount,
  ADD COLUMN worst_price DECIMAL(28,12) NULL AFTER best_price,
  ADD COLUMN impact_bps DECIMAL(20,8) NULL AFTER worst_price,
  ADD COLUMN slippage_amount DECIMAL(28,12) NOT NULL DEFAULT 0 AFTER impact_bps,
  ADD COLUMN levels_consumed INT NOT NULL DEFAULT 0 AFTER slippage_amount,
  ADD COLUMN book_fingerprint VARCHAR(64) NULL AFTER levels_consumed,
  ADD COLUMN book_received_at DATETIME(3) NULL AFTER book_fingerprint;

CREATE TABLE IF NOT EXISTS cross_exchange_execution_fill (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  position_code VARCHAR(64) NOT NULL,
  client_order_id VARCHAR(96) NOT NULL,
  level_no INT NOT NULL,
  exchange_name VARCHAR(16) NOT NULL,
  symbol VARCHAR(32) NOT NULL,
  side VARCHAR(8) NOT NULL,
  price DECIMAL(28,12) NOT NULL,
  filled_quantity DECIMAL(28,12) NOT NULL,
  filled_notional DECIMAL(28,12) NOT NULL,
  book_received_at DATETIME(3) NOT NULL,
  book_fingerprint VARCHAR(64) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_ceef_order_level (client_order_id, level_no),
  INDEX idx_ceef_position_created (position_code, created_at)
);
