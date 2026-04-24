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
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY `uk_wallet_chain_source` (`wallet_address`, `chain`, `source`),
    INDEX `idx_profile_qualified` (`qualified`, `watch_enabled`, `wallet_score`)
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
    `scheduled_at` TIMESTAMP NOT NULL COMMENT '计划执行时间',
    `executed_at` TIMESTAMP NULL COMMENT '实际执行时间',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY `uk_follow_source_event` (`source_event_id`),
    INDEX `idx_follow_status_time` (`status`, `scheduled_at`),
    INDEX `idx_follow_task_market_info` (`market_info_id`),
    INDEX `idx_follow_entity` (`entity_id`)
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
    `last_mark_price` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '最新估值价格',
    `opened_at` TIMESTAMP NULL COMMENT '首次开仓时间',
    `last_filled_at` TIMESTAMP NULL COMMENT '最近成交时间',
    `closed_at` TIMESTAMP NULL COMMENT '平仓时间',
    `status` VARCHAR(32) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/PARTIALLY_CLOSED/CLOSED/RESOLVED_UNCLAIMED/CLAIMED',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
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
    `question`      VARCHAR(512) DEFAULT NULL COMMENT '市场问题描述',
    `outcome`       VARCHAR(128) NOT NULL COMMENT 'Polymarket outcome label (YES/NO/候选名)',
    `token_id`      VARCHAR(128) DEFAULT NULL COMMENT 'CLOB outcome token id',
    `index_set`     INT DEFAULT NULL COMMENT 'CTF indexSet bitmask (1=outcome0/YES, 2=outcome1/NO)',
    `market_end_at` BIGINT DEFAULT NULL COMMENT '市场预计结算时间戳(毫秒)',
    `created_at`    TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`    TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY `uk_market_outcome` (`market_id`, `outcome`),
    INDEX `idx_market_end_at` (`market_end_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='公共市场信息表 — 收敛跟单链路中各表的市场维度冗余字段';



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
