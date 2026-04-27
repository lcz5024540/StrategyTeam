package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFollowTaskMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFollowTaskEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketProductOverview;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PolymarketProductOverviewService {

    private final SmartWalletProperties properties;
    private final PolymarketSmartWalletMonitorService monitorService;
    private final SmartWalletTradeEventMapper tradeEventMapper;
    private final SmartWalletFollowTaskMapper followTaskMapper;
    private final ExecutionWalletContextLoader executionWalletContextLoader;
    private final SmartWalletAccountingService accountingService;

    public PolymarketProductOverview overview() {
        Map<String, Long> taskStatusCounts = new LinkedHashMap<>();
        for (String status : List.of("PENDING", "FOLLOWED", "SKIPPED", "FAILED")) {
            long count = followTaskMapper.selectCount(new LambdaQueryWrapper<SmartWalletFollowTaskEntity>()
                    .eq(SmartWalletFollowTaskEntity::getStatus, status));
            taskStatusCounts.put(status, count);
        }

        long tradeEvents = tradeEventMapper.selectCount(new LambdaQueryWrapper<SmartWalletTradeEventEntity>());
        var capacity = accountingService.capacitySummary();
        int candidateWatchlist = monitorService.listQualifiedCandidates().size();
        int executableWatchlist = monitorService.listExecutableWatchlist().size();
        int warmWatchlist = monitorService.listWarmWatchlist().size();
        int coldProfiles = monitorService.listColdProfiles().size();
        return PolymarketProductOverview.builder()
                .mode(properties.isAutoExecutionEnabled() ? "LIVE" : "PAPER")
                .discoveryEnabled(monitorService.isDiscoveryEnabled())
                .eventSyncEnabled(monitorService.isEventSyncEnabled())
                .liveExecutionEnabled(properties.isAutoExecutionEnabled())
                .profiles(monitorService.listProfiles().size())
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
