SET @has_settlement_source := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'cross_exchange_funding_settlement'
    AND COLUMN_NAME = 'settlement_source'
);
SET @ddl := IF(@has_settlement_source = 0,
  'ALTER TABLE cross_exchange_funding_settlement ADD COLUMN settlement_source VARCHAR(32) NOT NULL DEFAULT ''PAPER_PUBLIC_ESTIMATE'' AFTER actual_amount',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_observed_at := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'cross_exchange_funding_settlement'
    AND COLUMN_NAME = 'observed_at'
);
SET @ddl := IF(@has_observed_at = 0,
  'ALTER TABLE cross_exchange_funding_settlement ADD COLUMN observed_at DATETIME(3) NULL AFTER settlement_source',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS cross_exchange_strategy_lease (
  lease_key VARCHAR(128) NOT NULL,
  owner_id VARCHAR(96) NOT NULL,
  expires_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (lease_key),
  KEY idx_cesl_expires_at (expires_at)
);
