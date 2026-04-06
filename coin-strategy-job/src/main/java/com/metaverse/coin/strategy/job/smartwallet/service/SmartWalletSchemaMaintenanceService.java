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
}
