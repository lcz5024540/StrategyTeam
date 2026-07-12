package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletBehaviorProfileMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFeatureSnapshotMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletBehaviorProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFeatureSnapshotEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.model.WalletBehaviorProfileView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SmartWalletFeatureSnapshotService {

    public static final String LIVE_DATASET_VERSION = "polymarket-live-v1";

    private final SmartWalletFeatureSnapshotMapper snapshotMapper;
    private final SmartWalletBehaviorProfileMapper behaviorProfileMapper;

    public SmartWalletFeatureSnapshotEntity capture(SmartWalletProfileEntity profile) {
        if (profile == null || !StringUtils.hasText(profile.getWalletAddress())) {
            return null;
        }
        Date availableAt = new Date();
        SmartWalletBehaviorProfileEntity behavior = latestBehavior(profile.getWalletAddress());
        Date featureAsOf = latest(profile.getUpdatedAt(), behavior == null ? null : behavior.getUpdatedAt(), availableAt);
        SmartWalletFeatureSnapshotEntity snapshot = new SmartWalletFeatureSnapshotEntity()
                .setDatasetVersion(LIVE_DATASET_VERSION)
                .setWalletAddress(normalizeWallet(profile.getWalletAddress()))
                .setFeatureAsOf(featureAsOf)
                .setFeatureAvailableAt(availableAt)
                .setSourceProfileUpdatedAt(profile.getUpdatedAt())
                .setWinRate(profile.getWinRate())
                .setCredibleWinRate(profile.getCredibleWinRate())
                .setShrunkWinRate(profile.getShrunkWinRate())
                .setDecisiveTrades(profile.getDecisiveTrades())
                .setRecentWinRate7d(profile.getRecentWinRate7d())
                .setRecentWinRate30d(profile.getRecentWinRate30d())
                .setRecentTradeCount7d(profile.getRecentTradeCount7d())
                .setRecentTradeCount30d(profile.getRecentTradeCount30d())
                .setRealizedPnlUsdc(profile.getRealizedPnlUsdc())
                .setTotalTrades(profile.getTotalTrades())
                .setAverageRoiRate(profile.getAverageRoiRate())
                .setMaxDrawdownRate(profile.getMaxDrawdownRate())
                .setWalletScore(profile.getWalletScore())
                .setQualified(profile.getQualified())
                .setWatchEnabled(profile.getWatchEnabled())
                .setObserveCooldownUntil(profile.getObserveCooldownUntil())
                .setCreatedAt(availableAt);
        if (behavior != null) {
            snapshot.setArchetype(behavior.getArchetype())
                    .setReplicabilityScore(behavior.getReplicabilityScore())
                    .setQualityScore(behavior.getQualityScore())
                    .setStabilityScore(behavior.getStabilityScore())
                    .setBehaviorComposite(behaviorComposite(behavior))
                    .setRecommendedExitLane(behavior.getRecommendedExitLane());
        }
        snapshotMapper.insert(snapshot);
        return snapshot;
    }

    public Map<String, List<SmartWalletFeatureSnapshotEntity>> loadTimeline(String datasetVersion,
                                                                            List<String> walletAddresses,
                                                                            Date decisionUpperBound) {
        if (!StringUtils.hasText(datasetVersion) || walletAddresses == null || walletAddresses.isEmpty()
                || decisionUpperBound == null) {
            return Map.of();
        }
        List<String> normalized = walletAddresses.stream()
                .filter(StringUtils::hasText)
                .map(this::normalizeWallet)
                .distinct()
                .toList();
        if (normalized.isEmpty()) {
            return Map.of();
        }
        List<SmartWalletFeatureSnapshotEntity> rows = snapshotMapper.selectList(
                new LambdaQueryWrapper<SmartWalletFeatureSnapshotEntity>()
                        .eq(SmartWalletFeatureSnapshotEntity::getDatasetVersion, datasetVersion)
                        .in(SmartWalletFeatureSnapshotEntity::getWalletAddress, normalized)
                        .le(SmartWalletFeatureSnapshotEntity::getFeatureAsOf, decisionUpperBound)
                        .le(SmartWalletFeatureSnapshotEntity::getFeatureAvailableAt, decisionUpperBound)
                        .orderByAsc(SmartWalletFeatureSnapshotEntity::getFeatureAsOf)
                        .orderByAsc(SmartWalletFeatureSnapshotEntity::getId));
        Map<String, List<SmartWalletFeatureSnapshotEntity>> timeline = new LinkedHashMap<>();
        for (SmartWalletFeatureSnapshotEntity row : rows) {
            timeline.computeIfAbsent(normalizeWallet(row.getWalletAddress()), ignored -> new ArrayList<>()).add(row);
        }
        return timeline;
    }

    public SmartWalletFeatureSnapshotEntity resolveAt(List<SmartWalletFeatureSnapshotEntity> snapshots, Date decisionAt) {
        if (snapshots == null || snapshots.isEmpty() || decisionAt == null) {
            return null;
        }
        return snapshots.stream()
                .filter(row -> !row.getFeatureAsOf().after(decisionAt))
                .filter(row -> !row.getFeatureAvailableAt().after(decisionAt))
                .max(Comparator.comparing(SmartWalletFeatureSnapshotEntity::getFeatureAsOf)
                        .thenComparing(row -> row.getId() == null ? 0L : row.getId()))
                .orElse(null);
    }

    public SmartWalletProfileEntity toProfile(SmartWalletFeatureSnapshotEntity snapshot) {
        if (snapshot == null) {
            return null;
        }
        return new SmartWalletProfileEntity()
                .setWalletAddress(snapshot.getWalletAddress())
                .setSource("POLYMARKET")
                .setWinRate(snapshot.getWinRate())
                .setCredibleWinRate(snapshot.getCredibleWinRate())
                .setShrunkWinRate(snapshot.getShrunkWinRate())
                .setDecisiveTrades(snapshot.getDecisiveTrades())
                .setRecentWinRate7d(snapshot.getRecentWinRate7d())
                .setRecentWinRate30d(snapshot.getRecentWinRate30d())
                .setRecentTradeCount7d(snapshot.getRecentTradeCount7d())
                .setRecentTradeCount30d(snapshot.getRecentTradeCount30d())
                .setRealizedPnlUsdc(snapshot.getRealizedPnlUsdc())
                .setTotalTrades(snapshot.getTotalTrades())
                .setAverageRoiRate(snapshot.getAverageRoiRate())
                .setMaxDrawdownRate(snapshot.getMaxDrawdownRate())
                .setWalletScore(snapshot.getWalletScore())
                .setQualified(snapshot.getQualified())
                .setWatchEnabled(snapshot.getWatchEnabled())
                .setObserveCooldownUntil(snapshot.getObserveCooldownUntil())
                .setUpdatedAt(snapshot.getFeatureAsOf());
    }

    public WalletBehaviorProfileView toBehavior(SmartWalletFeatureSnapshotEntity snapshot) {
        if (snapshot == null) {
            return null;
        }
        return WalletBehaviorProfileView.builder()
                .walletAddress(snapshot.getWalletAddress())
                .archetype(snapshot.getArchetype())
                .replicabilityScore(snapshot.getReplicabilityScore())
                .qualityScore(snapshot.getQualityScore())
                .stabilityScore(snapshot.getStabilityScore())
                .behaviorComposite(snapshot.getBehaviorComposite())
                .recommendedExitLane(snapshot.getRecommendedExitLane())
                .computedAt(snapshot.getFeatureAsOf())
                .build();
    }

    private SmartWalletBehaviorProfileEntity latestBehavior(String walletAddress) {
        return behaviorProfileMapper.selectOne(new LambdaQueryWrapper<SmartWalletBehaviorProfileEntity>()
                .eq(SmartWalletBehaviorProfileEntity::getWalletAddress, walletAddress.trim())
                .eq(SmartWalletBehaviorProfileEntity::getSource, "POLYMARKET")
                .orderByDesc(SmartWalletBehaviorProfileEntity::getUpdatedAt)
                .last("limit 1"));
    }

    private java.math.BigDecimal behaviorComposite(SmartWalletBehaviorProfileEntity behavior) {
        if (behavior.getReplicabilityScore() == null || behavior.getQualityScore() == null
                || behavior.getStabilityScore() == null) {
            return null;
        }
        return behavior.getReplicabilityScore().multiply(new java.math.BigDecimal("0.45"))
                .add(behavior.getQualityScore().multiply(new java.math.BigDecimal("0.30")))
                .add(behavior.getStabilityScore().multiply(new java.math.BigDecimal("0.25")));
    }

    private Date latest(Date first, Date second, Date fallback) {
        if (first == null && second == null) {
            return fallback;
        }
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.after(second) ? first : second;
    }

    private String normalizeWallet(String walletAddress) {
        return walletAddress.trim().toLowerCase(Locale.ROOT);
    }
}
