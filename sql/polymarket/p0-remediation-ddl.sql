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
