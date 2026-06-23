package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把原 SmartWalletController 中两个长任务的编排逻辑同步化、可重入地抽出来。
 * 调用方（{@code StrategyManualTriggerConsumer}）以异步命令的形式驱动；
 * 命令的状态/结果通过 strategy_manual_trigger 表持久化，无需再维护 in-memory job map。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SmartWalletProfileAsyncOpsService {

    private final ClosedPositionSyncDispatcher closedPositionSyncDispatcher;
    private final PolymarketSmartWalletMonitorService polymarketMonitorService;
    private final SmartWalletOrderAnalysisService smartWalletOrderAnalysisService;
    private final WalletOrderStatsService walletOrderStatsService;
    private final WalletBehaviorProfileService walletBehaviorProfileService;

    /**
     * 三级 watchlist 全量持仓同步（HOT → WARM → COLD）。同步阻塞执行；
     * 返回结构与原 controller 一致：包含三个 tier 的子结果。
     */
    public Map<String, Object> syncAllPositions(int rawBatchSize) {
        int batchSize = Math.max(5, Math.min(rawBatchSize, 200));
        long startedAt = System.currentTimeMillis();
        Map<String, Object> hotResult = dispatchTierSync("HOT", batchSize);
        Map<String, Object> warmResult = dispatchTierSync("WARM", batchSize);
        Map<String, Object> coldResult = dispatchTierSync("COLD", batchSize);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "COMPLETED");
        result.put("batchSize", batchSize);
        result.put("hotResult", hotResult);
        result.put("warmResult", warmResult);
        result.put("coldResult", coldResult);
        result.put("startedAt", startedAt);
        result.put("completedAt", System.currentTimeMillis());
        return result;
    }

    /**
     * 重建 watchlist 画像 metrics。同步阻塞执行；返回结构与原 controller 一致。
     */
    public Map<String, Object> rebuildProfileMetrics(String scope, int rawLimit) {
        int limit = Math.max(1, Math.min(rawLimit == 0 ? 30 : rawLimit, 500));
        long startedAt = System.currentTimeMillis();
        List<SmartWalletProfileEntity> profiles = resolveWatchlistScope(scope).stream()
                .limit(limit)
                .toList();
        int rebuiltOrders = 0;
        int rebuiltProfiles = 0;
        for (SmartWalletProfileEntity profile : profiles) {
            if (profile == null || !StringUtils.hasText(profile.getWalletAddress())) {
                continue;
            }
            smartWalletOrderAnalysisService.analyzeAndPersist(profile.getWalletAddress());
            walletOrderStatsService.summarize(profile.getWalletAddress(), "all");
            walletBehaviorProfileService.buildProfile(profile);
            rebuiltOrders++;
            rebuiltProfiles++;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "COMPLETED");
        result.put("scope", scope);
        result.put("walletCount", profiles.size());
        result.put("rebuiltOrders", rebuiltOrders);
        result.put("rebuiltProfiles", rebuiltProfiles);
        result.put("startedAt", startedAt);
        result.put("completedAt", System.currentTimeMillis());
        return result;
    }

    private Map<String, Object> dispatchTierSync(String tier, int batchSize) {
        long tierStart = System.currentTimeMillis();
        ClosedPositionSyncDispatcher.DispatchResult result =
                closedPositionSyncDispatcher.dispatchIncremental(java.util.List.of(tier), batchSize);
        long elapsedMs = System.currentTimeMillis() - tierStart;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tier", tier);
        m.put("total", result.total());
        m.put("ok", result.ok());
        m.put("failed", result.failed());
        m.put("inserted", result.inserted());
        m.put("elapsedMs", elapsedMs);
        return m;
    }

    private List<SmartWalletProfileEntity> resolveWatchlistScope(String scope) {
        if (scope == null) return polymarketMonitorService.listQualifiedCandidates();
        if ("all".equalsIgnoreCase(scope)) {
            return polymarketMonitorService.listProfiles();
        }
        if ("warm".equalsIgnoreCase(scope)) {
            return polymarketMonitorService.listWarmWatchlist();
        }
        if ("executable".equalsIgnoreCase(scope)) {
            return polymarketMonitorService.listExecutableWatchlist();
        }
        return polymarketMonitorService.listQualifiedCandidates();
    }
}
