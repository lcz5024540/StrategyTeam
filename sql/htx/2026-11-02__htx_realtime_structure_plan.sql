-- Explicit operator migration. Never executed by worker startup.
CREATE TABLE IF NOT EXISTS htx_trend_realtime_plan (
  instance_key VARCHAR(96) NOT NULL,
  plan_id CHAR(64) NOT NULL,
  state_json JSON NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (instance_key, plan_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
