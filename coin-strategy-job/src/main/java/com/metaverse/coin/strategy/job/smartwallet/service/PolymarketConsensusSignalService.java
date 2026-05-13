package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.intelligence.model.EntityDedupedConsensus;
import com.metaverse.coin.strategy.job.smartwallet.intelligence.service.EntityAwareConsensusService;
import com.metaverse.coin.strategy.job.smartwallet.model.ConsensusSignalView;
import com.metaverse.coin.strategy.common.service.smartwallet.model.WalletBehaviorProfileView;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PolymarketConsensusSignalService {

    private final SmartWalletProperties properties;
    private final SmartWalletTradeEventMapper tradeEventMapper;
    private final SmartWalletProfileStoreService profileStoreService;
    private final EntityAwareConsensusService entityAwareConsensusService;
    private final SmartWalletLeaderboardStateService leaderboardStateService;
    private final WalletBehaviorProfileService walletBehaviorProfileService;

    public List<ConsensusSignalView> listRecentConsensusSignals() {
        SmartWalletProperties.SignalRisk risk = properties.getSignalRisk();
        return listConsensusSignals(risk.getConsensusWindowSeconds(), null, null, null, 100);
    }

    public List<ConsensusSignalView> listConsensusSignals(Integer windowSeconds,
                                                          String marketId,
                                                          String outcome,
                                                          String side,
                                                          Integer limit) {
        return buildConsensusSignals(windowSeconds, marketId, outcome, side, limit, properties.getSignalRisk());
    }

    private List<ConsensusSignalView> buildConsensusSignals(Integer windowSeconds,
                                                            String marketId,
                                                            String outcome,
                                                            String side,
                                                            Integer limit,
                                                            SmartWalletProperties.SignalRisk risk) {
        Map<String, SmartWalletProfileEntity> watchlistProfiles = profileStoreService.listWatchEnabled().stream()
                .collect(Collectors.toMap(item -> item.getWalletAddress().toLowerCase(), Function.identity(), (a, b) -> a, LinkedHashMap::new));
        List<SmartWalletTradeEventEntity> events = loadRecentEvents(windowSeconds, marketId, outcome, side, limit);
        Map<String, ConsensusAccumulator> grouped = new LinkedHashMap<>();

        for (SmartWalletTradeEventEntity event : events) {
            SmartWalletProfileEntity profile = watchlistProfiles.get(normalize(event.getWalletAddress()));
            if (profile == null) {
                continue;
            }
            String key = String.join("|",
                    nullSafe(event.getMarketId()),
                    nullSafe(event.getOutcome()),
                    nullSafe(event.getSide()));
            grouped.computeIfAbsent(key, ignored -> new ConsensusAccumulator(
                    event.getMarketId(),
                    event.getQuestion(),
                    event.getOutcome(),
                    event.getSide()))
                    .add(event, profile);
        }

        return grouped.values().stream()
                .map(acc -> acc.toView(entityAwareConsensusService, risk))
                .sorted(Comparator.comparing(ConsensusSignalView::getConsensusScore).reversed()
                        .thenComparing(ConsensusSignalView::getDedupedEntityCount, Comparator.reverseOrder()))
                .limit(normalizeLimit(limit))
                .toList();
    }

    public ConsensusSignalView buildConsensusForEvent(SmartWalletTradeEventEntity currentEvent) {
        return buildConsensusForEvent(currentEvent, properties.getSignalRisk());
    }

    public ConsensusSignalView buildConsensusForEvent(SmartWalletTradeEventEntity currentEvent,
                                                      SmartWalletProperties.SignalRisk risk) {
        SmartWalletProperties.SignalRisk effectiveRisk = risk == null ? properties.getSignalRisk() : risk;
        return buildConsensusSignals(effectiveRisk.getConsensusWindowSeconds(),
                        currentEvent.getMarketId(),
                        currentEvent.getOutcome(),
                        currentEvent.getSide(),
                        20,
                        effectiveRisk)
                .stream()
                .filter(item -> equalsIgnoreCase(item.getMarketId(), currentEvent.getMarketId()))
                .filter(item -> equalsIgnoreCase(item.getOutcome(), currentEvent.getOutcome()))
                .filter(item -> equalsIgnoreCase(item.getSide(), currentEvent.getSide()))
                .findFirst()
                .orElse(ConsensusSignalView.builder()
                        .marketId(currentEvent.getMarketId())
                        .question(currentEvent.getQuestion())
                        .outcome(currentEvent.getOutcome())
                        .side(currentEvent.getSide())
                        .distinctWallets(0)
                        .dedupedEntityCount(0)
                        .hasSuspiciousMultiWallet(false)
                        .totalTriggerSizeUsdc(BigDecimal.ZERO)
                        .averageTriggerPrice(BigDecimal.ZERO)
                        .consensusScore(BigDecimal.ZERO)
                        .qualityWeightedConsensusScore(BigDecimal.ZERO)
                        .topEntityWeightShare(BigDecimal.ZERO)
                        .leaderFollowerScore(BigDecimal.ZERO)
                        .entityIndependenceScore(BigDecimal.ZERO)
                        .recentConsistencyScore(BigDecimal.ZERO)
                        .latestOpenedAt(currentEvent.getOpenedAt())
                        .walletAddresses(new ArrayList<>())
                        .build());
    }

    private List<SmartWalletTradeEventEntity> loadRecentEvents(Integer windowSeconds,
                                                               String marketId,
                                                               String outcome,
                                                               String side,
                                                               Integer limit) {
        int effectiveWindow = windowSeconds == null || windowSeconds <= 0
                ? properties.getSignalRisk().getConsensusWindowSeconds()
                : windowSeconds;
        Date from = new Date(System.currentTimeMillis() - effectiveWindow * 1000L);
        LambdaQueryWrapper<SmartWalletTradeEventEntity> query = new LambdaQueryWrapper<SmartWalletTradeEventEntity>()
                .ge(SmartWalletTradeEventEntity::getOpenedAt, from)
                .orderByDesc(SmartWalletTradeEventEntity::getOpenedAt);
        if (StringUtils.hasText(marketId)) {
            query.eq(SmartWalletTradeEventEntity::getMarketId, marketId.trim());
        }
        if (StringUtils.hasText(outcome)) {
            query.eq(SmartWalletTradeEventEntity::getOutcome, outcome.trim().toUpperCase());
        }
        if (StringUtils.hasText(side)) {
            query.eq(SmartWalletTradeEventEntity::getSide, side.trim().toUpperCase());
        }
        query.last("limit " + Math.max(1, normalizeLimit(limit) * 20));
        return tradeEventMapper.selectList(query);
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return 100;
        }
        return Math.min(limit, 200);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private boolean equalsIgnoreCase(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    @Data
    private class ConsensusAccumulator {
        private final String marketId;
        private final String question;
        private final String outcome;
        private final String side;
        private final Map<String, BigDecimal> walletScores = new LinkedHashMap<>();
        private final List<String> walletAddresses = new ArrayList<>();
        private final Map<String, SmartWalletProfileEntity> walletProfiles = new LinkedHashMap<>();
        private final Map<String, Date> earliestOpenedAtByWallet = new LinkedHashMap<>();
        private final Map<String, Date> latestOpenedAtByWallet = new LinkedHashMap<>();
        private BigDecimal totalTriggerSizeUsdc = BigDecimal.ZERO;
        private BigDecimal totalTriggerPrice = BigDecimal.ZERO;
        private int priceCount = 0;
        private Date latestOpenedAt;

        private void add(SmartWalletTradeEventEntity event, SmartWalletProfileEntity profile) {
            String wallet = profile.getWalletAddress().toLowerCase();
            if (!walletScores.containsKey(wallet)) {
                walletScores.put(wallet, leaderboardStateService.adjustWalletScore(profile, event));
                walletAddresses.add(profile.getWalletAddress());
            }
            walletProfiles.put(wallet, profile);
            if (event.getOpenedAt() != null) {
                Date currentEarliest = earliestOpenedAtByWallet.get(wallet);
                if (currentEarliest == null || event.getOpenedAt().before(currentEarliest)) {
                    earliestOpenedAtByWallet.put(wallet, event.getOpenedAt());
                }
            }
            if (event.getOpenedAt() != null) {
                Date currentLatest = latestOpenedAtByWallet.get(wallet);
                if (currentLatest == null || event.getOpenedAt().after(currentLatest)) {
                    latestOpenedAtByWallet.put(wallet, event.getOpenedAt());
                }
            }
            totalTriggerSizeUsdc = totalTriggerSizeUsdc.add(event.getSizeUsdc() == null ? BigDecimal.ZERO : event.getSizeUsdc());
            if (event.getPrice() != null) {
                totalTriggerPrice = totalTriggerPrice.add(event.getPrice());
                priceCount++;
            }
            if (latestOpenedAt == null || (event.getOpenedAt() != null && event.getOpenedAt().after(latestOpenedAt))) {
                latestOpenedAt = event.getOpenedAt();
            }
        }

        private ConsensusSignalView toView(EntityAwareConsensusService entityAwareConsensusService,
                                          SmartWalletProperties.SignalRisk risk) {
            BigDecimal averagePrice = priceCount == 0
                    ? BigDecimal.ZERO
                    : totalTriggerPrice.divide(BigDecimal.valueOf(priceCount), 4, RoundingMode.HALF_UP);

            // 实体去重：解决「地址膨胀」bug —— 一个实体的多钱包只计 1 票
            EntityDedupedConsensus deduped = entityAwareConsensusService.deduplicateByEntity(
                    walletAddresses, walletScores);

            WeightedConsensus weightedConsensus = weightedConsensus(deduped, risk);
            BigDecimal consensusScore = (risk == null ? properties.getSignalRisk() : risk).isQualityWeightedConsensusEnabled()
                    ? weightedConsensus.qualityWeightedConsensusScore
                    : deduped.getEntityAvgScore();

            return ConsensusSignalView.builder()
                    .marketId(marketId)
                    .question(question)
                    .outcome(outcome)
                    .side(side)
                    .distinctWallets(walletScores.size())              // 原始地址数（历史兼容）
                    .dedupedEntityCount(deduped.getDedupedEntityCount()) // 实体去重后数量（风控使用）
                    .hasSuspiciousMultiWallet(deduped.isHasSuspiciousMultiWallet())
                    .totalTriggerSizeUsdc(totalTriggerSizeUsdc.setScale(4, RoundingMode.HALF_UP))
                    .averageTriggerPrice(averagePrice)
                    .consensusScore(consensusScore)
                    .qualityWeightedConsensusScore(weightedConsensus.qualityWeightedConsensusScore)
                    .topEntityWeightShare(weightedConsensus.topEntityWeightShare)
                    .leaderFollowerScore(weightedConsensus.leaderFollowerScore)
                    .entityIndependenceScore(weightedConsensus.entityIndependenceScore)
                    .recentConsistencyScore(weightedConsensus.recentConsistencyScore)
                    .latestOpenedAt(latestOpenedAt)
                    .walletAddresses(walletAddresses)
                    .build();
        }

        private WeightedConsensus weightedConsensus(EntityDedupedConsensus deduped,
                                                    SmartWalletProperties.SignalRisk risk) {
            if (deduped == null || deduped.getDedupedEntityCount() <= 0) {
                return new WeightedConsensus(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP),
                        BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP),
                        BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP),
                        BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP),
                        BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));
            }
            SmartWalletProperties.SignalRisk effectiveRisk = risk == null ? properties.getSignalRisk() : risk;
            long windowMs = Math.max(1L, (effectiveRisk.getConsensusWindowSeconds() == null ? 180 : effectiveRisk.getConsensusWindowSeconds()) * 1000L);
            long now = System.currentTimeMillis();
            BigDecimal totalWeight = BigDecimal.ZERO;
            BigDecimal weightedScore = BigDecimal.ZERO;
            BigDecimal topWeight = BigDecimal.ZERO;
            BigDecimal weightedLeaderFollower = BigDecimal.ZERO;
            BigDecimal weightedIndependence = BigDecimal.ZERO;
            BigDecimal weightedConsistency = BigDecimal.ZERO;
            Map<String, Date> entityEarliestMap = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> entry : deduped.getEntityWalletsMap().entrySet()) {
                Date entityEarliestOpenedAt = entry.getValue() == null ? null : entry.getValue().stream()
                        .map(wallet -> earliestOpenedAtByWallet.get(normalize(wallet)))
                        .filter(Objects::nonNull)
                        .min(Date::compareTo)
                        .orElse(null);
                entityEarliestMap.put(entry.getKey(), entityEarliestOpenedAt);
            }
            List<Map.Entry<String, Date>> entityOrder = entityEarliestMap.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> entry.getValue() == null ? new Date(now) : entry.getValue()))
                    .toList();
            for (Map.Entry<String, List<String>> entry : deduped.getEntityWalletsMap().entrySet()) {
                String entityId = entry.getKey();
                List<String> wallets = entry.getValue();
                BigDecimal entityScore = deduped.getEntityMaxScoreMap().getOrDefault(entityId, BigDecimal.ZERO);
                BigDecimal behaviorComposite = wallets == null ? BigDecimal.ZERO : wallets.stream()
                        .filter(StringUtils::hasText)
                        .map(walletBehaviorProfileService::getProfile)
                        .filter(Objects::nonNull)
                        .map(profile -> {
                            BigDecimal composite = profile.behaviorComposite();
                            return composite != null ? composite : walletBehaviorProfileService.behaviorComposite(profile);
                        })
                        .max(BigDecimal::compareTo)
                        .orElse(BigDecimal.ZERO);
                Date entityLatestOpenedAt = wallets == null ? null : wallets.stream()
                        .map(wallet -> latestOpenedAtByWallet.get(normalize(wallet)))
                        .filter(Objects::nonNull)
                        .max(Date::compareTo)
                        .orElse(null);
                Date entityEarliestOpenedAt = entityEarliestMap.get(entityId);
                BigDecimal recencyWeight = BigDecimal.ZERO;
                if (entityLatestOpenedAt != null) {
                    long ageMs = Math.max(0L, now - entityLatestOpenedAt.getTime());
                    BigDecimal ratio = BigDecimal.ONE.subtract(BigDecimal.valueOf(ageMs)
                            .divide(BigDecimal.valueOf(windowMs), 6, RoundingMode.HALF_UP));
                    recencyWeight = ratio.max(BigDecimal.ZERO).min(BigDecimal.ONE);
                }
                BigDecimal leaderFollowerScore = leaderFollowerScore(entityId, entityEarliestOpenedAt, entityOrder, windowMs);
                BigDecimal independenceScore = independenceScore(
                        deduped.getEntityResolutionMap() == null ? null : deduped.getEntityResolutionMap().get(entityId));
                BigDecimal recentConsistencyScore = wallets == null ? BigDecimal.ZERO : wallets.stream()
                        .filter(StringUtils::hasText)
                        .map(this::recentConsistencyScore)
                        .max(BigDecimal::compareTo)
                        .orElse(BigDecimal.ZERO);
                BigDecimal entityWeight = safe(effectiveRisk.getConsensusEntityBaseWeight())
                        .add(behaviorComposite.multiply(safe(effectiveRisk.getConsensusBehaviorWeight())))
                        .add(recencyWeight.multiply(safe(effectiveRisk.getConsensusRecencyWeight())))
                        .add(leaderFollowerScore.multiply(safe(effectiveRisk.getConsensusLeaderFollowerWeight())))
                        .add(independenceScore.multiply(safe(effectiveRisk.getConsensusIndependenceWeight())))
                        .add(recentConsistencyScore.multiply(safe(effectiveRisk.getConsensusRecentConsistencyWeight())));
                totalWeight = totalWeight.add(entityWeight);
                weightedScore = weightedScore.add(entityScore.multiply(entityWeight));
                weightedLeaderFollower = weightedLeaderFollower.add(leaderFollowerScore.multiply(entityWeight));
                weightedIndependence = weightedIndependence.add(independenceScore.multiply(entityWeight));
                weightedConsistency = weightedConsistency.add(recentConsistencyScore.multiply(entityWeight));
                topWeight = topWeight.max(entityWeight);
            }
            if (totalWeight.compareTo(BigDecimal.ZERO) <= 0) {
                return new WeightedConsensus(deduped.getEntityAvgScore(),
                        BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP),
                        BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP),
                        BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP),
                        BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));
            }
            BigDecimal qualityWeightedConsensusScore = weightedScore.divide(totalWeight, 4, RoundingMode.HALF_UP);
            BigDecimal topEntityWeightShare = topWeight.divide(totalWeight, 4, RoundingMode.HALF_UP);
            return new WeightedConsensus(
                    qualityWeightedConsensusScore,
                    topEntityWeightShare,
                    weightedLeaderFollower.divide(totalWeight, 4, RoundingMode.HALF_UP),
                    weightedIndependence.divide(totalWeight, 4, RoundingMode.HALF_UP),
                    weightedConsistency.divide(totalWeight, 4, RoundingMode.HALF_UP)
            );
        }

        private BigDecimal recentConsistencyScore(String walletAddress) {
            SmartWalletProfileEntity profile = walletProfiles.get(normalize(walletAddress));
            WalletBehaviorProfileView behaviorProfile = walletBehaviorProfileService.getProfile(walletAddress);
            BigDecimal stability = behaviorProfile == null ? BigDecimal.ZERO : safe(behaviorProfile.stabilityScore());
            BigDecimal recent7d = safe(profile == null ? null : profile.getRecentWinRate7d());
            BigDecimal recent30d = safe(profile == null ? null : profile.getRecentWinRate30d());
            BigDecimal historical = safe(profile == null ? null : profile.getWinRate());
            BigDecimal recentGap = BigDecimal.ONE.subtract(recent30d.subtract(recent7d).abs()).max(BigDecimal.ZERO).min(BigDecimal.ONE);
            BigDecimal historicalGap = BigDecimal.ONE.subtract(historical.subtract(recent7d).abs()).max(BigDecimal.ZERO).min(BigDecimal.ONE);
            return stability.multiply(new BigDecimal("0.50"))
                    .add(recentGap.multiply(new BigDecimal("0.25")))
                    .add(historicalGap.multiply(new BigDecimal("0.25")))
                    .setScale(4, RoundingMode.HALF_UP)
                    .max(BigDecimal.ZERO)
                    .min(BigDecimal.ONE);
        }

        private BigDecimal leaderFollowerScore(String entityId,
                                               Date entityEarliestOpenedAt,
                                               List<Map.Entry<String, Date>> entityOrder,
                                               long windowMs) {
            if (entityOrder == null || entityOrder.isEmpty()) {
                return BigDecimal.ONE.setScale(4, RoundingMode.HALF_UP);
            }
            int index = 0;
            Date leaderAt = entityOrder.get(0).getValue();
            for (int i = 0; i < entityOrder.size(); i++) {
                if (Objects.equals(entityOrder.get(i).getKey(), entityId)) {
                    index = i;
                    break;
                }
            }
            if (entityOrder.size() == 1 || entityEarliestOpenedAt == null || leaderAt == null) {
                return BigDecimal.ONE.setScale(4, RoundingMode.HALF_UP);
            }
            long gapMs = Math.max(0L, entityEarliestOpenedAt.getTime() - leaderAt.getTime());
            BigDecimal gapScore = BigDecimal.ONE.subtract(BigDecimal.valueOf(gapMs)
                    .divide(BigDecimal.valueOf(Math.max(1L, windowMs)), 6, RoundingMode.HALF_UP));
            BigDecimal orderScore = BigDecimal.ONE.subtract(BigDecimal.valueOf(index)
                    .divide(BigDecimal.valueOf(Math.max(1, entityOrder.size() - 1)), 6, RoundingMode.HALF_UP));
            return gapScore.max(BigDecimal.ZERO).min(BigDecimal.ONE).multiply(new BigDecimal("0.55"))
                    .add(orderScore.max(BigDecimal.ZERO).min(BigDecimal.ONE).multiply(new BigDecimal("0.45")))
                    .setScale(4, RoundingMode.HALF_UP);
        }

        private BigDecimal independenceScore(com.metaverse.coin.strategy.job.smartwallet.intelligence.model.EntityResolutionResult resolution) {
            if (resolution == null) {
                return new BigDecimal("0.55");
            }
            if (resolution.isPendingClustering()) {
                return new BigDecimal("0.35");
            }
            BigDecimal confidence = resolution.getConfidenceScore() == null ? BigDecimal.ZERO : resolution.getConfidenceScore();
            BigDecimal score = new BigDecimal("0.50")
                    .add(confidence.multiply(new BigDecimal("0.35")));
            if (resolution.isResolvedFromMap()) {
                score = score.add(new BigDecimal("0.10"));
            }
            int walletCount = Math.max(1, resolution.getEntityWalletCount());
            if (walletCount > 3) {
                score = score.subtract(BigDecimal.valueOf(Math.min(walletCount - 3, 4)).multiply(new BigDecimal("0.05")));
            }
            return score.max(new BigDecimal("0.20")).min(BigDecimal.ONE).setScale(4, RoundingMode.HALF_UP);
        }

        private BigDecimal safe(BigDecimal value) {
            return value == null ? BigDecimal.ZERO : value;
        }
    }

    private record WeightedConsensus(BigDecimal qualityWeightedConsensusScore,
                                     BigDecimal topEntityWeightShare,
                                     BigDecimal leaderFollowerScore,
                                     BigDecimal entityIndependenceScore,
                                     BigDecimal recentConsistencyScore) {
    }
}
