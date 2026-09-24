-- HTX trend execution identity for Ledger-01.
-- Purpose: preserve account_label compatibility while adding stable account/strategy/model identity
--          to positions and orders, then make existing ledger events joinable by run_id and factor snapshot.
-- Preconditions: run on the same schema as the HTX trend service. No service restart is needed for this SQL alone.
-- Rollback: fields are additive. Do not drop them until the deployed service has been rolled back and historical
--           dashboard/replay queries no longer read the new dimensions.

DROP PROCEDURE IF EXISTS add_htx_trend_identity_column;
DELIMITER $$
CREATE PROCEDURE add_htx_trend_identity_column(
    IN p_table_name VARCHAR(64), IN p_column_name VARCHAR(64),
    IN p_column_definition TEXT, IN p_after_column VARCHAR(64)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table_name AND COLUMN_NAME = p_column_name
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE ', p_table_name, ' ADD COLUMN ', p_column_name,
                ' ', p_column_definition, ' AFTER ', p_after_column);
        PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;

CALL add_htx_trend_identity_column('htx_trend_position', 'account_id', 'BIGINT NULL', 'message');
CALL add_htx_trend_identity_column('htx_trend_position', 'strategy_family', "VARCHAR(32) NOT NULL DEFAULT 'TREND'", 'account_label');
CALL add_htx_trend_identity_column('htx_trend_position', 'strategy_name', "VARCHAR(64) NOT NULL DEFAULT 'HTX_TREND_V1'", 'strategy_family');
CALL add_htx_trend_identity_column('htx_trend_position', 'model_name', "VARCHAR(64) NOT NULL DEFAULT 'LEGACY_TREND_SIGNAL'", 'strategy_name');

CALL add_htx_trend_identity_column('htx_trend_order', 'account_id', 'BIGINT NULL', 'message');
CALL add_htx_trend_identity_column('htx_trend_order', 'strategy_family', "VARCHAR(32) NOT NULL DEFAULT 'TREND'", 'account_label');
CALL add_htx_trend_identity_column('htx_trend_order', 'strategy_name', "VARCHAR(64) NOT NULL DEFAULT 'HTX_TREND_V1'", 'strategy_family');
CALL add_htx_trend_identity_column('htx_trend_order', 'model_name', "VARCHAR(64) NOT NULL DEFAULT 'LEGACY_TREND_SIGNAL'", 'strategy_name');
CALL add_htx_trend_identity_column('htx_trend_order', 'factor_snapshot_id', 'BIGINT NULL', 'model_name');

DROP PROCEDURE IF EXISTS add_htx_trend_identity_index;
DELIMITER $$
CREATE PROCEDURE add_htx_trend_identity_index(
    IN p_table_name VARCHAR(64), IN p_index_name VARCHAR(64), IN p_columns TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table_name AND INDEX_NAME = p_index_name
    ) THEN
        SET @ddl = CONCAT('CREATE INDEX ', p_index_name, ' ON ', p_table_name, ' ', p_columns);
        PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;

CALL add_htx_trend_identity_index('htx_trend_position', 'idx_htx_trend_position_identity',
    '(account_id, strategy_family, strategy_name, model_name, updated_at)');
CALL add_htx_trend_identity_index('htx_trend_order', 'idx_htx_trend_order_identity',
    '(account_id, strategy_family, strategy_name, model_name, updated_at)');

-- Historical rows retain their original execution facts. Only missing attribution values are filled.
UPDATE htx_trend_position p
LEFT JOIN htx_trade_account a ON a.account_label = p.account_label
SET p.account_id = COALESCE(p.account_id, a.id),
    p.strategy_family = COALESCE(NULLIF(p.strategy_family, ''), 'TREND'),
    p.strategy_name = COALESCE(NULLIF(p.strategy_name, ''), 'HTX_TREND_V1'),
    p.model_name = COALESCE(NULLIF(p.model_name, ''), NULLIF(p.primary_model, ''), 'LEGACY_TREND_SIGNAL')
WHERE p.account_id IS NULL
   OR p.strategy_family IS NULL OR p.strategy_family = ''
   OR p.strategy_name IS NULL OR p.strategy_name = ''
   OR p.model_name IS NULL OR p.model_name = '';

UPDATE htx_trend_order o
LEFT JOIN htx_trade_account a ON a.account_label = o.account_label
LEFT JOIN htx_trend_position p ON p.id = o.position_id
SET o.account_id = COALESCE(o.account_id, a.id),
    o.strategy_family = COALESCE(NULLIF(o.strategy_family, ''), p.strategy_family, 'TREND'),
    o.strategy_name = COALESCE(NULLIF(o.strategy_name, ''), p.strategy_name, 'HTX_TREND_V1'),
    o.model_name = COALESCE(NULLIF(o.model_name, ''), p.model_name, p.primary_model, 'LEGACY_TREND_SIGNAL'),
    o.factor_snapshot_id = COALESCE(o.factor_snapshot_id, p.factor_snapshot_id)
WHERE o.account_id IS NULL
   OR o.strategy_family IS NULL OR o.strategy_family = ''
   OR o.strategy_name IS NULL OR o.strategy_name = ''
   OR o.model_name IS NULL OR o.model_name = ''
   OR o.factor_snapshot_id IS NULL;

UPDATE coin_strategy_ledger_event l
LEFT JOIN htx_trade_account a ON a.account_label = l.account_label
LEFT JOIN htx_trend_position p ON p.id = l.position_id
SET l.account_id = COALESCE(l.account_id, a.id, p.account_id),
    l.strategy_family = COALESCE(NULLIF(l.strategy_family, ''), p.strategy_family, 'TREND'),
    l.strategy_name = COALESCE(NULLIF(l.strategy_name, ''), p.strategy_name, 'HTX_TREND_V1'),
    l.model_name = COALESCE(NULLIF(l.model_name, ''), p.model_name, p.primary_model, 'LEGACY_TREND_SIGNAL'),
    l.run_id = COALESCE(l.run_id, p.run_id),
    l.factor_snapshot_id = COALESCE(l.factor_snapshot_id, p.factor_snapshot_id)
WHERE l.account_id IS NULL
   OR l.strategy_family IS NULL OR l.strategy_family = ''
   OR l.strategy_name IS NULL OR l.strategy_name = ''
   OR l.model_name IS NULL OR l.model_name = ''
   OR l.run_id IS NULL
   OR l.factor_snapshot_id IS NULL;

DROP PROCEDURE IF EXISTS add_htx_trend_identity_column;
DROP PROCEDURE IF EXISTS add_htx_trend_identity_index;
