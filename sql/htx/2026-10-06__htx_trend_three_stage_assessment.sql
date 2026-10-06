CREATE TABLE IF NOT EXISTS htx_trend_assessment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, assessment_key VARCHAR(192) NOT NULL UNIQUE,
    assessment_stage VARCHAR(32) NOT NULL, position_id BIGINT, run_id VARCHAR(128), account_label VARCHAR(64) NOT NULL DEFAULT 'primary', contract_code VARCHAR(32) NOT NULL,
    side VARCHAR(8), primary_model VARCHAR(64), timeframe_profile VARCHAR(32), bar_time BIGINT,
    trend_state VARCHAR(32) NOT NULL, previous_trend_state VARCHAR(32), transition_type VARCHAR(64),
    transition_reason VARCHAR(512), reference_price DECIMAL(36,18), expected_r_multiple DECIMAL(36,18),
    current_net_pnl DECIMAL(36,18), mfe_net_pnl DECIMAL(36,18), mae_net_pnl DECIMAL(36,18),
    protection_price DECIMAL(36,18), evidence_json CLOB,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_trend_assessment_position_time (position_id, bar_time),
    INDEX idx_trend_assessment_account_time (account_label, created_at),
    INDEX idx_trend_assessment_stage_time (assessment_stage, created_at)
);
