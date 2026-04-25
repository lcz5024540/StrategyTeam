-- 用途：
-- 1. 给 smart_wallet_order / smart_wallet_fill 补齐 trading_fee_usdc / gas_fee_usdc
-- 2. 给 smart_wallet_position 补齐 fee_pol_total
-- 3. 保守回填历史数据：
--    - 旧 fee_usdc 统一视为 trading_fee_usdc
--    - gas_fee_usdc 默认回填 0（不臆造历史链上美元折算）
--    - fee_pol_total 从已有 fill.fee_pol 聚合回填
--
-- 执行建议：
-- - 先在测试库执行
-- - 再在生产库执行
-- - 执行完成后重启应用，让新字段开始被新逻辑持续写入

DROP PROCEDURE IF EXISTS `sp_smart_wallet_fee_split_migration`;

DELIMITER $$
CREATE PROCEDURE `sp_smart_wallet_fee_split_migration`()
BEGIN
    DECLARE v_exists INT DEFAULT 0;
    DECLARE v_schema_name VARCHAR(128) DEFAULT DATABASE();

    -- smart_wallet_order.trading_fee_usdc
    SELECT COUNT(1) INTO v_exists
    FROM information_schema.columns
    WHERE table_schema = v_schema_name
      AND table_name = 'smart_wallet_order'
      AND column_name = 'trading_fee_usdc';
    IF v_exists = 0 THEN
        ALTER TABLE `smart_wallet_order`
            ADD COLUMN `trading_fee_usdc` DECIMAL(20,8) NOT NULL DEFAULT 0 COMMENT '交易手续费USDC' AFTER `fee_usdc`;
    END IF;

    -- smart_wallet_order.fee_pol
    SELECT COUNT(1) INTO v_exists
    FROM information_schema.columns
    WHERE table_schema = v_schema_name
      AND table_name = 'smart_wallet_order'
      AND column_name = 'fee_pol';
    IF v_exists = 0 THEN
        ALTER TABLE `smart_wallet_order`
            ADD COLUMN `fee_pol` DECIMAL(20,8) NOT NULL DEFAULT 0 COMMENT '手续费POL' AFTER `trading_fee_usdc`;
    END IF;

    -- smart_wallet_order.gas_fee_usdc
    SELECT COUNT(1) INTO v_exists
    FROM information_schema.columns
    WHERE table_schema = v_schema_name
      AND table_name = 'smart_wallet_order'
      AND column_name = 'gas_fee_usdc';
    IF v_exists = 0 THEN
        ALTER TABLE `smart_wallet_order`
            ADD COLUMN `gas_fee_usdc` DECIMAL(20,8) NOT NULL DEFAULT 0 COMMENT '链上手续费USDC折算' AFTER `fee_pol`;
    END IF;

    -- smart_wallet_fill.trading_fee_usdc
    SELECT COUNT(1) INTO v_exists
    FROM information_schema.columns
    WHERE table_schema = v_schema_name
      AND table_name = 'smart_wallet_fill'
      AND column_name = 'trading_fee_usdc';
    IF v_exists = 0 THEN
        ALTER TABLE `smart_wallet_fill`
            ADD COLUMN `trading_fee_usdc` DECIMAL(20,8) NOT NULL DEFAULT 0 COMMENT '交易手续费USDC' AFTER `fee_usdc`;
    END IF;

    -- smart_wallet_fill.fee_pol
    SELECT COUNT(1) INTO v_exists
    FROM information_schema.columns
    WHERE table_schema = v_schema_name
      AND table_name = 'smart_wallet_fill'
      AND column_name = 'fee_pol';
    IF v_exists = 0 THEN
        ALTER TABLE `smart_wallet_fill`
            ADD COLUMN `fee_pol` DECIMAL(20,8) NOT NULL DEFAULT 0 COMMENT '手续费POL' AFTER `trading_fee_usdc`;
    END IF;

    -- smart_wallet_fill.gas_fee_usdc
    SELECT COUNT(1) INTO v_exists
    FROM information_schema.columns
    WHERE table_schema = v_schema_name
      AND table_name = 'smart_wallet_fill'
      AND column_name = 'gas_fee_usdc';
    IF v_exists = 0 THEN
        ALTER TABLE `smart_wallet_fill`
            ADD COLUMN `gas_fee_usdc` DECIMAL(20,8) NOT NULL DEFAULT 0 COMMENT '链上手续费USDC折算' AFTER `fee_pol`;
    END IF;

    -- smart_wallet_position.fee_pol_total
    SELECT COUNT(1) INTO v_exists
    FROM information_schema.columns
    WHERE table_schema = v_schema_name
      AND table_name = 'smart_wallet_position'
      AND column_name = 'fee_pol_total';
    IF v_exists = 0 THEN
        ALTER TABLE `smart_wallet_position`
            ADD COLUMN `fee_pol_total` DECIMAL(20,8) NOT NULL DEFAULT 0 COMMENT '累计手续费POL' AFTER `original_cost_basis_usdc`;
    END IF;

    -- 历史订单：旧 fee_usdc 视为 trading fee，gas 默认 0
    UPDATE `smart_wallet_order`
    SET `trading_fee_usdc` = `fee_usdc`,
        `gas_fee_usdc` = 0
    WHERE IFNULL(`fee_usdc`, 0) <> 0
      AND IFNULL(`trading_fee_usdc`, 0) = 0
      AND IFNULL(`gas_fee_usdc`, 0) = 0;

    -- 历史成交：旧 fee_usdc 视为 trading fee，gas 默认 0
    UPDATE `smart_wallet_fill`
    SET `trading_fee_usdc` = `fee_usdc`,
        `gas_fee_usdc` = 0
    WHERE IFNULL(`fee_usdc`, 0) <> 0
      AND IFNULL(`trading_fee_usdc`, 0) = 0
      AND IFNULL(`gas_fee_usdc`, 0) = 0;

    -- 历史持仓：从 fill 聚合回填 fee_pol_total
    UPDATE `smart_wallet_position` p
    JOIN (
        SELECT `position_id`, SUM(IFNULL(`fee_pol`, 0)) AS total_fee_pol
        FROM `smart_wallet_fill`
        WHERE `position_id` IS NOT NULL
        GROUP BY `position_id`
    ) f ON f.`position_id` = p.`id`
    SET p.`fee_pol_total` = f.`total_fee_pol`
    WHERE IFNULL(p.`fee_pol_total`, 0) = 0
      AND IFNULL(f.`total_fee_pol`, 0) <> 0;
END $$
DELIMITER ;

CALL `sp_smart_wallet_fee_split_migration`();

DROP PROCEDURE IF EXISTS `sp_smart_wallet_fee_split_migration`;

-- 可选检查：
-- SHOW COLUMNS FROM smart_wallet_order;
-- SHOW COLUMNS FROM smart_wallet_fill;
-- SHOW COLUMNS FROM smart_wallet_position;
-- SELECT id, fee_usdc, trading_fee_usdc, gas_fee_usdc FROM smart_wallet_order ORDER BY id DESC LIMIT 20;
