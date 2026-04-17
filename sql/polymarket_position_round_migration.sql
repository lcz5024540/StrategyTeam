-- 目的：
-- 1. 为 smart_wallet_order / smart_wallet_fill 增加 position_id，明确一笔成交属于哪一轮持仓
-- 2. 去掉 smart_wallet_position 上 execution_address + market_id + outcome 的唯一约束
--    防止历史已关闭仓位和新一轮同市场同 outcome 开仓被错误合并

ALTER TABLE `smart_wallet_order`
    ADD COLUMN `position_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '关联持仓ID，标识该订单属于哪一轮仓位' AFTER `follow_task_id`;

ALTER TABLE `smart_wallet_fill`
    ADD COLUMN `position_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '关联持仓ID，标识该成交属于哪一轮仓位' AFTER `order_id`;

CREATE INDEX `idx_order_position` ON `smart_wallet_order` (`position_id`, `placed_at`);
CREATE INDEX `idx_fill_position` ON `smart_wallet_fill` (`position_id`, `filled_at`);

ALTER TABLE `smart_wallet_position`
    DROP INDEX `uk_position_execution_market_outcome`;

CREATE INDEX `idx_position_execution_market_outcome`
    ON `smart_wallet_position` (`execution_address`, `market_id`, `outcome`, `status`, `last_filled_at`);
