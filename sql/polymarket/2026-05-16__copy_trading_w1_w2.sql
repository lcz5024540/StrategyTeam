-- =================================================================
-- Batch diff: 2026-05-16 跟单策略 W1+W2 闭环 ALTER
--
-- 合并自:
--   1. smart_wallet_trace_id_migration.sql               (W1-T3 trace_id 五表)
--   2. smart_wallet_behavior_vector_migration.sql        (方案 B 行为向量 centroid 复用)
--   3. smart_wallet_signal_shadow_estimator_source_migration.sql (第十四批 VWAP/PROXY 分桶)
--   4. smart_wallet_order_analysis_migration.sql 的 ALTER 部分
--      (smart_wallet_behavior_profile 9 个新字段)
--   5. weather_arbitrage_migration.sql 的 ALTER 部分
--      (smart_wallet_position 加 source 列, FOLLOW_TRADE / WEATHER)
--
-- 整体说明: 2026-05-16 W1+W2 闭环可观测性的 ALTER 收口 — 跟单链路 trace_id
-- 全链路五表新增列, behavior_profile 加 9 个 score/rate 字段供质量分析,
-- behavior_vector 让 LeaderBehaviorDriftService 直接读 centroid (避免每次 cache miss
-- 重算 500 trade_event), signal_shadow 加 entry_estimator_source 让 VWAP vs PROXY
-- 周报可分桶, smart_wallet_position 加 source 区分 FOLLOW_TRADE / WEATHER.
-- =================================================================


-- ─────────────────────────────────────────────────────────────────
-- ── 子段: smart_wallet_trace_id_migration.sql ──
-- ─────────────────────────────────────────────────────────────────
-- ============================================================================
-- Migration: 为跟单链路核心表新增 trace_id, 实现端到端可追踪
--
-- 目的:
--   leader trade_event -> follow_task -> order / trade_order -> position
--   全链路共享同一 trace_id, 便于日终审计 (W1-T1 Live/Replay diff,
--   W1-T2 结算源审计, W1-T4 影子记账) 和线上问题定位.
--
-- 设计:
--   * trace_id 来源 = smart_wallet_trade_event.id (派生为 "tev_%012d")
--   * 类型 VARCHAR(32), 普通 index (允许重复, 拆单/重试场景)
--   * position 表使用 origin_trace_id 语义: 仓位首次创建时的 trace_id
--   * settlement_run / settlement_retry / claim_retry 是批次/聚合表, 不加列
--
-- 来源决议:
--   docs/polymarket/copy-trading-improvement-decision-2026-05-16.md (W1-T3)
--
-- 幂等: 所有 ALTER 均检查 information_schema, 重复执行不报错.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. smart_wallet_trade_event  增加 trace_id (链路起点)
-- ----------------------------------------------------------------------------
SET @sql_check = (SELECT COUNT(*) FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = DATABASE()
                    AND TABLE_NAME   = 'smart_wallet_trade_event'
                    AND COLUMN_NAME  = 'trace_id');

SET @sql_alter = IF(@sql_check = 0,
    'ALTER TABLE `smart_wallet_trade_event`
       ADD COLUMN `trace_id` VARCHAR(32) NULL DEFAULT NULL
       COMMENT ''跟单链路追踪 ID, 派生自 trade_event.id, 格式 tev_%012d''
       AFTER `external_trade_id`',
    'SELECT ''Column trace_id already exists in smart_wallet_trade_event''');
PREPARE stmt FROM @sql_alter;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_check = (SELECT COUNT(*) FROM information_schema.STATISTICS
                  WHERE TABLE_SCHEMA = DATABASE()
                    AND TABLE_NAME   = 'smart_wallet_trade_event'
                    AND INDEX_NAME   = 'idx_trace_id');

SET @sql_idx = IF(@idx_check = 0,
    'ALTER TABLE `smart_wallet_trade_event` ADD INDEX `idx_trace_id` (`trace_id`)',
    'SELECT ''Index idx_trace_id already exists in smart_wallet_trade_event''');
PREPARE stmt FROM @sql_idx;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;


-- ----------------------------------------------------------------------------
-- 2. smart_wallet_follow_task  增加 trace_id (信号->意图层)
-- ----------------------------------------------------------------------------
SET @sql_check = (SELECT COUNT(*) FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = DATABASE()
                    AND TABLE_NAME   = 'smart_wallet_follow_task'
                    AND COLUMN_NAME  = 'trace_id');

SET @sql_alter = IF(@sql_check = 0,
    'ALTER TABLE `smart_wallet_follow_task`
       ADD COLUMN `trace_id` VARCHAR(32) NULL DEFAULT NULL
       COMMENT ''继承自 source_event_id 对应 trade_event.trace_id''
       AFTER `source_event_id`',
    'SELECT ''Column trace_id already exists in smart_wallet_follow_task''');
PREPARE stmt FROM @sql_alter;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_check = (SELECT COUNT(*) FROM information_schema.STATISTICS
                  WHERE TABLE_SCHEMA = DATABASE()
                    AND TABLE_NAME   = 'smart_wallet_follow_task'
                    AND INDEX_NAME   = 'idx_trace_id');

SET @sql_idx = IF(@idx_check = 0,
    'ALTER TABLE `smart_wallet_follow_task` ADD INDEX `idx_trace_id` (`trace_id`)',
    'SELECT ''Index idx_trace_id already exists in smart_wallet_follow_task''');
PREPARE stmt FROM @sql_idx;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;


-- ----------------------------------------------------------------------------
-- 3. smart_wallet_order  增加 trace_id (CLOB 单)
-- ----------------------------------------------------------------------------
SET @sql_check = (SELECT COUNT(*) FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = DATABASE()
                    AND TABLE_NAME   = 'smart_wallet_order'
                    AND COLUMN_NAME  = 'trace_id');

SET @sql_alter = IF(@sql_check = 0,
    'ALTER TABLE `smart_wallet_order`
       ADD COLUMN `trace_id` VARCHAR(32) NULL DEFAULT NULL
       COMMENT ''继承自 follow_task.trace_id, 拆单/重发可能多行同 trace''
       AFTER `follow_task_id`',
    'SELECT ''Column trace_id already exists in smart_wallet_order''');
PREPARE stmt FROM @sql_alter;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_check = (SELECT COUNT(*) FROM information_schema.STATISTICS
                  WHERE TABLE_SCHEMA = DATABASE()
                    AND TABLE_NAME   = 'smart_wallet_order'
                    AND INDEX_NAME   = 'idx_trace_id');

SET @sql_idx = IF(@idx_check = 0,
    'ALTER TABLE `smart_wallet_order` ADD INDEX `idx_trace_id` (`trace_id`)',
    'SELECT ''Index idx_trace_id already exists in smart_wallet_order''');
PREPARE stmt FROM @sql_idx;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;


-- ----------------------------------------------------------------------------
-- 4. smart_wallet_trade_order  增加 trace_id (聚合订单)
-- ----------------------------------------------------------------------------
SET @sql_check = (SELECT COUNT(*) FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = DATABASE()
                    AND TABLE_NAME   = 'smart_wallet_trade_order'
                    AND COLUMN_NAME  = 'trace_id');

SET @sql_alter = IF(@sql_check = 0,
    'ALTER TABLE `smart_wallet_trade_order`
       ADD COLUMN `trace_id` VARCHAR(32) NULL DEFAULT NULL
       COMMENT ''继承自 source_follow_task_id 对应 follow_task.trace_id''
       AFTER `source_follow_task_id`',
    'SELECT ''Column trace_id already exists in smart_wallet_trade_order''');
PREPARE stmt FROM @sql_alter;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_check = (SELECT COUNT(*) FROM information_schema.STATISTICS
                  WHERE TABLE_SCHEMA = DATABASE()
                    AND TABLE_NAME   = 'smart_wallet_trade_order'
                    AND INDEX_NAME   = 'idx_trace_id');

SET @sql_idx = IF(@idx_check = 0,
    'ALTER TABLE `smart_wallet_trade_order` ADD INDEX `idx_trace_id` (`trace_id`)',
    'SELECT ''Index idx_trace_id already exists in smart_wallet_trade_order''');
PREPARE stmt FROM @sql_idx;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;


-- ----------------------------------------------------------------------------
-- 5. smart_wallet_position  增加 origin_trace_id (仓位首次创建时的 trace)
--    语义: 仓位可由多次 trade_order 合并而成, 仅记录"开仓"那笔 trace_id
-- ----------------------------------------------------------------------------
SET @sql_check = (SELECT COUNT(*) FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = DATABASE()
                    AND TABLE_NAME   = 'smart_wallet_position'
                    AND COLUMN_NAME  = 'origin_trace_id');

SET @sql_alter = IF(@sql_check = 0,
    'ALTER TABLE `smart_wallet_position`
       ADD COLUMN `origin_trace_id` VARCHAR(32) NULL DEFAULT NULL
       COMMENT ''首次开仓 trace_id (此后合并仓位不覆盖)''
       AFTER `source`',
    'SELECT ''Column origin_trace_id already exists in smart_wallet_position''');
PREPARE stmt FROM @sql_alter;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_check = (SELECT COUNT(*) FROM information_schema.STATISTICS
                  WHERE TABLE_SCHEMA = DATABASE()
                    AND TABLE_NAME   = 'smart_wallet_position'
                    AND INDEX_NAME   = 'idx_origin_trace_id');

SET @sql_idx = IF(@idx_check = 0,
    'ALTER TABLE `smart_wallet_position` ADD INDEX `idx_origin_trace_id` (`origin_trace_id`)',
    'SELECT ''Index idx_origin_trace_id already exists in smart_wallet_position''');
PREPARE stmt FROM @sql_idx;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;


-- ============================================================================
-- 验收 SQL (执行后人工检查)
-- ============================================================================
-- SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, COLUMN_COMMENT
--   FROM information_schema.COLUMNS
--  WHERE TABLE_SCHEMA = DATABASE()
--    AND COLUMN_NAME IN ('trace_id', 'origin_trace_id')
--    AND TABLE_NAME LIKE 'smart_wallet_%'
--  ORDER BY TABLE_NAME;
--
-- SELECT TABLE_NAME, INDEX_NAME, COLUMN_NAME
--   FROM information_schema.STATISTICS
--  WHERE TABLE_SCHEMA = DATABASE()
--    AND INDEX_NAME IN ('idx_trace_id', 'idx_origin_trace_id')
--    AND TABLE_NAME LIKE 'smart_wallet_%'
--  ORDER BY TABLE_NAME, INDEX_NAME;

-- ─────────────────────────────────────────────────────────────────
-- ── 子段: smart_wallet_behavior_vector_migration.sql ──
-- ─────────────────────────────────────────────────────────────────
-- ============================================================================
-- Migration: 方案 B (W2-T6 优化) — 让 BehaviorProfile snapshot 同时持有
--            LeaderBehaviorDrift 所需的特征向量 centroid, 让 Drift service
--            从"每次 cache miss 重算 500 trade_event"降为"O(1) row 查询".
--
-- 设计:
--   * `behavior_vector_json` LONGTEXT — JSON 序列化 {"centroid":[...], "sampleCount":N}
--   * `behavior_vector_computed_at` DATETIME — vector 计算时间, 用于陈旧度判断
--   * 默认 NULL (老数据兼容); LeaderBehaviorDriftService 在 null/陈旧时 fallback 现有算法
--
-- 决议:
--   docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第十五节
--
-- 幂等: information_schema 检查, 重复执行不报错.
-- ============================================================================

-- 1. 列 behavior_vector_json
SET @sql_check = (SELECT COUNT(*) FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = DATABASE()
                    AND TABLE_NAME   = 'smart_wallet_behavior_profile'
                    AND COLUMN_NAME  = 'behavior_vector_json');

SET @sql_alter = IF(@sql_check = 0,
    'ALTER TABLE `smart_wallet_behavior_profile`
       ADD COLUMN `behavior_vector_json` LONGTEXT NULL DEFAULT NULL
       COMMENT ''方案 B: LeaderBehaviorDrift 共享的特征向量 centroid JSON''
       AFTER `summary`',
    'SELECT ''Column behavior_vector_json already exists in smart_wallet_behavior_profile''');
PREPARE stmt FROM @sql_alter;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. 列 behavior_vector_computed_at
SET @sql_check2 = (SELECT COUNT(*) FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE()
                     AND TABLE_NAME   = 'smart_wallet_behavior_profile'
                     AND COLUMN_NAME  = 'behavior_vector_computed_at');

SET @sql_alter2 = IF(@sql_check2 = 0,
    'ALTER TABLE `smart_wallet_behavior_profile`
       ADD COLUMN `behavior_vector_computed_at` DATETIME NULL DEFAULT NULL
       COMMENT ''方案 B: vector centroid 最近一次计算时间, 用于 Drift fallback 判断''
       AFTER `behavior_vector_json`',
    'SELECT ''Column behavior_vector_computed_at already exists''');
PREPARE stmt FROM @sql_alter2;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ─────────────────────────────────────────────────────────────────
-- ── 子段: smart_wallet_signal_shadow_estimator_source_migration.sql ──
-- ─────────────────────────────────────────────────────────────────
-- =================================================================
-- 第十四批: smart_wallet_signal_shadow 增加 entry_estimator_source 列
--
-- 来源决议: docs/polymarket/copy-trading-improvement-decision-2026-05-16.md
--          二十一节 (第十四批 #1)
-- 用途: ShadowVsLive 周报按 VWAP / PROXY 两种 bps 估算 source 分桶,
--      量化 VWAP estimator (第十三批接入) 与既有 PROXY 算法的胜率差异.
-- 风格对齐: smart_wallet_profile_discovery_enrichment.sql (直接 ALTER ADD COLUMN)
-- =================================================================

ALTER TABLE `smart_wallet_signal_shadow`
    ADD COLUMN `entry_estimator_source` VARCHAR(32) DEFAULT NULL
        COMMENT '价格冲击 bps 来源: VWAP(OK) / VWAP(DRY_FALLBACK) / PROXY / null(未启用门控)'
        AFTER `shadow_entry_slippage_bps`,
    ADD INDEX `idx_estimator_source` (`entry_estimator_source`);

-- ─────────────────────────────────────────────────────────────────
-- ── 子段: smart_wallet_order_analysis_migration.sql 的 ALTER 部分 ──
-- ── (CREATE TABLE smart_wallet_order_analysis 已合入 arbitrage_schema.sql)
-- ─────────────────────────────────────────────────────────────────
DROP PROCEDURE IF EXISTS `sp_smart_wallet_behavior_profile_5_16_alter`;

DELIMITER $$
CREATE PROCEDURE `sp_smart_wallet_behavior_profile_5_16_alter`()
BEGIN
    DECLARE v_exists INT DEFAULT 0;
    DECLARE v_schema_name VARCHAR(128) DEFAULT DATABASE();

    SELECT COUNT(1) INTO v_exists FROM information_schema.columns
    WHERE table_schema = v_schema_name AND table_name = 'smart_wallet_behavior_profile' AND column_name = 'win_source_type';
    IF v_exists = 0 THEN
        ALTER TABLE `smart_wallet_behavior_profile`
            ADD COLUMN `win_source_type` VARCHAR(64) NULL COMMENT '胜率来源类型' AFTER `loss_early_exit_rate`;
    END IF;

    SELECT COUNT(1) INTO v_exists FROM information_schema.columns
    WHERE table_schema = v_schema_name AND table_name = 'smart_wallet_behavior_profile' AND column_name = 'latency_alpha_rate';
    IF v_exists = 0 THEN
        ALTER TABLE `smart_wallet_behavior_profile`
            ADD COLUMN `latency_alpha_rate` DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '短周期 alpha 比例' AFTER `win_source_type`;
    END IF;

    SELECT COUNT(1) INTO v_exists FROM information_schema.columns
    WHERE table_schema = v_schema_name AND table_name = 'smart_wallet_behavior_profile' AND column_name = 'swing_alpha_rate';
    IF v_exists = 0 THEN
        ALTER TABLE `smart_wallet_behavior_profile`
            ADD COLUMN `swing_alpha_rate` DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '中周期 alpha 比例' AFTER `latency_alpha_rate`;
    END IF;

    SELECT COUNT(1) INTO v_exists FROM information_schema.columns
    WHERE table_schema = v_schema_name AND table_name = 'smart_wallet_behavior_profile' AND column_name = 'settlement_win_rate';
    IF v_exists = 0 THEN
        ALTER TABLE `smart_wallet_behavior_profile`
            ADD COLUMN `settlement_win_rate` DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '等待结算胜率' AFTER `swing_alpha_rate`;
    END IF;

    SELECT COUNT(1) INTO v_exists FROM information_schema.columns
    WHERE table_schema = v_schema_name AND table_name = 'smart_wallet_behavior_profile' AND column_name = 'early_settlement_rate';
    IF v_exists = 0 THEN
        ALTER TABLE `smart_wallet_behavior_profile`
            ADD COLUMN `early_settlement_rate` DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '提前结算占比' AFTER `loss_early_exit_rate`;
    END IF;

    SELECT COUNT(1) INTO v_exists FROM information_schema.columns
    WHERE table_schema = v_schema_name AND table_name = 'smart_wallet_behavior_profile' AND column_name = 'median_hold_minutes';
    IF v_exists = 0 THEN
        ALTER TABLE `smart_wallet_behavior_profile`
            ADD COLUMN `median_hold_minutes` DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '持仓时长中位数(分钟)' AFTER `settlement_win_rate`;
    END IF;

    SELECT COUNT(1) INTO v_exists FROM information_schema.columns
    WHERE table_schema = v_schema_name AND table_name = 'smart_wallet_behavior_profile' AND column_name = 'stability_score';
    IF v_exists = 0 THEN
        ALTER TABLE `smart_wallet_behavior_profile`
            ADD COLUMN `stability_score` DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '稳定性分数' AFTER `median_hold_minutes`;
    END IF;

    SELECT COUNT(1) INTO v_exists FROM information_schema.columns
    WHERE table_schema = v_schema_name AND table_name = 'smart_wallet_behavior_profile' AND column_name = 'quality_score';
    IF v_exists = 0 THEN
        ALTER TABLE `smart_wallet_behavior_profile`
            ADD COLUMN `quality_score` DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '地址质量分数' AFTER `stability_score`;
    END IF;
END $$
DELIMITER ;

CALL `sp_smart_wallet_behavior_profile_5_16_alter`();
DROP PROCEDURE IF EXISTS `sp_smart_wallet_behavior_profile_5_16_alter`;

-- ─────────────────────────────────────────────────────────────────
-- ── 子段: weather_arbitrage_migration.sql 的 ALTER 部分 ──
-- ── (6 个 weather_* CREATE TABLE 已合入 arbitrage_schema.sql)
-- ─────────────────────────────────────────────────────────────────
-- smart_wallet_position 加 source 列, 区分 FOLLOW_TRADE / WEATHER
-- 注: 原 weather_arbitrage_migration 没有 IF NOT EXISTS guard, 重跑会报错;
-- 这里包一层 information_schema 检查保持本批次 diff 文件可幂等.
DROP PROCEDURE IF EXISTS `sp_smart_wallet_position_source_alter`;

DELIMITER $$
CREATE PROCEDURE `sp_smart_wallet_position_source_alter`()
BEGIN
    DECLARE v_exists INT DEFAULT 0;
    SELECT COUNT(1) INTO v_exists FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'smart_wallet_position' AND column_name = 'source';
    IF v_exists = 0 THEN
        ALTER TABLE `smart_wallet_position`
            ADD COLUMN `source` VARCHAR(32) NOT NULL DEFAULT 'FOLLOW_TRADE'
                COMMENT 'FOLLOW_TRADE / WEATHER'
                AFTER `status`;
    END IF;
END $$
DELIMITER ;

CALL `sp_smart_wallet_position_source_alter`();
DROP PROCEDURE IF EXISTS `sp_smart_wallet_position_source_alter`;
