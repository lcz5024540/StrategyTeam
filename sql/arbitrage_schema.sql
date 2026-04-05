-- =================================================================
-- 数据库表结构设计 (Database Schema Design)
-- 适用于 Binance 资金费率套利策略
-- =================================================================

-- 1. 资金费率历史表 (Funding Rate History)
-- 用于存储历史资金费率，避免重复查询 API，支持趋势分析
CREATE TABLE IF NOT EXISTS `funding_rate_history` (
    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    `symbol` VARCHAR(20) NOT NULL COMMENT '交易对 (e.g., BTCUSDT)',
    `funding_rate` DECIMAL(12, 8) NOT NULL COMMENT '资金费率 (e.g., 0.0001)',
    `mark_price` DECIMAL(20, 8) COMMENT '记录时的标记价格',
    `funding_time` BIGINT NOT NULL COMMENT '资金费率结算时间戳 (毫秒)',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    UNIQUE KEY `uk_symbol_time` (`symbol`, `funding_time`),
    INDEX `idx_symbol_rate` (`symbol`, `funding_rate`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资金费率历史记录表';

-- 2. 套利策略仓位表 (Arbitrage Positions)
-- 用于持久化策略状态，确保程序重启后能找回仓位并正确平仓
CREATE TABLE IF NOT EXISTS `arbitrage_position` (
    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    `symbol` VARCHAR(20) NOT NULL COMMENT '交易对',
    `strategy_type` VARCHAR(50) DEFAULT 'FUNDING_RATE_ARBITRAGE' COMMENT '策略类型',
    `status` VARCHAR(20) NOT NULL COMMENT '状态: OPENING, OPENED, CLOSING, CLOSED, FAILED',
    
    -- 开仓信息
    `entry_time` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '开仓时间',
    `spot_buy_order_id` VARCHAR(64) COMMENT '现货买入订单ID',
    `futures_sell_order_id` VARCHAR(64) COMMENT '合约做空订单ID',
    `entry_spot_price` DECIMAL(20, 8) COMMENT '现货开仓均价',
    `entry_futures_price` DECIMAL(20, 8) COMMENT '合约开仓均价',
    `quantity` DECIMAL(20, 8) NOT NULL COMMENT '持仓数量 (币)',
    `total_investment` DECIMAL(20, 8) COMMENT '总投入USDT (双边)',
    
    -- 平仓信息
    `exit_time` TIMESTAMP NULL COMMENT '平仓时间',
    `spot_sell_order_id` VARCHAR(64) COMMENT '现货卖出订单ID',
    `futures_buy_order_id` VARCHAR(64) COMMENT '合约平空订单ID',
    `exit_spot_price` DECIMAL(20, 8) COMMENT '现货平仓均价',
    `exit_futures_price` DECIMAL(20, 8) COMMENT '合约平仓均价',
    
    -- 收益统计
    `realized_pnl` DECIMAL(20, 8) DEFAULT 0 COMMENT '已实现盈亏 (USDT)',
    `funding_fees_earned` DECIMAL(20, 8) DEFAULT 0 COMMENT '累计赚取的资金费 (预估)',
    
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='套利策略仓位表';

-- 3. 策略运行日志表 (可选) (Strategy Execution Log)
-- 记录每次策略运行的决策过程，便于排查为什么开单或不开单
CREATE TABLE IF NOT EXISTS `strategy_execution_log` (
    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    `symbol` VARCHAR(20) NOT NULL,
    `check_time` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `current_funding_rate` DECIMAL(12, 8),
    `action_taken` VARCHAR(50) COMMENT '执行动作: OPEN, CLOSE, SKIP, WAIT',
    `message` TEXT COMMENT '详细日志信息',
    INDEX `idx_symbol_time` (`symbol`, `check_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='策略运行日志';

-- 4. Polymarket 聪明钱包画像表
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
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY `uk_wallet_chain_source` (`wallet_address`, `chain`, `source`),
    INDEX `idx_profile_qualified` (`qualified`, `watch_enabled`, `wallet_score`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Polymarket聪明钱包画像表';

-- 5. Polymarket 钱包开单事件表
CREATE TABLE IF NOT EXISTS `smart_wallet_trade_event` (
    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    `wallet_address` VARCHAR(128) NOT NULL COMMENT '钱包地址',
    `chain` VARCHAR(32) NOT NULL DEFAULT 'polygon' COMMENT '链',
    `source` VARCHAR(32) NOT NULL DEFAULT 'POLYMARKET' COMMENT '来源',
    `external_trade_id` VARCHAR(128) NOT NULL COMMENT '外部交易ID',
    `market_id` VARCHAR(128) NOT NULL COMMENT '市场ID',
    `question` VARCHAR(512) DEFAULT NULL COMMENT '问题描述',
    `outcome` VARCHAR(16) NOT NULL COMMENT 'YES/NO',
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

-- 6. Polymarket 跟单任务表
CREATE TABLE IF NOT EXISTS `smart_wallet_follow_task` (
    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    `wallet_address` VARCHAR(128) NOT NULL COMMENT '触发钱包地址',
    `source_event_id` BIGINT UNSIGNED NOT NULL COMMENT '来源交易事件ID',
    `market_id` VARCHAR(128) NOT NULL COMMENT '市场ID',
    `question` VARCHAR(512) DEFAULT NULL COMMENT '问题描述',
    `outcome` VARCHAR(16) NOT NULL COMMENT 'YES/NO',
    `side` VARCHAR(16) NOT NULL COMMENT 'BUY/SELL',
    `trigger_price` DECIMAL(10, 4) NOT NULL DEFAULT 0 COMMENT '触发价格',
    `trigger_size_usdc` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '触发钱包仓位',
    `recommended_stake_usdc` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '建议跟单金额',
    `status` VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/FOLLOWED/SKIPPED/FAILED',
    `execution_mode` VARCHAR(16) NOT NULL DEFAULT 'PAPER' COMMENT 'PAPER/LIVE',
    `external_order_id` VARCHAR(128) DEFAULT NULL COMMENT '跟单订单ID',
    `error_message` VARCHAR(512) DEFAULT NULL COMMENT '错误信息',
    `scheduled_at` TIMESTAMP NOT NULL COMMENT '计划执行时间',
    `executed_at` TIMESTAMP NULL COMMENT '实际执行时间',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY `uk_follow_source_event` (`source_event_id`),
    INDEX `idx_follow_status_time` (`status`, `scheduled_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Polymarket跟单任务表';

-- 7. Polymarket 跟单订单表
CREATE TABLE IF NOT EXISTS `smart_wallet_order` (
    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    `follow_task_id` BIGINT UNSIGNED NOT NULL COMMENT '跟单任务ID',
    `wallet_address` VARCHAR(128) NOT NULL COMMENT '触发钱包地址',
    `execution_mode` VARCHAR(16) NOT NULL DEFAULT 'PAPER' COMMENT 'PAPER/LIVE',
    `execution_address` VARCHAR(128) DEFAULT NULL COMMENT '执行钱包地址',
    `external_order_id` VARCHAR(128) DEFAULT NULL COMMENT '外部订单ID',
    `market_id` VARCHAR(128) NOT NULL COMMENT '条件市场ID',
    `token_id` VARCHAR(128) DEFAULT NULL COMMENT 'CLOB outcome token id',
    `question` VARCHAR(512) DEFAULT NULL COMMENT '问题描述',
    `outcome` VARCHAR(16) NOT NULL COMMENT 'YES/NO',
    `side` VARCHAR(16) NOT NULL COMMENT 'BUY/SELL',
    `order_type` VARCHAR(16) NOT NULL DEFAULT 'GTC' COMMENT '订单类型',
    `status` VARCHAR(32) NOT NULL DEFAULT 'SUBMITTED' COMMENT 'SUBMITTED/FILLED/CANCELED/FAILED',
    `price` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '委托价格',
    `size` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '份额数量',
    `notional_usdc` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '委托名义USDC',
    `fee_usdc` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '手续费',
    `error_message` VARCHAR(512) DEFAULT NULL COMMENT '错误信息',
    `placed_at` TIMESTAMP NOT NULL COMMENT '下单时间',
    `filled_at` TIMESTAMP NULL COMMENT '成交时间',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY `uk_order_external` (`external_order_id`),
    INDEX `idx_order_follow_task` (`follow_task_id`),
    INDEX `idx_order_market_status` (`market_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Polymarket跟单订单表';

-- 8. Polymarket 成交明细表
CREATE TABLE IF NOT EXISTS `smart_wallet_fill` (
    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    `order_id` BIGINT UNSIGNED NOT NULL COMMENT '订单ID',
    `follow_task_id` BIGINT UNSIGNED NOT NULL COMMENT '跟单任务ID',
    `wallet_address` VARCHAR(128) NOT NULL COMMENT '触发钱包地址',
    `execution_address` VARCHAR(128) DEFAULT NULL COMMENT '执行钱包地址',
    `market_id` VARCHAR(128) NOT NULL COMMENT '条件市场ID',
    `token_id` VARCHAR(128) DEFAULT NULL COMMENT 'CLOB outcome token id',
    `question` VARCHAR(512) DEFAULT NULL COMMENT '问题描述',
    `outcome` VARCHAR(16) NOT NULL COMMENT 'YES/NO',
    `side` VARCHAR(16) NOT NULL COMMENT 'BUY/SELL',
    `price` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '成交价格',
    `size` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '成交份额',
    `notional_usdc` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '成交名义USDC',
    `fee_usdc` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '手续费',
    `filled_at` TIMESTAMP NOT NULL COMMENT '成交时间',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX `idx_fill_order` (`order_id`),
    INDEX `idx_fill_market_time` (`market_id`, `filled_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Polymarket成交明细表';

-- 9. Polymarket 持仓表
CREATE TABLE IF NOT EXISTS `smart_wallet_position` (
    `id` BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    `execution_address` VARCHAR(128) NOT NULL COMMENT '执行钱包地址',
    `market_id` VARCHAR(128) NOT NULL COMMENT '条件市场ID',
    `token_id` VARCHAR(128) DEFAULT NULL COMMENT 'CLOB outcome token id',
    `question` VARCHAR(512) DEFAULT NULL COMMENT '问题描述',
    `outcome` VARCHAR(16) NOT NULL COMMENT 'YES/NO',
    `net_side` VARCHAR(16) NOT NULL DEFAULT 'BUY' COMMENT '净方向',
    `quantity` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '净份额数量',
    `average_entry_price` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '平均开仓成本',
    `cost_basis_usdc` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '持仓成本',
    `realized_pnl_usdc` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '已实现收益',
    `unrealized_pnl_usdc` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '未实现收益',
    `last_mark_price` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '最新估值价格',
    `opened_at` TIMESTAMP NULL COMMENT '首次开仓时间',
    `last_filled_at` TIMESTAMP NULL COMMENT '最近成交时间',
    `closed_at` TIMESTAMP NULL COMMENT '平仓时间',
    `status` VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/CLOSED',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY `uk_position_execution_market_outcome` (`execution_address`, `market_id`, `outcome`),
    INDEX `idx_position_status` (`status`, `last_filled_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Polymarket持仓表';

-- 10. Polymarket 资金流水表
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

-- 11. Polymarket 每日报表表
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
    `win_rate` DECIMAL(10, 4) NOT NULL DEFAULT 0 COMMENT '胜率',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY `uk_daily_report_date_execution` (`report_date`, `execution_address`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Polymarket每日报表表';
