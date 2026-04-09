-- =================================================================
-- Polymarket 订单轮询字段补齐
-- 目标：
-- - 为 smart_wallet_order 补充轮询状态字段
-- - 为轮询任务补充查询索引
-- =================================================================

ALTER TABLE `smart_wallet_order`
    ADD COLUMN IF NOT EXISTS `next_poll_at` TIMESTAMP NULL COMMENT '下一次轮询时间' AFTER `filled_at`,
    ADD COLUMN IF NOT EXISTS `poll_attempts` INT NOT NULL DEFAULT 0 COMMENT '轮询次数' AFTER `next_poll_at`;

CREATE INDEX `idx_order_poll` ON `smart_wallet_order` (`status`, `next_poll_at`);
