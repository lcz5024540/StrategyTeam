-- Measured-move/Fibonacci target evidence for combination entries.
-- Compatible with MySQL versions that do not support ADD COLUMN IF NOT EXISTS.
-- This is additive only: it repairs schema drift without changing live entry or exit behavior.

DROP PROCEDURE IF EXISTS add_htx_trend_target_evidence_column;

DELIMITER $$
CREATE PROCEDURE add_htx_trend_target_evidence_column(
    IN p_table_name VARCHAR(64),
    IN p_column_name VARCHAR(64),
    IN p_column_definition TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table_name
          AND COLUMN_NAME = p_column_name
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE ', p_table_name, ' ADD COLUMN ', p_column_name, ' ', p_column_definition);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;

CALL add_htx_trend_target_evidence_column('htx_trend_candidate_signal', 'target_zone_low', 'DECIMAL(36,18) NULL');
CALL add_htx_trend_target_evidence_column('htx_trend_candidate_signal', 'target_zone_high', 'DECIMAL(36,18) NULL');
CALL add_htx_trend_target_evidence_column('htx_trend_candidate_signal', 'box_measured_move_target', 'DECIMAL(36,18) NULL');
CALL add_htx_trend_target_evidence_column('htx_trend_candidate_signal', 'fib_extension_1272', 'DECIMAL(36,18) NULL');
CALL add_htx_trend_target_evidence_column('htx_trend_candidate_signal', 'fib_extension_1618', 'DECIMAL(36,18) NULL');
CALL add_htx_trend_target_evidence_column('htx_trend_candidate_signal', 'expected_r_multiple', 'DECIMAL(36,18) NULL');
CALL add_htx_trend_target_evidence_column('htx_trend_candidate_signal', 'momentum_quality_score', 'DECIMAL(36,18) NULL');

CALL add_htx_trend_target_evidence_column('htx_trend_position', 'target_zone_low', 'DECIMAL(36,18) NULL');
CALL add_htx_trend_target_evidence_column('htx_trend_position', 'target_zone_high', 'DECIMAL(36,18) NULL');
CALL add_htx_trend_target_evidence_column('htx_trend_position', 'expected_r_multiple', 'DECIMAL(36,18) NULL');
CALL add_htx_trend_target_evidence_column('htx_trend_position', 'momentum_quality_score', 'DECIMAL(36,18) NULL');

DROP PROCEDURE IF EXISTS add_htx_trend_target_evidence_column;
