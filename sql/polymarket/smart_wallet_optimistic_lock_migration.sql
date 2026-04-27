-- ============================================================================
-- Migration: 为 smart_wallet_position 和 smart_wallet_order 增加乐观锁 version 列
--
-- 目的：防止 PositionSyncService / SettlementSyncService / ClaimService /
--        OrderPollingService 等多个定时任务并发修改同一行数据时出现状态跳跃。
--
-- 配合 MyBatis-Plus @Version 注解 + OptimisticLockerInnerInterceptor 使用。
-- updateById() 时自动追加 WHERE version=? 并 SET version=version+1。
-- ============================================================================

-- 1. smart_wallet_position 增加 version 列
-- 幂等检查：只在列不存在时添加
SET @table_name = 'smart_wallet_position';
SET @column_name = 'version';
SET @sql_check = (SELECT COUNT(*) FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = DATABASE()
                    AND TABLE_NAME = @table_name
                    AND COLUMN_NAME = @column_name);

SET @sql_alter = IF(@sql_check = 0,
    'ALTER TABLE `smart_wallet_position` ADD COLUMN `version` INT NOT NULL DEFAULT 0 COMMENT ''乐观锁版本号'' AFTER `updated_at`',
    'SELECT ''Column version already exists in smart_wallet_position''');
PREPARE stmt FROM @sql_alter;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;


-- 2. smart_wallet_order 增加 version 列
SET @table_name = 'smart_wallet_order';
SET @sql_check = (SELECT COUNT(*) FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = DATABASE()
                    AND TABLE_NAME = @table_name
                    AND COLUMN_NAME = 'version');

SET @sql_alter = IF(@sql_check = 0,
    'ALTER TABLE `smart_wallet_order` ADD COLUMN `version` INT NOT NULL DEFAULT 0 COMMENT ''乐观锁版本号'' AFTER `updated_at`',
    'SELECT ''Column version already exists in smart_wallet_order''');
PREPARE stmt FROM @sql_alter;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
