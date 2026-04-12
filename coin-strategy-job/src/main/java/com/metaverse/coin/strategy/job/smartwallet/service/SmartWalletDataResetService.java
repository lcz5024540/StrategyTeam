package com.metaverse.coin.strategy.job.smartwallet.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class SmartWalletDataResetService {

    private static final List<String> CORE_TABLES = List.of(
            "smart_wallet_claim_retry",
            "smart_wallet_claim_run",
            "smart_wallet_settlement_retry",
            "smart_wallet_settlement_run",
            "smart_wallet_daily_report",
            "smart_wallet_cash_ledger",
            "smart_wallet_fill",
            "smart_wallet_order",
            "smart_wallet_position",
            "smart_wallet_follow_task",
            "smart_wallet_trade_event",
            "smart_wallet_profile"
    );

    private static final List<String> RISK_ARTIFACT_TABLES = List.of(
            "smart_wallet_treatment_control_sample",
            "smart_wallet_trade_effect_label",
            "smart_wallet_wallet_feature_daily",
            "smart_wallet_market_snapshot"
    );

    private final JdbcTemplate jdbcTemplate;
    private final SmartWalletRegistryService walletRegistryService;
    private final PredictionMarketOpportunityService opportunityService;
    private final PolymarketMarketMetadataService marketMetadataService;

    public Map<String, Object> reset(boolean includeProfiles,
                                     boolean includeRiskArtifacts,
                                     boolean includeRuntimeSeeds,
                                     boolean includeMetadataCache) {
        List<String> tables = new ArrayList<>();
        for (String table : CORE_TABLES) {
            if (!includeProfiles && "smart_wallet_profile".equals(table)) {
                continue;
            }
            tables.add(table);
        }
        if (includeRiskArtifacts) {
            tables.addAll(0, RISK_ARTIFACT_TABLES);
        }

        Map<String, Integer> beforeCounts = new LinkedHashMap<>();
        Map<String, Integer> afterCounts = new LinkedHashMap<>();
        List<String> clearedTables = new ArrayList<>();
        Map<String, Object> runtimeCleared = new LinkedHashMap<>();

        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
        try {
            for (String table : tables) {
                if (!tableExists(table)) {
                    continue;
                }
                beforeCounts.put(table, countRows(table));
                truncateOrDelete(table);
                afterCounts.put(table, countRows(table));
                clearedTables.add(table);
            }
        } finally {
            jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");
        }

        if (includeRuntimeSeeds) {
            runtimeCleared.put("walletSeeds", walletRegistryService.clearAll());
            runtimeCleared.put("marketSeeds", opportunityService.clearAll());
        }
        if (includeMetadataCache) {
            runtimeCleared.put("metadataCache", marketMetadataService.clearAllCaches());
        }

        log.warn("Smart wallet data reset completed. includeProfiles={}, includeRiskArtifacts={}, includeRuntimeSeeds={}, includeMetadataCache={}, clearedTables={}",
                includeProfiles, includeRiskArtifacts, includeRuntimeSeeds, includeMetadataCache, clearedTables);

        return Map.of(
                "resetAt", OffsetDateTime.now().toString(),
                "includeProfiles", includeProfiles,
                "includeRiskArtifacts", includeRiskArtifacts,
                "includeRuntimeSeeds", includeRuntimeSeeds,
                "includeMetadataCache", includeMetadataCache,
                "clearedTables", clearedTables,
                "beforeCounts", beforeCounts,
                "afterCounts", afterCounts,
                "runtimeCleared", runtimeCleared
        );
    }

    private void truncateOrDelete(String table) {
        try {
            jdbcTemplate.execute("TRUNCATE TABLE `" + table + "`");
        } catch (Exception ex) {
            log.warn("Truncate failed for table {}. Falling back to DELETE. reason={}", table, ex.getMessage());
            jdbcTemplate.execute("DELETE FROM `" + table + "`");
        }
    }

    private Integer countRows(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(1) FROM `" + table + "`", Integer.class);
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
