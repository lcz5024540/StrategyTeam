package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletOrderStatsSnapshotMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderAnalysisEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderStatsSnapshotEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.model.WalletOrderStatsView;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletProfileStoreReadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Phase 4.4 引入：定期把 executable+warm watchlist 的 order-stats 快照写到
 * {@code smart_wallet_order_stats_snapshot} 表。
 *
 * <p>调用 job 端原 {@code WalletOrderStatsService.summarize(...)}（不动）+
 * {@code SmartWalletOrderAnalysisService.analyzeAndPersist(...)}（按需 refresh order_analysis 表），
 * 转 entity 写表。web 端读表。</p>
 *
 * <p>默认 window="30d"。可通过 {@code smart-wallet.order-stats-snapshot-window}
 * 覆盖；默认间隔 15min（可通过 {@code smart-wallet.order-stats-snapshot-delay-ms} 覆盖）。</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ProfileOrderStatsSnapshotJob {

    private static final String SOURCE = "POLYMARKET";

    private final WalletOrderStatsService walletOrderStatsService;
    private final SmartWalletOrderAnalysisService smartWalletOrderAnalysisService;
    private final SmartWalletOrderStatsSnapshotMapper orderStatsSnapshotMapper;
    private final SmartWalletProfileStoreReadService profileStoreReadService;

    @Scheduled(fixedDelayString = "${smart-wallet.order-stats-snapshot-delay-ms:900000}")
    public void snapshot() {
        String window = "30d"; // 单一默认窗口；扩展窗口可在此循环
        try {
            List<SmartWalletProfileEntity> targets = collectTargets();
            if (targets.isEmpty()) {
                return;
            }
            int ok = 0;
            int failed = 0;
            for (SmartWalletProfileEntity profile : targets) {
                try {
                    String walletAddress = profile.getWalletAddress();
                    List<SmartWalletOrderAnalysisEntity> orders =
                            smartWalletOrderAnalysisService.listFreshOrRefresh(walletAddress, false);
                    WalletOrderStatsView view = walletOrderStatsService.summarize(walletAddress, orders, window);
                    if (view == null) {
                        continue;
                    }
                    upsert(walletAddress, window, view);
                    ok++;
                } catch (Exception e) {
                    failed++;
                    log.warn("[ProfileOrderStatsSnapshotJob] failed for wallet={} reason={}",
                            profile.getWalletAddress(), e.getMessage());
                }
            }
            log.info("[ProfileOrderStatsSnapshotJob] snapshot done window={} targets={} ok={} failed={}",
                    window, targets.size(), ok, failed);
        } catch (Exception e) {
            log.error("[ProfileOrderStatsSnapshotJob] unexpected failure: {}", e.getMessage(), e);
        }
    }

    private List<SmartWalletProfileEntity> collectTargets() {
        Set<String> seen = new LinkedHashSet<>();
        List<SmartWalletProfileEntity> targets = new ArrayList<>();
        for (SmartWalletProfileEntity profile : profileStoreReadService.listExecutableWatchlist()) {
            if (profile != null && profile.getWalletAddress() != null
                    && seen.add(profile.getWalletAddress().toLowerCase())) {
                targets.add(profile);
            }
        }
        for (SmartWalletProfileEntity profile : profileStoreReadService.listWarmWatchlist()) {
            if (profile != null && profile.getWalletAddress() != null
                    && seen.add(profile.getWalletAddress().toLowerCase())) {
                targets.add(profile);
            }
        }
        return targets;
    }

    private void upsert(String walletAddress, String window, WalletOrderStatsView view) {
        SmartWalletOrderStatsSnapshotEntity existing = orderStatsSnapshotMapper.selectOne(
                new LambdaQueryWrapper<SmartWalletOrderStatsSnapshotEntity>()
                        .eq(SmartWalletOrderStatsSnapshotEntity::getWalletAddress, walletAddress)
                        .eq(SmartWalletOrderStatsSnapshotEntity::getSource, SOURCE)
                        .eq(SmartWalletOrderStatsSnapshotEntity::getWindow, window)
                        .last("limit 1"));
        Date now = new Date();
        SmartWalletOrderStatsSnapshotEntity entity = existing == null
                ? new SmartWalletOrderStatsSnapshotEntity().setCreatedAt(now)
                : existing;
        entity.setWalletAddress(walletAddress)
                .setSource(SOURCE)
                .setWindow(window)
                .setGeneratedAt(view.generatedAt() == null ? now : view.generatedAt())
                .setClosedOrderCount(view.closedOrderCount())
                .setWinCount(view.winCount())
                .setLossCount(view.lossCount())
                .setDrawCount(view.drawCount())
                .setClosedWinRate(view.closedWinRate())
                .setClosedRealizedPnlUsdc(view.closedRealizedPnlUsdc())
                .setActiveUnrealizedPnlUsdc(view.activeUnrealizedPnlUsdc())
                .setNetPnlUsdc(view.netPnlUsdc())
                .setAverageRoiRate(view.averageRoiRate())
                .setActivePositionCount(view.activePositionCount())
                .setPendingSettlementCount(view.pendingSettlementCount())
                .setEarlySettlementRate(view.earlySettlementRate())
                .setSettlementWinRate(view.settlementWinRate())
                .setUpdatedAt(now);
        if (existing == null) {
            orderStatsSnapshotMapper.insert(entity);
        } else {
            orderStatsSnapshotMapper.updateById(entity);
        }
    }
}
