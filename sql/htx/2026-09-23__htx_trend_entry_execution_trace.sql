-- HTX trend entry trace: stable scan id links candidate, final gate, position and order.
-- Additive and idempotent for the deployed MySQL schema.

DROP PROCEDURE IF EXISTS add_htx_trend_execution_trace_column;

DELIMITER $$
CREATE PROCEDURE add_htx_trend_execution_trace_column(
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

CALL add_htx_trend_execution_trace_column('htx_trend_candidate_signal', 'run_id', 'VARCHAR(128) NULL', 'policy_version');
CALL add_htx_trend_execution_trace_column('htx_trend_candidate_signal', 'execution_gate_result', 'VARCHAR(32) NULL', 'gate_reason');
CALL add_htx_trend_execution_trace_column('htx_trend_candidate_signal', 'execution_gate_reason', 'VARCHAR(1024) NULL', 'execution_gate_result');
CALL add_htx_trend_execution_trace_column('htx_trend_candidate_signal', 'execution_evaluated_at', 'DATETIME(3) NULL', 'execution_gate_reason');
CALL add_htx_trend_execution_trace_column('htx_trend_position', 'run_id', 'VARCHAR(128) NULL', 'position_key');
CALL add_htx_trend_execution_trace_column('htx_trend_order', 'run_id', 'VARCHAR(128) NULL', 'position_key');

DROP PROCEDURE IF EXISTS add_htx_trend_execution_trace_index;
DELIMITER $$
CREATE PROCEDURE add_htx_trend_execution_trace_index(
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

CALL add_htx_trend_execution_trace_index('htx_trend_candidate_signal', 'idx_htx_trend_candidate_run', '(run_id, contract_code)');
CALL add_htx_trend_execution_trace_index('htx_trend_position', 'idx_htx_trend_position_run', '(run_id)');
CALL add_htx_trend_execution_trace_index('htx_trend_order', 'idx_htx_trend_order_run', '(run_id)');

DROP PROCEDURE IF EXISTS add_htx_trend_execution_trace_column;
DROP PROCEDURE IF EXISTS add_htx_trend_execution_trace_index;
