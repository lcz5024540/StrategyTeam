-- HTX trend P0 MFE profit-protection observations.
-- Additive only. It does not enable exits, conditional orders, or any other trading behavior.

DROP PROCEDURE IF EXISTS add_htx_trend_profit_protection_column;

DELIMITER $$
CREATE PROCEDURE add_htx_trend_profit_protection_column(
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

CALL add_htx_trend_profit_protection_column('profit_protection_mfe_price', "DECIMAL(36,18) NULL COMMENT 'Best price used by MFE protection'", 'profit_lock_price');
CALL add_htx_trend_profit_protection_column('profit_protection_mfe_net_pnl', "DECIMAL(36,18) NULL COMMENT 'Estimated net PnL at MFE'", 'profit_protection_mfe_price');
CALL add_htx_trend_profit_protection_column('profit_protection_price', "DECIMAL(36,18) NULL COMMENT 'Monotonic MFE protection recommendation'", 'profit_protection_mfe_net_pnl');
CALL add_htx_trend_profit_protection_column('profit_protection_projected_net_pnl', "DECIMAL(36,18) NULL COMMENT 'Estimated net PnL if protection price fills'", 'profit_protection_price');
CALL add_htx_trend_profit_protection_column('profit_protection_capture_ratio', "DECIMAL(18,8) NULL COMMENT 'Projected locked net divided by MFE net'", 'profit_protection_projected_net_pnl');
CALL add_htx_trend_profit_protection_column('profit_protection_mode', "VARCHAR(32) NULL COMMENT 'COST_FLOOR or MFE_LOCK'", 'profit_protection_capture_ratio');
CALL add_htx_trend_profit_protection_column('profit_protection_updated_at', "DATETIME(3) NULL COMMENT 'Last MFE protection evaluation'", 'profit_protection_mode');

DROP PROCEDURE IF EXISTS add_htx_trend_profit_protection_column;

CREATE TABLE IF NOT EXISTS htx_trend_profit_protection_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_key VARCHAR(160) NOT NULL,
    position_id BIGINT NOT NULL,
    run_id VARCHAR(128) NULL,
    account_id BIGINT NULL,
    account_label VARCHAR(32) NOT NULL DEFAULT 'primary',
    contract_code VARCHAR(32) NOT NULL,
    side VARCHAR(8) NOT NULL,
    primary_model VARCHAR(64) NULL,
    event_type VARCHAR(32) NOT NULL,
    protection_mode VARCHAR(32) NULL,
    reference_price DECIMAL(36,18) NULL,
    mfe_price DECIMAL(36,18) NULL,
    mfe_net_pnl DECIMAL(36,18) NULL,
    protection_price DECIMAL(36,18) NULL,
    projected_locked_net_pnl DECIMAL(36,18) NULL,
    mfe_capture_ratio DECIMAL(18,8) NULL,
    execution_readiness VARCHAR(32) NOT NULL,
    reason VARCHAR(512) NULL,
    evidence_json TEXT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_htx_trend_profit_protection_event_key (event_key),
    KEY idx_htx_trend_profit_protection_position_time (position_id, created_at),
    KEY idx_htx_trend_profit_protection_account_time (account_label, created_at)
) COMMENT='Append-only MFE profit protection lifecycle events';

-- htx_trend_exit_decision is append-only, therefore columns are added idempotently without changing prior evidence.
DROP PROCEDURE IF EXISTS add_htx_trend_exit_decision_profit_column;
DELIMITER $$
CREATE PROCEDURE add_htx_trend_exit_decision_profit_column(
    IN p_column_name VARCHAR(64),
    IN p_column_definition TEXT,
    IN p_after_column VARCHAR(64)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'htx_trend_exit_decision'
          AND COLUMN_NAME = p_column_name
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE htx_trend_exit_decision ADD COLUMN ', p_column_name, ' ', p_column_definition,
                          ' AFTER ', p_after_column);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;

CALL add_htx_trend_exit_decision_profit_column('profit_protection_mfe_price', "DECIMAL(36,18) NULL", 'max_favorable_r');
CALL add_htx_trend_exit_decision_profit_column('projected_locked_net_pnl', "DECIMAL(36,18) NULL", 'profit_protection_mfe_price');
CALL add_htx_trend_exit_decision_profit_column('mfe_capture_ratio', "DECIMAL(18,8) NULL", 'projected_locked_net_pnl');
CALL add_htx_trend_exit_decision_profit_column('protection_mode', "VARCHAR(32) NULL", 'mfe_capture_ratio');
CALL add_htx_trend_exit_decision_profit_column('execution_readiness', "VARCHAR(32) NULL", 'protection_mode');

DROP PROCEDURE IF EXISTS add_htx_trend_exit_decision_profit_column;
