package com.metaverse.coin.strategy.job.smartwallet.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;

@Service
@Slf4j
@RequiredArgsConstructor
public class SmartWalletSchemaMaintenanceService {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void ensureSmartWalletOutcomeColumns() {
        ensureTableExists("smart_wallet_behavior_profile",
                """
                CREATE TABLE `smart_wallet_behavior_profile` (
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
                  UNIQUE KEY `uk_sw_behavior_wallet_source` (`wallet_address`,`source`),
                  KEY `idx_sw_behavior_lane` (`recommended_exit_lane`,`updated_at`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='smart wallet 行为画像快照'
                """);
        ensureTableExists("smart_wallet_order_analysis",
                """
                CREATE TABLE `smart_wallet_order_analysis` (
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
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='smart wallet 单笔闭环分析'
                """);
        widenOutcomeColumn("smart_wallet_trade_event");
        widenOutcomeColumn("smart_wallet_follow_task");
        widenOutcomeColumn("smart_wallet_order");
        widenOutcomeColumn("smart_wallet_fill");
        widenOutcomeColumn("smart_wallet_position");
        ensureMinimumVarcharLength("smart_wallet_order", "status", 32,
                "ALTER TABLE `smart_wallet_order` MODIFY COLUMN `status` VARCHAR(32) NOT NULL DEFAULT 'FILLED' COMMENT 'LIVE/PARTIALLY_FILLED/FILLED/FAILED/SETTLEMENT_PENDING_ORACLE/SETTLED_WIN/SETTLED_LOSS'");
        ensureMinimumVarcharLength("smart_wallet_position", "status", 32,
                "ALTER TABLE `smart_wallet_position` MODIFY COLUMN `status` VARCHAR(32) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/PARTIALLY_CLOSED/CLOSED/RESOLVED_UNCLAIMED/CLAIMED'");
        ensureMinimumVarcharLength("smart_wallet_settlement_run", "error_message", 2048,
                "ALTER TABLE `smart_wallet_settlement_run` MODIFY COLUMN `error_message` VARCHAR(2048) DEFAULT NULL");
        ensureMinimumVarcharLength("smart_wallet_settlement_retry", "last_error_message", 2048,
                "ALTER TABLE `smart_wallet_settlement_retry` MODIFY COLUMN `last_error_message` VARCHAR(2048) DEFAULT NULL");
        ensureMinimumVarcharLength("smart_wallet_claim_run", "error_message", 2048,
                "ALTER TABLE `smart_wallet_claim_run` MODIFY COLUMN `error_message` VARCHAR(2048) DEFAULT NULL");
        ensureMinimumVarcharLength("smart_wallet_claim_retry", "last_error_message", 2048,
                "ALTER TABLE `smart_wallet_claim_retry` MODIFY COLUMN `last_error_message` VARCHAR(2048) DEFAULT NULL");
        ensureLegacyColumnExists("smart_wallet_follow_task", "market_end_at", "outcome",
                "ALTER TABLE `smart_wallet_follow_task` ADD COLUMN `market_end_at` BIGINT NULL COMMENT '市场预计结算时间戳(毫秒)' AFTER `outcome`");
        ensureLegacyColumnExists("smart_wallet_order", "market_end_at", "outcome",
                "ALTER TABLE `smart_wallet_order` ADD COLUMN `market_end_at` BIGINT NULL COMMENT '市场预计结算时间戳(毫秒)' AFTER `outcome`");
        ensureColumnExists("smart_wallet_order", "position_id",
                "ALTER TABLE `smart_wallet_order` ADD COLUMN `position_id` BIGINT NULL COMMENT '关联持仓轮次ID' AFTER `follow_task_id`");
        ensureColumnExists("smart_wallet_order", "realized_pnl_usdc",
                "ALTER TABLE `smart_wallet_order` ADD COLUMN `realized_pnl_usdc` DECIMAL(20,8) NOT NULL DEFAULT 0 COMMENT '该订单已实现收益' AFTER `fee_usdc`");
        ensureColumnExists("smart_wallet_order", "trading_fee_usdc",
                "ALTER TABLE `smart_wallet_order` ADD COLUMN `trading_fee_usdc` DECIMAL(20,8) NOT NULL DEFAULT 0 COMMENT '交易手续费USDC' AFTER `fee_usdc`");
        ensureColumnExists("smart_wallet_order", "fee_pol",
                "ALTER TABLE `smart_wallet_order` ADD COLUMN `fee_pol` DECIMAL(20,8) NOT NULL DEFAULT 0 COMMENT '手续费POL' AFTER `trading_fee_usdc`");
        ensureColumnExists("smart_wallet_order", "gas_fee_usdc",
                "ALTER TABLE `smart_wallet_order` ADD COLUMN `gas_fee_usdc` DECIMAL(20,8) NOT NULL DEFAULT 0 COMMENT '链上手续费USDC折算' AFTER `fee_pol`");
        ensureColumnExists("smart_wallet_order", "exit_reason",
                "ALTER TABLE `smart_wallet_order` ADD COLUMN `exit_reason` VARCHAR(64) NULL COMMENT '提前退出原因' AFTER `realized_pnl_usdc`");
        ensureLegacyColumnExists("smart_wallet_position", "market_end_at", "outcome",
                "ALTER TABLE `smart_wallet_position` ADD COLUMN `market_end_at` BIGINT NULL COMMENT '市场预计结算时间戳(毫秒)' AFTER `outcome`");
        ensureColumnExists("smart_wallet_position", "original_cost_basis_usdc",
                "ALTER TABLE `smart_wallet_position` ADD COLUMN `original_cost_basis_usdc` DECIMAL(20,8) NOT NULL DEFAULT 0 COMMENT '原始持仓成本(用于结算后展示)' AFTER `cost_basis_usdc`");
        ensureColumnExists("smart_wallet_position", "fee_pol_total",
                "ALTER TABLE `smart_wallet_position` ADD COLUMN `fee_pol_total` DECIMAL(20,8) NOT NULL DEFAULT 0 COMMENT '累计手续费POL' AFTER `original_cost_basis_usdc`");
        ensureColumnExists("smart_wallet_fill", "position_id",
                "ALTER TABLE `smart_wallet_fill` ADD COLUMN `position_id` BIGINT NULL COMMENT '关联持仓轮次ID' AFTER `order_id`");
        ensureColumnExists("smart_wallet_fill", "trading_fee_usdc",
                "ALTER TABLE `smart_wallet_fill` ADD COLUMN `trading_fee_usdc` DECIMAL(20,8) NOT NULL DEFAULT 0 COMMENT '交易手续费USDC' AFTER `fee_usdc`");
        ensureColumnExists("smart_wallet_fill", "fee_pol",
                "ALTER TABLE `smart_wallet_fill` ADD COLUMN `fee_pol` DECIMAL(20,8) NOT NULL DEFAULT 0 COMMENT '手续费POL' AFTER `trading_fee_usdc`");
        ensureColumnExists("smart_wallet_fill", "gas_fee_usdc",
                "ALTER TABLE `smart_wallet_fill` ADD COLUMN `gas_fee_usdc` DECIMAL(20,8) NOT NULL DEFAULT 0 COMMENT '链上手续费USDC折算' AFTER `fee_pol`");
        ensureColumnExists("smart_wallet_daily_report", "winning_positions",
                "ALTER TABLE `smart_wallet_daily_report` ADD COLUMN `winning_positions` INT NOT NULL DEFAULT 0 COMMENT '盈利仓位数' AFTER `net_pnl_usdc`");
        ensureColumnExists("smart_wallet_daily_report", "losing_positions",
                "ALTER TABLE `smart_wallet_daily_report` ADD COLUMN `losing_positions` INT NOT NULL DEFAULT 0 COMMENT '亏损仓位数' AFTER `winning_positions`");
        ensureColumnExists("smart_wallet_daily_report", "loss_rate",
                "ALTER TABLE `smart_wallet_daily_report` ADD COLUMN `loss_rate` DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '亏损率' AFTER `win_rate`");
        ensureColumnExists("smart_wallet_profile", "manual_watch_enabled",
                "ALTER TABLE `smart_wallet_profile` ADD COLUMN `manual_watch_enabled` TINYINT(1) NULL COMMENT '人工覆盖是否允许进入 executable watchlist' AFTER `watch_enabled`");
        ensureColumnExists("smart_wallet_profile", "recent_win_rate_7d",
                "ALTER TABLE `smart_wallet_profile` ADD COLUMN `recent_win_rate_7d` DECIMAL(10,4) DEFAULT NULL COMMENT '近7日胜率' AFTER `active_hour_histogram_json`");
        ensureColumnExists("smart_wallet_profile", "recent_win_rate_30d",
                "ALTER TABLE `smart_wallet_profile` ADD COLUMN `recent_win_rate_30d` DECIMAL(10,4) DEFAULT NULL COMMENT '近30日胜率' AFTER `recent_win_rate_7d`");
        ensureColumnExists("smart_wallet_profile", "recent_trade_count_7d",
                "ALTER TABLE `smart_wallet_profile` ADD COLUMN `recent_trade_count_7d` INT NOT NULL DEFAULT 0 COMMENT '近7日交易样本数' AFTER `recent_win_rate_7d`");
        ensureColumnExists("smart_wallet_profile", "recent_trade_count_30d",
                "ALTER TABLE `smart_wallet_profile` ADD COLUMN `recent_trade_count_30d` INT NOT NULL DEFAULT 0 COMMENT '近30日交易样本数' AFTER `recent_win_rate_30d`");
        ensureColumnExists("smart_wallet_profile", "observe_cooldown_until",
                "ALTER TABLE `smart_wallet_profile` ADD COLUMN `observe_cooldown_until` TIMESTAMP NULL COMMENT '近期退化观察冷却截止时间' AFTER `recent_trade_count_30d`");
        ensureColumnExists("smart_wallet_profile", "observe_cooldown_reason",
                "ALTER TABLE `smart_wallet_profile` ADD COLUMN `observe_cooldown_reason` VARCHAR(255) DEFAULT NULL COMMENT '近期退化观察原因' AFTER `observe_cooldown_until`");
        ensureColumnExists("smart_wallet_profile", "manual_recommended_exit_lane",
                "ALTER TABLE `smart_wallet_profile` ADD COLUMN `manual_recommended_exit_lane` VARCHAR(64) NULL COMMENT '人工覆盖的退出风格 lane' AFTER `manual_watch_enabled`");
        ensureColumnExists("smart_wallet_profile", "manual_override_note",
                "ALTER TABLE `smart_wallet_profile` ADD COLUMN `manual_override_note` VARCHAR(255) NULL COMMENT '人工覆盖备注' AFTER `manual_recommended_exit_lane`");
        ensureColumnExists("smart_wallet_profile", "manual_override_updated_at",
                "ALTER TABLE `smart_wallet_profile` ADD COLUMN `manual_override_updated_at` DATETIME NULL COMMENT '人工覆盖更新时间' AFTER `manual_override_note`");
        ensureColumnExists("smart_wallet_behavior_profile", "win_source_type",
                "ALTER TABLE `smart_wallet_behavior_profile` ADD COLUMN `win_source_type` VARCHAR(64) NULL COMMENT '胜率来源类型' AFTER `loss_early_exit_rate`");
        ensureColumnExists("smart_wallet_behavior_profile", "latency_alpha_rate",
                "ALTER TABLE `smart_wallet_behavior_profile` ADD COLUMN `latency_alpha_rate` DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '短周期 alpha 比例' AFTER `win_source_type`");
        ensureColumnExists("smart_wallet_behavior_profile", "swing_alpha_rate",
                "ALTER TABLE `smart_wallet_behavior_profile` ADD COLUMN `swing_alpha_rate` DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '中周期 alpha 比例' AFTER `latency_alpha_rate`");
        ensureColumnExists("smart_wallet_behavior_profile", "settlement_win_rate",
                "ALTER TABLE `smart_wallet_behavior_profile` ADD COLUMN `settlement_win_rate` DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '等待结算胜率' AFTER `swing_alpha_rate`");
        ensureColumnExists("smart_wallet_behavior_profile", "median_hold_minutes",
                "ALTER TABLE `smart_wallet_behavior_profile` ADD COLUMN `median_hold_minutes` DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '持仓时长中位数(分钟)' AFTER `settlement_win_rate`");
        ensureColumnExists("smart_wallet_behavior_profile", "stability_score",
                "ALTER TABLE `smart_wallet_behavior_profile` ADD COLUMN `stability_score` DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '稳定性分数' AFTER `median_hold_minutes`");
        ensureColumnExists("smart_wallet_behavior_profile", "quality_score",
                "ALTER TABLE `smart_wallet_behavior_profile` ADD COLUMN `quality_score` DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '地址质量分数' AFTER `stability_score`");
        dropLegacyIndexIfExists("smart_wallet_position", "uk_position_execution_market_outcome", "market_id", "outcome");
        ensureIndexExists("smart_wallet_order", "idx_order_position",
                "CREATE INDEX `idx_order_position` ON `smart_wallet_order` (`position_id`, `placed_at`)");
        ensureIndexExists("smart_wallet_fill", "idx_fill_position",
                "CREATE INDEX `idx_fill_position` ON `smart_wallet_fill` (`position_id`, `filled_at`)");
        ensureIndexExists("smart_wallet_behavior_profile", "idx_sw_behavior_quality",
                "CREATE INDEX `idx_sw_behavior_quality` ON `smart_wallet_behavior_profile` (`quality_score`, `replicability_score`, `updated_at`)");
        ensureLegacyIndexExists("smart_wallet_position", "idx_position_execution_market_outcome",
                new String[]{"market_id", "outcome"},
                "CREATE INDEX `idx_position_execution_market_outcome` ON `smart_wallet_position` (`execution_address`, `market_id`, `outcome`, `status`, `last_filled_at`)");
    }

    private void widenOutcomeColumn(String tableName) {
        if (!tableExists(tableName) || !columnExists(tableName, "outcome")) {
            return;
        }
        try {
            jdbcTemplate.execute("ALTER TABLE `" + tableName + "` MODIFY COLUMN `outcome` VARCHAR(128) NOT NULL COMMENT 'Polymarket outcome label'");
        } catch (Exception ex) {
            log.warn("Failed to widen outcome column for table {}: {}", tableName, ex.getMessage());
        }
    }

    private void ensureTableExists(String tableName, String ddl) {
        if (tableExists(tableName)) {
            return;
        }
        try {
            jdbcTemplate.execute(ddl);
        } catch (Exception ex) {
            log.warn("Failed to create table {}: {}", tableName, ex.getMessage());
        }
    }

    private void ensureColumnExists(String tableName, String columnName, String ddl) {
        if (!tableExists(tableName) || columnExists(tableName, columnName)) {
            return;
        }
        try {
            jdbcTemplate.execute(ddl);
        } catch (Exception ex) {
            log.warn("Failed to add column {}.{}: {}", tableName, columnName, ex.getMessage());
        }
    }

    private void ensureLegacyColumnExists(String tableName, String columnName, String anchorColumn, String ddl) {
        if (!tableExists(tableName) || !columnExists(tableName, anchorColumn) || columnExists(tableName, columnName)) {
            return;
        }
        try {
            jdbcTemplate.execute(ddl);
        } catch (Exception ex) {
            log.warn("Failed to add legacy-compatible column {}.{}: {}", tableName, columnName, ex.getMessage());
        }
    }

    private void ensureMinimumVarcharLength(String tableName, String columnName, int minLength, String ddl) {
        Integer currentLength = varcharLength(tableName, columnName);
        if (currentLength == null || currentLength >= minLength) {
            return;
        }
        try {
            jdbcTemplate.execute(ddl);
        } catch (Exception ex) {
            log.warn("Failed to widen column {}.{} to at least {} chars: {}", tableName, columnName, minLength, ex.getMessage());
        }
    }

    private void ensureIndexExists(String tableName, String indexName, String ddl) {
        if (!tableExists(tableName) || indexExists(tableName, indexName)) {
            return;
        }
        try {
            jdbcTemplate.execute(ddl);
        } catch (Exception ex) {
            log.warn("Failed to create index {} on {}: {}", indexName, tableName, ex.getMessage());
        }
    }

    private void ensureLegacyIndexExists(String tableName, String indexName, String[] requiredColumns, String ddl) {
        if (!tableExists(tableName) || indexExists(tableName, indexName)) {
            return;
        }
        for (String requiredColumn : requiredColumns) {
            if (!columnExists(tableName, requiredColumn)) {
                return;
            }
        }
        try {
            jdbcTemplate.execute(ddl);
        } catch (Exception ex) {
            log.warn("Failed to create legacy-compatible index {} on {}: {}", indexName, tableName, ex.getMessage());
        }
    }

    private void dropIndexIfExists(String tableName, String indexName) {
        if (!tableExists(tableName) || !indexExists(tableName, indexName)) {
            return;
        }
        try {
            jdbcTemplate.execute("ALTER TABLE `" + tableName + "` DROP INDEX `" + indexName + "`");
        } catch (Exception ex) {
            log.warn("Failed to drop index {} on {}: {}", indexName, tableName, ex.getMessage());
        }
    }

    private void dropLegacyIndexIfExists(String tableName, String indexName, String... requiredColumns) {
        if (!tableExists(tableName) || !indexExists(tableName, indexName)) {
            return;
        }
        for (String requiredColumn : requiredColumns) {
            if (!columnExists(tableName, requiredColumn)) {
                return;
            }
        }
        dropIndexIfExists(tableName, indexName);
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(1)
                FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                """,
                Integer.class,
                tableName
        );
        return count != null && count > 0;
    }

    private boolean columnExists(String tableName, String columnName) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(1)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND column_name = ?
                """,
                Integer.class,
                tableName,
                columnName
        );
        return count != null && count > 0;
    }

    private Integer varcharLength(String tableName, String columnName) {
        return jdbcTemplate.query(
                """
                SELECT character_maximum_length
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND column_name = ?
                """,
                rs -> rs.next() ? rs.getInt(1) : null,
                tableName,
                columnName
        );
    }

    private boolean indexExists(String tableName, String indexName) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(1)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND index_name = ?
                """,
                Integer.class,
                tableName,
                indexName
        );
        return count != null && count > 0;
    }
}
