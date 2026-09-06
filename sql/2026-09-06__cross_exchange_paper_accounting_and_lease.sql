ALTER TABLE cross_exchange_funding_settlement
  ADD COLUMN IF NOT EXISTS settlement_source VARCHAR(32) NOT NULL DEFAULT 'PAPER_PUBLIC_ESTIMATE' AFTER actual_amount,
  ADD COLUMN IF NOT EXISTS observed_at DATETIME(3) NULL AFTER settlement_source;

CREATE TABLE IF NOT EXISTS cross_exchange_strategy_lease (
  lease_key VARCHAR(128) NOT NULL,
  owner_id VARCHAR(96) NOT NULL,
  expires_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (lease_key),
  KEY idx_cesl_expires_at (expires_at)
);
