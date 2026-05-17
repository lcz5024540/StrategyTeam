package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletBehaviorProfileMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletBehaviorProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.model.WalletBehaviorProfileView;
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
 * Phase 4.2 引入：定期把 executable+warm watchlist 的 behavior profile 计算结果落到
 * {@code smart_wallet_behavior_profile} 表，让 web 端可以离线读到。
 *
 * <p>替代以前"web 直接调 {@code walletBehaviorProfileService.getProfile()} 现算"路径，
 * 解耦 web 与 job 进程内的 cache + 算法依赖。</p>
 *
 * <p>job 端原 {@code WalletBehaviorProfileService} 和 {@code WalletArchetypeClassifierService}
 * 不动；本类只是一个独立 schedule 容器。</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class BehaviorProfileSnapshotJob {

    private static final String SOURCE = "POLYMARKET";

    private final WalletBehaviorProfileService walletBehaviorProfileService;
    private final SmartWalletProfileStoreReadService profileStoreReadService;
    private final SmartWalletBehaviorProfileMapper behaviorProfileMapper;
    /** 方案 B (W2-T6 优化): snapshot 时一并计算 Drift centroid 并写入 entity.behavior_vector_json. */
    private final LeaderBehaviorDriftService leaderBehaviorDriftService;

    /**
     * 默认 10 分钟一次，可通过 {@code smart-wallet.behavior-profile-snapshot-delay-ms} 覆盖。
     * 写库失败单条容错，不阻断后续钱包。
     */
    @Scheduled(fixedDelayString = "${smart-wallet.behavior-profile-snapshot-delay-ms:600000}")
    public void snapshot() {
        try {
            List<SmartWalletProfileEntity> targets = collectTargets();
            if (targets.isEmpty()) {
                return;
            }
            int ok = 0;
            int failed = 0;
            for (SmartWalletProfileEntity profile : targets) {
                try {
                    WalletBehaviorProfileView view = walletBehaviorProfileService.buildProfile(profile);
                    if (view == null) {
                        continue;
                    }
                    upsert(profile.getWalletAddress(), view);
                    ok++;
                } catch (Exception e) {
                    failed++;
                    log.warn("[BehaviorProfileSnapshotJob] failed for wallet={} reason={}",
                            profile.getWalletAddress(), e.getMessage());
                }
            }
            log.info("[BehaviorProfileSnapshotJob] snapshot done targets={} ok={} failed={}",
                    targets.size(), ok, failed);
        } catch (Exception e) {
            log.error("[BehaviorProfileSnapshotJob] unexpected snapshot failure: {}", e.getMessage(), e);
        }
    }

    // ─────────────────────────────── helpers ───────────────────────────────

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

    private void upsert(String walletAddress, WalletBehaviorProfileView view) {
        SmartWalletBehaviorProfileEntity existing = behaviorProfileMapper.selectOne(
                new LambdaQueryWrapper<SmartWalletBehaviorProfileEntity>()
                        .eq(SmartWalletBehaviorProfileEntity::getWalletAddress, walletAddress)
                        .eq(SmartWalletBehaviorProfileEntity::getSource, SOURCE)
                        .last("limit 1"));
        Date now = new Date();
        SmartWalletBehaviorProfileEntity entity = existing == null
                ? new SmartWalletBehaviorProfileEntity().setCreatedAt(now)
                : existing;
        entity.setWalletAddress(walletAddress)
                .setSource(SOURCE)
                .setAlias(view.alias())
                .setArchetype(view.archetype())
                .setFollowLane(view.followLane())
                .setHighFrequencyTradingAddress(view.highFrequencyTradingAddress())
                .setTrades7d(view.trades7d())
                .setTrades30d(view.trades30d())
                .setMedianInterTradeMinutes(view.medianInterTradeMinutes())
                .setClosedPositionSampleSize(view.closedPositionSampleSize())
                .setOrderWinRate(view.orderWinRate())
                .setAverageOrderRoiRate(view.averageOrderRoiRate())
                .setMedianOrderRoiRate(view.medianOrderRoiRate())
                .setTotalRealizedPnlUsdc(view.totalRealizedPnlUsdc())
                .setSettlementCompletionRate(view.settlementCompletionRate())
                .setEarlyExitRate(view.earlyExitRate())
                .setProfitEarlyExitRate(view.profitEarlyExitRate())
                .setLossEarlyExitRate(view.lossEarlyExitRate())
                .setEarlySettlementRate(view.earlySettlementRate())
                .setWinSourceType(view.winSourceType())
                .setLatencyAlphaRate(view.latencyAlphaRate())
                .setSwingAlphaRate(view.swingAlphaRate())
                .setSettlementWinRate(view.settlementWinRate())
                .setMedianHoldMinutes(view.medianHoldMinutes())
                .setStabilityScore(view.stabilityScore())
                .setQualityScore(view.qualityScore())
                .setReplicabilityScore(view.replicabilityScore())
                .setRecommendedExitLane(view.recommendedExitLane())
                .setSummary(view.summary())
                .setComputedAt(view.computedAt() == null ? now : view.computedAt())
                .setUpdatedAt(now);
        // 方案 B (W2-T6 优化): 一并计算并写入 Drift centroid; 失败保留旧值, 不阻塞 profile 写表.
        try {
            String centroidJson = leaderBehaviorDriftService.computeProfileVectorJson(walletAddress, null);
            if (centroidJson != null) {
                entity.setBehaviorVectorJson(centroidJson);
                entity.setBehaviorVectorComputedAt(now);
            }
        } catch (Exception ex) {
            log.debug("[BehaviorProfileSnapshotJob] centroid compute failed wallet={} reason={}",
                    walletAddress, ex.getMessage());
        }
        if (existing == null) {
            behaviorProfileMapper.insert(entity);
        } else {
            behaviorProfileMapper.updateById(entity);
        }
    }
}
