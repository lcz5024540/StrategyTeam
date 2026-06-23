-- =================================================================
-- 数据库表结构设计 (Database Schema Design)
-- 当前文件保留通用业务表以及与单交易所套利主表无重复的内容。
-- 单交易所资金费率套利主表结构已统一迁移至 funding_rate_arbitrage.sql。
-- =================================================================

-- 1. Polymarket 聪明钱包画像表
CREATE TABLE IF NOT EXISTS `smart_wallet_profile` (
    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    `wallet_address` VARCHAR(128) NOT NULL COMMENT '钱包地址',
    `chain` VARCHAR(32) NOT NULL DEFAULT 'polygon' COMMENT '链',
    `alias` VARCHAR(128) DEFAULT NULL COMMENT '别名',
    `source` VARCHAR(32) NOT NULL DEFAULT 'POLYMARKET' COMMENT '来源',
    `win_rate` DECIMAL(10, 4) NOT NULL DEFAULT 0 COMMENT '正确率 0-1',
    `realized_pnl_usdc` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '累计已实现收益',
    `total_trades` INT NOT NULL DEFAULT 0 COMMENT '交易次数',
    `average_size_usdc` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '平均仓位',
    `average_roi_rate` DECIMAL(10, 4) NOT NULL DEFAULT 0 COMMENT '平均收益率 0-1',
    `max_drawdown_rate` DECIMAL(10, 4) NOT NULL DEFAULT 0 COMMENT '最大回撤 0-1',
    `wallet_score` DECIMAL(10, 4) NOT NULL DEFAULT 0 COMMENT '综合评分 0-1',
    `qualified` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否满足 smart wallet 条件',
    `watch_enabled` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否开启实时跟单监控',
    `last_trade_at` TIMESTAMP NULL COMMENT '最后一笔交易时间',
    `last_evaluated_at` TIMESTAMP NULL COMMENT '最后评估时间',
    `last_discovered_at` TIMESTAMP NULL COMMENT '最近一次 discovery 命中时间',
    `last_discovery_query` VARCHAR(128) DEFAULT NULL COMMENT '最近命中的 leaderboard 查询',
    `leaderboard_hit_count_30d` INT NOT NULL DEFAULT 0 COMMENT '近30日 leaderboard 命中次数',
    `dominant_session` VARCHAR(16) DEFAULT NULL COMMENT 'ASIA/EU/US/MIXED',
    `asia_trade_ratio_30d` DECIMAL(10, 4) NOT NULL DEFAULT 0 COMMENT '近30日亚洲时段成交占比',
    `eu_trade_ratio_30d` DECIMAL(10, 4) NOT NULL DEFAULT 0 COMMENT '近30日欧洲时段成交占比',
    `us_trade_ratio_30d` DECIMAL(10, 4) NOT NULL DEFAULT 0 COMMENT '近30日美洲时段成交占比',
    `active_hour_histogram_json` TEXT DEFAULT NULL COMMENT '近30日按UTC小时聚合的成交直方图',
    `recent_win_rate_7d` DECIMAL(10, 4) DEFAULT NULL COMMENT '近7日胜率',
    `recent_win_rate_30d` DECIMAL(10, 4) DEFAULT NULL COMMENT '近30日胜率',
    `recent_trade_count_7d` INT NOT NULL DEFAULT 0 COMMENT '近7日交易样本数',
    `recent_trade_count_30d` INT NOT NULL DEFAULT 0 COMMENT '近30日交易样本数',
    `observe_cooldown_until` TIMESTAMP NULL COMMENT '近期退化观察冷却截止时间',
    `observe_cooldown_reason` VARCHAR(255) DEFAULT NULL COMMENT '近期退化观察原因',
    `manual_watch_enabled` TINYINT(1) NULL COMMENT '人工覆盖是否允许进入 executable watchlist',
    `manual_recommended_exit_lane` VARCHAR(64) NULL COMMENT '人工覆盖的退出风格 lane',
    `manual_override_note` VARCHAR(255) NULL COMMENT '人工覆盖备注',
    `manual_override_updated_at` DATETIME NULL COMMENT '人工覆盖更新时间',
    `hot_retention_until` TIMESTAMP NULL COMMENT 'HOT 保留期截止时间',
    `hot_retention_reason` VARCHAR(255) DEFAULT NULL COMMENT 'HOT 保留原因',
    `monitor_tier` VARCHAR(16) DEFAULT NULL COMMENT '地址监控层级 HOT/WARM/COLD',
    `monitor_reason` VARCHAR(255) DEFAULT NULL COMMENT '监控层级判定原因',
    `last_event_sync_at` TIMESTAMP NULL COMMENT '最近一次 HOT 事件同步时间',
    `last_metrics_refresh_at` TIMESTAMP NULL COMMENT '最近一次 WARM 画像刷新时间',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY `uk_wallet_chain_source` (`wallet_address`, `chain`, `source`),
    INDEX `idx_profile_qualified` (`qualified`, `watch_enabled`, `wallet_score`),
    INDEX `idx_profile_monitor_tier` (`monitor_tier`, `wallet_score`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Polymarket聪明钱包画像表';

-- 2. Polymarket 钱包开单事件表
CREATE TABLE IF NOT EXISTS `smart_wallet_trade_event` (
    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    `wallet_address` VARCHAR(128) NOT NULL COMMENT '钱包地址',
    `chain` VARCHAR(32) NOT NULL DEFAULT 'polygon' COMMENT '链',
    `source` VARCHAR(32) NOT NULL DEFAULT 'POLYMARKET' COMMENT '来源',
    `external_trade_id` VARCHAR(128) NOT NULL COMMENT '外部交易ID',
    `market_id` VARCHAR(128) NOT NULL COMMENT '市场ID',
    `question` VARCHAR(512) DEFAULT NULL COMMENT '问题描述',
    `outcome` VARCHAR(128) NOT NULL COMMENT 'Polymarket outcome label',
    `side` VARCHAR(16) NOT NULL COMMENT 'BUY/SELL',
    `price` DECIMAL(10, 4) NOT NULL DEFAULT 0 COMMENT '价格',
    `size_shares` DECIMAL(20, 8) DEFAULT NULL COMMENT '成交份额数量',
    `size_usdc` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '名义金额',
    `opened_at` TIMESTAMP NOT NULL COMMENT '开单时间',
    `processed` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否已生成跟单任务',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY `uk_trade_source_id` (`source`, `external_trade_id`),
    INDEX `idx_trade_wallet_time` (`wallet_address`, `opened_at`),
    INDEX `idx_trade_processed` (`processed`, `opened_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Polymarket钱包开单事件表';

-- 3. Polymarket 跟单任务表
CREATE TABLE IF NOT EXISTS `smart_wallet_follow_task` (
    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    `wallet_address` VARCHAR(128) NOT NULL COMMENT '触发钱包地址',
    `entity_id`   VARCHAR(64)  DEFAULT NULL COMMENT '触发钱包对应的实体ID',
    `source_event_id` BIGINT UNSIGNED NOT NULL COMMENT '来源交易事件ID',
    `follow_candidate_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '关联的候选跟单信号ID',
    `market_info_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '关联 smart_wallet_market_info.id',
    `side` VARCHAR(16) NOT NULL COMMENT 'BUY/SELL',
    `trigger_price` DECIMAL(10, 4) NOT NULL DEFAULT 0 COMMENT '触发价格',
    `trigger_size_usdc` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '触发钱包仓位',
    `recommended_stake_usdc` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '建议跟单金额',
    `status` VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/FOLLOWED/SKIPPED/FAILED',
    `execution_mode` VARCHAR(16) NOT NULL DEFAULT 'PAPER' COMMENT 'PAPER/LIVE',
    `external_order_id` VARCHAR(128) DEFAULT NULL COMMENT '跟单订单ID',
    `error_message` TEXT DEFAULT NULL COMMENT '错误信息',
    `primary_skip_reason` VARCHAR(64) DEFAULT NULL COMMENT '主拒绝原因：NON_TRADABLE/PRICE/PROFILE_BEHAVIOR/CAPACITY/STALE/SIZE/OTHER',
    `skip_reason_tags` VARCHAR(512) DEFAULT NULL COMMENT '全部拒绝原因标签，逗号分隔',
    `would_have_skipped_reason` TEXT DEFAULT NULL COMMENT 'PAPER_SHADOW 观测记录对应的原始软门控拒绝原因',
    `scheduled_at` TIMESTAMP NOT NULL COMMENT '计划执行时间',
    `executed_at` TIMESTAMP NULL COMMENT '实际执行时间',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY `uk_follow_source_event` (`source_event_id`),
    INDEX `idx_follow_status_time` (`status`, `scheduled_at`),
    INDEX `idx_follow_status_created` (`status`, `created_at` DESC),
    INDEX `idx_follow_task_market_info` (`market_info_id`),
    INDEX `idx_follow_entity` (`entity_id`),
    INDEX `idx_follow_shadow_reason` (`status`, `primary_skip_reason`, `wallet_address`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Polymarket跟单任务表';

-- 4. Polymarket 跟单订单表
CREATE TABLE IF NOT EXISTS `smart_wallet_order` (
    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    `follow_task_id` BIGINT UNSIGNED NOT NULL COMMENT '跟单任务ID',
    `market_info_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '关联 smart_wallet_market_info.id',
    `position_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '关联持仓ID，标识该订单属于哪一轮仓位',
    `wallet_address` VARCHAR(128) NOT NULL COMMENT '触发钱包地址',
    `execution_mode` VARCHAR(16) NOT NULL DEFAULT 'PAPER' COMMENT 'PAPER/LIVE',
    `execution_address` VARCHAR(128) DEFAULT NULL COMMENT '执行钱包地址',
    `external_order_id` VARCHAR(128) DEFAULT NULL COMMENT '外部订单ID',
    `side` VARCHAR(16) NOT NULL COMMENT 'BUY/SELL',
    `order_type` VARCHAR(16) NOT NULL DEFAULT 'GTC' COMMENT '订单类型',
    `status` VARCHAR(32) NOT NULL DEFAULT 'SUBMITTED' COMMENT 'SUBMITTED/FILLED/CANCELED/FAILED',
    `price` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '委托价格',
    `size` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '份额数量',
    `notional_usdc` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '委托名义USDC',
    `fee_usdc` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '手续费',
    `trading_fee_usdc` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '交易手续费USDC',
    `fee_pol` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '手续费POL',
    `gas_fee_usdc` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '链上手续费USDC折算',
    `realized_pnl_usdc` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '该订单已实现收益',
    `exit_reason` VARCHAR(64) DEFAULT NULL COMMENT '提前退出原因',
    `error_message` TEXT DEFAULT NULL COMMENT '错误信息',
    `placed_at` TIMESTAMP NOT NULL COMMENT '下单时间',
    `filled_at` TIMESTAMP NULL COMMENT '成交时间',
    `next_poll_at` TIMESTAMP NULL COMMENT '下一次轮询时间',
    `poll_attempts` INT NOT NULL DEFAULT 0 COMMENT '轮询次数',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    UNIQUE KEY `uk_order_external` (`external_order_id`),
    INDEX `idx_order_follow_task` (`follow_task_id`),
    INDEX `idx_order_position` (`position_id`, `placed_at`),
    INDEX `idx_order_market_status` (`market_info_id`, `status`),
    INDEX `idx_order_poll` (`status`, `next_poll_at`),
    INDEX `idx_order_market_info` (`market_info_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Polymarket跟单订单表';

-- 5. Polymarket 成交明细表
CREATE TABLE IF NOT EXISTS `smart_wallet_fill` (
    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    `market_info_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '关联 smart_wallet_market_info.id',
    `order_id` BIGINT UNSIGNED NOT NULL COMMENT '订单ID',
    `position_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '关联持仓ID，标识该成交属于哪一轮仓位',
    `follow_task_id` BIGINT UNSIGNED NOT NULL COMMENT '跟单任务ID',
    `wallet_address` VARCHAR(128) NOT NULL COMMENT '触发钱包地址',
    `execution_address` VARCHAR(128) DEFAULT NULL COMMENT '执行钱包地址',
    `side` VARCHAR(16) NOT NULL COMMENT 'BUY/SELL',
    `price` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '成交价格',
    `size` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '成交份额',
    `notional_usdc` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '成交名义USDC',
    `fee_usdc` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '手续费',
    `trading_fee_usdc` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '交易手续费USDC',
    `fee_pol` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '手续费POL',
    `gas_fee_usdc` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '链上手续费USDC折算',
    `realized_pnl_usdc` DECIMAL(20, 8) DEFAULT NULL COMMENT 'SELL 成交实现 P&L（USDC）；BUY 成交为 NULL',
    `filled_at` TIMESTAMP NOT NULL COMMENT '成交时间',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX `idx_fill_order` (`order_id`),
    INDEX `idx_fill_position` (`position_id`, `filled_at`),
    INDEX `idx_fill_market_time` (`market_info_id`, `filled_at`),
    INDEX `idx_fill_market_info` (`market_info_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Polymarket成交明细表';

-- 6. Polymarket 持仓表
CREATE TABLE IF NOT EXISTS `smart_wallet_position` (
    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    `market_info_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '关联 smart_wallet_market_info.id',
    `execution_address` VARCHAR(128) NOT NULL COMMENT '执行钱包地址',
    `net_side` VARCHAR(16) NOT NULL DEFAULT 'BUY' COMMENT '净方向',
    `quantity` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '净份额数量',
    `average_entry_price` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '平均开仓成本',
    `cost_basis_usdc` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '持仓成本',
    `original_cost_basis_usdc` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '原始持仓成本(用于结算后展示)',
    `fee_pol_total` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '累计手续费POL',
    `realized_pnl_usdc` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '已实现收益',
    `unrealized_pnl_usdc` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '未实现收益',
    -- NOTE: entity 上还有一个 claimableProceedsUsdc 字段, 但它标了 @TableField(exist=false),
    --       仅作 view 传递使用, 不落库. 业务实时算, 见 SmartWalletAccountingService.lockedClaimProceedsForPosition().
    `last_mark_price` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '最新估值价格',
    `opened_at` TIMESTAMP NULL COMMENT '首次开仓时间',
    `last_filled_at` TIMESTAMP NULL COMMENT '最近成交时间',
    `closed_at` TIMESTAMP NULL COMMENT '平仓时间',
    `status` VARCHAR(32) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/PARTIALLY_CLOSED/CLOSED/RESOLVED_UNCLAIMED/CLAIMED',
    `source` VARCHAR(32) NOT NULL DEFAULT 'FOLLOW_TRADE' COMMENT '仓位来源：FOLLOW_TRADE/WEATHER',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    INDEX `idx_position_execution_market` (`execution_address`, `market_info_id`, `status`, `last_filled_at`),
    INDEX `idx_position_status` (`status`, `last_filled_at`),
    INDEX `idx_position_market_info` (`market_info_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Polymarket持仓表';

-- 7. Polymarket 资金流水表
CREATE TABLE IF NOT EXISTS `smart_wallet_cash_ledger` (
    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    `execution_address` VARCHAR(128) NOT NULL COMMENT '执行钱包地址',
    `asset` VARCHAR(32) NOT NULL DEFAULT 'USDC' COMMENT '资产',
    `entry_type` VARCHAR(32) NOT NULL COMMENT 'ORDER_NOTIONAL/FEE/REALIZED_PNL/DEPOSIT/WITHDRAW',
    `direction` VARCHAR(16) NOT NULL COMMENT 'IN/OUT',
    `amount` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '流水金额',
    `balance_after` DECIMAL(20, 8) DEFAULT NULL COMMENT '变动后余额(可空)',
    `reference_type` VARCHAR(32) NOT NULL COMMENT 'ORDER/FILL/POSITION/REPORT',
    `reference_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '引用对象ID',
    `market_id` VARCHAR(128) DEFAULT NULL COMMENT '市场ID',
    `question` VARCHAR(512) DEFAULT NULL COMMENT '问题描述',
    `occurred_at` TIMESTAMP NOT NULL COMMENT '发生时间',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX `idx_ledger_execution_time` (`execution_address`, `occurred_at`),
    INDEX `idx_ledger_type` (`entry_type`, `occurred_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Polymarket资金流水表';

-- 8. Polymarket 每日报表表
CREATE TABLE IF NOT EXISTS `smart_wallet_daily_report` (
    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    `report_date` DATE NOT NULL COMMENT '报表日期',
    `execution_address` VARCHAR(128) DEFAULT NULL COMMENT '执行钱包地址',
    `total_orders` INT NOT NULL DEFAULT 0 COMMENT '订单数',
    `filled_orders` INT NOT NULL DEFAULT 0 COMMENT '成交订单数',
    `total_volume_usdc` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '成交量',
    `fee_usdc` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '手续费',
    `realized_pnl_usdc` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '已实现收益',
    `unrealized_pnl_usdc` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '未实现收益',
    `net_pnl_usdc` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '净收益',
    `winning_positions` INT NOT NULL DEFAULT 0 COMMENT '盈利仓位数',
    `losing_positions` INT NOT NULL DEFAULT 0 COMMENT '亏损仓位数',
    `win_rate` DECIMAL(10, 4) NOT NULL DEFAULT 0 COMMENT '胜率',
    `loss_rate` DECIMAL(10, 4) NOT NULL DEFAULT 0 COMMENT '亏损率',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY `uk_daily_report_date_execution` (`report_date`, `execution_address`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Polymarket每日报表表';

-- 9. Polymarket 结算同步运行历史表
CREATE TABLE IF NOT EXISTS `smart_wallet_settlement_run` (
    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    `trigger_type` VARCHAR(16) NOT NULL COMMENT 'SCHEDULED/MANUAL',
    `position_limit` INT NOT NULL DEFAULT 0 COMMENT '本轮仓位扫描上限',
    `market_limit` INT NOT NULL DEFAULT 0 COMMENT '本轮市场扫描上限',
    `refresh_cache` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否刷新缓存',
    `status` VARCHAR(16) NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING/SUCCESS/FAILED',
    `scanned_positions` INT NOT NULL DEFAULT 0 COMMENT '扫描仓位数',
    `distinct_markets` INT NOT NULL DEFAULT 0 COMMENT '去重市场数',
    `fetched_markets` INT NOT NULL DEFAULT 0 COMMENT '实际拉取市场数',
    `resolved_positions` INT NOT NULL DEFAULT 0 COMMENT '已结算仓位数',
    `unresolved_markets` INT NOT NULL DEFAULT 0 COMMENT '未结算市场数',
    `skipped_future_markets` INT NOT NULL DEFAULT 0 COMMENT '跳过的未来市场数',
    `failed_markets` INT NOT NULL DEFAULT 0 COMMENT '失败市场数',
    `notes` TEXT DEFAULT NULL COMMENT '运行备注',
    `error_message` VARCHAR(2048) DEFAULT NULL COMMENT '整体错误',
    `started_at` TIMESTAMP NOT NULL COMMENT '开始时间',
    `finished_at` TIMESTAMP NULL COMMENT '结束时间',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX `idx_settlement_run_started` (`started_at`),
    INDEX `idx_settlement_run_status` (`status`, `started_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Polymarket结算同步运行历史表';

-- 10. Polymarket 结算失败重试队列表
CREATE TABLE IF NOT EXISTS `smart_wallet_settlement_retry` (
    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    `market_id` VARCHAR(128) NOT NULL COMMENT '市场ID',
    `question` VARCHAR(512) DEFAULT NULL COMMENT '问题描述',
    `retry_type` VARCHAR(32) DEFAULT NULL COMMENT 'SYNC_FAILURE/ORACLE_PENDING/DATA_SOURCE_STALE',
    `latest_run_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '最近一次运行ID',
    `failure_count` INT NOT NULL DEFAULT 1 COMMENT '累计失败次数',
    `last_error_message` text DEFAULT NULL COMMENT '最近错误',
    `last_failed_at` TIMESTAMP NOT NULL COMMENT '最近失败时间',
    `next_retry_at` TIMESTAMP NOT NULL COMMENT '下一次重试时间',
    `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RETRYING/RESOLVED/ABANDONED',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY `uk_settlement_retry_market` (`market_id`),
    INDEX `idx_settlement_retry_status` (`status`, `next_retry_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Poly market结算失败重试队列表';

-- 11. Polymarket claim 运行历史表
CREATE TABLE IF NOT EXISTS `smart_wallet_claim_run` (
    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    `trigger_type` VARCHAR(16) NOT NULL COMMENT 'SCHEDULED/MANUAL/RETRY',
    `position_limit` INT NOT NULL DEFAULT 0 COMMENT '本轮扫描仓位上限',
    `status` VARCHAR(16) NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING/SUCCESS/FAILED',
    `scanned_positions` INT NOT NULL DEFAULT 0 COMMENT '扫描仓位数',
    `attempted_claims` INT NOT NULL DEFAULT 0 COMMENT '实际尝试领取数',
    `claimed_positions` INT NOT NULL DEFAULT 0 COMMENT '已完成领取数',
    `waiting_confirmation_positions` INT NOT NULL DEFAULT 0 COMMENT '等待人工确认数',
    `failed_positions` INT NOT NULL DEFAULT 0 COMMENT '失败仓位数',
    `skipped_positions` INT NOT NULL DEFAULT 0 COMMENT '跳过仓位数',
    `notes` TEXT DEFAULT NULL COMMENT '运行备注',
    `error_message` TEXT DEFAULT NULL COMMENT '整体错误',
    `started_at` TIMESTAMP NOT NULL COMMENT '开始时间',
    `finished_at` TIMESTAMP NULL COMMENT '结束时间',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX `idx_claim_run_started` (`started_at`),
    INDEX `idx_claim_run_status` (`status`, `started_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Polymarket claim运行历史表';

-- 12. Polymarket claim 重试队列表
CREATE TABLE IF NOT EXISTS `smart_wallet_claim_retry` (
    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    `position_id` BIGINT UNSIGNED NOT NULL COMMENT '仓位ID',
    `execution_address` VARCHAR(128) DEFAULT NULL COMMENT '执行地址',
    `market_id` VARCHAR(128) DEFAULT NULL COMMENT '市场ID',
    `question` VARCHAR(512) DEFAULT NULL COMMENT '问题描述',
    `latest_run_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '最近一次运行ID',
    `failure_count` INT NOT NULL DEFAULT 1 COMMENT '累计失败次数',
    `last_error_message` VARCHAR(2048) DEFAULT NULL COMMENT '最近错误',
    `last_failed_at` TIMESTAMP NOT NULL COMMENT '最近失败时间',
    `next_retry_at` TIMESTAMP NOT NULL COMMENT '下一次重试时间',
    `status` VARCHAR(24) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RETRYING/WAITING_CONFIRMATION/RESOLVED/ABANDONED',
    `tx_hash` VARCHAR(128) DEFAULT NULL COMMENT '链上交易哈希',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY `uk_claim_retry_position` (`position_id`),
    INDEX `idx_claim_retry_status` (`status`, `next_retry_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Polymarket claim重试队列表';


-- 16. 创建 smart_wallet_market_info 公共市场信息表
CREATE TABLE IF NOT EXISTS `smart_wallet_market_info` (
    `id`            BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    `market_id`     VARCHAR(128) NOT NULL COMMENT 'Polymarket conditionId / 条件市场ID',
    `market_slug`   VARCHAR(512) DEFAULT NULL COMMENT 'Polymarket market URL slug',
    `question`      VARCHAR(512) DEFAULT NULL COMMENT '市场问题描述',
    `outcome`       VARCHAR(128) NOT NULL COMMENT 'Polymarket outcome label (YES/NO/候选名)',
    `token_id`      VARCHAR(128) DEFAULT NULL COMMENT 'CLOB outcome token id',
    `index_set`     INT DEFAULT NULL COMMENT 'CTF indexSet bitmask (1=outcome0/YES, 2=outcome1/NO)',
    `market_end_at` BIGINT DEFAULT NULL COMMENT '市场预计结算时间戳(毫秒)',
    `source_category` VARCHAR(128) DEFAULT NULL COMMENT 'Gamma 原始市场分类',
    `normalized_category` VARCHAR(64) DEFAULT NULL COMMENT '内部统一市场大类',
    `market_lifecycle` VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT '市场生命周期：ACTIVE/CLOSED_UNRESOLVED/RESOLVED',
    `closed` TINYINT(1) NOT NULL DEFAULT 0 COMMENT 'Gamma closed 标记',
    `accepting_orders` TINYINT(1) NOT NULL DEFAULT 1 COMMENT 'Gamma acceptingOrders 标记',
    `resolved_outcome` VARCHAR(128) DEFAULT NULL COMMENT '市场最终获胜 outcome',
    `last_metadata_sync_at` TIMESTAMP NULL DEFAULT NULL COMMENT '最近一次元数据同步时间',
    `created_at`    TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`    TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY `uk_market_outcome` (`market_id`, `outcome`),
    INDEX `idx_market_end_at` (`market_end_at`),
    INDEX `idx_market_lifecycle` (`market_lifecycle`, `market_id`),
    INDEX `idx_market_category` (`normalized_category`, `market_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='公共市场信息表 — 收敛跟单链路中各表的市场维度冗余字段';

CREATE TABLE IF NOT EXISTS `smart_wallet_market_concentration_snapshot` (
    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    `market_id` VARCHAR(128) NOT NULL COMMENT 'Polymarket conditionId / 市场ID',
    `outcome` VARCHAR(32) NOT NULL DEFAULT 'ALL' COMMENT 'YES/NO/ALL',
    `observed_holders` INT NOT NULL DEFAULT 0 COMMENT '观测到的持仓地址数',
    `top1_pct` DECIMAL(10, 6) NOT NULL DEFAULT 0 COMMENT 'CR1',
    `top3_pct` DECIMAL(10, 6) NOT NULL DEFAULT 0 COMMENT 'CR3',
    `top5_pct` DECIMAL(10, 6) NOT NULL DEFAULT 0 COMMENT 'CR5',
    `hhi` INT NOT NULL DEFAULT 0 COMMENT 'HHI',
    `highly_concentrated` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否高集中',
    `extremely_concentrated` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否极端集中',
    `snapshot_at` TIMESTAMP NOT NULL COMMENT '快照采集时间',
    `top_holder_addresses_json` TEXT DEFAULT NULL COMMENT 'Top holder 地址 JSON 列表',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY `uk_market_concentration_snapshot` (`market_id`, `outcome`),
    INDEX `idx_market_concentration_snapshot_at` (`snapshot_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='市场集中度快照表';



-- 表17：钱包 → 实体映射（核心）
CREATE TABLE IF NOT EXISTS `wallet_entity_map` (
    `id`                    BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    `wallet_address`        VARCHAR(128) NOT NULL COMMENT '钱包地址（小写）',
    `entity_id`             VARCHAR(64)  NOT NULL COMMENT '实体UUID，同一真实交易者共享',
    `cluster_method`        VARCHAR(30)  NOT NULL COMMENT '聚类方法: FUNDING_SOURCE / BEHAVIOR_SIM / MANUAL / SELF',
    `cluster_confidence`    DECIMAL(5,4) NOT NULL DEFAULT 0.0000 COMMENT '聚类置信度 0-1',
    `funding_source_address` VARCHAR(128) DEFAULT NULL COMMENT '共同入金地址（来自 Polygonscan）',
    `behavior_sim_score`    DECIMAL(5,4) DEFAULT NULL COMMENT '行为相似度得分',
    `is_primary_wallet`     TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否为该实体的主钱包',
    `verified`              TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否人工确认',
    `created_at`            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY `uk_wallet_address` (`wallet_address`),
    INDEX `idx_entity_id` (`entity_id`),
    INDEX `idx_funding_source` (`funding_source_address`),
    INDEX `idx_cluster_method_confidence` (`cluster_method`, `cluster_confidence`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='钱包→实体映射表（Entity Intelligence 核心索引）';

-- 表18：实体级特征快照（合并同实体所有钱包数据后的指标）
CREATE TABLE IF NOT EXISTS `wallet_entity_feature_snapshot` (
    `id`                    BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    `entity_id`             VARCHAR(64)  NOT NULL COMMENT '实体ID',
    `snapshot_time`         DATETIME NOT NULL COMMENT '快照时间',
    `wallet_count`          INT NOT NULL DEFAULT 1 COMMENT '该实体下的钱包数',
    `aggregated_win_rate`   DECIMAL(5,4) DEFAULT NULL COMMENT '合并后胜率（加权平均）',
    `aggregated_roi`        DECIMAL(10,4) DEFAULT NULL COMMENT '合并后ROI',
    `aggregated_pnl_usdc`   DECIMAL(18,2) DEFAULT NULL COMMENT '合并后累计PnL',
    `aggregated_total_trades` INT DEFAULT NULL COMMENT '合并后交易次数',
    `entity_score`          DECIMAL(5,4) DEFAULT NULL COMMENT '实体维度综合评分 0-1',
    `is_multi_wallet`       TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否存在多钱包',
    `wallet_addresses_json` TEXT DEFAULT NULL COMMENT '钱包地址列表（JSON数组）',
    `created_at`            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX `idx_entity_snapshot_time` (`entity_id`, `snapshot_time`),
    INDEX `idx_snapshot_time` (`snapshot_time`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='实体级特征快照表（合并同实体多钱包指标）';

-- 表19：候选跟单信号（intelligence enrich 的输出，follow task 的前置记录）
CREATE TABLE IF NOT EXISTS `wallet_follow_candidate` (
    `id`                    BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    `source_event_id`       BIGINT UNSIGNED NOT NULL COMMENT '来源交易事件ID',
    `wallet_address`        VARCHAR(128) NOT NULL COMMENT '触发钱包地址',
    `entity_id`             VARCHAR(64)  NOT NULL COMMENT '解析到的实体ID',
    `cluster_confidence`    DECIMAL(5,4) NOT NULL DEFAULT 0.0000 COMMENT '聚类置信度 0-1',
    `cluster_method`        VARCHAR(30)  NOT NULL DEFAULT 'SELF' COMMENT '聚类方法',
    `entity_wallet_count`   INT NOT NULL DEFAULT 1 COMMENT '该实体下钱包总数',
    `is_entity_first_signal` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '该实体在本市场是否首次触发',
    `entity_consensus_dedup_count` INT DEFAULT NULL COMMENT '去重后的实体共识数（同市场同方向）',
    `enrichment_status`     VARCHAR(20) NOT NULL DEFAULT 'ENRICHED' COMMENT 'ENRICHED/SKIPPED_NO_ENTITY/PENDING',
    `rejection_reason`      VARCHAR(512) DEFAULT NULL COMMENT '候选被拒绝的原因',
    `follow_task_id`        BIGINT UNSIGNED DEFAULT NULL COMMENT '最终生成的 follow task ID（如有）',
    `created_at`            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY `uk_source_event` (`source_event_id`),
    INDEX `idx_entity_id` (`entity_id`),
    INDEX `idx_wallet_address` (`wallet_address`),
    INDEX `idx_follow_task` (`follow_task_id`),
    INDEX `idx_enrichment_status` (`enrichment_status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='候选跟单信号表（Intelligence Enrich 输出）';


-- 用途：
-- 1. 创建 Polymarket 手工地址白名单表
-- 2. 支持绕过地址画像准入，但保留交易级和执行级风控

CREATE TABLE IF NOT EXISTS `smart_wallet_manual_allowlist` (
    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    `wallet_address` VARCHAR(128) NOT NULL COMMENT '白名单钱包地址，小写归一化',
    `source` VARCHAR(32) NOT NULL DEFAULT 'POLYMARKET' COMMENT '来源',
    `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
    `allowed_execution_modes` VARCHAR(16) NOT NULL DEFAULT 'PAPER' COMMENT 'PAPER/LIVE/BOTH',
    `stake_multiplier` DECIMAL(10,4) NOT NULL DEFAULT 0.5000 COMMENT '白名单仓位乘数',
    `max_live_orders_per_day` INT NOT NULL DEFAULT 5 COMMENT '单地址每日最多 LIVE 单数',
    `max_live_orders_per_run` INT NOT NULL DEFAULT 2 COMMENT '单地址每轮最多 LIVE 单数',
    `expires_at` DATETIME DEFAULT NULL COMMENT '过期时间',
    `reason` VARCHAR(255) DEFAULT NULL COMMENT '白名单原因',
    `created_by` VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY `uk_sw_manual_allowlist_wallet_source` (`wallet_address`, `source`),
    KEY `idx_sw_manual_allowlist_status_expiry` (`status`, `expires_at`),
    KEY `idx_sw_manual_allowlist_updated` (`updated_at`)
 ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='smart wallet 手工地址白名单';

CREATE TABLE IF NOT EXISTS `smart_wallet_manual_blocklist` (
    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    `wallet_address` VARCHAR(128) NOT NULL COMMENT '阻断钱包地址，小写归一化',
    `source` VARCHAR(32) NOT NULL DEFAULT 'POLYMARKET' COMMENT '来源',
    `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
    `reason` VARCHAR(255) DEFAULT NULL COMMENT '阻断原因',
    `created_by` VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY `uk_sw_manual_blocklist_wallet_source` (`wallet_address`, `source`),
    KEY `idx_sw_manual_blocklist_status` (`status`),
    KEY `idx_sw_manual_blocklist_updated` (`updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='smart wallet 手工地址阻断名单';


CREATE TABLE IF NOT EXISTS `smart_wallet_trade_order` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '生命周期主单ID',
    `position_id` BIGINT UNSIGNED NOT NULL COMMENT '关联 smart_wallet_position.id',
    `market_info_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '关联 smart_wallet_market_info.id',
    `execution_address` VARCHAR(128) NOT NULL COMMENT '执行地址',
    `wallet_address` VARCHAR(128) DEFAULT NULL COMMENT '触发钱包地址',
    `source_follow_task_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '首个跟单任务ID',
    `entry_quantity` DECIMAL(20,8) NOT NULL DEFAULT 0,
    `entry_avg_price` DECIMAL(20,8) NOT NULL DEFAULT 0,
    `entry_cost_usdc` DECIMAL(20,8) NOT NULL DEFAULT 0,
    `entry_fee_usdc` DECIMAL(20,8) NOT NULL DEFAULT 0,
    `opened_at` TIMESTAMP NULL,
    `exit_quantity` DECIMAL(20,8) NOT NULL DEFAULT 0,
    `exit_avg_price` DECIMAL(20,8) NOT NULL DEFAULT 0,
    `exit_proceeds_usdc` DECIMAL(20,8) NOT NULL DEFAULT 0,
    `exit_realized_pnl_usdc` DECIMAL(20,8) NOT NULL DEFAULT 0,
    `exit_fee_usdc` DECIMAL(20,8) NOT NULL DEFAULT 0,
    `last_exit_at` TIMESTAMP NULL,
    `remaining_quantity` DECIMAL(20,8) NOT NULL DEFAULT 0,
    `remaining_cost_basis_usdc` DECIMAL(20,8) NOT NULL DEFAULT 0,
    `last_mark_price` DECIMAL(20,8) NOT NULL DEFAULT 0,
    `unrealized_pnl_usdc` DECIMAL(20,8) NOT NULL DEFAULT 0,
    `settlement_price` DECIMAL(20,8) DEFAULT NULL,
    `settlement_proceeds_usdc` DECIMAL(20,8) NOT NULL DEFAULT 0,
    `settlement_pnl_usdc` DECIMAL(20,8) NOT NULL DEFAULT 0,
    `settled_at` TIMESTAMP NULL,
    `total_realized_pnl_usdc` DECIMAL(20,8) NOT NULL DEFAULT 0,
    `total_pnl_usdc` DECIMAL(20,8) NOT NULL DEFAULT 0,
    `roi_rate` DECIMAL(20,8) NOT NULL DEFAULT 0,
    `status` VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_trade_order_position` (`position_id`),
    KEY `idx_trade_order_market` (`market_info_id`, `status`),
    KEY `idx_trade_order_execution` (`execution_address`, `status`, `opened_at`),
    KEY `idx_trade_order_status` (`status`, `updated_at`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Polymarket生命周期主单表';

CREATE TABLE IF NOT EXISTS `smart_wallet_leader_trade_outcome` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `wallet_address` VARCHAR(128) NOT NULL COMMENT 'leader 钱包地址',
    `market_id` VARCHAR(128) NOT NULL COMMENT 'Polymarket conditionId',
    `market_info_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '关联 smart_wallet_market_info.id',
    `question` VARCHAR(512) DEFAULT NULL COMMENT '市场问题',
    `outcome` VARCHAR(128) NOT NULL COMMENT '标准化 outcome',
    `token_id` VARCHAR(128) DEFAULT NULL COMMENT 'outcome token id',
    `first_trade_at` TIMESTAMP NULL COMMENT '该 outcome 首笔成交时间',
    `last_trade_at` TIMESTAMP NULL COMMENT '该 outcome 最近成交时间',
    `buy_count` INT NOT NULL DEFAULT 0 COMMENT 'BUY 成交数',
    `sell_count` INT NOT NULL DEFAULT 0 COMMENT 'SELL 成交数',
    `buy_shares` DECIMAL(28,8) NOT NULL DEFAULT 0 COMMENT '累计买入份额',
    `sell_shares` DECIMAL(28,8) NOT NULL DEFAULT 0 COMMENT '累计卖出份额',
    `net_shares` DECIMAL(28,8) NOT NULL DEFAULT 0 COMMENT '剩余净份额',
    `avg_buy_price` DECIMAL(20,8) NOT NULL DEFAULT 0 COMMENT '买入均价',
    `buy_cost_usdc` DECIMAL(28,8) NOT NULL DEFAULT 0 COMMENT '买入成本',
    `sell_proceeds_usdc` DECIMAL(28,8) NOT NULL DEFAULT 0 COMMENT '卖出回收',
    `resolved_outcome` VARCHAR(128) DEFAULT NULL COMMENT '市场最终胜出 outcome',
    `settlement_price` DECIMAL(20,8) DEFAULT NULL COMMENT '结算价格',
    `estimated_settlement_value_usdc` DECIMAL(28,8) NOT NULL DEFAULT 0 COMMENT '估算结算价值',
    `estimated_realized_pnl_usdc` DECIMAL(28,8) NOT NULL DEFAULT 0 COMMENT '估算总盈亏',
    `estimated_roi_rate` DECIMAL(20,8) NOT NULL DEFAULT 0 COMMENT '估算 ROI',
    `is_win` TINYINT(1) DEFAULT NULL COMMENT '闭环后是否盈利',
    `outcome_status` VARCHAR(32) NOT NULL COMMENT 'CLOSED_WIN/CLOSED_LOSS/CLOSED_BREAKEVEN/OPEN/UNRESOLVED/LOW_CONFIDENCE',
    `source_confidence` DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '数据可信度',
    `data_source` VARCHAR(64) NOT NULL DEFAULT 'POLYMARKET_USER_TRADES' COMMENT '数据来源',
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sw_leader_outcome_wallet_market_outcome` (`wallet_address`, `market_id`, `outcome`),
    KEY `idx_sw_leader_outcome_wallet_status` (`wallet_address`, `outcome_status`, `first_trade_at`),
    KEY `idx_sw_leader_outcome_market` (`market_id`, `outcome`),
    KEY `idx_sw_leader_outcome_market_info` (`market_info_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='leader 钱包真实成交闭环 outcome 复盘表';


-- 1. 多策略钱包配置表
CREATE TABLE IF NOT EXISTS strategy_wallet (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    wallet_id         VARCHAR(64)    NOT NULL UNIQUE     COMMENT '钱包唯一标识，如 follow-main, weather-1',
    strategy_id       VARCHAR(64)    NOT NULL             COMMENT '绑定的策略 ID',
    chain             VARCHAR(20)    DEFAULT 'polygon'    COMMENT '链名称',
    chain_id          INT            DEFAULT 137          COMMENT '链 ID (Polygon=137)',
    address           VARCHAR(128)   NOT NULL             COMMENT 'Polymarket 钱包地址',
    private_key       TEXT                                COMMENT '私钥（加密存储）',
    funder_address    VARCHAR(128)                        COMMENT '资金来源地址',
    api_key           VARCHAR(256)                        COMMENT 'CLOB API Key',
    api_secret        TEXT                                COMMENT 'CLOB API Secret（加密存储）',
    api_passphrase    VARCHAR(256)                        COMMENT 'CLOB API Passphrase',
    enabled           TINYINT(1)     DEFAULT 0            COMMENT '是否启用',
    capital_pool_usdc DECIMAL(18,2)  DEFAULT 0            COMMENT '该钱包的资金预算上限（USDC）',
    notes             VARCHAR(512)                        COMMENT '备注',
    created_at        DATETIME       DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME       DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_strategy_wallet_strategy_id (strategy_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='多策略钱包配置表';

-- 2. 统一策略运行日志表
CREATE TABLE IF NOT EXISTS strategy_run_log (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    strategy_id     VARCHAR(64)    NOT NULL             COMMENT '策略 ID',
    wallet_id       VARCHAR(64)    NOT NULL             COMMENT '钱包 ID',
    run_type        VARCHAR(32)    NOT NULL             COMMENT '运行类型: SIGNAL_SCAN / EXIT_SCAN / HEALTH_CHECK',
    run_mode        VARCHAR(16)    NOT NULL             COMMENT '运行模式: SHADOW / PAPER / LIVE',
    started_at      DATETIME       NOT NULL             COMMENT '开始时间',
    finished_at     DATETIME                            COMMENT '结束时间',
    signals_found   INT            DEFAULT 0            COMMENT '发现信号数',
    orders_placed   INT            DEFAULT 0            COMMENT '下单数',
    errors          INT            DEFAULT 0            COMMENT '错误数',
    summary_json    TEXT                                COMMENT '策略特有的运行摘要（JSON）',
    INDEX idx_strategy_run_log_strategy_time (strategy_id, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统一策略运行日志表';

-- 3. BTC 5分钟策略仓位表
CREATE TABLE IF NOT EXISTS btc_minute_position (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    strategy_id         VARCHAR(64)    NOT NULL             COMMENT '策略 ID',
    wallet_id           VARCHAR(64)    NOT NULL             COMMENT '钱包 ID',
    market_id           VARCHAR(256)   NOT NULL             COMMENT 'Polymarket conditionId',
    token_id            VARCHAR(256)                        COMMENT 'Token ID',
    market_title        VARCHAR(512)                        COMMENT '市场标题',
    outcome             VARCHAR(32)                         COMMENT 'YES / NO',
    side                VARCHAR(8)     NOT NULL             COMMENT 'BUY / SELL',
    btc_entry_price     DECIMAL(18,2)                       COMMENT '入场时 BTC 价格',
    entry_price         DECIMAL(18,8)                       COMMENT '入场时 Polymarket 价格',
    current_price       DECIMAL(18,8)                       COMMENT '当前 Polymarket 价格',
    quantity            DECIMAL(18,4)                       COMMENT '持仓数量（shares）',
    stake_usdc          DECIMAL(18,4)                       COMMENT '投入金额（USDC）',
    trading_fee_usdc    DECIMAL(18,8)  DEFAULT 0            COMMENT '交易手续费',
    fair_probability    DECIMAL(18,6)                       COMMENT '公允概率',
    edge                DECIMAL(18,6)                       COMMENT 'Edge',
    rsi_at_entry        DECIMAL(18,4)                       COMMENT 'RSI at entry',
    volatility_at_entry DECIMAL(18,6)                       COMMENT '波动率 at entry',
    status              VARCHAR(32)    NOT NULL             COMMENT 'OPEN / CLOSED / SETTLED',
    execution_mode      VARCHAR(16)    NOT NULL             COMMENT 'PAPER / LIVE',
    external_order_id   VARCHAR(256)                        COMMENT 'CLOB 订单 ID',
    settlement_time     DATETIME                            COMMENT '市场结算时间',
    settlement_result   VARCHAR(16)                         COMMENT 'WIN / LOSE / null',
    realized_pnl_usdc   DECIMAL(18,4)                       COMMENT '已实现 PNL',
    signal_reason       VARCHAR(512)                        COMMENT '信号原因',
    created_at          DATETIME       DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME       DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_btc_minute_status (strategy_id, status),
    INDEX idx_btc_minute_market (market_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='BTC 5分钟策略仓位表';

-- 4. 网格策略挂单表
CREATE TABLE IF NOT EXISTS grid_order (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    strategy_id         VARCHAR(64)    NOT NULL             COMMENT '策略 ID',
    wallet_id           VARCHAR(64)    NOT NULL             COMMENT '钱包 ID',
    market_id           VARCHAR(256)   NOT NULL             COMMENT 'Polymarket conditionId',
    token_id            VARCHAR(256)                        COMMENT 'Token ID',
    market_title        VARCHAR(512)                        COMMENT '市场标题',
    level_index         INT                                 COMMENT '网格层级编号',
    outcome             VARCHAR(32)                         COMMENT 'YES / NO',
    side                VARCHAR(8)     NOT NULL             COMMENT 'BUY / SELL',
    price               DECIMAL(18,8)  NOT NULL             COMMENT '挂单价格',
    quantity            DECIMAL(18,4)                       COMMENT '挂单数量（shares）',
    amount_usdc         DECIMAL(18,4)                       COMMENT '挂单金额（USDC）',
    external_order_id   VARCHAR(256)                        COMMENT 'CLOB 外部订单 ID',
    status              VARCHAR(32)    NOT NULL             COMMENT 'PENDING / LIVE / FILLED / CANCELLED',
    execution_mode      VARCHAR(16)    NOT NULL             COMMENT 'PAPER / LIVE',
    filled_quantity     DECIMAL(18,4)  DEFAULT 0            COMMENT '成交数量',
    filled_avg_price    DECIMAL(18,8)                       COMMENT '成交均价',
    counter_order_id    BIGINT                              COMMENT '对侧补单的订单 ID',
    realized_profit_usdc DECIMAL(18,4) DEFAULT 0            COMMENT '已实现利润',
    created_at          DATETIME       DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME       DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_grid_order_market_status (market_id, status),
    INDEX idx_grid_order_strategy (strategy_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='网格策略挂单表';

-- ============================================================
-- Phase 4: 统一监控与运维
-- ============================================================

-- 5. 策略告警记录表
CREATE TABLE IF NOT EXISTS strategy_alert (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    strategy_id     VARCHAR(64)    NOT NULL             COMMENT '策略 ID（ALL 表示全局）',
    alert_level     VARCHAR(16)    NOT NULL             COMMENT '告警级别: INFO / WARN / CRITICAL / FATAL',
    alert_type      VARCHAR(64)    NOT NULL             COMMENT '告警类型: HEALTH_CHECK_FAIL / EXPOSURE_LIMIT / DAILY_LOSS_LIMIT / CONSECUTIVE_ERRORS / HEARTBEAT_MISS / KILL_SWITCH',
    title           VARCHAR(256)   NOT NULL             COMMENT '告警标题',
    message         TEXT                                COMMENT '告警详细信息',
    metric_value    DECIMAL(18,4)                       COMMENT '触发指标值',
    threshold_value DECIMAL(18,4)                       COMMENT '阈值',
    acknowledged    TINYINT(1)     DEFAULT 0            COMMENT '是否已确认',
    acknowledged_at DATETIME                            COMMENT '确认时间',
    resolved        TINYINT(1)     DEFAULT 0            COMMENT '是否已恢复',
    resolved_at     DATETIME                            COMMENT '恢复时间',
    created_at      DATETIME       DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_alert_strategy_time (strategy_id, created_at),
    INDEX idx_alert_level_resolved (alert_level, resolved)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='策略告警记录表';

-- 6. 策略健康快照表（定时采集）
CREATE TABLE IF NOT EXISTS strategy_health_snapshot (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    strategy_id           VARCHAR(64)    NOT NULL             COMMENT '策略 ID',
    snapshot_time         DATETIME       NOT NULL             COMMENT '快照时间',
    healthy               TINYINT(1)     DEFAULT 1            COMMENT '是否健康',
    mode                  VARCHAR(16)                         COMMENT '运行模式',
    open_positions        INT            DEFAULT 0            COMMENT '持仓数',
    pending_orders        INT            DEFAULT 0            COMMENT '挂单数',
    wallet_balance_usdc   DECIMAL(18,4)                       COMMENT '钱包余额(USDC)',
    total_exposure_usdc   DECIMAL(18,4)                       COMMENT '总曝险(USDC)',
    today_realized_pnl    DECIMAL(18,4)                       COMMENT '今日已实现PnL',
    unrealized_pnl        DECIMAL(18,4)                       COMMENT '未实现PnL',
    notes                 VARCHAR(512)                        COMMENT '备注',
    created_at            DATETIME       DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_snapshot_strategy_time (strategy_id, snapshot_time),
    INDEX idx_snapshot_time (snapshot_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='策略健康快照表';

CREATE TABLE IF NOT EXISTS `smart_wallet_leader_trade_outcome` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `wallet_address` VARCHAR(128) NOT NULL COMMENT 'leader 钱包地址',
    `market_id` VARCHAR(128) NOT NULL COMMENT 'Polymarket conditionId',
    `market_info_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '关联 smart_wallet_market_info.id',
    `question` VARCHAR(512) DEFAULT NULL COMMENT '市场问题',
    `outcome` VARCHAR(128) NOT NULL COMMENT '标准化 outcome',
    `token_id` VARCHAR(128) DEFAULT NULL COMMENT 'outcome token id',
    `first_trade_at` TIMESTAMP NULL COMMENT '该 outcome 首笔成交时间',
    `last_trade_at` TIMESTAMP NULL COMMENT '该 outcome 最近成交时间',
    `buy_count` INT NOT NULL DEFAULT 0 COMMENT 'BUY 成交数',
    `sell_count` INT NOT NULL DEFAULT 0 COMMENT 'SELL 成交数',
    `buy_shares` DECIMAL(28,8) NOT NULL DEFAULT 0 COMMENT '累计买入份额',
    `sell_shares` DECIMAL(28,8) NOT NULL DEFAULT 0 COMMENT '累计卖出份额',
    `net_shares` DECIMAL(28,8) NOT NULL DEFAULT 0 COMMENT '剩余净份额',
    `avg_buy_price` DECIMAL(20,8) NOT NULL DEFAULT 0 COMMENT '买入均价',
    `buy_cost_usdc` DECIMAL(28,8) NOT NULL DEFAULT 0 COMMENT '买入成本',
    `sell_proceeds_usdc` DECIMAL(28,8) NOT NULL DEFAULT 0 COMMENT '卖出回收',
    `resolved_outcome` VARCHAR(128) DEFAULT NULL COMMENT '市场最终胜出 outcome',
    `settlement_price` DECIMAL(20,8) DEFAULT NULL COMMENT '结算价格',
    `estimated_settlement_value_usdc` DECIMAL(28,8) NOT NULL DEFAULT 0 COMMENT '估算结算价值',
    `estimated_realized_pnl_usdc` DECIMAL(28,8) NOT NULL DEFAULT 0 COMMENT '估算总盈亏',
    `estimated_roi_rate` DECIMAL(20,8) NOT NULL DEFAULT 0 COMMENT '估算 ROI',
    `is_win` TINYINT(1) DEFAULT NULL COMMENT '闭环后是否盈利',
    `outcome_status` VARCHAR(32) NOT NULL COMMENT 'CLOSED_WIN/CLOSED_LOSS/CLOSED_BREAKEVEN/OPEN/UNRESOLVED/LOW_CONFIDENCE',
    `source_confidence` DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '数据可信度',
    `data_source` VARCHAR(64) NOT NULL DEFAULT 'POLYMARKET_USER_TRADES' COMMENT '数据来源',
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sw_leader_outcome_wallet_market_outcome` (`wallet_address`, `market_id`, `outcome`),
    KEY `idx_sw_leader_outcome_wallet_status` (`wallet_address`, `outcome_status`, `first_trade_at`),
    KEY `idx_sw_leader_outcome_market` (`market_id`, `outcome`),
    KEY `idx_sw_leader_outcome_market_info` (`market_info_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='leader 钱包真实成交闭环 outcome 复盘表';


-- 用途：
-- 1. 显式创建 smart_wallet_behavior_profile 表
-- 2. 与当前代码中的 SmartWalletBehaviorProfileEntity 保持一致

CREATE TABLE IF NOT EXISTS `smart_wallet_behavior_profile` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `wallet_address` VARCHAR(128) NOT NULL COMMENT '监控钱包地址',
    `source` VARCHAR(32) NOT NULL DEFAULT 'POLYMARKET' COMMENT '来源',
    `alias` VARCHAR(255) DEFAULT NULL COMMENT '钱包别名',
    `archetype` VARCHAR(64) DEFAULT NULL COMMENT '交易风格分型',
    `follow_lane` VARCHAR(64) DEFAULT NULL COMMENT '建议跟随 lane',
    `high_frequency_trading_address` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否高频交易地址',
    `trades_7d` INT NOT NULL DEFAULT 0 COMMENT '近7天交易数',
    `trades_30d` INT NOT NULL DEFAULT 0 COMMENT '近30天交易数',
    `median_inter_trade_minutes` DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '交易间隔中位数(分钟)',
    `closed_position_sample_size` INT NOT NULL DEFAULT 0 COMMENT '已关闭仓位样本数',
    `order_win_rate` DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '订单胜率',
    `average_order_roi_rate` DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '平均订单收益率',
    `median_order_roi_rate` DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '中位订单收益率',
    `total_realized_pnl_usdc` DECIMAL(20,8) NOT NULL DEFAULT 0 COMMENT '总实现收益USDC',
    `settlement_completion_rate` DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '持有到结算比例',
    `early_exit_rate` DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '提前退出比例',
    `profit_early_exit_rate` DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '盈利单提前退出比例',
    `loss_early_exit_rate` DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '亏损单提前退出比例',
    `early_settlement_rate` DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '提前结算占比',
    `win_source_type` VARCHAR(64) DEFAULT NULL COMMENT '胜率来源类型',
    `latency_alpha_rate` DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '短周期 alpha 比例',
    `swing_alpha_rate` DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '中周期 alpha 比例',
    `settlement_win_rate` DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '等待结算胜率',
    `median_hold_minutes` DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '持仓时长中位数(分钟)',
    `stability_score` DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '稳定性分数',
    `quality_score` DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '地址质量分数',
    `replicability_score` DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '可复制分数',
    `recommended_exit_lane` VARCHAR(64) DEFAULT NULL COMMENT '推荐退出 lane',
    `summary` VARCHAR(1024) DEFAULT NULL COMMENT '画像摘要',
    `computed_at` DATETIME NOT NULL COMMENT '画像计算时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sw_behavior_wallet_source` (`wallet_address`, `source`),
    KEY `idx_sw_behavior_lane` (`recommended_exit_lane`, `updated_at`),
    KEY `idx_sw_behavior_quality` (`quality_score`, `replicability_score`, `updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='smart wallet 行为画像快照';


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

-- Phase 4.4: 单地址综合诊断快照表
-- 由 job 端 ProfileDiagnosisSnapshotJob 定期写入；web 端读
CREATE TABLE IF NOT EXISTS `smart_wallet_profile_diagnosis_snapshot` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `wallet_address` VARCHAR(64) NOT NULL,
    `source` VARCHAR(32) NOT NULL DEFAULT 'POLYMARKET',
    `alias` VARCHAR(128) NULL,
    `archetype` VARCHAR(64) NULL,
    `follow_lane` VARCHAR(64) NULL,
    `archetype_reason` VARCHAR(512) NULL,
    `archetype_confidence` DECIMAL(10, 4) NULL,
    `screener_tier` VARCHAR(32) NULL,
    `high_quality_address` BOOLEAN NULL,
    `high_frequency_trading_address` BOOLEAN NULL,
    `win_source_type` VARCHAR(32) NULL,
    `quality_score` DECIMAL(10, 4) NULL,
    `replicability_score` DECIMAL(10, 4) NULL,
    `stability_score` DECIMAL(10, 4) NULL,
    `settlement_win_rate` DECIMAL(10, 4) NULL,
    `latency_alpha_rate` DECIMAL(10, 4) NULL,
    `swing_alpha_rate` DECIMAL(10, 4) NULL,
    `early_settlement_rate` DECIMAL(10, 4) NULL,
    `dedup_order_count_7d` INT NULL,
    `dedup_order_count_30d` INT NULL,
    `median_hold_minutes` DECIMAL(20, 4) NULL,
    `data_quality` VARCHAR(32) NULL,
    `recommended_exit_lane` VARCHAR(64) NULL,
    `summary` TEXT NULL,
    `strengths_json` TEXT NULL,
    `risks_json` TEXT NULL,
    `generated_at` DATETIME NULL,
    `created_at` DATETIME NULL,
    `updated_at` DATETIME NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_wallet_source` (`wallet_address`, `source`),
    KEY `idx_generated_at` (`generated_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='单地址综合诊断快照（Phase 4.4）';

CREATE TABLE IF NOT EXISTS `strategy_runtime_config` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `strategy_code` VARCHAR(64) NOT NULL COMMENT '策略代码，例如 POLYMARKET_COPY',
  `config_key` VARCHAR(160) NOT NULL COMMENT '配置键，例如 smart-wallet.signal-risk.min-trigger-size-usdc',
  `config_value` TEXT NOT NULL COMMENT '配置值，按 value_type 解析',
  `value_type` VARCHAR(32) NOT NULL COMMENT 'BOOLEAN / INTEGER / DECIMAL / STRING / STRING_LIST / JSON',
  `group_name` VARCHAR(64) NOT NULL COMMENT '页面分组',
  `editable` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否允许页面编辑',
  `description` VARCHAR(512) DEFAULT NULL COMMENT '配置说明',
  `updated_by` VARCHAR(64) DEFAULT NULL COMMENT '最后更新人',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_strategy_runtime_config_key` (`strategy_code`, `config_key`),
  KEY `idx_strategy_runtime_config_group` (`strategy_code`, `group_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='策略运行时可编辑配置';


-- =================================================================
-- 闭环可观测性 + 风控升级 + W2-T2 灰度自动化 (2026-05-12 起累积合并)
-- 收口来源:
--   * polymarket_risk_upgrade_migration.sql              → 4 表
--   * smart_wallet_signal_shadow_migration.sql           → 1 表
--   * pm_resolution_audit_migration.sql                  → 1 表
--   * pm_market_liquidity_percentile_daily_migration.sql → 1 表
--   * live_replay_diff_history_migration.sql             → 1 表
--   * smart_wallet_order_analysis_migration.sql          → 1 表 (ALTER 部分入批次 diff)
--   * leader_rolling_aggregation_daily_migration.sql     → 1 表 (第十八批)
--   * weather_arbitrage_migration.sql                    → 6 表 (ALTER 部分入批次 diff)
-- =================================================================

-- W2 风控升级: 市场微观结构快照
CREATE TABLE IF NOT EXISTS `smart_wallet_market_snapshot` (
    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    `market_id` VARCHAR(128) NOT NULL COMMENT '条件市场ID',
    `outcome` VARCHAR(128) NOT NULL COMMENT 'Outcome 标签',
    `snapshot_at` TIMESTAMP NOT NULL COMMENT '快照时间',
    `mid_price` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '中间价',
    `best_bid` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '最优买价',
    `best_ask` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '最优卖价',
    `spread_bps` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '买卖价差(bps)',
    `bid_depth_1c` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '买一侧1c内深度',
    `ask_depth_1c` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '卖一侧1c内深度',
    `bid_depth_3c` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '买一侧3c内深度',
    `ask_depth_3c` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '卖一侧3c内深度',
    `bid_depth_5c` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '买一侧5c内深度',
    `ask_depth_5c` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '卖一侧5c内深度',
    `order_imbalance` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '盘口不平衡度',
    `last_trade_price` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '最近成交价',
    `last_trade_size` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '最近成交额/份额',
    `trade_count_30s` INT NOT NULL DEFAULT 0 COMMENT '过去30秒成交笔数',
    `buy_volume_30s` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '过去30秒买成交量',
    `sell_volume_30s` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '过去30秒卖成交量',
    `realized_vol_30s` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '过去30秒实现波动率',
    `time_to_end_seconds` BIGINT DEFAULT NULL COMMENT '距离市场结束剩余秒数',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY `uk_snapshot_market_outcome_time` (`market_id`, `outcome`, `snapshot_at`),
    INDEX `idx_snapshot_time` (`snapshot_at`),
    INDEX `idx_snapshot_market_time` (`market_id`, `snapshot_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Polymarket市场微观结构快照表';

-- W2 风控升级: leader 交易效果标签
CREATE TABLE IF NOT EXISTS `smart_wallet_trade_effect_label` (
    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    `source_event_id` BIGINT UNSIGNED NOT NULL COMMENT '来源 trade event ID',
    `wallet_address` VARCHAR(128) NOT NULL COMMENT '触发钱包地址',
    `market_id` VARCHAR(128) NOT NULL COMMENT '条件市场ID',
    `outcome` VARCHAR(128) NOT NULL COMMENT 'Outcome 标签',
    `side` VARCHAR(16) NOT NULL COMMENT 'BUY/SELL',
    `event_at` TIMESTAMP NOT NULL COMMENT '事件时间',
    `mid_t0` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '事件时点中间价',
    `mid_t_10s` DECIMAL(20, 8) DEFAULT NULL COMMENT '10秒后中间价',
    `mid_t_30s` DECIMAL(20, 8) DEFAULT NULL COMMENT '30秒后中间价',
    `mid_t_60s` DECIMAL(20, 8) DEFAULT NULL COMMENT '60秒后中间价',
    `mid_t_5m` DECIMAL(20, 8) DEFAULT NULL COMMENT '5分钟后中间价',
    `mid_t_15m` DECIMAL(20, 8) DEFAULT NULL COMMENT '15分钟后中间价',
    `last_trade_t_10s` DECIMAL(20, 8) DEFAULT NULL COMMENT '10秒后最近成交价',
    `last_trade_t_30s` DECIMAL(20, 8) DEFAULT NULL COMMENT '30秒后最近成交价',
    `last_trade_t_5m` DECIMAL(20, 8) DEFAULT NULL COMMENT '5分钟后最近成交价',
    `paper_stake_usdc` DECIMAL(20, 8) DEFAULT NULL COMMENT '纸面跟单名义本金',
    `estimated_fee_rate` DECIMAL(10, 8) DEFAULT NULL COMMENT '预估单边手续费率',
    `estimated_slippage_rate` DECIMAL(10, 8) DEFAULT NULL COMMENT '预估单边滑点率',
    `estimated_fee_usdc` DECIMAL(20, 8) DEFAULT NULL COMMENT '双边预估手续费',
    `estimated_slippage_usdc` DECIMAL(20, 8) DEFAULT NULL COMMENT '双边预估滑点成本',
    `realized_slippage_bps` DECIMAL(20, 8) DEFAULT NULL COMMENT '事件价相对t0 mid的真实单边滑点(bps)',
    `realized_slippage_usdc` DECIMAL(20, 8) DEFAULT NULL COMMENT '事件价相对t0 mid的真实单边滑点成本',
    `paper_entry_price` DECIMAL(20, 8) DEFAULT NULL COMMENT '纸面跟单入场价',
    `paper_exit_price_30s` DECIMAL(20, 8) DEFAULT NULL COMMENT '30秒退出价',
    `paper_exit_price_5m` DECIMAL(20, 8) DEFAULT NULL COMMENT '5分钟退出价',
    `paper_exit_price_rule` DECIMAL(20, 8) DEFAULT NULL COMMENT '按规则退出价',
    `paper_pnl_30s` DECIMAL(20, 8) DEFAULT NULL COMMENT '30秒毛PnL',
    `paper_pnl_5m` DECIMAL(20, 8) DEFAULT NULL COMMENT '5分钟毛PnL',
    `paper_pnl_rule` DECIMAL(20, 8) DEFAULT NULL COMMENT '规则退出毛PnL',
    `net_pnl_30s` DECIMAL(20, 8) DEFAULT NULL COMMENT '30秒净PnL',
    `net_pnl_5m` DECIMAL(20, 8) DEFAULT NULL COMMENT '5分钟净PnL',
    `net_pnl_rule` DECIMAL(20, 8) DEFAULT NULL COMMENT '规则退出净PnL',
    `is_positive_ev_30s` TINYINT(1) DEFAULT NULL COMMENT '30秒是否正EV',
    `is_positive_ev_5m` TINYINT(1) DEFAULT NULL COMMENT '5分钟是否正EV',
    `is_positive_ev_rule` TINYINT(1) DEFAULT NULL COMMENT '规则退出是否正EV',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY `uk_effect_source_event` (`source_event_id`),
    INDEX `idx_effect_market_time` (`market_id`, `event_at`),
    INDEX `idx_effect_wallet_time` (`wallet_address`, `event_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Polymarket leader交易效果标签表';

-- W2 风控升级: 钱包日特征
CREATE TABLE IF NOT EXISTS `smart_wallet_wallet_feature_daily` (
    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    `wallet_address` VARCHAR(128) NOT NULL COMMENT '钱包地址',
    `feature_date` DATE NOT NULL COMMENT '特征日期',
    `trades_7d` INT NOT NULL DEFAULT 0 COMMENT '近7日交易数',
    `trades_30d` INT NOT NULL DEFAULT 0 COMMENT '近30日交易数',
    `markets_30d` INT NOT NULL DEFAULT 0 COMMENT '近30日参与市场数',
    `pnl_30d` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '近30日PnL',
    `win_rate_30d` DECIMAL(10, 4) NOT NULL DEFAULT 0 COMMENT '近30日胜率',
    `avg_size_30d` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '近30日平均交易额',
    `drawdown_30d` DECIMAL(10, 4) NOT NULL DEFAULT 0 COMMENT '近30日最大回撤',
    `concentration_market_30d` DECIMAL(10, 4) NOT NULL DEFAULT 0 COMMENT '近30日市场集中度',
    `low_liquidity_ratio_30d` DECIMAL(10, 4) NOT NULL DEFAULT 0 COMMENT '近30日低流动性市场占比',
    `late_market_ratio_30d` DECIMAL(10, 4) NOT NULL DEFAULT 0 COMMENT '近30日临近结算交易占比',
    `followable_rate_30d` DECIMAL(10, 4) NOT NULL DEFAULT 0 COMMENT '近30日可跟单信号占比',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY `uk_wallet_feature_date` (`wallet_address`, `feature_date`),
    INDEX `idx_wallet_feature_date` (`feature_date`),
    INDEX `idx_wallet_feature_wallet` (`wallet_address`, `feature_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Polymarket钱包日特征表';

-- W2 风控升级: treatment / control 配对样本
CREATE TABLE IF NOT EXISTS `smart_wallet_treatment_control_sample` (
    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    `source_event_id` BIGINT UNSIGNED NOT NULL COMMENT 'treatment来源 trade event ID',
    `control_market_id` VARCHAR(128) NOT NULL COMMENT '对照市场ID',
    `control_outcome` VARCHAR(128) DEFAULT NULL COMMENT '对照Outcome',
    `control_snapshot_at` TIMESTAMP NOT NULL COMMENT '对照快照时间',
    `match_distance` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '匹配距离/相似度损失',
    `covariates_json` JSON DEFAULT NULL COMMENT '匹配时使用的协变量',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX `idx_tc_source_event` (`source_event_id`),
    INDEX `idx_tc_control_market_time` (`control_market_id`, `control_snapshot_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Polymarket treatment-control配对样本表';

-- 单笔订单闭环分析 (smart_wallet_order_analysis_migration 内, profile 表的 9 个 ALTER 已入批次 diff)
CREATE TABLE IF NOT EXISTS `smart_wallet_order_analysis` (
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

-- W1-T4 被拒/降级信号影子记账
CREATE TABLE IF NOT EXISTS `smart_wallet_signal_shadow` (
    `id`                           BIGINT       NOT NULL AUTO_INCREMENT,
    `trace_id`                     VARCHAR(32)  DEFAULT NULL COMMENT '跟单链路 trace_id, 关联 trade_event',
    `follow_task_id`               BIGINT       DEFAULT NULL COMMENT '关联 follow_task; 硬拒绝可能为 NULL',
    `source_event_id`              BIGINT       NOT NULL     COMMENT '关联 smart_wallet_trade_event.id',
    `market_info_id`               BIGINT       DEFAULT NULL COMMENT '关联 smart_wallet_market_info.id',
    `wallet_address`               VARCHAR(64)  DEFAULT NULL,
    `decision_status`              VARCHAR(32)  NOT NULL     COMMENT 'REJECTED / PAPER_SHADOW / DOWNGRADED',
    `reject_layer`                 VARCHAR(64)  DEFAULT NULL COMMENT '命中的风控门 (与 firstRejectGate 一致)',
    `reject_reasons`               TEXT         DEFAULT NULL COMMENT '拒绝原因列表, | 分隔',
    `signal_score`                 DECIMAL(10,4) DEFAULT NULL,
    `follow_probability`           DECIMAL(10,4) DEFAULT NULL,
    `side`                         VARCHAR(8)   DEFAULT NULL COMMENT 'BUY/SELL',
    `shadow_entry_price`           DECIMAL(18,6) DEFAULT NULL,
    `shadow_entry_size`            DECIMAL(18,6) DEFAULT NULL,
    `shadow_entry_cost_usdc`       DECIMAL(18,6) DEFAULT NULL,
    `shadow_entry_slippage_bps`    INT           DEFAULT NULL,
    `entry_estimator_source`       VARCHAR(16)  DEFAULT NULL COMMENT '第十四批: VWAP / PROXY',
    `shadow_exit_price`            DECIMAL(18,6) DEFAULT NULL,
    `shadow_exit_proceeds_usdc`    DECIMAL(18,6) DEFAULT NULL,
    `shadow_pnl_usdc`              DECIMAL(18,6) DEFAULT NULL,
    `shadow_settled_at`            DATETIME     DEFAULT NULL,
    `status`                       VARCHAR(32)  NOT NULL DEFAULT 'PENDING_SETTLEMENT'
                                                          COMMENT 'PENDING_SETTLEMENT / SETTLED / EXPIRED / NO_DEPTH / DRY_FALLBACK',
    `created_at`                   DATETIME     DEFAULT NULL,
    `updated_at`                   DATETIME     DEFAULT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_trace_id` (`trace_id`),
    KEY `idx_source_event` (`source_event_id`),
    KEY `idx_follow_task` (`follow_task_id`),
    KEY `idx_status_settled` (`status`, `shadow_settled_at`),
    KEY `idx_market_info` (`market_info_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='W1-T4 被拒/降级信号的影子记账与回填';

-- W1-T2.2 每次结算解析的审计快照
CREATE TABLE IF NOT EXISTS `pm_resolution_audit` (
    `id`                          BIGINT       NOT NULL AUTO_INCREMENT,
    `market_id`                   VARCHAR(128) NOT NULL COMMENT 'Polymarket conditionId / marketId',
    `observed_at`                 DATETIME     NOT NULL COMMENT '解析时刻 (服务器本地)',
    `resolution_status`           VARCHAR(32)  NOT NULL COMMENT 'RESOLVED / WAIT_FINAL_PRICE / NEED_MANUAL_CHECK / UNRESOLVED',
    `resolution_source`           VARCHAR(64)  DEFAULT NULL COMMENT 'TOKEN_WINNER / WINNING_TOKEN_ID / EXPLICIT_WINNER / FINAL_PRICE_INFERENCE',
    `winning_outcome`             VARCHAR(128) DEFAULT NULL,
    `winning_token_id`            VARCHAR(128) DEFAULT NULL,
    `manual_check_reason`         VARCHAR(255) DEFAULT NULL,
    `closed`                      TINYINT(1)   NOT NULL DEFAULT 0,
    `accepting_orders_present`    TINYINT(1)   NOT NULL DEFAULT 0,
    `accepting_orders`            TINYINT(1)   NOT NULL DEFAULT 0,
    `uma_resolution_status`       VARCHAR(64)  DEFAULT NULL,
    `uma_resolved`                TINYINT(1)   NOT NULL DEFAULT 0,
    `outcomes_json`               TEXT         DEFAULT NULL,
    `outcome_prices_json`         TEXT         DEFAULT NULL,
    `outcome_token_ids_json`      TEXT         DEFAULT NULL,
    `created_at`                  DATETIME     NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_market_observed` (`market_id`, `observed_at`),
    KEY `idx_observed_at` (`observed_at`),
    KEY `idx_resolution_status` (`resolution_status`, `observed_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='W1-T2.2 每次结算解析的审计快照';

-- W2-T1.2 市场流动性 + 价差分位 日级快照
CREATE TABLE IF NOT EXISTS `pm_market_liquidity_percentile_daily` (
    `id`                  BIGINT       NOT NULL AUTO_INCREMENT,
    `category`            VARCHAR(64)  NOT NULL  COMMENT 'market normalizedCategory (POLITICS / CRYPTO / SPORTS / WEATHER / OTHER ...)',
    `as_of_date`          DATE         NOT NULL  COMMENT '快照日期 (Asia/Shanghai)',
    `sample_count`        INT          NOT NULL  COMMENT '该品类当日纳入聚合的市场数',
    `liquidity_p10`       DECIMAL(20,2) DEFAULT NULL  COMMENT 'P10 流动性 USDC',
    `liquidity_p30`       DECIMAL(20,2) DEFAULT NULL  COMMENT 'P30 流动性 USDC (W2-T1 门控阈值)',
    `liquidity_p50`       DECIMAL(20,2) DEFAULT NULL,
    `liquidity_p70`       DECIMAL(20,2) DEFAULT NULL  COMMENT 'W2-T1.4 价差门预留: 流动性 P70',
    `liquidity_mean`      DECIMAL(20,2) DEFAULT NULL,
    `liquidity_max`       DECIMAL(20,2) DEFAULT NULL,
    `spread_bps_p70`      DECIMAL(10,2) DEFAULT NULL  COMMENT 'W2-T1.4: 该品类价差 P70 bps (后续 sprint 写入)',
    `computed_at`         DATETIME     NOT NULL,
    `created_at`          DATETIME     NOT NULL,
    `updated_at`          DATETIME     NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_category_date` (`category`, `as_of_date`),
    KEY `idx_as_of_date` (`as_of_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='W2-T1.2 市场流动性 + 价差分位 日级快照';

-- W1-T1 + 下一轮#7 Live↔Replay diff 日级历史
CREATE TABLE IF NOT EXISTS `live_replay_diff_history` (
    `id`                          BIGINT       NOT NULL AUTO_INCREMENT,
    `as_of_date`                  DATE         NOT NULL,
    `window_start`                DATETIME     DEFAULT NULL,
    `window_end`                  DATETIME     DEFAULT NULL,
    `replay_scanned_events`       INT          NOT NULL DEFAULT 0,
    `replay_accepted_events`      INT          NOT NULL DEFAULT 0,
    `replay_realized_pnl_usdc`    DECIMAL(20,4) DEFAULT NULL,
    `replay_policy_tag`           VARCHAR(64)  DEFAULT NULL,
    `live_task_total`             INT          NOT NULL DEFAULT 0,
    `live_task_non_skipped`       INT          NOT NULL DEFAULT 0,
    `live_task_skipped`           INT          NOT NULL DEFAULT 0,
    `live_realized_pnl_usdc`      DECIMAL(20,4) DEFAULT NULL,
    `signal_accepted_diff`        INT          NOT NULL DEFAULT 0,
    `signal_miss_pct`             DECIMAL(10,4) DEFAULT NULL,
    `pnl_diff_usdc`               DECIMAL(20,4) DEFAULT NULL,
    `pnl_diff_bps`                DECIMAL(20,4) DEFAULT NULL,
    `pnl_diff_threshold_bps`      INT          DEFAULT NULL,
    `signal_miss_threshold_pct`   DECIMAL(10,4) DEFAULT NULL,
    `pnl_diff_exceeded`           TINYINT(1)   NOT NULL DEFAULT 0,
    `signal_miss_exceeded`        TINYINT(1)   NOT NULL DEFAULT 0,
    `created_at`                  DATETIME     NOT NULL,
    `updated_at`                  DATETIME     NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_as_of_date` (`as_of_date`),
    KEY `idx_exceeded` (`pnl_diff_exceeded`, `signal_miss_exceeded`, `as_of_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='W1-T1 + 下一轮#7 Live↔Replay diff 日级历史';

-- 第十八批 W2-T2 灰度自动化: leader 滑动窗口胜率日级聚合
CREATE TABLE IF NOT EXISTS `leader_rolling_aggregation_daily` (
    `id`                       BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    `as_of_date`               DATE         NOT NULL COMMENT '快照日期 (Asia/Shanghai)',
    `lookback_days`            INT          NOT NULL COMMENT '聚合 lookback 窗口天数',
    `window_size`              INT          NOT NULL COMMENT 'leader rolling 窗口大小 (笔)',
    `min_samples`              INT          NOT NULL COMMENT '触发 ACTIVE / COOLING 判定的最小样本数',
    `min_win_rate`             DECIMAL(10, 4) NOT NULL COMMENT 'COOLING 阈值 (winRate < this → COOLING)',
    `active_leaders`           INT          NOT NULL COMMENT '窗口内有 ENTRY 订单的 leader 数 (含 cold)',
    `evaluated_count`          INT          NOT NULL COMMENT '真正参与 ACTIVE/COOLING 判定的 leader 数',
    `active_count`             INT          NOT NULL COMMENT 'ACTIVE 状态 leader 数',
    `cooling_count`            INT          NOT NULL COMMENT 'COOLING 状态 leader 数',
    `cold_count`               INT          NOT NULL COMMENT 'COLD (样本不足) leader 数',
    `cooling_ratio`            DECIMAL(10, 4) DEFAULT NULL COMMENT 'coolingCount / evaluatedCount, 关键灰度指标',
    `overall_win_rate`         DECIMAL(10, 4) DEFAULT NULL COMMENT '所有评估 leader 的全局加权 win rate',
    `health_status`            VARCHAR(32)  NOT NULL DEFAULT 'OK' COMMENT 'OK / WARN_HIGH_COOLING / WARN_LOW_WIN_RATE / WARN_BOTH',
    `top_cooling_leaders_json` TEXT         DEFAULT NULL COMMENT 'top-N 低胜率 leader 简短 JSON (供运维快速 review)',
    `computed_at`              DATETIME     NOT NULL COMMENT '快照计算时间',
    `created_at`               DATETIME     NOT NULL COMMENT '行创建时间',
    `updated_at`               DATETIME     NOT NULL COMMENT '行最后更新时间',
    UNIQUE KEY `uk_as_of_date_lookback` (`as_of_date`, `lookback_days`),
    KEY `idx_as_of_date` (`as_of_date`),
    KEY `idx_health_status` (`health_status`, `as_of_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='W2-T2 leader 滑动窗口胜率灰度日级历史 (LeaderRollingMonitorJob 写入)';


-- =================================================================
-- 天气套利策略 6 表 (weather_arbitrage_migration 收口, smart_wallet_position
-- 的 source 列 ALTER 已合入批次 diff 文件)
-- =================================================================

CREATE TABLE IF NOT EXISTS `weather_arbitrage_signal_log` (
    `id`           BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    `market_id`    VARCHAR(128) NOT NULL COMMENT 'Polymarket conditionId',
    `city`         VARCHAR(64)  NOT NULL,
    `market_price` DECIMAL(20, 8) NOT NULL DEFAULT 0,
    `fair_value`   DECIMAL(20, 8) NOT NULL DEFAULT 0,
    `edge`         DECIMAL(20, 8) NOT NULL DEFAULT 0,
    `confidence`   DECIMAL(20, 8) NOT NULL DEFAULT 0,
    `forecast_age_minutes` INT NOT NULL DEFAULT 0,
    `lead_hours`   INT NOT NULL DEFAULT 0,
    `calibration_version` VARCHAR(64) NOT NULL DEFAULT '',
    `mode`         VARCHAR(16) NOT NULL DEFAULT 'OFF' COMMENT 'OFF / SHADOW / PAPER / LIVE',
    `result_label` VARCHAR(16) NOT NULL DEFAULT '' COMMENT 'YES / NO / UNKNOWN',
    `action`       VARCHAR(16)  NOT NULL COMMENT 'PASS / BUY_YES / BUY_NO',
    `reason`       VARCHAR(512) NOT NULL DEFAULT '',
    `scanned_at`   DATETIME     NOT NULL,
    `created_at`   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_signal_log_city_scanned` (`city`, `scanned_at`),
    INDEX `idx_signal_log_market`       (`market_id`, `scanned_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='天气套利信号扫描日志';

CREATE TABLE IF NOT EXISTS `weather_forecast_snapshot` (
    `id`              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    `city`            VARCHAR(64) NOT NULL,
    `forecast_date`   DATE NOT NULL COMMENT '预报发布日期',
    `target_date`     DATE NOT NULL COMMENT '目标结算日期',
    `temp_max_f`      DECIMAL(10, 4) NOT NULL DEFAULT 0,
    `temp_min_f`      DECIMAL(10, 4) NOT NULL DEFAULT 0,
    `temp_avg_f`      DECIMAL(10, 4) NOT NULL DEFAULT 0,
    `provider`        VARCHAR(64) NOT NULL DEFAULT '',
    `lead_hours`      INT NOT NULL DEFAULT 0,
    `data_source_count` INT NOT NULL DEFAULT 1,
    `fetched_at`      DATETIME NOT NULL,
    `created_at`      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_weather_forecast_city_target` (`city`, `target_date`, `fetched_at`),
    INDEX `idx_weather_forecast_provider` (`provider`, `target_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='天气预报快照';

CREATE TABLE IF NOT EXISTS `weather_observation` (
    `id`              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    `city`            VARCHAR(64) NOT NULL,
    `target_date`     DATE NOT NULL COMMENT '实际观测日期',
    `actual_max_f`    DECIMAL(10, 4) NOT NULL DEFAULT 0,
    `actual_min_f`    DECIMAL(10, 4) NOT NULL DEFAULT 0,
    `source`          VARCHAR(64) NOT NULL DEFAULT '',
    `resolved_at`     DATETIME NOT NULL,
    `created_at`      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_weather_observation_city_date` (`city`, `target_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='天气实际观测结果';

CREATE TABLE IF NOT EXISTS `weather_calibration_profile` (
    `id`              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    `city`            VARCHAR(64) NOT NULL,
    `month`           INT NOT NULL,
    `lead_hour_bucket` INT NOT NULL,
    `sigma_max_f`     DECIMAL(10, 4) NOT NULL DEFAULT 0,
    `sigma_min_f`     DECIMAL(10, 4) NOT NULL DEFAULT 0,
    `sample_size`     INT NOT NULL DEFAULT 0,
    `version`         VARCHAR(64) NOT NULL DEFAULT '',
    `updated_at`      DATETIME NOT NULL,
    `created_at`      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_weather_calibration_profile` (`city`, `month`, `lead_hour_bucket`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='天气模型校准画像';

CREATE TABLE IF NOT EXISTS `weather_market_parse_log` (
    `id`              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    `market_id`       VARCHAR(128) NOT NULL,
    `question`        VARCHAR(512) NOT NULL DEFAULT '',
    `parse_status`    VARCHAR(32) NOT NULL DEFAULT 'PASS' COMMENT 'PARSED / REJECTED / LOW_CONFIDENCE',
    `reason`          VARCHAR(512) NOT NULL DEFAULT '',
    `confidence`      DECIMAL(20, 8) NOT NULL DEFAULT 0,
    `created_at`      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_weather_parse_market` (`market_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='天气市场解析日志';

CREATE TABLE IF NOT EXISTS `weather_paper_position` (
    `id`              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    `market_id`       VARCHAR(128) NOT NULL,
    `token_id`        VARCHAR(128) NOT NULL DEFAULT '',
    `city`            VARCHAR(64) NOT NULL,
    `question`        VARCHAR(512) NOT NULL DEFAULT '',
    `side`            VARCHAR(16) NOT NULL DEFAULT '',
    `entry_price`     DECIMAL(20, 8) NOT NULL DEFAULT 0,
    `quantity`        DECIMAL(20, 8) NOT NULL DEFAULT 0,
    `stake_usdc`      DECIMAL(20, 8) NOT NULL DEFAULT 0,
    `fair_value`      DECIMAL(20, 8) NOT NULL DEFAULT 0,
    `confidence`      DECIMAL(20, 8) NOT NULL DEFAULT 0,
    `market_end_at`   DATETIME NOT NULL,
    `status`          VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    `opened_at`       DATETIME NOT NULL,
    `closed_at`       DATETIME NULL,
    `exit_reason`     VARCHAR(128) NOT NULL DEFAULT '',
    `created_at`      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_weather_paper_status` (`status`, `opened_at`),
    INDEX `idx_weather_paper_market` (`market_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='天气策略纸面持仓';




