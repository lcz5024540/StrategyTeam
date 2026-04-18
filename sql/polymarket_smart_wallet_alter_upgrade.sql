-- 用途：
-- 1. 给已创建的 smart_wallet 表补齐本次代码依赖的列和索引
-- 2. 移除会导致“同市场同 outcome 历史仓位被错误合并”的唯一索引
-- 3. 采用 information_schema + 动态 SQL，避免在已升级库上重复执行时报错
--
-- 适用场景：
-- - 现有库已经建表完成，不希望直接重跑 arbitrage_schema.sql
-- - 需要把线上 / 测试库升级到“按 position_id 区分持仓轮次”的版本
--
-- 执行建议：
-- - 先在测试库执行
-- - 再在生产库执行
-- - 执行完成后重启应用，让新逻辑开始给 order / fill 回写 position_id

SET @schema_name = DATABASE();

DROP PROCEDURE IF EXISTS `sp_upgrade_polymarket_smart_wallet_schema`;

DELIMITER $$
CREATE PROCEDURE `sp_upgrade_polymarket_smart_wallet_schema`()
BEGIN
    DECLARE v_exists INT DEFAULT 0;

    -- 1. smart_wallet_follow_task.market_end_at
    SELECT COUNT(1) INTO v_exists
    FROM information_schema.columns
    WHERE table_schema = @schema_name
      AND table_name = 'smart_wallet_follow_task'
      AND column_name = 'market_end_at';
    IF v_exists = 0 THEN
        ALTER TABLE `smart_wallet_follow_task`
            ADD COLUMN `market_end_at` BIGINT NULL COMMENT '市场预计结算时间戳(毫秒)' AFTER `outcome`;
    END IF;

    -- 2. smart_wallet_order.market_end_at
    SELECT COUNT(1) INTO v_exists
    FROM information_schema.columns
    WHERE table_schema = @schema_name
      AND table_name = 'smart_wallet_order'
      AND column_name = 'market_end_at';
    IF v_exists = 0 THEN
        ALTER TABLE `smart_wallet_order`
            ADD COLUMN `market_end_at` BIGINT NULL COMMENT '市场预计结算时间戳(毫秒)' AFTER `outcome`;
    END IF;

    -- 3. smart_wallet_order.position_id
    SELECT COUNT(1) INTO v_exists
    FROM information_schema.columns
    WHERE table_schema = @schema_name
      AND table_name = 'smart_wallet_order'
      AND column_name = 'position_id';
    IF v_exists = 0 THEN
        ALTER TABLE `smart_wallet_order`
            ADD COLUMN `position_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '关联持仓轮次ID' AFTER `follow_task_id`;
    END IF;

    -- 4. smart_wallet_fill.position_id
    SELECT COUNT(1) INTO v_exists
    FROM information_schema.columns
    WHERE table_schema = @schema_name
      AND table_name = 'smart_wallet_fill'
      AND column_name = 'position_id';
    IF v_exists = 0 THEN
        ALTER TABLE `smart_wallet_fill`
            ADD COLUMN `position_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '关联持仓轮次ID' AFTER `order_id`;
    END IF;

    -- 5. smart_wallet_position.market_end_at
    SELECT COUNT(1) INTO v_exists
    FROM information_schema.columns
    WHERE table_schema = @schema_name
      AND table_name = 'smart_wallet_position'
      AND column_name = 'market_end_at';
    IF v_exists = 0 THEN
        ALTER TABLE `smart_wallet_position`
            ADD COLUMN `market_end_at` BIGINT NULL COMMENT '市场预计结算时间戳(毫秒)' AFTER `outcome`;
    END IF;

    -- 6. smart_wallet_position.original_cost_basis_usdc
    SELECT COUNT(1) INTO v_exists
    FROM information_schema.columns
    WHERE table_schema = @schema_name
      AND table_name = 'smart_wallet_position'
      AND column_name = 'original_cost_basis_usdc';
    IF v_exists = 0 THEN
        ALTER TABLE `smart_wallet_position`
            ADD COLUMN `original_cost_basis_usdc` DECIMAL(20,8) NOT NULL DEFAULT 0 COMMENT '原始持仓成本(用于结算后展示)' AFTER `cost_basis_usdc`;
    END IF;

    -- 7. 删除旧唯一索引，允许同 execution_address + market_id + outcome 出现多轮历史仓位
    SELECT COUNT(1) INTO v_exists
    FROM information_schema.statistics
    WHERE table_schema = @schema_name
      AND table_name = 'smart_wallet_position'
      AND index_name = 'uk_position_execution_market_outcome';
    IF v_exists > 0 THEN
        ALTER TABLE `smart_wallet_position`
            DROP INDEX `uk_position_execution_market_outcome`;
    END IF;

    -- 8. smart_wallet_order.position_id 索引
    SELECT COUNT(1) INTO v_exists
    FROM information_schema.statistics
    WHERE table_schema = @schema_name
      AND table_name = 'smart_wallet_order'
      AND index_name = 'idx_order_position';
    IF v_exists = 0 THEN
        CREATE INDEX `idx_order_position`
            ON `smart_wallet_order` (`position_id`, `placed_at`);
    END IF;

    -- 9. smart_wallet_fill.position_id 索引
    SELECT COUNT(1) INTO v_exists
    FROM information_schema.statistics
    WHERE table_schema = @schema_name
      AND table_name = 'smart_wallet_fill'
      AND index_name = 'idx_fill_position';
    IF v_exists = 0 THEN
        CREATE INDEX `idx_fill_position`
            ON `smart_wallet_fill` (`position_id`, `filled_at`);
    END IF;

    -- 10. smart_wallet_position 新查询索引
    SELECT COUNT(1) INTO v_exists
    FROM information_schema.statistics
    WHERE table_schema = @schema_name
      AND table_name = 'smart_wallet_position'
      AND index_name = 'idx_position_execution_market_outcome';
    IF v_exists = 0 THEN
        CREATE INDEX `idx_position_execution_market_outcome`
            ON `smart_wallet_position` (`execution_address`, `market_id`, `outcome`, `status`, `last_filled_at`);
    END IF;

    -- 11. 可选兼容项：扩大 status 列长度，避免后续状态值写入失败
    SELECT COUNT(1) INTO v_exists
    FROM information_schema.columns
    WHERE table_schema = @schema_name
      AND table_name = 'smart_wallet_order'
      AND column_name = 'status'
      AND IFNULL(character_maximum_length, 0) < 32;
    IF v_exists > 0 THEN
        ALTER TABLE `smart_wallet_order`
            MODIFY COLUMN `status` VARCHAR(32) NOT NULL DEFAULT 'FILLED'
            COMMENT 'LIVE/PARTIALLY_FILLED/FILLED/FAILED/SETTLEMENT_PENDING_ORACLE/SETTLED_WIN/SETTLED_LOSS';
    END IF;

    SELECT COUNT(1) INTO v_exists
    FROM information_schema.columns
    WHERE table_schema = @schema_name
      AND table_name = 'smart_wallet_position'
      AND column_name = 'status'
      AND IFNULL(character_maximum_length, 0) < 32;
    IF v_exists > 0 THEN
        ALTER TABLE `smart_wallet_position`
            MODIFY COLUMN `status` VARCHAR(32) NOT NULL DEFAULT 'OPEN'
            COMMENT 'OPEN/PARTIALLY_CLOSED/CLOSED/RESOLVED_UNCLAIMED/CLAIMED';
    END IF;
END $$
DELIMITER ;

CALL `sp_upgrade_polymarket_smart_wallet_schema`();

DROP PROCEDURE IF EXISTS `sp_upgrade_polymarket_smart_wallet_schema`;

-- 可选检查：
-- SHOW INDEX FROM smart_wallet_position;
-- SHOW COLUMNS FROM smart_wallet_order;
-- SHOW COLUMNS FROM smart_wallet_fill;
