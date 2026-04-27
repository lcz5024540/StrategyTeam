-- 用途：给 smart_wallet_fill 表补充 realized_pnl_usdc 列
-- 已有存量数据该列为 NULL（旧成交记录无 P&L），新成交将在落库时直接写入。
-- 执行建议：先在测试库执行，确认无误后再在生产库执行。

DROP PROCEDURE IF EXISTS `sp_add_fill_realized_pnl`;

DELIMITER $$
CREATE PROCEDURE `sp_add_fill_realized_pnl`()
BEGIN
    DECLARE v_exists INT DEFAULT 0;
    DECLARE v_schema_name VARCHAR(64) DEFAULT DATABASE();

    SELECT COUNT(1) INTO v_exists
    FROM information_schema.columns
    WHERE table_schema = v_schema_name
      AND table_name = 'smart_wallet_fill'
      AND column_name = 'realized_pnl_usdc';
    IF v_exists = 0 THEN
        ALTER TABLE `smart_wallet_fill`
            ADD COLUMN `realized_pnl_usdc` DECIMAL(20, 8) DEFAULT NULL
            COMMENT 'SELL 成交实现 P&L（USDC）；BUY 成交为 NULL'
            AFTER `fee_usdc`;
    END IF;
END $$
DELIMITER ;

CALL `sp_add_fill_realized_pnl`();

DROP PROCEDURE IF EXISTS `sp_add_fill_realized_pnl`;

-- 可选检查：
-- SHOW COLUMNS FROM smart_wallet_fill;
