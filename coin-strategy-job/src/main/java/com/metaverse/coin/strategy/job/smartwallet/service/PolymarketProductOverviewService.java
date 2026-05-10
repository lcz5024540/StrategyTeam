package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFollowTaskMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFollowTaskEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketProductOverview;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletCapacitySummaryView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class PolymarketProductOverviewService {

    private static final long OVERVIEW_CACHE_TTL_MS = 30_000L;

    private final SmartWalletProperties properties;
    private final PolymarketSmartWalletMonitorService monitorService;
    private final SmartWalletProfileStoreService profileStoreService;
    private final SmartWalletTradeEventMapper tradeEventMapper;
    private final SmartWalletFollowTaskMapper followTaskMapper;
    private final ExecutionWalletContextLoader executionWalletContextLoader;
    private final SmartWalletAccountingService accountingService;

    private volatile PolymarketProductOverview cachedOverview;
    private volatile long cachedOverviewExpiresAt;

    public PolymarketProductOverview overview() {
        long now = System.currentTimeMillis();
        PolymarketProductOverview cached = this.cachedOverview;
        if (cached != null && now < cachedOverviewExpiresAt) {
            return cached;
        }
        PolymarketProductOverview fresh = buildOverview();
        this.cachedOverview = fresh;
        this.cachedOverviewExpiresAt = now + OVERVIEW_CACHE_TTL_MS;
        return fresh;
    }

    private PolymarketProductOverview buildOverview() {
        // 单次 COUNT 查询获取各状态任务数（比逐个状态查更高效，但 MyBatis-Plus 不支持 GROUP BY 返回 Map，保留逐状态 COUNT）
        Map<String, Long> taskStatusCounts = new LinkedHashMap<>();
        for (String status : List.of("PENDING", "FOLLOWED", "SKIPPED", "FAILED")) {
            long count = followTaskMapper.selectCount(new LambdaQueryWrapper<SmartWalletFollowTaskEntity>()
                    .eq(SmartWalletFollowTaskEntity::getStatus, status));
            taskStatusCounts.put(status, count);
        }

        long tradeEvents = tradeEventMapper.selectCount(new LambdaQueryWrapper<SmartWalletTradeEventEntity>());

        // ---- 只加载一次 profiles 全表，在内存中分类计数，避免 5 次重复全表扫描 ----
        List<SmartWalletProfileEntity> allProfiles = profileStoreService.listAll();
        int totalProfiles = allProfiles.size();
        int candidateWatchlist = 0;
        int executableWatchlist = 0;
        int warmWatchlist = 0;
        int coldProfiles = 0;
        for (SmartWalletProfileEntity profile : allProfiles) {
            String tier = profile.getMonitorTier();
            if ("HOT".equalsIgnoreCase(tier)) {
                executableWatchlist++;
            } else if ("WARM".equalsIgnoreCase(tier)) {
                warmWatchlist++;
            } else if ("COLD".equalsIgnoreCase(tier)) {
                coldProfiles++;
            }
            if (Boolean.TRUE.equals(profile.getQualified())) {
                candidateWatchlist++;
            }
        }

        SmartWalletCapacitySummaryView capacity = accountingService.capacitySummary();

        return PolymarketProductOverview.builder()
                .mode(properties.isAutoExecutionEnabled() ? "LIVE" : "PAPER")
                .discoveryEnabled(monitorService.isDiscoveryEnabled())
                .eventSyncEnabled(monitorService.isEventSyncEnabled())
                .liveExecutionEnabled(properties.isAutoExecutionEnabled())
                .profiles(totalProfiles)
                .candidateWatchlist(candidateWatchlist)
                .executableWatchlist(executableWatchlist)
                .warmWatchlist(warmWatchlist)
                .coldProfiles(coldProfiles)
                .watchlist(executableWatchlist)
                .tradeEvents(tradeEvents)
                .totalDeployedUsdc(capacity.getTotalOpenCostUsdc())
                .activePositions(capacity.getOpenPositionCount())
                .taskStatusCounts(taskStatusCounts)
                .executionWallet(executionWalletContextLoader.getStatus())
                .notes(List.of(
                        "Smart wallet discovery is sourced from Polymarket public APIs.",
                        "Signal risk rules are enforced before creating a follow task.",
                        "LIVE mode routes orders through the official Python SDK bridge when execution-wallet is enabled."
                ))
                .build();
    }
}
