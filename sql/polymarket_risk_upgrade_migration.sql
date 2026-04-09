-- =================================================================
-- Polymarket 跟单风控升级迁移
-- 新增市场微观结构快照、交易效果标签、钱包日特征、treatment/control 样本表
-- =================================================================

-- 1. 市场微观结构快照表
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

-- 2. Leader 交易效果标签表
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

-- 3. 钱包日特征表
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

-- 4. Treatment / Control 配对样本表
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
