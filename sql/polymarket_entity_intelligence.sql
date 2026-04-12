-- =================================================================
-- Entity Intelligence 层 — 数据库迁移
-- 修复共识信号「地址膨胀」bug：将共识从地址维度升级为实体维度
-- =================================================================

-- 表1：钱包 → 实体映射（核心）
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

-- 表2：实体级特征快照（合并同实体所有钱包数据后的指标）
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

-- 表3：候选跟单信号（intelligence enrich 的输出，follow task 的前置记录）
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

-- 现有表扩展：smart_wallet_follow_task 增加实体维度字段
ALTER TABLE `smart_wallet_follow_task`
    ADD COLUMN `entity_id`           VARCHAR(64)  DEFAULT NULL COMMENT '触发钱包对应的实体ID' AFTER `wallet_address`,
    ADD COLUMN `follow_candidate_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '关联的候选跟单信号ID' AFTER `source_event_id`,
    ADD INDEX `idx_follow_entity` (`entity_id`);
