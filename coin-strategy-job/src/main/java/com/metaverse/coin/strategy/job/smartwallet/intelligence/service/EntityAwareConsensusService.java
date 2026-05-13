package com.metaverse.coin.strategy.job.smartwallet.intelligence.service;

import com.metaverse.coin.strategy.job.smartwallet.intelligence.model.EntityDedupedConsensus;
import com.metaverse.coin.strategy.job.smartwallet.intelligence.model.EntityResolutionResult;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 实体感知的共识去重服务
 *
 * 解决问题：一个实体持有 N 个 watchlist 钱包，每个钱包都触发信号时，
 * 原系统会将 distinctWallets 计为 N，而真实共识强度应为 1（个实体）。
 *
 * 使用方式：在 PolymarketConsensusSignalService 构建共识视图时注入此服务，
 * 用 dedupedEntityCount 替代 distinctWallets 作为共识强度指标。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EntityAwareConsensusService {

    private final WalletEntityResolutionService resolutionService;
    private final SmartWalletProperties properties;

    /**
     * 对共识信号中的钱包地址按实体去重
     *
     * @param walletAddresses 参与共识的钱包地址列表
     * @param walletScoreMap  钱包地址 → 钱包评分的映射（wallet_score）
     * @return 实体去重后的共识结果
     */
    public EntityDedupedConsensus deduplicateByEntity(List<String> walletAddresses,
                                                       Map<String, BigDecimal> walletScoreMap) {
        if (walletAddresses == null || walletAddresses.isEmpty()) {
            return EntityDedupedConsensus.builder()
                    .rawWalletCount(0)
                    .dedupedEntityCount(0)
                    .entityIds(new ArrayList<>())
                    .entityAvgScore(BigDecimal.ZERO)
                    .entityMaxScoreMap(new LinkedHashMap<>())
                    .entityWalletsMap(new LinkedHashMap<>())
                    .hasSuspiciousMultiWallet(false)
                    .suspiciousEntityIds(new ArrayList<>())
                    .build();
        }

        // 批量解析地址 → 实体
        Map<String, EntityResolutionResult> resolutions = resolutionService.resolveEntities(walletAddresses);

        // 按实体ID分组：entityId → [walletAddresses]
        Map<String, List<String>> entityToWallets = new LinkedHashMap<>();
        Map<String, EntityResolutionResult> entityResolutionMap = new LinkedHashMap<>();
        for (String wallet : walletAddresses) {
            EntityResolutionResult res = resolutions.getOrDefault(wallet.toLowerCase(),
                    EntityResolutionResult.selfEntity(wallet));
            String effectiveEntityKey = shouldUseEntityForDedup(res)
                    ? res.getEntityId()
                    : wallet.toLowerCase();
            entityToWallets.computeIfAbsent(effectiveEntityKey, k -> new ArrayList<>()).add(wallet);
            entityResolutionMap.merge(effectiveEntityKey, res, this::pickHigherConfidence);
        }

        // 统计实体级最高分（一个实体内取 walletScore 最大值）
        Set<String> entityIds = new LinkedHashSet<>(entityToWallets.keySet());
        List<String> suspiciousEntityIds = new ArrayList<>();
        BigDecimal totalEntityScore = BigDecimal.ZERO;
        Map<String, BigDecimal> entityMaxScoreMap = new LinkedHashMap<>();

        for (Map.Entry<String, List<String>> entry : entityToWallets.entrySet()) {
            String entityId = entry.getKey();
            List<String> wallets = entry.getValue();

            // 多钱包告警
            if (wallets.size() > 1 && !isWalletLevelBucket(entityId, wallets)) {
                suspiciousEntityIds.add(entityId);
                log.warn("[EntityAwareConsensusService] Suspicious multi-wallet entity detected: entityId={}, wallets={}, count={}",
                        entityId, wallets, wallets.size());
            }

            // 取该实体内的最大钱包分数
            BigDecimal maxScore = wallets.stream()
                    .map(w -> walletScoreMap.getOrDefault(w.toLowerCase(), BigDecimal.ZERO))
                    .max(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO);
            entityMaxScoreMap.put(entityId, maxScore);
            totalEntityScore = totalEntityScore.add(maxScore);
        }

        BigDecimal entityAvgScore = entityIds.isEmpty()
                ? BigDecimal.ZERO
                : totalEntityScore.divide(BigDecimal.valueOf(entityIds.size()), 4, RoundingMode.HALF_UP);

        return EntityDedupedConsensus.builder()
                .rawWalletCount(walletAddresses.size())
                .dedupedEntityCount(entityIds.size())
                .entityIds(new ArrayList<>(entityIds))
                .entityAvgScore(entityAvgScore)
                .entityMaxScoreMap(entityMaxScoreMap)
                .entityWalletsMap(entityToWallets)
                .entityResolutionMap(entityResolutionMap)
                .hasSuspiciousMultiWallet(!suspiciousEntityIds.isEmpty())
                .suspiciousEntityIds(suspiciousEntityIds)
                .build();
    }

    /**
     * 快捷方法：仅返回去重后的实体数（用于简单门控判断）
     */
    public int countDistinctEntities(List<String> walletAddresses, Map<String, BigDecimal> walletScoreMap) {
        return deduplicateByEntity(walletAddresses, walletScoreMap).getDedupedEntityCount();
    }

    private boolean shouldUseEntityForDedup(EntityResolutionResult resolution) {
        if (resolution == null || !resolution.isResolvedFromMap()) {
            return false;
        }
        if (!StringUtils.hasText(resolution.getEntityId())
                || !StringUtils.hasText(resolution.getClusterMethod())
                || "SELF".equalsIgnoreCase(resolution.getClusterMethod())) {
            return false;
        }
        BigDecimal threshold = properties.getIntelligence().getMinClusterConfidenceForDedup();
        BigDecimal confidence = resolution.getConfidenceScore() == null ? BigDecimal.ZERO : resolution.getConfidenceScore();
        return confidence.compareTo(threshold == null ? BigDecimal.ZERO : threshold) >= 0;
    }

    private boolean isWalletLevelBucket(String entityId, List<String> wallets) {
        if (wallets == null || wallets.isEmpty() || wallets.size() != 1 || !StringUtils.hasText(entityId)) {
            return false;
        }
        return entityId.equalsIgnoreCase(wallets.get(0));
    }

    private EntityResolutionResult pickHigherConfidence(EntityResolutionResult left, EntityResolutionResult right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        BigDecimal leftScore = left.getConfidenceScore() == null ? BigDecimal.ZERO : left.getConfidenceScore();
        BigDecimal rightScore = right.getConfidenceScore() == null ? BigDecimal.ZERO : right.getConfidenceScore();
        return rightScore.compareTo(leftScore) >= 0 ? right : left;
    }
}
