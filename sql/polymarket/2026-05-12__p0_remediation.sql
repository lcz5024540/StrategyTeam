-- =================================================================
-- Batch diff: 2026-05-12 P0 跟单链路漏洞整改
--
-- 合并自:
--   1. p0-remediation-ddl.sql                       (P0-2 intent_key)
--   2. smart_wallet_follow_shadow_coverage_migration.sql (P0-3 影子覆盖字段)
--
-- 整体说明: 2026-05-12 P0 漏洞整改 — 给 smart_wallet_follow_task 加意图聚合 key
-- (intent_key) + 影子拒绝原因覆盖字段 (primary_skip_reason / skip_reason_tags /
-- would_have_skipped_reason / idx_follow_shadow_reason).
-- =================================================================


-- ─────────────────────────────────────────────────────────────────
-- ── 子段: p0-remediation-ddl.sql ──
-- ─────────────────────────────────────────────────────────────────
-- ============================================================
-- P0 跟单链路漏洞整改 DDL
-- 日期: 2026-05-12
-- ============================================================

-- P0-2: smart_wallet_follow_task 新增意图聚合字段
ALTER TABLE smart_wallet_follow_task
    ADD COLUMN intent_key VARCHAR(512) DEFAULT NULL COMMENT 'P0-2: 意图聚合标识 wallet:market:outcome:side:windowBucket' AFTER would_have_skipped_reason,
    ADD COLUMN fragment_count INT DEFAULT NULL COMMENT 'P0-2: 该意图下聚合的碎片事件数量' AFTER intent_key;

-- 索引: 按 intentKey 查找同意图任务
CREATE INDEX idx_follow_task_intent_key ON smart_wallet_follow_task (intent_key);

-- 索引: 意图聚合窗口查询 (wallet + market_info_id + side + status + created_at)
CREATE INDEX idx_follow_task_intent_agg ON smart_wallet_follow_task (wallet_address, market_info_id, side, status, created_at);

-- ─────────────────────────────────────────────────────────────────
-- ── 子段: smart_wallet_follow_shadow_coverage_migration.sql ──
-- ─────────────────────────────────────────────────────────────────
ALTER TABLE `smart_wallet_follow_task`
    ADD COLUMN `primary_skip_reason` VARCHAR(64) DEFAULT NULL COMMENT '主拒绝原因：NON_TRADABLE/PRICE/PROFILE_BEHAVIOR/CAPACITY/STALE/SIZE/OTHER' AFTER `error_message`,
    ADD COLUMN `skip_reason_tags` VARCHAR(512) DEFAULT NULL COMMENT '全部拒绝原因标签，逗号分隔' AFTER `primary_skip_reason`,
    ADD COLUMN `would_have_skipped_reason` TEXT DEFAULT NULL COMMENT 'PAPER_SHADOW 观测记录对应的原始软门控拒绝原因' AFTER `skip_reason_tags`,
    ADD INDEX `idx_follow_shadow_reason` (`status`, `primary_skip_reason`, `wallet_address`);
