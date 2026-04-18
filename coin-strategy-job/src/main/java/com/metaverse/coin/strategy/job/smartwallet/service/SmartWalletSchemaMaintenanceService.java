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
        ensureColumnExists("smart_wallet_follow_task", "market_end_at",
                "ALTER TABLE `smart_wallet_follow_task` ADD COLUMN `market_end_at` BIGINT NULL COMMENT '市场预计结算时间戳(毫秒)' AFTER `outcome`");
        ensureColumnExists("smart_wallet_order", "market_end_at",
                "ALTER TABLE `smart_wallet_order` ADD COLUMN `market_end_at` BIGINT NULL COMMENT '市场预计结算时间戳(毫秒)' AFTER `outcome`");
        ensureColumnExists("smart_wallet_order", "position_id",
                "ALTER TABLE `smart_wallet_order` ADD COLUMN `position_id` BIGINT NULL COMMENT '关联持仓轮次ID' AFTER `follow_task_id`");
        ensureColumnExists("smart_wallet_order", "realized_pnl_usdc",
                "ALTER TABLE `smart_wallet_order` ADD COLUMN `realized_pnl_usdc` DECIMAL(20,8) NOT NULL DEFAULT 0 COMMENT '该订单已实现收益' AFTER `fee_usdc`");
        ensureColumnExists("smart_wallet_order", "exit_reason",
                "ALTER TABLE `smart_wallet_order` ADD COLUMN `exit_reason` VARCHAR(64) NULL COMMENT '提前退出原因' AFTER `realized_pnl_usdc`");
        ensureColumnExists("smart_wallet_position", "market_end_at",
                "ALTER TABLE `smart_wallet_position` ADD COLUMN `market_end_at` BIGINT NULL COMMENT '市场预计结算时间戳(毫秒)' AFTER `outcome`");
        ensureColumnExists("smart_wallet_position", "original_cost_basis_usdc",
                "ALTER TABLE `smart_wallet_position` ADD COLUMN `original_cost_basis_usdc` DECIMAL(20,8) NOT NULL DEFAULT 0 COMMENT '原始持仓成本(用于结算后展示)' AFTER `cost_basis_usdc`");
        ensureColumnExists("smart_wallet_fill", "position_id",
                "ALTER TABLE `smart_wallet_fill` ADD COLUMN `position_id` BIGINT NULL COMMENT '关联持仓轮次ID' AFTER `order_id`");
        dropIndexIfExists("smart_wallet_position", "uk_position_execution_market_outcome");
        ensureIndexExists("smart_wallet_order", "idx_order_position",
                "CREATE INDEX `idx_order_position` ON `smart_wallet_order` (`position_id`, `placed_at`)");
        ensureIndexExists("smart_wallet_fill", "idx_fill_position",
                "CREATE INDEX `idx_fill_position` ON `smart_wallet_fill` (`position_id`, `filled_at`)");
        ensureIndexExists("smart_wallet_position", "idx_position_execution_market_outcome",
                "CREATE INDEX `idx_position_execution_market_outcome` ON `smart_wallet_position` (`execution_address`, `market_id`, `outcome`, `status`, `last_filled_at`)");
    }

    private void widenOutcomeColumn(String tableName) {
        if (!tableExists(tableName)) {
            return;
        }
        try {
            jdbcTemplate.execute("ALTER TABLE `" + tableName + "` MODIFY COLUMN `outcome` VARCHAR(128) NOT NULL COMMENT 'Polymarket outcome label'");
        } catch (Exception ex) {
            log.warn("Failed to widen outcome column for table {}: {}", tableName, ex.getMessage());
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
