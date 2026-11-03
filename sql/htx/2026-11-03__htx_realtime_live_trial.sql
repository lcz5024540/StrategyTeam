-- Explicit deployment only. Worker startup checks this table without running DDL.
CREATE TABLE IF NOT EXISTS htx_trend_live_trial (
  trial_id VARCHAR(128) NOT NULL,
  account_id BIGINT NOT NULL,
  instance_key VARCHAR(128) NOT NULL,
  revision BIGINT NOT NULL,
  state_json JSON NOT NULL,
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (trial_id),
  KEY idx_htx_trial_owner (account_id, instance_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
