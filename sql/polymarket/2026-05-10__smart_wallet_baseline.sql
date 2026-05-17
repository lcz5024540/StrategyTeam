-- =================================================================
-- Batch diff: 2026-05-10 smart wallet baseline
--
-- 合并自以下 7 个 single-purpose migration (按"每批一个 diff 文件"规则归并):
--   1. smart_wallet_optimistic_lock_migration.sql
--   2. smart_wallet_profile_discovery_enrichment.sql
--   3. smart_wallet_profile_manual_override_migration.sql
--   4. smart_wallet_closed_position_sync_state_migration.sql
--   5. smart_wallet_daily_report_outcome_metrics_migration.sql
--   6. smart_wallet_fee_split_migration.sql
--   7. smart_wallet_schema_consolidation_migration.sql
--
-- 整体说明: 2026-05-10 跟单策略基线 ALTER 收口 — 包括乐观锁列、profile
-- discovery / manual override / closed-position 同步状态、daily-report 胜负字段、
-- fee split 拆分 (trading_fee_usdc / gas_fee_usdc / fee_pol_total) 与 schema
-- 一致性补齐.
--
-- 幂等: 各原 migration 已用 information_schema / 存储过程做存在性检查, 直接复用.
-- =================================================================


-- ─────────────────────────────────────────────────────────────────
-- ── 子段: smart_wallet_optimistic_lock_migration.sql ──
-- ─────────────────────────────────────────────────────────────────
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

-- ─────────────────────────────────────────────────────────────────
-- ── 子段: smart_wallet_profile_discovery_enrichment.sql ──
-- ─────────────────────────────────────────────────────────────────
ALTER TABLE `smart_wallet_profile`
    ADD COLUMN `last_discovered_at` TIMESTAMP NULL COMMENT '最近一次 discovery 命中时间' AFTER `last_evaluated_at`,
    ADD COLUMN `last_discovery_query` VARCHAR(128) DEFAULT NULL COMMENT '最近命中的 leaderboard 查询' AFTER `last_discovered_at`,
    ADD COLUMN `leaderboard_hit_count_30d` INT NOT NULL DEFAULT 0 COMMENT '近30日 leaderboard 命中次数' AFTER `last_discovery_query`,
    ADD COLUMN `dominant_session` VARCHAR(16) DEFAULT NULL COMMENT 'ASIA/EU/US/MIXED' AFTER `leaderboard_hit_count_30d`,
    ADD COLUMN `asia_trade_ratio_30d` DECIMAL(10, 4) NOT NULL DEFAULT 0 COMMENT '近30日亚洲时段成交占比' AFTER `dominant_session`,
    ADD COLUMN `eu_trade_ratio_30d` DECIMAL(10, 4) NOT NULL DEFAULT 0 COMMENT '近30日欧洲时段成交占比' AFTER `asia_trade_ratio_30d`,
    ADD COLUMN `us_trade_ratio_30d` DECIMAL(10, 4) NOT NULL DEFAULT 0 COMMENT '近30日美洲时段成交占比' AFTER `eu_trade_ratio_30d`,
    ADD COLUMN `active_hour_histogram_json` TEXT DEFAULT NULL COMMENT '近30日按UTC小时聚合的成交直方图' AFTER `us_trade_ratio_30d`,
    ADD COLUMN `recent_win_rate_7d` DECIMAL(10, 4) DEFAULT NULL COMMENT '近7日胜率' AFTER `active_hour_histogram_json`,
    ADD COLUMN `recent_win_rate_30d` DECIMAL(10, 4) DEFAULT NULL COMMENT '近30日胜率' AFTER `recent_win_rate_7d`;

ALTER TABLE `smart_wallet_profile`
    ADD COLUMN `recent_trade_count_7d` INT NOT NULL DEFAULT 0 COMMENT '近7日交易样本数' AFTER `recent_win_rate_7d`,
    ADD COLUMN `recent_trade_count_30d` INT NOT NULL DEFAULT 0 COMMENT '近30日交易样本数' AFTER `recent_win_rate_30d`,
    ADD COLUMN `observe_cooldown_until` TIMESTAMP NULL COMMENT '近期退化观察冷却截止时间' AFTER `recent_trade_count_30d`,
    ADD COLUMN `observe_cooldown_reason` VARCHAR(255) DEFAULT NULL COMMENT '近期退化观察原因' AFTER `observe_cooldown_until`;

-- ─────────────────────────────────────────────────────────────────
-- ── 子段: smart_wallet_profile_manual_override_migration.sql ──
-- ─────────────────────────────────────────────────────────────────
-- 用途：
-- 1. 给 smart_wallet_profile 增加人工覆盖相关字段
-- 2. 支持“只观察 / 人工退出 lane / 备注”等后台管理能力

DROP PROCEDURE IF EXISTS `sp_smart_wallet_profile_manual_override_migration`;

DELIMITER $$
CREATE PROCEDURE `sp_smart_wallet_profile_manual_override_migration`()
BEGIN
    DECLARE v_exists INT DEFAULT 0;
    DECLARE v_schema_name VARCHAR(128) DEFAULT DATABASE();

    SELECT COUNT(1) INTO v_exists
    FROM information_schema.columns
    WHERE table_schema = v_schema_name
      AND table_name = 'smart_wallet_profile'
      AND column_name = 'manual_watch_enabled';
    IF v_exists = 0 THEN
        ALTER TABLE `smart_wallet_profile`
            ADD COLUMN `manual_watch_enabled` TINYINT(1) NULL COMMENT '人工覆盖是否允许进入 executable watchlist' AFTER `watch_enabled`;
    END IF;

    SELECT COUNT(1) INTO v_exists
    FROM information_schema.columns
    WHERE table_schema = v_schema_name
      AND table_name = 'smart_wallet_profile'
      AND column_name = 'manual_recommended_exit_lane';
    IF v_exists = 0 THEN
        ALTER TABLE `smart_wallet_profile`
            ADD COLUMN `manual_recommended_exit_lane` VARCHAR(64) NULL COMMENT '人工覆盖的退出风格 lane' AFTER `manual_watch_enabled`;
    END IF;

    SELECT COUNT(1) INTO v_exists
    FROM information_schema.columns
    WHERE table_schema = v_schema_name
      AND table_name = 'smart_wallet_profile'
      AND column_name = 'manual_override_note';
    IF v_exists = 0 THEN
        ALTER TABLE `smart_wallet_profile`
            ADD COLUMN `manual_override_note` VARCHAR(255) NULL COMMENT '人工覆盖备注' AFTER `manual_recommended_exit_lane`;
    END IF;

    SELECT COUNT(1) INTO v_exists
    FROM information_schema.columns
    WHERE table_schema = v_schema_name
      AND table_name = 'smart_wallet_profile'
      AND column_name = 'manual_override_updated_at';
    IF v_exists = 0 THEN
        ALTER TABLE `smart_wallet_profile`
            ADD COLUMN `manual_override_updated_at` DATETIME NULL COMMENT '人工覆盖更新时间' AFTER `manual_override_note`;
    END IF;
END $$
DELIMITER ;

CALL `sp_smart_wallet_profile_manual_override_migration`();

DROP PROCEDURE IF EXISTS `sp_smart_wallet_profile_manual_override_migration`;

-- ─────────────────────────────────────────────────────────────────
-- ── 子段: smart_wallet_closed_position_sync_state_migration.sql ──
-- ─────────────────────────────────────────────────────────────────
-- 用途：
-- 为 smart_wallet_profile 增加 closed-positions 增量同步状态字段。
-- 方案：基于 Polymarket /closed-positions 接口的 sortBy=TIMESTAMP&sortDirection=ASC 能力，
--      用 closed_at 业务高水位做增量同步，不依赖 offset 拐点。
-- 设计说明详见 docs/polymarket/smart-wallet-follow-address-api-vs-db-2026-05-07.md 第 9 节。
--
-- 字段含义：
--   closed_sync_max_closed_at       已落库的最大 closed_at(ms)，增量同步业务高水位
--   closed_sync_total_count         已落库 closed-position 总数，用于一致性校验
--   closed_sync_data_quality        数据质量标签：TRUTH / PARTIAL / UNTRUSTED / STUCK
--   closed_sync_last_full_at        上次完整全量复扫时间

DROP PROCEDURE IF EXISTS `sp_smart_wallet_closed_position_sync_state_migration`;

DELIMITER $
CREATE PROCEDURE `sp_smart_wallet_closed_position_sync_state_migration`()
BEGIN
    DECLARE v_exists INT DEFAULT 0;
    DECLARE v_schema_name VARCHAR(128) DEFAULT DATABASE();

    SELECT COUNT(1) INTO v_exists
    FROM information_schema.columns
    WHERE table_schema = v_schema_name
      AND table_name = 'smart_wallet_profile'
      AND column_name = 'closed_sync_max_closed_at';
    IF v_exists = 0 THEN
        ALTER TABLE `smart_wallet_profile`
            ADD COLUMN `closed_sync_max_closed_at` BIGINT DEFAULT NULL
                COMMENT '已抓到的最大 closed_at(ms)，作为增量同步业务高水位';
    END IF;

    SELECT COUNT(1) INTO v_exists
    FROM information_schema.columns
    WHERE table_schema = v_schema_name
      AND table_name = 'smart_wallet_profile'
      AND column_name = 'closed_sync_total_count';
    IF v_exists = 0 THEN
        ALTER TABLE `smart_wallet_profile`
            ADD COLUMN `closed_sync_total_count` INT NOT NULL DEFAULT 0
                COMMENT '已落库 closed-position 总数（一致性校验用）';
    END IF;

    SELECT COUNT(1) INTO v_exists
    FROM information_schema.columns
    WHERE table_schema = v_schema_name
      AND table_name = 'smart_wallet_profile'
      AND column_name = 'closed_sync_data_quality';
    IF v_exists = 0 THEN
        ALTER TABLE `smart_wallet_profile`
            ADD COLUMN `closed_sync_data_quality` VARCHAR(32) DEFAULT NULL
                COMMENT '增量同步数据质量：TRUTH/PARTIAL/UNTRUSTED/STUCK';
    END IF;

    SELECT COUNT(1) INTO v_exists
    FROM information_schema.columns
    WHERE table_schema = v_schema_name
      AND table_name = 'smart_wallet_profile'
      AND column_name = 'closed_sync_last_full_at';
    IF v_exists = 0 THEN
        ALTER TABLE `smart_wallet_profile`
            ADD COLUMN `closed_sync_last_full_at` DATETIME DEFAULT NULL
                COMMENT '上次完整全量复扫时间（HOT 周 1 次 / 其他月 1 次）';
    END IF;

    -- 便于按"最久未同步 / 质量差"优先调度
    SELECT COUNT(1) INTO v_exists
    FROM information_schema.statistics
    WHERE table_schema = v_schema_name
      AND table_name = 'smart_wallet_profile'
      AND index_name = 'idx_sw_profile_closed_sync_dispatch';
    IF v_exists = 0 THEN
        ALTER TABLE `smart_wallet_profile`
            ADD INDEX `idx_sw_profile_closed_sync_dispatch`
            (`closed_sync_data_quality`, `closed_sync_last_full_at`);
    END IF;
END $
DELIMITER ;

CALL `sp_smart_wallet_closed_position_sync_state_migration`();

DROP PROCEDURE IF EXISTS `sp_smart_wallet_closed_position_sync_state_migration`;
-- ─────────────────────────────────────────────────────────────────
-- ── 子段: smart_wallet_daily_report_outcome_metrics_migration.sql ──
-- ─────────────────────────────────────────────────────────────────
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

-- ─────────────────────────────────────────────────────────────────
-- ── 子段: smart_wallet_fee_split_migration.sql ──
-- ─────────────────────────────────────────────────────────────────
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

-- ─────────────────────────────────────────────────────────────────
-- ── 子段: smart_wallet_schema_consolidation_migration.sql ──
-- ─────────────────────────────────────────────────────────────────
-- =================================================================
-- Smart wallet schema consolidation migration
--
-- Purpose:
-- 1. Align the canonical arbitrage_schema.sql with runtime entity fields.
-- 2. Keep existing databases compatible without relying on service startup DDL.
-- 3. Avoid destructive cleanup: unused/research tables are documented separately.
-- =================================================================

DROP PROCEDURE IF EXISTS `sp_smart_wallet_schema_consolidation`;

DELIMITER $$

CREATE PROCEDURE `sp_smart_wallet_schema_consolidation`()
BEGIN
    -- smart_wallet_trade_event.size_shares
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'smart_wallet_trade_event'
          AND column_name = 'size_shares'
    ) THEN
        ALTER TABLE `smart_wallet_trade_event`
            ADD COLUMN `size_shares` DECIMAL(20,8) DEFAULT NULL COMMENT '成交份额数量' AFTER `price`;
    END IF;

    -- smart_wallet_profile manual override fields
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'smart_wallet_profile'
          AND column_name = 'manual_watch_enabled'
    ) THEN
        ALTER TABLE `smart_wallet_profile`
            ADD COLUMN `manual_watch_enabled` TINYINT(1) NULL COMMENT '人工覆盖是否允许进入 executable watchlist' AFTER `observe_cooldown_reason`;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'smart_wallet_profile'
          AND column_name = 'manual_recommended_exit_lane'
    ) THEN
        ALTER TABLE `smart_wallet_profile`
            ADD COLUMN `manual_recommended_exit_lane` VARCHAR(64) NULL COMMENT '人工覆盖的退出风格 lane' AFTER `manual_watch_enabled`;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'smart_wallet_profile'
          AND column_name = 'manual_override_note'
    ) THEN
        ALTER TABLE `smart_wallet_profile`
            ADD COLUMN `manual_override_note` VARCHAR(255) NULL COMMENT '人工覆盖备注' AFTER `manual_recommended_exit_lane`;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'smart_wallet_profile'
          AND column_name = 'manual_override_updated_at'
    ) THEN
        ALTER TABLE `smart_wallet_profile`
            ADD COLUMN `manual_override_updated_at` DATETIME NULL COMMENT '人工覆盖更新时间' AFTER `manual_override_note`;
    END IF;

    -- smart_wallet_market_info.market_slug
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'smart_wallet_market_info'
          AND column_name = 'market_slug'
    ) THEN
        ALTER TABLE `smart_wallet_market_info`
            ADD COLUMN `market_slug` VARCHAR(512) DEFAULT NULL COMMENT 'Polymarket market URL slug' AFTER `market_id`;
    END IF;

    -- smart_wallet_order.version
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'smart_wallet_order'
          AND column_name = 'version'
    ) THEN
        ALTER TABLE `smart_wallet_order`
            ADD COLUMN `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号' AFTER `updated_at`;
    END IF;

    -- smart_wallet_position.source / version
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'smart_wallet_position'
          AND column_name = 'source'
    ) THEN
        ALTER TABLE `smart_wallet_position`
            ADD COLUMN `source` VARCHAR(32) NOT NULL DEFAULT 'FOLLOW_TRADE' COMMENT '仓位来源：FOLLOW_TRADE/WEATHER' AFTER `status`;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'smart_wallet_position'
          AND column_name = 'version'
    ) THEN
        ALTER TABLE `smart_wallet_position`
            ADD COLUMN `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号' AFTER `updated_at`;
    END IF;

    -- Align runtime-created operations tables with canonical schema.
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name = 'smart_wallet_settlement_run'
    ) THEN
        ALTER TABLE `smart_wallet_settlement_run`
            MODIFY COLUMN `error_message` VARCHAR(2048) DEFAULT NULL COMMENT '整体错误';
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name = 'smart_wallet_settlement_retry'
    ) THEN
        ALTER TABLE `smart_wallet_settlement_retry`
            MODIFY COLUMN `last_error_message` TEXT DEFAULT NULL COMMENT '最近错误';
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name = 'smart_wallet_claim_run'
    ) THEN
        ALTER TABLE `smart_wallet_claim_run`
            MODIFY COLUMN `error_message` TEXT DEFAULT NULL COMMENT '整体错误';
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name = 'smart_wallet_claim_retry'
    ) THEN
        ALTER TABLE `smart_wallet_claim_retry`
            MODIFY COLUMN `last_error_message` VARCHAR(2048) DEFAULT NULL COMMENT '最近错误';
    END IF;
END$$

DELIMITER ;

CALL `sp_smart_wallet_schema_consolidation`();

DROP PROCEDURE IF EXISTS `sp_smart_wallet_schema_consolidation`;
