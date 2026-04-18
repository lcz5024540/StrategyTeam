-- =================================================================
-- Polymarket settlement retry 字段补齐
-- 目标：
-- - 为 smart_wallet_settlement_retry 补充 retry_type 字段
-- - 对齐 last_error_message 字段长度，避免与运行时 schema 维护逻辑不一致
-- =================================================================

SET @retry_type_exists := (
    SELECT COUNT(1)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'smart_wallet_settlement_retry'
      AND column_name = 'retry_type'
);

SET @retry_type_ddl := IF(
    @retry_type_exists > 0,
    'SELECT 1',
    'ALTER TABLE `smart_wallet_settlement_retry` ADD COLUMN `retry_type` VARCHAR(32) DEFAULT NULL COMMENT ''SYNC_FAILURE/ORACLE_PENDING/DATA_SOURCE_STALE'' AFTER `question`'
);

PREPARE retry_type_stmt FROM @retry_type_ddl;
EXECUTE retry_type_stmt;
DEALLOCATE PREPARE retry_type_stmt;

ALTER TABLE `smart_wallet_settlement_retry`
    MODIFY COLUMN `last_error_message` VARCHAR(2048) DEFAULT NULL COMMENT '最近错误';
