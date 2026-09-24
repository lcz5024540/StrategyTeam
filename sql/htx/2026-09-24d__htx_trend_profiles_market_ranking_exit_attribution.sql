-- HTX trend P1: immutable timeframe profile, exit attribution linkage and market-ranked EMA continuation policy.
-- Run this migration before deploying the JAR. It is additive and safe to re-run.
DROP PROCEDURE IF EXISTS add_htx_trend_p1_column;
DELIMITER $$
CREATE PROCEDURE add_htx_trend_p1_column(IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_definition TEXT, IN p_after VARCHAR(64))
BEGIN
  IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND COLUMN_NAME = p_column) THEN
    SET @sql = CONCAT('ALTER TABLE ', p_table, ' ADD COLUMN ', p_column, ' ', p_definition, ' AFTER ', p_after);
    PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
  END IF;
END$$
DELIMITER ;
CALL add_htx_trend_p1_column('htx_trend_position', 'timeframe_profile', "VARCHAR(32) NOT NULL DEFAULT 'INTRADAY_15M'", 'policy_version');
CALL add_htx_trend_p1_column('htx_trend_position', 'exit_attribution_snapshot_id', 'BIGINT NULL', 'exit_snapshot_updated_at');
CALL add_htx_trend_p1_column('htx_trend_order', 'timeframe_profile', "VARCHAR(32) NOT NULL DEFAULT 'INTRADAY_15M'", 'factor_snapshot_id');
DROP PROCEDURE IF EXISTS add_htx_trend_p1_column;

DROP PROCEDURE IF EXISTS add_htx_trend_p1_index;
DELIMITER $$
CREATE PROCEDURE add_htx_trend_p1_index()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = 'htx_trend_position' AND INDEX_NAME = 'idx_htx_trend_position_profile') THEN
    CREATE INDEX idx_htx_trend_position_profile ON htx_trend_position (timeframe_profile, status, updated_at);
  END IF;
END$$
DELIMITER ;
CALL add_htx_trend_p1_index();
DROP PROCEDURE IF EXISTS add_htx_trend_p1_index;

-- New model is enabled as a policy only. The 4h profile remains disabled in application configuration.
INSERT INTO htx_trend_strategy_policy (policy_code, policy_version, primary_model, confirmation_models,
    score_threshold, base_notional_usdt, max_notional_usdt, box_window_bars, enabled, updated_by, change_note)
SELECT 'EMA_VWAP_MOMENTUM_CONTINUATION', 'combo-v3', 'EMA_VWAP_MOMENTUM_CONTINUATION', 'EMA_STACK,MOMENTUM',
    88, 160, 240, 32, 1, 'migration', 'market-ranked continuation policy'
WHERE NOT EXISTS (SELECT 1 FROM htx_trend_strategy_policy WHERE policy_code = 'EMA_VWAP_MOMENTUM_CONTINUATION' AND policy_version = 'combo-v3');
