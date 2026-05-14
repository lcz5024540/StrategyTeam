-- Phase 4.4: 单地址订单窗口统计快照表
-- 由 job 端 ProfileOrderStatsSnapshotJob 定期写入；web 端读
CREATE TABLE IF NOT EXISTS `smart_wallet_order_stats_snapshot` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `wallet_address` VARCHAR(64) NOT NULL,
    `source` VARCHAR(32) NOT NULL DEFAULT 'POLYMARKET',
    `window` VARCHAR(16) NOT NULL,
    `generated_at` DATETIME NULL,
    `closed_order_count` INT NULL,
    `win_count` INT NULL,
    `loss_count` INT NULL,
    `draw_count` INT NULL,
    `closed_win_rate` DECIMAL(10, 4) NULL,
    `closed_realized_pnl_usdc` DECIMAL(20, 4) NULL,
    `active_unrealized_pnl_usdc` DECIMAL(20, 4) NULL,
    `net_pnl_usdc` DECIMAL(20, 4) NULL,
    `average_roi_rate` DECIMAL(10, 4) NULL,
    `active_position_count` INT NULL,
    `pending_settlement_count` INT NULL,
    `early_settlement_rate` DECIMAL(10, 4) NULL,
    `settlement_win_rate` DECIMAL(10, 4) NULL,
    `created_at` DATETIME NULL,
    `updated_at` DATETIME NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_wallet_source_window` (`wallet_address`, `source`, `window`),
    KEY `idx_generated_at` (`generated_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='单地址订单窗口统计快照（Phase 4.4）';
