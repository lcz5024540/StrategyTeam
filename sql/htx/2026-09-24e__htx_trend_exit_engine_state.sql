-- HTX trend exit-engine v2 state and immutable decision ledger.
-- This migration is additive and does not activate any trading behavior.

DROP PROCEDURE IF EXISTS add_htx_trend_exit_engine_column;

DELIMITER $$
CREATE PROCEDURE add_htx_trend_exit_engine_column(
    IN p_column_name VARCHAR(64),
    IN p_column_definition TEXT,
    IN p_after_column VARCHAR(64)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'htx_trend_position'
          AND COLUMN_NAME = p_column_name
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE htx_trend_position ADD COLUMN ', p_column_name, ' ', p_column_definition,
                          ' AFTER ', p_after_column);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;

CALL add_htx_trend_exit_engine_column('exit_phase', "VARCHAR(32) NULL COMMENT 'Exit-engine phase'", 'exit_snapshot_updated_at');
CALL add_htx_trend_exit_engine_column('trailing_armed_at', "DATETIME(3) NULL COMMENT 'First durable trailing-arm time'", 'exit_phase');
CALL add_htx_trend_exit_engine_column('trailing_arm_reason', "VARCHAR(512) NULL COMMENT 'Evidence that armed trailing'", 'trailing_armed_at');
CALL add_htx_trend_exit_engine_column('max_favorable_r', "DECIMAL(36,18) NULL COMMENT 'Maximum favorable excursion in initial-risk multiples'", 'trailing_arm_reason');
CALL add_htx_trend_exit_engine_column('profit_lock_price', "DECIMAL(36,18) NULL COMMENT 'Latest exit-engine protective price'", 'max_favorable_r');
CALL add_htx_trend_exit_engine_column('exit_engine_version', "VARCHAR(64) NULL COMMENT 'Exit engine semantics used for the latest decision'", 'profit_lock_price');

DROP PROCEDURE IF EXISTS add_htx_trend_exit_engine_column;

CREATE TABLE IF NOT EXISTS htx_trend_exit_decision (
    id BIGINT NOT NULL AUTO_INCREMENT,
    decision_key VARCHAR(160) NOT NULL,
    position_id BIGINT NOT NULL,
    run_id VARCHAR(128) NULL,
    account_id BIGINT NULL,
    account_label VARCHAR(32) NOT NULL DEFAULT 'primary',
    contract_code VARCHAR(32) NOT NULL,
    side VARCHAR(8) NOT NULL,
    primary_model VARCHAR(64) NULL,
    engine_version VARCHAR(64) NOT NULL,
    exit_phase VARCHAR(32) NOT NULL,
    suggested_action VARCHAR(32) NOT NULL,
    executed_action VARCHAR(32) NOT NULL,
    execution_block_reason VARCHAR(512) NULL,
    reference_price DECIMAL(36,18) NULL,
    protection_price DECIMAL(36,18) NULL,
    expected_target_price DECIMAL(36,18) NULL,
    current_net_pnl DECIMAL(36,18) NULL,
    mfe_net_pnl DECIMAL(36,18) NULL,
    mae_net_pnl DECIMAL(36,18) NULL,
    giveback_rate DECIMAL(18,8) NULL,
    max_favorable_r DECIMAL(36,18) NULL,
    target_remaining_contracts BIGINT NULL,
    evidence_json TEXT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_htx_trend_exit_decision_key (decision_key),
    KEY idx_htx_trend_exit_decision_position_time (position_id, created_at),
    KEY idx_htx_trend_exit_decision_account_time (account_label, created_at)
) COMMENT='Append-only HTX trend exit-engine decisions';
