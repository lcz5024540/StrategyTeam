-- ============================================================
-- 对冲策略：PAPER / LIVE 模式分流迁移
-- 在 hedge_position / hedge_order / hedge_arbitrage_log 三张表上
-- 增加 mode 字段，以便模拟仓（PAPER）与实盘仓（LIVE）在同一套
-- 表结构中共存，同时保持现有业务逻辑向后兼容。
--
-- 兼容策略：
--   * 默认值 mode='LIVE'：历史记录语义不变；
--   * hedge_position 的原 uk_market_id 唯一键替换为 (market_id, mode)
--     复合唯一键，允许同一市场同时存在一个 PAPER 和一个 LIVE 仓位；
--   * 新增 (mode, status) 复合索引，便于 PAPER/LIVE 维度的统计查询。
--
-- 执行前请先备份，然后按顺序执行。
-- ============================================================

-- 1) hedge_position
ALTER TABLE `hedge_position`
    ADD COLUMN `mode` VARCHAR(8) NOT NULL DEFAULT 'LIVE'
        COMMENT '执行模式: PAPER 模拟仓 | LIVE 实盘仓' AFTER `status`;

-- 删除旧的 market_id 唯一键（若存在），替换为 (market_id, mode) 复合唯一
ALTER TABLE `hedge_position` DROP INDEX `uk_market_id`;
ALTER TABLE `hedge_position`
    ADD UNIQUE KEY `uk_market_id_mode` (`market_id`, `mode`);

-- 辅助索引：按 mode + status 过滤（风控敞口查询、Dashboard 统计）
ALTER TABLE `hedge_position`
    ADD INDEX `idx_mode_status` (`mode`, `status`);


-- 2) hedge_order
ALTER TABLE `hedge_order`
    ADD COLUMN `mode` VARCHAR(8) NOT NULL DEFAULT 'LIVE'
        COMMENT '执行模式: PAPER | LIVE' AFTER `status`;

ALTER TABLE `hedge_order`
    ADD INDEX `idx_mode_status` (`mode`, `status`);


-- 3) hedge_arbitrage_log
ALTER TABLE `hedge_arbitrage_log`
    ADD COLUMN `mode` VARCHAR(8) NOT NULL DEFAULT 'LIVE'
        COMMENT '扫描模式: PAPER | LIVE';

ALTER TABLE `hedge_arbitrage_log`
    ADD INDEX `idx_mode_scanned_at` (`mode`, `scanned_at`);
