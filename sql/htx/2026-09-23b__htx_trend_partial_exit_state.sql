-- Purpose: add durable state for HTX trend reduce-only partial exits.
-- Related feature: EXIT-02 / EXIT-03 runner and staged profit-taking state machine.
-- Date: 2026-09-23. Downtime: none expected; additive columns only, execute during a low-write window.
-- Rollback: deploy the prior application first, then (only after verifying no active partial exits)
--           DROP the four added columns. Do not delete or rewrite historical order/ledger rows.
-- Compatibility: legacy MySQL; one reusable INFORMATION_SCHEMA-guarded procedure keeps the DDL idempotent.

DROP PROCEDURE IF EXISTS add_htx_trend_partial_exit_column;

DELIMITER $$
CREATE PROCEDURE add_htx_trend_partial_exit_column(
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
        SET @ddl = CONCAT('ALTER TABLE htx_trend_position ADD COLUMN ', p_column_name, ' ', p_column_definition, ' AFTER ', p_after_column);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;

CALL add_htx_trend_partial_exit_column('remaining_contract_volume', 'BIGINT NOT NULL DEFAULT 0 COMMENT ''Live contracts remaining after partial exits''', 'contract_volume');
CALL add_htx_trend_partial_exit_column('partial_exit_stage', 'INT NOT NULL DEFAULT 0 COMMENT ''0 none, 1 first partial, 2 second partial''', 'remaining_contract_volume');
CALL add_htx_trend_partial_exit_column('partial_close_client_order_id', 'VARCHAR(64) NULL COMMENT ''Pending partial close client order id''', 'partial_exit_stage');
CALL add_htx_trend_partial_exit_column('partial_close_order_id', 'BIGINT NULL COMMENT ''Pending partial close exchange order id''', 'partial_close_client_order_id');

-- Existing active positions predate this column. Preserve their true contract quantity as the initial remaining quantity.
UPDATE htx_trend_position
SET remaining_contract_volume = contract_volume
WHERE remaining_contract_volume = 0
  AND status IN ('INTENT', 'ENTRY_SENT', 'ACTIVE', 'CLOSE_SENT', 'UNKNOWN');

DROP PROCEDURE IF EXISTS add_htx_trend_partial_exit_column;
