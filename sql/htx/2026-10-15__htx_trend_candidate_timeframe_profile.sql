-- HTX trend candidate trace: persist the independent timeframe lifecycle.
-- Apply manually before deploying the worker; the worker never executes DDL.
-- NULL remains valid for historical R0 rows and older Java candidates.

DROP PROCEDURE IF EXISTS add_htx_trend_candidate_timeframe_column;

DELIMITER $$
CREATE PROCEDURE add_htx_trend_candidate_timeframe_column()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'htx_trend_candidate_signal'
          AND COLUMN_NAME = 'timeframe_profile'
    ) THEN
        ALTER TABLE htx_trend_candidate_signal
            ADD COLUMN timeframe_profile VARCHAR(32) NULL AFTER contract_code;
    END IF;
END$$
DELIMITER ;

CALL add_htx_trend_candidate_timeframe_column();
DROP PROCEDURE IF EXISTS add_htx_trend_candidate_timeframe_column;

DROP PROCEDURE IF EXISTS add_htx_trend_candidate_timeframe_index;

DELIMITER $$
CREATE PROCEDURE add_htx_trend_candidate_timeframe_index()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'htx_trend_candidate_signal'
          AND INDEX_NAME = 'idx_htx_candidate_timeframe'
    ) THEN
        CREATE INDEX idx_htx_candidate_timeframe
            ON htx_trend_candidate_signal (timeframe_profile, contract_code, bar_time, created_at);
    END IF;
END$$
DELIMITER ;

CALL add_htx_trend_candidate_timeframe_index();
DROP PROCEDURE IF EXISTS add_htx_trend_candidate_timeframe_index;
