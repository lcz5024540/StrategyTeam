-- 天气市场生命周期数据采集表
-- 用于持续采集 Polymarket 天气市场状态快照，分析市场运行规律
CREATE TABLE IF NOT EXISTS `weather_market_lifecycle_log` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `market_id` varchar(128) DEFAULT NULL COMMENT 'Polymarket conditionId',
    `question` varchar(512) DEFAULT NULL COMMENT '市场问题原文',
    `city` varchar(64) DEFAULT NULL COMMENT '解析出的城市',
    `temperature_type` varchar(16) DEFAULT NULL COMMENT 'MAX / MIN',
    `threshold_f` decimal(8,2) DEFAULT NULL COMMENT '阈值温度 (°F)',
    `target_date` varchar(16) DEFAULT NULL COMMENT '目标日期 YYYY-MM-DD',

    `active` tinyint(1) DEFAULT NULL COMMENT '是否活跃',
    `closed` tinyint(1) DEFAULT NULL COMMENT '是否关闭',
    `accepting_orders` tinyint(1) DEFAULT NULL COMMENT '是否接受订单',
    `resolution_status` varchar(64) DEFAULT NULL COMMENT '结算状态',
    `resolved_outcome` varchar(16) DEFAULT NULL COMMENT '结算结果 YES/NO',

    `end_at_ms` bigint DEFAULT NULL COMMENT '市场结束时间 epoch millis',
    `lead_hours` int DEFAULT NULL COMMENT '距 endAt 小时数（负=已过期）',
    `first_seen_lead_hours` int DEFAULT NULL COMMENT '首次扫描到时的 lead hours',

    `yes_price` decimal(10,6) DEFAULT NULL COMMENT 'YES 价格 [0,1]',
    `no_price` decimal(10,6) DEFAULT NULL COMMENT 'NO 价格 [0,1]',

    `batch_id` varchar(32) DEFAULT NULL COMMENT '采集批次 ID',
    `scanned_at` datetime DEFAULT NULL COMMENT '采集时间',
    `first_seen` tinyint(1) DEFAULT FALSE COMMENT '是否首次出现',
    `created_at` datetime DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (`id`),
    KEY `idx_market_id` (`market_id`),
    KEY `idx_batch_id` (`batch_id`),
    KEY `idx_city_target_date` (`city`, `target_date`),
    KEY `idx_first_seen` (`first_seen`, `first_seen_lead_hours`),
    KEY `idx_scanned_at` (`scanned_at`),
    KEY `idx_resolved_outcome` (`resolved_outcome`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='天气市场生命周期采集快照';
