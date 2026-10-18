-- HTX trend Fibonacci structure ownership and final order-ledger PnL.
-- Run manually against the production strategy database before deploying the matching JAR.
-- This migration is additive. It does not change a strategy switch, position, leverage or order.

CREATE TABLE IF NOT EXISTS htx_trend_structure_lifecycle (
    id BIGINT NOT NULL AUTO_INCREMENT,
    structure_key VARCHAR(192) NOT NULL,
    contract_code VARCHAR(32) NOT NULL,
    timeframe VARCHAR(16) NOT NULL,
    direction VARCHAR(8) NOT NULL,
    primary_model VARCHAR(64) NOT NULL,
    breakout_bar_time BIGINT NULL,
    state VARCHAR(32) NOT NULL,
    source_run_id VARCHAR(128) NULL,
    entry_position_id BIGINT NULL,
    terminal_position_id BIGINT NULL,
    terminal_reason VARCHAR(512) NULL,
    first_seen_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    opened_at DATETIME(3) NULL,
    terminal_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_htx_trend_structure_lifecycle_key (structure_key),
    KEY idx_htx_trend_structure_lifecycle_position (entry_position_id),
    KEY idx_htx_trend_structure_lifecycle_contract_time (contract_code, timeframe, breakout_bar_time)
) COMMENT='HTX trend entry-cycle ownership; each completed K-line has an independent lifecycle';

DROP PROCEDURE IF EXISTS add_htx_trend_structure_pnl_column;
DELIMITER $$
CREATE PROCEDURE add_htx_trend_structure_pnl_column(
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

CALL add_htx_trend_structure_pnl_column('final_gross_profit', 'DECIMAL(36,18) NULL COMMENT \'Sum of filled order realized PnL\'', 'realized_profit');
CALL add_htx_trend_structure_pnl_column('final_total_fee', 'DECIMAL(36,18) NULL COMMENT \'Sum of absolute filled order fees\'', 'final_gross_profit');
CALL add_htx_trend_structure_pnl_column('final_net_profit', 'DECIMAL(36,18) NULL COMMENT \'Final gross less all filled order fees\'', 'final_total_fee');
CALL add_htx_trend_structure_pnl_column('final_pnl_source', 'VARCHAR(32) NULL COMMENT \'ORDER_LEDGER or legacy fallback\'', 'final_net_profit');
CALL add_htx_trend_structure_pnl_column('final_pnl_calculated_at', 'DATETIME(3) NULL COMMENT \'Last final PnL aggregation time\'', 'final_pnl_source');
DROP PROCEDURE IF EXISTS add_htx_trend_structure_pnl_column;

-- Backfill only positions for which filled order legs are present. It never writes a decision snapshot as PnL.
UPDATE htx_trend_position p
JOIN (
    SELECT position_id,
           SUM(COALESCE(realized_profit, 0)) AS gross_profit,
           SUM(ABS(COALESCE(fee, 0))) AS total_fee
    FROM htx_trend_order
    WHERE status IN ('FILLED', 'PARTIALLY_FILLED')
    GROUP BY position_id
) o ON o.position_id = p.id
SET p.final_gross_profit = o.gross_profit,
    p.final_total_fee = o.total_fee,
    p.final_net_profit = o.gross_profit - o.total_fee,
    p.final_pnl_source = 'ORDER_LEDGER',
    p.final_pnl_calculated_at = CURRENT_TIMESTAMP(3)
WHERE p.status = 'DONE';
