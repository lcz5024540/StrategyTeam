-- HTX trend combination strategy: execution evidence, dashboard policy and model attribution.
-- Run against MySQL 8 before deploying the corresponding HTX/Web services.

CREATE TABLE IF NOT EXISTS htx_trend_factor_snapshot (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    snapshot_key VARCHAR(160) NOT NULL,
    contract_code VARCHAR(32) NOT NULL,
    period VARCHAR(16) NOT NULL,
    bar_time BIGINT NOT NULL,
    market_regime VARCHAR(32) NOT NULL,
    factors_json TEXT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_trend_factor_snapshot_key (snapshot_key),
    INDEX idx_trend_factor_contract_time (contract_code, period, bar_time)
);

CREATE TABLE IF NOT EXISTS htx_trend_strategy_policy (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    policy_code VARCHAR(96) NOT NULL,
    policy_version VARCHAR(64) NOT NULL,
    primary_model VARCHAR(64) NOT NULL,
    confirmation_models VARCHAR(256) NOT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    base_notional_usdt DECIMAL(36,18) NOT NULL,
    max_notional_usdt DECIMAL(36,18) NOT NULL,
    score_threshold DECIMAL(36,18) NOT NULL,
    box_window_bars INT NOT NULL DEFAULT 32,
    updated_by VARCHAR(64) NOT NULL DEFAULT 'system',
    change_note VARCHAR(512),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_trend_policy_code_version (policy_code, policy_version),
    INDEX idx_trend_policy_active (enabled, policy_code, updated_at)
);

CREATE TABLE IF NOT EXISTS htx_trend_strategy_policy_audit (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    policy_id BIGINT NOT NULL,
    policy_code VARCHAR(96) NOT NULL,
    policy_version VARCHAR(64) NOT NULL,
    action VARCHAR(32) NOT NULL,
    enabled TINYINT(1) NOT NULL,
    changed_by VARCHAR(64) NOT NULL,
    change_note VARCHAR(512),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_trend_policy_audit_policy_time (policy_id, created_at)
);

ALTER TABLE htx_trend_candidate_signal
    ADD COLUMN IF NOT EXISTS primary_model VARCHAR(64),
    ADD COLUMN IF NOT EXISTS confirmation_models VARCHAR(256),
    ADD COLUMN IF NOT EXISTS policy_version VARCHAR(64),
    ADD COLUMN IF NOT EXISTS factor_snapshot_id BIGINT,
    ADD COLUMN IF NOT EXISTS gate_result VARCHAR(16),
    ADD COLUMN IF NOT EXISTS gate_reason VARCHAR(1024),
    ADD COLUMN IF NOT EXISTS invalidation_price DECIMAL(36,18),
    ADD COLUMN IF NOT EXISTS entry_hypothesis VARCHAR(1024),
    ADD COLUMN IF NOT EXISTS sizing_multiplier DECIMAL(18,8),
    ADD COLUMN IF NOT EXISTS planned_notional_usdt DECIMAL(36,18);

ALTER TABLE htx_trend_position
    ADD COLUMN IF NOT EXISTS primary_model VARCHAR(64),
    ADD COLUMN IF NOT EXISTS confirmation_models VARCHAR(256),
    ADD COLUMN IF NOT EXISTS policy_version VARCHAR(64),
    ADD COLUMN IF NOT EXISTS market_regime VARCHAR(32),
    ADD COLUMN IF NOT EXISTS factor_snapshot_id BIGINT,
    ADD COLUMN IF NOT EXISTS entry_hypothesis VARCHAR(1024),
    ADD COLUMN IF NOT EXISTS invalidation_price DECIMAL(36,18);

INSERT INTO htx_trend_strategy_policy (
    policy_code, policy_version, primary_model, confirmation_models, enabled,
    base_notional_usdt, max_notional_usdt, score_threshold, box_window_bars, updated_by, change_note
) SELECT 'BOX_DONCHIAN', 'combo-v1', 'BOX_BREAKOUT', 'DONCHIAN_BREAKOUT', 1,
         190, 285, 85, 32, 'system', 'initial combined live policy'
WHERE NOT EXISTS (SELECT 1 FROM htx_trend_strategy_policy WHERE policy_code = 'BOX_DONCHIAN' AND policy_version = 'combo-v1');

INSERT INTO htx_trend_strategy_policy (
    policy_code, policy_version, primary_model, confirmation_models, enabled,
    base_notional_usdt, max_notional_usdt, score_threshold, box_window_bars, updated_by, change_note
) SELECT 'BOX_FIB', 'combo-v1', 'BOX_BREAKOUT', 'FIB_PULLBACK_CONTINUATION', 1,
         190, 285, 85, 32, 'system', 'initial combined live policy'
WHERE NOT EXISTS (SELECT 1 FROM htx_trend_strategy_policy WHERE policy_code = 'BOX_FIB' AND policy_version = 'combo-v1');

INSERT INTO htx_trend_strategy_policy (
    policy_code, policy_version, primary_model, confirmation_models, enabled,
    base_notional_usdt, max_notional_usdt, score_threshold, box_window_bars, updated_by, change_note
) SELECT 'DONCHIAN_FIB', 'combo-v1', 'DONCHIAN_BREAKOUT', 'FIB_PULLBACK_CONTINUATION', 1,
         190, 285, 85, 32, 'system', 'initial combined live policy'
WHERE NOT EXISTS (SELECT 1 FROM htx_trend_strategy_policy WHERE policy_code = 'DONCHIAN_FIB' AND policy_version = 'combo-v1');

INSERT INTO htx_trend_strategy_policy (
    policy_code, policy_version, primary_model, confirmation_models, enabled,
    base_notional_usdt, max_notional_usdt, score_threshold, box_window_bars, updated_by, change_note
) SELECT 'BOX_DONCHIAN_FIB', 'combo-v1', 'BOX_BREAKOUT', 'DONCHIAN_BREAKOUT,FIB_PULLBACK_CONTINUATION', 1,
         190, 285, 90, 32, 'system', 'initial combined live policy'
WHERE NOT EXISTS (SELECT 1 FROM htx_trend_strategy_policy WHERE policy_code = 'BOX_DONCHIAN_FIB' AND policy_version = 'combo-v1');
