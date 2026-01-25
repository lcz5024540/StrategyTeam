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
