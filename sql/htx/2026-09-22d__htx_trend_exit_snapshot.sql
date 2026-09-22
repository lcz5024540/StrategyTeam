-- HTX trend exit diagnostics snapshot.
-- This migration is intentionally additive: it structures the existing exitSnapshot message fields
-- without changing trading behavior or existing close/stop rules.
-- Compatible with MySQL versions that do not support ADD COLUMN IF NOT EXISTS.

DROP PROCEDURE IF EXISTS add_htx_trend_exit_snapshot_column;

DELIMITER $$
CREATE PROCEDURE add_htx_trend_exit_snapshot_column(
    IN p_column_name VARCHAR(64),
    IN p_column_definition TEXT,
    IN p_after_column VARCHAR(64)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'htx_trend_position'
          AND COLUMN_NAME = p_column_name
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE htx_trend_position ADD COLUMN ', p_column_name, ' ', p_column_definition, ' AFTER ', p_after_column);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;

CALL add_htx_trend_exit_snapshot_column('exit_snapshot_state', "VARCHAR(32) NULL COMMENT 'Latest exit diagnostics state: LOSS_ZONE/FEE_BREAKEVEN_ZONE/RUNNER_ZONE/EXHAUSTION_ZONE/PROFIT_ZONE/UNKNOWN'", 'momentum_quality_score');
CALL add_htx_trend_exit_snapshot_column('exit_snapshot_net', "DECIMAL(36,18) NULL COMMENT 'Estimated close net PnL after fees at latest mark/reference price'", 'exit_snapshot_state');
CALL add_htx_trend_exit_snapshot_column('exit_snapshot_mfe', "DECIMAL(36,18) NULL COMMENT 'Estimated maximum favorable excursion after fees'", 'exit_snapshot_net');
CALL add_htx_trend_exit_snapshot_column('exit_snapshot_mae', "DECIMAL(36,18) NULL COMMENT 'Estimated maximum adverse excursion after fees'", 'exit_snapshot_mfe');
CALL add_htx_trend_exit_snapshot_column('exit_snapshot_giveback', "DECIMAL(18,8) NULL COMMENT 'Profit giveback ratio from MFE to current net'", 'exit_snapshot_mae');
CALL add_htx_trend_exit_snapshot_column('exit_snapshot_bars', "BIGINT NULL COMMENT 'Approximate bars elapsed since position creation'", 'exit_snapshot_giveback');
CALL add_htx_trend_exit_snapshot_column('exit_snapshot_candidates', "VARCHAR(1024) NULL COMMENT 'Latest candidate exit trigger states'", 'exit_snapshot_bars');
CALL add_htx_trend_exit_snapshot_column('exit_snapshot_updated_at', "DATETIME(3) NULL COMMENT 'Latest exit diagnostics refresh time'", 'exit_snapshot_candidates');

DROP PROCEDURE IF EXISTS add_htx_trend_exit_snapshot_column;
