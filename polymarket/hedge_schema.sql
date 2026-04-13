-- ============================================================
-- Polymarket 对冲套利策略 数据库表结构
-- 命名前缀 hedge_ 与 smart_wallet_ 完全隔离
-- ============================================================

-- 1. 对冲持仓主表（每次双边建仓记录一行）
CREATE TABLE IF NOT EXISTS `hedge_position` (
    `id`                    BIGINT          NOT NULL AUTO_INCREMENT,
    `market_id`             VARCHAR(128)    NOT NULL COMMENT 'Polymarket 市场 ID',
    `question`              VARCHAR(512)    NOT NULL COMMENT '市场问题文本',
    `yes_token_id`          VARCHAR(128)    NOT NULL COMMENT 'YES token ID',
    `no_token_id`           VARCHAR(128)    NOT NULL COMMENT 'NO token ID',

    -- 建仓价格（买入价格 = ask 价）
    `yes_entry_price`       DECIMAL(18,6)   NOT NULL COMMENT 'YES 买入价格',
    `no_entry_price`        DECIMAL(18,6)   NOT NULL COMMENT 'NO 买入价格',
    -- 理论套利边际 = 1 - yes_entry_price - no_entry_price
    `prob_deviation`        DECIMAL(18,6)   NOT NULL COMMENT '概率偏差 (1 - P_yes - P_no)',
    `gross_edge_rate`       DECIMAL(18,6)   NOT NULL COMMENT '毛利率 = prob_deviation',

    -- 仓位数量与成本
    `quantity`              DECIMAL(18,6)   NOT NULL COMMENT '双边均等数量（股数）',
    `yes_cost_usdc`         DECIMAL(18,4)   NOT NULL COMMENT 'YES 边成本 USDC',
    `no_cost_usdc`          DECIMAL(18,4)   NOT NULL COMMENT 'NO 边成本 USDC',
    `total_cost_usdc`       DECIMAL(18,4)   NOT NULL COMMENT '总成本 USDC',
    `expected_profit_usdc`  DECIMAL(18,4)   NOT NULL COMMENT '预期利润 USDC（建仓时估算）',

    -- 流动性快照（建仓时记录）
    `yes_liquidity_usdc`    DECIMAL(18,2)   DEFAULT NULL,
    `no_liquidity_usdc`     DECIMAL(18,2)   DEFAULT NULL,

    -- 执行账户
    `execution_address`     VARCHAR(64)     DEFAULT NULL COMMENT '执行钱包地址',

    -- 状态流转: OPEN → SETTLING → SETTLED | CANCELLED
    `status`                VARCHAR(32)     NOT NULL DEFAULT 'OPEN',
    `final_outcome`         VARCHAR(8)      DEFAULT NULL COMMENT '结算结果: YES | NO',
    `claimed_usdc`          DECIMAL(18,4)   DEFAULT NULL COMMENT '实际领取 USDC',
    `fee_usdc`              DECIMAL(18,4)   DEFAULT NULL COMMENT '手续费 USDC',
    `realized_pnl_usdc`     DECIMAL(18,4)   DEFAULT NULL COMMENT '实际 PnL = claimed - total_cost - fee',

    -- 市场时间
    `market_end_at`         BIGINT          DEFAULT NULL COMMENT '市场到期时间戳(ms)',
    `opened_at`             DATETIME        DEFAULT NULL,
    `settling_at`           DATETIME        DEFAULT NULL,
    `settled_at`            DATETIME        DEFAULT NULL,
    `created_at`            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_market_id` (`market_id`),          -- 同一市场只建一个对冲仓位
    KEY `idx_status` (`status`),
    KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对冲持仓主表';

-- ============================================================
-- 2. 对冲订单表（每个 hedge_position 对应 2 个订单: YES + NO）
-- ============================================================
CREATE TABLE IF NOT EXISTS `hedge_order` (
    `id`                    BIGINT          NOT NULL AUTO_INCREMENT,
    `hedge_position_id`     BIGINT          NOT NULL COMMENT 'FK → hedge_position.id',
    `market_id`             VARCHAR(128)    NOT NULL,
    `token_id`              VARCHAR(128)    NOT NULL COMMENT '对应的 token ID',
    `side`                  VARCHAR(8)      NOT NULL COMMENT 'YES | NO',

    -- Polymarket 订单信息
    `external_order_id`     VARCHAR(128)    DEFAULT NULL COMMENT 'Polymarket 订单 ID',
    `order_type`            VARCHAR(16)     NOT NULL DEFAULT 'GTC',
    `price`                 DECIMAL(18,6)   NOT NULL COMMENT '限价',
    `requested_quantity`    DECIMAL(18,6)   NOT NULL COMMENT '请求数量',
    `filled_quantity`       DECIMAL(18,6)   DEFAULT NULL COMMENT '成交数量',
    `cost_usdc`             DECIMAL(18,4)   DEFAULT NULL COMMENT '实际成本 USDC',
    `fee_usdc`              DECIMAL(18,4)   DEFAULT NULL COMMENT '手续费 USDC',

    -- 状态: PENDING → FILLED | PARTIAL | FAILED | CANCELLED
    `status`                VARCHAR(32)     NOT NULL DEFAULT 'PENDING',
    `error_message`         TEXT            DEFAULT NULL,
    `poll_attempts`         INT             NOT NULL DEFAULT 0,
    `next_poll_at`          DATETIME        DEFAULT NULL,

    `submitted_at`          DATETIME        DEFAULT NULL,
    `filled_at`             DATETIME        DEFAULT NULL,
    `created_at`            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (`id`),
    KEY `idx_hedge_position_id` (`hedge_position_id`),
    KEY `idx_external_order_id` (`external_order_id`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对冲订单表';

-- ============================================================
-- 3. 套利机会扫描日志（记录每次扫描结果，无论是否执行）
-- ============================================================
CREATE TABLE IF NOT EXISTS `hedge_arbitrage_log` (
    `id`                    BIGINT          NOT NULL AUTO_INCREMENT,
    `market_id`             VARCHAR(128)    NOT NULL,
    `question`              VARCHAR(512)    DEFAULT NULL,
    `yes_token_id`          VARCHAR(128)    DEFAULT NULL,
    `no_token_id`           VARCHAR(128)    DEFAULT NULL,

    -- 价格快照（扫描时刻）
    `yes_best_ask`          DECIMAL(18,6)   DEFAULT NULL COMMENT 'YES 最优卖出价（我们买入价）',
    `no_best_ask`           DECIMAL(18,6)   DEFAULT NULL COMMENT 'NO 最优卖出价（我们买入价）',
    `yes_liquidity_usdc`    DECIMAL(18,2)   DEFAULT NULL,
    `no_liquidity_usdc`     DECIMAL(18,2)   DEFAULT NULL,

    -- 套利指标
    `total_implied_prob`    DECIMAL(18,6)   DEFAULT NULL COMMENT 'P_yes + P_no',
    `prob_deviation`        DECIMAL(18,6)   DEFAULT NULL COMMENT '1 - total_implied_prob',
    `gross_edge_rate`       DECIMAL(18,6)   DEFAULT NULL,
    `expected_profit_usdc`  DECIMAL(18,4)   DEFAULT NULL COMMENT '按最大单笔估算',

    -- 处理结果
    -- SKIP_LOW_EDGE | SKIP_LIQUIDITY | SKIP_RISK | SKIP_DUPLICATE | SKIP_DISABLED | EXECUTED
    `action`                VARCHAR(32)     NOT NULL COMMENT '扫描结论',
    `skip_reason`           VARCHAR(256)    DEFAULT NULL,
    `hedge_position_id`     BIGINT          DEFAULT NULL COMMENT '执行时关联的持仓 ID',

    `scanned_at`            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (`id`),
    KEY `idx_market_id` (`market_id`),
    KEY `idx_action` (`action`),
    KEY `idx_scanned_at` (`scanned_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='套利机会扫描日志';
