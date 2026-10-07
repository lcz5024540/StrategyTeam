-- One immutable execution chain may fan out into multiple follower accounts.  Each follower now owns
-- a separate trend position row; its orders must never be attached to the primary account position.
-- Execute manually before deploying the matching application JAR.  Service startup remains SQL-free.

DROP PROCEDURE IF EXISTS add_htx_trend_mirror_position_column;

DELIMITER $$
CREATE PROCEDURE add_htx_trend_mirror_position_column()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'htx_trend_position'
          AND COLUMN_NAME = 'source_position_id'
    ) THEN
        ALTER TABLE htx_trend_position
            ADD COLUMN source_position_id BIGINT NULL COMMENT 'Primary position that originated a follower-account mirror' AFTER id;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'htx_trend_position'
          AND INDEX_NAME = 'idx_trend_position_source_account'
    ) THEN
        ALTER TABLE htx_trend_position
            ADD INDEX idx_trend_position_source_account (source_position_id, account_label);
    END IF;
END$$
DELIMITER ;

CALL add_htx_trend_mirror_position_column();
DROP PROCEDURE IF EXISTS add_htx_trend_mirror_position_column;
