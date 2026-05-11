-- ============================================================
-- 多策略引擎 DDL（Phase 0 + Phase 1/2/3）
-- 执行前请确认连接到 coin_strategy 数据库
-- 所有新表，不修改任何现有表
-- ============================================================

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
