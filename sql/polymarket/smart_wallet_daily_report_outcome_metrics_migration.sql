-- 用途：
-- 1. 给 smart_wallet_daily_report 增加盈利仓位数、亏损仓位数、亏损率
-- 2. 为历史日报提供持久化字段，便于前端直接展示胜负趋势
--
-- 执行建议：
-- - 先在测试库执行
-- - 执行完成后调用 /strategy/api/smart-wallet/reports/daily/rebuild 重建历史日报

DROP PROCEDURE IF EXISTS `sp_smart_wallet_daily_report_outcome_metrics_migration`;

DELIMITER $$
CREATE PROCEDURE `sp_smart_wallet_daily_report_outcome_metrics_migration`()
BEGIN
    DECLARE v_exists INT DEFAULT 0;
    DECLARE v_schema_name VARCHAR(128) DEFAULT DATABASE();

    SELECT COUNT(1) INTO v_exists
    FROM information_schema.columns
    WHERE table_schema = v_schema_name
      AND table_name = 'smart_wallet_daily_report'
      AND column_name = 'winning_positions';
    IF v_exists = 0 THEN
        ALTER TABLE `smart_wallet_daily_report`
            ADD COLUMN `winning_positions` INT NOT NULL DEFAULT 0 COMMENT '盈利仓位数' AFTER `net_pnl_usdc`;
    END IF;

    SELECT COUNT(1) INTO v_exists
    FROM information_schema.columns
    WHERE table_schema = v_schema_name
      AND table_name = 'smart_wallet_daily_report'
      AND column_name = 'losing_positions';
    IF v_exists = 0 THEN
        ALTER TABLE `smart_wallet_daily_report`
            ADD COLUMN `losing_positions` INT NOT NULL DEFAULT 0 COMMENT '亏损仓位数' AFTER `winning_positions`;
    END IF;

    SELECT COUNT(1) INTO v_exists
    FROM information_schema.columns
    WHERE table_schema = v_schema_name
      AND table_name = 'smart_wallet_daily_report'
      AND column_name = 'loss_rate';
    IF v_exists = 0 THEN
        ALTER TABLE `smart_wallet_daily_report`
            ADD COLUMN `loss_rate` DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '亏损率' AFTER `win_rate`;
    END IF;
END $$
DELIMITER ;

CALL `sp_smart_wallet_daily_report_outcome_metrics_migration`();

DROP PROCEDURE IF EXISTS `sp_smart_wallet_daily_report_outcome_metrics_migration`;
