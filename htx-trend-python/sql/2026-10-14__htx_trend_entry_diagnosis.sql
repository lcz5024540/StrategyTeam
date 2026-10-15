-- R0 observational ledger only. Apply separately before deploying the worker.
-- bar_time is the completed candle OPEN epoch (seconds), matching candidate keys.
-- SCAN failures use the expected completed candle epoch when evidence is unavailable.
-- NULL timeframe_profile means no profile was supplied; R0 does not infer R1 profiles.
CREATE TABLE IF NOT EXISTS htx_trend_entry_diagnosis (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    instance_key VARCHAR(128) NOT NULL,
    run_id VARCHAR(255) NOT NULL,
    contract_code VARCHAR(32) NOT NULL,
    period VARCHAR(16) NOT NULL,
    timeframe_profile VARCHAR(32) NULL,
    bar_time BIGINT NOT NULL,
    signal_side VARCHAR(16) NULL,
    signal_score DECIMAL(20,8) NULL,
    score_gap DECIMAL(20,8) NULL,
    regime VARCHAR(32) NULL,
    market_regime_source VARCHAR(32) NOT NULL,
    stage VARCHAR(32) NULL,
    reject_code VARCHAR(128) NULL,
    raw_candidate_count INT NOT NULL DEFAULT 0,
    policy_selected_count INT NOT NULL DEFAULT 0,
    flags_json VARCHAR(512) NOT NULL DEFAULT '{}',
    decision_hash VARCHAR(64) NULL,
    package_version VARCHAR(128) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_entry_diagnosis_bar (instance_key, contract_code, period, bar_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
