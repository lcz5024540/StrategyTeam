-- 用途：
-- 1. 新增 smart_wallet_order_analysis 单笔闭环分析表
-- 2. 扩展 smart_wallet_behavior_profile，支持质量分、稳定性、胜率来源和结算胜率

DROP PROCEDURE IF EXISTS `sp_smart_wallet_order_analysis_migration`;

DELIMITER $$
CREATE PROCEDURE `sp_smart_wallet_order_analysis_migration`()
BEGIN
    DECLARE v_exists INT DEFAULT 0;
    DECLARE v_schema_name VARCHAR(128) DEFAULT DATABASE();

    SELECT COUNT(1) INTO v_exists
    FROM information_schema.tables
    WHERE table_schema = v_schema_name
      AND table_name = 'smart_wallet_order_analysis';
    IF v_exists = 0 THEN
        CREATE TABLE `smart_wallet_order_analysis` (
            `id` BIGINT NOT NULL AUTO_INCREMENT,
            `wallet_address` VARCHAR(128) NOT NULL COMMENT '地址',
            `source` VARCHAR(32) NOT NULL DEFAULT 'POLYMARKET' COMMENT '来源',
            `market_id` VARCHAR(128) NOT NULL COMMENT '市场ID',
            `question` VARCHAR(512) DEFAULT NULL COMMENT '问题',
            `outcome` VARCHAR(128) DEFAULT NULL COMMENT '结果标签',
            `opened_at` DATETIME DEFAULT NULL COMMENT '建仓时间',
            `closed_at` DATETIME DEFAULT NULL COMMENT '平仓时间',
            `market_end_at` BIGINT DEFAULT NULL COMMENT '市场预计结算时间戳(毫秒)',
            `average_entry_price` DECIMAL(20,8) NOT NULL DEFAULT 0 COMMENT '平均建仓价格',
            `invested_usdc` DECIMAL(20,8) NOT NULL DEFAULT 0 COMMENT '投入本金USDC',
            `realized_pnl_usdc` DECIMAL(20,8) NOT NULL DEFAULT 0 COMMENT '已实现收益USDC',
            `roi_rate` DECIMAL(20,8) NOT NULL DEFAULT 0 COMMENT '单笔收益率',
            `hold_minutes` DECIMAL(12,2) DEFAULT NULL COMMENT '持有时长(分钟)',
            `exit_style` VARCHAR(64) DEFAULT NULL COMMENT '退出类型',
            `exit_lead_minutes` DECIMAL(12,2) DEFAULT NULL COMMENT '相对结算提前退出分钟数',
            `is_win` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否盈利',
            `source_confidence` DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '分析可信度',
            `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
            PRIMARY KEY (`id`),
            UNIQUE KEY `uk_sw_order_analysis_wallet_market_outcome_closed` (`wallet_address`,`market_id`,`outcome`,`closed_at`),
            KEY `idx_sw_order_analysis_wallet_closed` (`wallet_address`,`source`,`closed_at`),
            KEY `idx_sw_order_analysis_exit` (`exit_style`,`closed_at`)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='smart wallet 单笔闭环分析';
    END IF;

    SELECT COUNT(1) INTO v_exists
    FROM information_schema.columns
    WHERE table_schema = v_schema_name
      AND table_name = 'smart_wallet_behavior_profile'
      AND column_name = 'win_source_type';
    IF v_exists = 0 THEN
        ALTER TABLE `smart_wallet_behavior_profile`
            ADD COLUMN `win_source_type` VARCHAR(64) NULL COMMENT '胜率来源类型' AFTER `loss_early_exit_rate`;
    END IF;

    SELECT COUNT(1) INTO v_exists
    FROM information_schema.columns
    WHERE table_schema = v_schema_name
      AND table_name = 'smart_wallet_behavior_profile'
      AND column_name = 'latency_alpha_rate';
    IF v_exists = 0 THEN
        ALTER TABLE `smart_wallet_behavior_profile`
            ADD COLUMN `latency_alpha_rate` DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '短周期 alpha 比例' AFTER `win_source_type`;
    END IF;

    SELECT COUNT(1) INTO v_exists
    FROM information_schema.columns
    WHERE table_schema = v_schema_name
      AND table_name = 'smart_wallet_behavior_profile'
      AND column_name = 'swing_alpha_rate';
    IF v_exists = 0 THEN
        ALTER TABLE `smart_wallet_behavior_profile`
            ADD COLUMN `swing_alpha_rate` DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '中周期 alpha 比例' AFTER `latency_alpha_rate`;
    END IF;

    SELECT COUNT(1) INTO v_exists
    FROM information_schema.columns
    WHERE table_schema = v_schema_name
      AND table_name = 'smart_wallet_behavior_profile'
      AND column_name = 'settlement_win_rate';
    IF v_exists = 0 THEN
        ALTER TABLE `smart_wallet_behavior_profile`
            ADD COLUMN `settlement_win_rate` DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '等待结算胜率' AFTER `swing_alpha_rate`;
    END IF;

    SELECT COUNT(1) INTO v_exists
    FROM information_schema.columns
    WHERE table_schema = v_schema_name
      AND table_name = 'smart_wallet_behavior_profile'
      AND column_name = 'median_hold_minutes';
    IF v_exists = 0 THEN
        ALTER TABLE `smart_wallet_behavior_profile`
            ADD COLUMN `median_hold_minutes` DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '持仓时长中位数(分钟)' AFTER `settlement_win_rate`;
    END IF;

    SELECT COUNT(1) INTO v_exists
    FROM information_schema.columns
    WHERE table_schema = v_schema_name
      AND table_name = 'smart_wallet_behavior_profile'
      AND column_name = 'stability_score';
    IF v_exists = 0 THEN
        ALTER TABLE `smart_wallet_behavior_profile`
            ADD COLUMN `stability_score` DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '稳定性分数' AFTER `median_hold_minutes`;
    END IF;

    SELECT COUNT(1) INTO v_exists
    FROM information_schema.columns
    WHERE table_schema = v_schema_name
      AND table_name = 'smart_wallet_behavior_profile'
      AND column_name = 'quality_score';
    IF v_exists = 0 THEN
        ALTER TABLE `smart_wallet_behavior_profile`
            ADD COLUMN `quality_score` DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '地址质量分数' AFTER `stability_score`;
    END IF;
END $$
DELIMITER ;

CALL `sp_smart_wallet_order_analysis_migration`();

DROP PROCEDURE IF EXISTS `sp_smart_wallet_order_analysis_migration`;
