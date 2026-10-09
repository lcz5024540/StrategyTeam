-- Explicit deployment migration. Never executed by worker startup.
CREATE TABLE IF NOT EXISTS python_trend_exit_state (
    position_id BIGINT PRIMARY KEY,
    state_json LONGTEXT NOT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);
