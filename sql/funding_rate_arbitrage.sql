-- =====================================================================
-- 单交易所资金费率套利策略表结构
-- 适用场景：同一交易所内，现货做多 + 永续合约做空 的 Spot-Futures Arbitrage
-- 设计目标：
-- 1. 兼容当前项目中的 single_exchange_funding_rate_history / single_exchange_arbitrage_position 实体
-- 2. 支撑开仓信号记录、双侧订单执行、资金费结算、风控快照等运行数据
-- 3. 为后续持仓恢复、收益归因、回测复盘和告警联动预留字段
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 1. 资金费率历史表
-- 说明：
-- - 对应当前项目实体：SingleExchangeFundingRateHistory
-- - 用于缓存历史 funding、mark price、next funding time
-- - 既可支持策略过滤，也可支持复盘和收益归因
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `single_exchange_funding_rate_history` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `exchange_name` VARCHAR(32) NOT NULL DEFAULT 'BINANCE' COMMENT '交易所名称，单交易所策略当前默认 BINANCE',
    `symbol` VARCHAR(32) NOT NULL COMMENT '交易对，例如 BTCUSDT',
    `funding_rate` DECIMAL(12, 8) NOT NULL COMMENT '本期/当前资金费率',
    `predicted_funding_rate` DECIMAL(12, 8) DEFAULT NULL COMMENT '预测的下一期资金费率，可空',
    `mark_price` DECIMAL(20, 8) DEFAULT NULL COMMENT '标记价格',
    `index_price` DECIMAL(20, 8) DEFAULT NULL COMMENT '指数价格',
    `next_funding_time` BIGINT DEFAULT NULL COMMENT '下一次资金费结算时间戳（毫秒）',
    `funding_time` BIGINT NOT NULL COMMENT '当前记录对应的资金费结算时间戳（毫秒）',
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_funding_rate_exchange_symbol_time` (`exchange_name`, `symbol`, `funding_time`),
    KEY `idx_funding_rate_symbol_time` (`symbol`, `funding_time`),
    KEY `idx_funding_rate_exchange_symbol_created` (`exchange_name`, `symbol`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='单交易所资金费率历史表';

-- ---------------------------------------------------------------------
-- 2. 套利持仓主表
-- 说明：
-- - 对应当前项目实体：SingleExchangeArbitragePosition
-- - 一条记录代表一笔完整的单交易所期现套利持仓
-- - 既记录开平仓订单信息，也记录开仓时的 funding / basis 快照
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `single_exchange_arbitrage_position` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `position_code` VARCHAR(64) NOT NULL COMMENT '业务唯一持仓编号，便于日志和订单关联',
    `exchange_name` VARCHAR(32) NOT NULL DEFAULT 'BINANCE' COMMENT '交易所名称',
    `symbol` VARCHAR(32) NOT NULL COMMENT '主交易对，例如 BTCUSDT',
    `spot_symbol` VARCHAR(32) NOT NULL COMMENT '现货交易对',
    `futures_symbol` VARCHAR(32) NOT NULL COMMENT '永续合约交易对',
    `strategy_type` VARCHAR(64) NOT NULL DEFAULT 'SPOT_FUTURES_SINGLE_EXCHANGE' COMMENT '策略类型',
    `status` VARCHAR(24) NOT NULL COMMENT '状态：OPENING/OPENED/CLOSING/CLOSED/FAILED/CANCELED',
    `leverage` INT NOT NULL DEFAULT 1 COMMENT '合约空单使用的杠杆倍数',
    `planned_usdt_amount` DECIMAL(20, 8) DEFAULT NULL COMMENT '策略预算金额（USDT）',
    `quantity` DECIMAL(28, 12) NOT NULL COMMENT '持仓币数量',
    `quantity_step` DECIMAL(28, 12) DEFAULT NULL COMMENT '下单数量步进',
    `spot_notional` DECIMAL(20, 8) DEFAULT NULL COMMENT '现货名义金额',
    `futures_notional` DECIMAL(20, 8) DEFAULT NULL COMMENT '合约名义金额',
    `total_investment` DECIMAL(20, 8) DEFAULT NULL COMMENT '总投入USDT（兼容现有实体字段）',
    `entry_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '开仓时间',
    `entry_funding_rate` DECIMAL(12, 8) DEFAULT NULL COMMENT '开仓时当前资金费率',
    `entry_predicted_funding_rate` DECIMAL(12, 8) DEFAULT NULL COMMENT '开仓时预测下一期资金费率',
    `entry_basis_spread` DECIMAL(20, 8) DEFAULT NULL COMMENT '开仓时基差（合约价 - 现货价）',
    `entry_basis_rate` DECIMAL(12, 8) DEFAULT NULL COMMENT '开仓时基差率',
    `entry_next_funding_time` BIGINT DEFAULT NULL COMMENT '开仓时下一次 funding 结算时间戳（毫秒）',
    `entry_spot_price` DECIMAL(20, 8) DEFAULT NULL COMMENT '现货开仓均价',
    `entry_futures_price` DECIMAL(20, 8) DEFAULT NULL COMMENT '合约开仓均价',
    `spot_buy_order_id` VARCHAR(64) DEFAULT NULL COMMENT '现货买入订单ID',
    `futures_sell_order_id` VARCHAR(64) DEFAULT NULL COMMENT '合约做空订单ID',
    `open_reason` VARCHAR(255) DEFAULT NULL COMMENT '开仓原因摘要',
    `exit_time` DATETIME DEFAULT NULL COMMENT '平仓时间',
    `exit_funding_rate` DECIMAL(12, 8) DEFAULT NULL COMMENT '平仓时当前资金费率',
    `exit_basis_spread` DECIMAL(20, 8) DEFAULT NULL COMMENT '平仓时基差',
    `exit_basis_rate` DECIMAL(12, 8) DEFAULT NULL COMMENT '平仓时基差率',
    `exit_spot_price` DECIMAL(20, 8) DEFAULT NULL COMMENT '现货平仓均价',
    `exit_futures_price` DECIMAL(20, 8) DEFAULT NULL COMMENT '合约平仓均价',
    `spot_sell_order_id` VARCHAR(64) DEFAULT NULL COMMENT '现货卖出订单ID',
    `futures_buy_order_id` VARCHAR(64) DEFAULT NULL COMMENT '合约平空订单ID',
    `close_reason` VARCHAR(255) DEFAULT NULL COMMENT '平仓原因摘要',
    `realized_pnl` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '已实现收益（USDT）',
    `funding_fees_earned` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '累计资金费收益（USDT）',
    `fee_cost` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '累计手续费成本（USDT）',
    `slippage_cost` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '累计滑点成本（USDT）',
    `error_message` VARCHAR(1024) DEFAULT NULL COMMENT '失败或异常信息',
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_arb_position_code` (`position_code`),
    KEY `idx_arb_position_symbol_status` (`symbol`, `status`),
    KEY `idx_arb_position_exchange_status_entry` (`exchange_name`, `status`, `entry_time`),
    KEY `idx_arb_position_strategy_type` (`strategy_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='单交易所资金费率套利持仓主表';

-- ---------------------------------------------------------------------
-- 3. 套利订单执行表
-- 说明：
-- - 一笔持仓通常会拆成四类订单：现货开仓、合约开仓、现货平仓、合约平仓
-- - 用于记录双侧执行明细，支撑回滚、对账、手续费归因和成交复盘
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `single_exchange_arbitrage_order` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `position_id` BIGINT UNSIGNED NOT NULL COMMENT '关联持仓ID，逻辑关联字段',
    `position_code` VARCHAR(64) NOT NULL COMMENT '关联持仓编号，逻辑关联字段',
    `exchange_name` VARCHAR(32) NOT NULL DEFAULT 'BINANCE' COMMENT '交易所名称',
    `symbol` VARCHAR(32) NOT NULL COMMENT '订单交易对',
    `market_type` VARCHAR(16) NOT NULL COMMENT '市场类型：SPOT/FUTURES',
    `trade_phase` VARCHAR(16) NOT NULL COMMENT '交易阶段：OPEN/CLOSE/ROLLBACK',
    `hedge_side` VARCHAR(16) NOT NULL COMMENT '对冲侧：SPOT/FUTURES',
    `side` VARCHAR(8) NOT NULL COMMENT '买卖方向：BUY/SELL',
    `position_side` VARCHAR(16) DEFAULT NULL COMMENT '仓位方向：LONG/SHORT/BOTH',
    `order_type` VARCHAR(16) NOT NULL DEFAULT 'MARKET' COMMENT '订单类型：MARKET/LIMIT/IOC/FOK',
    `status` VARCHAR(24) NOT NULL COMMENT '订单状态：NEW/FILLED/CANCELED/REJECTED/FAILED',
    `exchange_order_id` VARCHAR(64) DEFAULT NULL COMMENT '交易所订单ID',
    `client_order_id` VARCHAR(64) DEFAULT NULL COMMENT '客户端订单ID',
    `requested_qty` DECIMAL(28, 12) NOT NULL COMMENT '请求下单数量',
    `executed_qty` DECIMAL(28, 12) NOT NULL DEFAULT 0 COMMENT '实际成交数量',
    `requested_price` DECIMAL(20, 8) DEFAULT NULL COMMENT '请求价格，市价单可空',
    `avg_price` DECIMAL(20, 8) DEFAULT NULL COMMENT '成交均价',
    `quote_qty` DECIMAL(20, 8) DEFAULT NULL COMMENT '成交名义金额',
    `fee_amount` DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '手续费金额',
    `fee_asset` VARCHAR(16) DEFAULT NULL COMMENT '手续费资产',
    `placed_at` DATETIME DEFAULT NULL COMMENT '下单时间',
    `filled_at` DATETIME DEFAULT NULL COMMENT '成交时间',
    `error_message` VARCHAR(1024) DEFAULT NULL COMMENT '失败信息',
    `raw_response` LONGTEXT DEFAULT NULL COMMENT '交易所原始响应',
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_arb_order_position` (`position_id`, `trade_phase`),
    KEY `idx_arb_order_exchange_order` (`exchange_name`, `exchange_order_id`),
    KEY `idx_arb_order_symbol_time` (`symbol`, `placed_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='单交易所资金费率套利订单执行表';

-- ---------------------------------------------------------------------
-- 4. 套利机会/决策快照表
-- 说明：
-- - 记录每次策略轮询时的 funding、basis、价格、保证金和决策结果
-- - 用于回答“为什么开仓 / 为什么不开仓 / 为什么平仓”
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `single_exchange_arbitrage_signal_snapshot` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `exchange_name` VARCHAR(32) NOT NULL DEFAULT 'BINANCE' COMMENT '交易所名称',
    `symbol` VARCHAR(32) NOT NULL COMMENT '交易对',
    `position_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '关联持仓ID，逻辑关联字段，可空',
    `check_stage` VARCHAR(24) NOT NULL COMMENT '检查阶段：OPEN_CHECK/CLOSE_CHECK/RISK_CHECK/REBALANCE_CHECK',
    `current_funding_rate` DECIMAL(12, 8) DEFAULT NULL COMMENT '当前资金费率',
    `predicted_funding_rate` DECIMAL(12, 8) DEFAULT NULL COMMENT '预测下一期资金费率',
    `mark_price` DECIMAL(20, 8) DEFAULT NULL COMMENT '标记价格',
    `index_price` DECIMAL(20, 8) DEFAULT NULL COMMENT '指数价格',
    `spot_price` DECIMAL(20, 8) DEFAULT NULL COMMENT '现货价格',
    `futures_price` DECIMAL(20, 8) DEFAULT NULL COMMENT '合约价格',
    `basis_spread` DECIMAL(20, 8) DEFAULT NULL COMMENT '基差（合约价 - 现货价）',
    `basis_rate` DECIMAL(12, 8) DEFAULT NULL COMMENT '基差率',
    `estimated_round_trip_fee_rate` DECIMAL(12, 8) DEFAULT NULL COMMENT '预估双边手续费率',
    `estimated_slippage_rate` DECIMAL(12, 8) DEFAULT NULL COMMENT '预估滑点率',
    `estimated_net_yield_rate` DECIMAL(12, 8) DEFAULT NULL COMMENT '预估净收益率',
    `margin_ratio` DECIMAL(20, 8) DEFAULT NULL COMMENT '检查时保证金率',
    `next_funding_time` BIGINT DEFAULT NULL COMMENT '下一次资金费结算时间戳（毫秒）',
    `action_taken` VARCHAR(24) NOT NULL COMMENT '动作：OPEN/CLOSE/SKIP/WAIT/REJECT',
    `decision_reason` VARCHAR(255) DEFAULT NULL COMMENT '决策原因摘要',
    `snapshot_payload` LONGTEXT DEFAULT NULL COMMENT '补充快照JSON',
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_signal_snapshot_symbol_stage_time` (`symbol`, `check_stage`, `created_at`),
    KEY `idx_signal_snapshot_action_time` (`action_taken`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='单交易所资金费率套利决策快照表';

-- ---------------------------------------------------------------------
-- 5. 资金费结算流水表
-- 说明：
-- - 每次 funding 真正结算时记录一条流水
-- - 用于区分“预估 funding 收益”与“实际 funding 收益”
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `single_exchange_arbitrage_funding_settlement` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `position_id` BIGINT UNSIGNED NOT NULL COMMENT '关联持仓ID，逻辑关联字段',
    `position_code` VARCHAR(64) NOT NULL COMMENT '关联持仓编号，逻辑关联字段',
    `exchange_name` VARCHAR(32) NOT NULL DEFAULT 'BINANCE' COMMENT '交易所名称',
    `symbol` VARCHAR(32) NOT NULL COMMENT '交易对',
    `funding_time` BIGINT NOT NULL COMMENT '资金费结算时间戳（毫秒）',
    `funding_rate` DECIMAL(12, 8) NOT NULL COMMENT '本次结算对应资金费率',
    `mark_price` DECIMAL(20, 8) DEFAULT NULL COMMENT '结算时标记价格',
    `position_qty` DECIMAL(28, 12) NOT NULL COMMENT '参与结算的合约数量',
    `notional_value` DECIMAL(20, 8) DEFAULT NULL COMMENT '参与结算的名义价值',
    `settlement_amount` DECIMAL(20, 8) NOT NULL COMMENT '本次资金费结算金额，正数表示收到，负数表示支付',
    `direction` VARCHAR(16) NOT NULL COMMENT '方向：RECEIVED/PAID',
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_funding_settlement_position_time` (`position_id`, `funding_time`),
    KEY `idx_funding_settlement_symbol_time` (`symbol`, `funding_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='单交易所资金费率套利资金费结算流水表';

-- ---------------------------------------------------------------------
-- 6. 风控/保证金快照表
-- 说明：
-- - 记录开仓前、持仓中、再平衡检查时的风险状态
-- - 便于复盘为何被风控拦截，以及何时触发紧急平仓
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `single_exchange_arbitrage_risk_snapshot` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `position_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '关联持仓ID，逻辑关联字段，可空',
    `position_code` VARCHAR(64) DEFAULT NULL COMMENT '关联持仓编号，逻辑关联字段，可空',
    `exchange_name` VARCHAR(32) NOT NULL DEFAULT 'BINANCE' COMMENT '交易所名称',
    `symbol` VARCHAR(32) NOT NULL COMMENT '交易对',
    `check_stage` VARCHAR(24) NOT NULL COMMENT '风控阶段：PRE_OPEN/HOLDING/PRE_CLOSE/REBALANCE',
    `spot_usdt_balance` DECIMAL(20, 8) DEFAULT NULL COMMENT '现货账户 USDT 可用余额',
    `futures_available_balance` DECIMAL(20, 8) DEFAULT NULL COMMENT '期货账户可用余额',
    `required_margin` DECIMAL(20, 8) DEFAULT NULL COMMENT '所需初始保证金',
    `buffered_required_margin` DECIMAL(20, 8) DEFAULT NULL COMMENT '加入安全缓冲后的所需保证金',
    `margin_ratio` DECIMAL(20, 8) DEFAULT NULL COMMENT '保证金率',
    `risk_passed` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否通过风控检查',
    `rejection_reason` VARCHAR(255) DEFAULT NULL COMMENT '拒绝原因或风险说明',
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_risk_snapshot_symbol_stage_time` (`symbol`, `check_stage`, `created_at`),
    KEY `idx_risk_snapshot_passed_time` (`risk_passed`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='单交易所资金费率套利风控快照表';

-- ---------------------------------------------------------------------
-- 7. 再平衡流水表
-- 说明：
-- - 记录每次进入再平衡动作后的最终结果，包括执行成功、条件不满足跳过和异常失败
-- - 用于审计“为什么触发了划转 / 为什么没有划转 / 实际划转了多少”
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `single_exchange_arbitrage_rebalance_record` (
    `risk_snapshot_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '关联的再平衡风控快照ID，逻辑关联字段',
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `exchange_name` VARCHAR(32) NOT NULL DEFAULT 'BINANCE' COMMENT '交易所名称',
    `symbol` VARCHAR(32) DEFAULT NULL COMMENT '关联交易对',
    `asset` VARCHAR(16) NOT NULL COMMENT '划转资产，如 USDT',
    `transfer_type` VARCHAR(32) NOT NULL COMMENT '划转类型，如 MAIN_UMFUTURE',
    `status` VARCHAR(16) NOT NULL COMMENT '结果状态：EXECUTED/SKIPPED/FAILED',
    `reason_code` VARCHAR(32) NOT NULL COMMENT '结果原因：EXECUTED/PARTIAL_TRANSFER/PERMISSION_MISSING 等',
    `current_margin_ratio` DECIMAL(20, 8) DEFAULT NULL COMMENT '触发时当前保证金率',
    `threshold_margin_ratio` DECIMAL(20, 8) DEFAULT NULL COMMENT '触发阈值保证金率',
    `target_margin_ratio` DECIMAL(20, 8) DEFAULT NULL COMMENT '目标保证金率',
    `futures_margin_balance` DECIMAL(20, 8) DEFAULT NULL COMMENT '合约账户当前保证金余额',
    `futures_maint_margin` DECIMAL(20, 8) DEFAULT NULL COMMENT '合约账户维持保证金',
    `target_margin_balance` DECIMAL(20, 8) DEFAULT NULL COMMENT '按目标保证金率计算出的目标保证金余额',
    `required_transfer_amount` DECIMAL(20, 8) DEFAULT NULL COMMENT '理论应补充的资金金额',
    `available_spot_balance` DECIMAL(20, 8) DEFAULT NULL COMMENT '现货账户可用余额',
    `actual_transfer_amount` DECIMAL(20, 8) DEFAULT NULL COMMENT '实际划转金额',
    `transfer_id` BIGINT DEFAULT NULL COMMENT '交易所返回的划转流水号',
    `error_message` VARCHAR(1024) DEFAULT NULL COMMENT '失败原因或异常摘要',
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_rebalance_risk_snapshot` (`risk_snapshot_id`),
    KEY `idx_rebalance_status_time` (`status`, `created_at`),
    KEY `idx_rebalance_symbol_time` (`symbol`, `created_at`),
    KEY `idx_rebalance_transfer_id` (`transfer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='单交易所资金费率套利再平衡流水表';

-- ---------------------------------------------------------------------
-- 8. 单交易所策略运行日志表（可选）
-- 说明：
-- - 保留每次策略轮询或人工触发时的决策结果
-- - 适合快速排查“为什么开仓 / 为什么不开仓 / 为什么平仓”
-- - 若已全面接入 single_exchange_arbitrage_signal_snapshot，可将本表视为轻量日志补充
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `single_exchange_strategy_execution_log` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `symbol` VARCHAR(32) NOT NULL COMMENT '交易对，例如 BTCUSDT',
    `check_time` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '检查时间',
    `current_funding_rate` DECIMAL(12, 8) DEFAULT NULL COMMENT '检查时资金费率',
    `action_taken` VARCHAR(50) DEFAULT NULL COMMENT '执行动作：OPEN/CLOSE/SKIP/WAIT/REJECT',
    `message` TEXT DEFAULT NULL COMMENT '详细决策日志',
    PRIMARY KEY (`id`),
    KEY `idx_single_exchange_execution_log_symbol_time` (`symbol`, `check_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='单交易所策略运行日志表';

-- ---------------------------------------------------------------------
-- 8. 建议说明
-- ---------------------------------------------------------------------
-- 1) 当前项目若仅希望最小可用落地，可至少先使用：
--    single_exchange_funding_rate_history + single_exchange_arbitrage_position + single_exchange_arbitrage_order
--
-- 2) 若要支持稳定实盘运维，建议同时启用：
--    single_exchange_arbitrage_signal_snapshot
--    + single_exchange_arbitrage_funding_settlement
--    + single_exchange_arbitrage_risk_snapshot
--    + single_exchange_arbitrage_rebalance_record
--
-- 3) 若希望保留简洁的策略轮询日志，可额外启用：
--    single_exchange_strategy_execution_log
--
-- 4) 当前 Java 实体已直接对应：
--    single_exchange_funding_rate_history / single_exchange_arbitrage_position
--    其余表为单交易所资金费率套利策略的辅助运行表
