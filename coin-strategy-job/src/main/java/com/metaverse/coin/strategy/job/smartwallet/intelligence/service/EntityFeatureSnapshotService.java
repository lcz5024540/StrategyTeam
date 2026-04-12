package com.metaverse.coin.strategy.job.smartwallet.intelligence.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletProfileMapper;
import com.metaverse.coin.strategy.common.dao.mapper.WalletEntityFeatureSnapshotMapper;
import com.metaverse.coin.strategy.common.dao.mapper.WalletEntityMapMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.WalletEntityFeatureSnapshotEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.WalletEntityMapEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 实体特征快照服务（Phase 2）
 *
 * 功能：
 * 1. 合并同实体下所有钱包的 SmartWalletProfile 指标
 * 2. 计算实体维度的聚合胜率/ROI/PnL/交易次数/实体评分
 * 3. 写入 wallet_entity_feature_snapshot 表
 * 4. 定时每6小时更新一次
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EntityFeatureSnapshotService {

    private final WalletEntityMapMapper entityMapMapper;
    private final WalletEntityFeatureSnapshotMapper snapshotMapper;
    private final SmartWalletProfileMapper profileMapper;
    private final ObjectMapper objectMapper;

    /**
     * 为单个实体计算并保存特征快照
     */
    public WalletEntityFeatureSnapshotEntity computeAndSave(String entityId) {
        if (!StringUtils.hasText(entityId)) {
            return null;
        }

        // 获取实体下所有钱包地址
        List<WalletEntityMapEntity> entityMaps = entityMapMapper.selectList(
                new LambdaQueryWrapper<WalletEntityMapEntity>()
                        .eq(WalletEntityMapEntity::getEntityId, entityId));

        if (entityMaps.isEmpty()) {
            return null;
        }

        List<String> walletAddresses = entityMaps.stream()
                .map(WalletEntityMapEntity::getWalletAddress)
                .toList();

        // 加载所有钱包的 profile
        List<SmartWalletProfileEntity> profiles = profileMapper.selectList(
                new LambdaQueryWrapper<SmartWalletProfileEntity>()
                        .in(SmartWalletProfileEntity::getWalletAddress, walletAddresses));

        if (profiles.isEmpty()) {
            return null;
        }

        // 聚合指标
        AggregatedMetrics metrics = aggregate(profiles);

        // 序列化钱包地址列表
        String walletAddressesJson = serializeAddresses(walletAddresses);

        Date now = new Date();
        WalletEntityFeatureSnapshotEntity snapshot = new WalletEntityFeatureSnapshotEntity()
                .setEntityId(entityId)
                .setSnapshotTime(now)
                .setWalletCount(walletAddresses.size())
                .setAggregatedWinRate(metrics.winRate)
                .setAggregatedRoi(metrics.roi)
                .setAggregatedPnlUsdc(metrics.pnlUsdc)
                .setAggregatedTotalTrades(metrics.totalTrades)
                .setEntityScore(metrics.entityScore)
                .setIsMultiWallet(walletAddresses.size() > 1)
                .setWalletAddressesJson(walletAddressesJson)
                .setCreatedAt(now);

        snapshotMapper.insert(snapshot);
        return snapshot;
    }

    /**
     * 获取实体最新的特征快照
     */
    public Optional<WalletEntityFeatureSnapshotEntity> getLatestSnapshot(String entityId) {
        if (!StringUtils.hasText(entityId)) {
            return Optional.empty();
        }
        WalletEntityFeatureSnapshotEntity snapshot = snapshotMapper.selectOne(
                new LambdaQueryWrapper<WalletEntityFeatureSnapshotEntity>()
                        .eq(WalletEntityFeatureSnapshotEntity::getEntityId, entityId)
                        .orderByDesc(WalletEntityFeatureSnapshotEntity::getSnapshotTime)
                        .last("limit 1"));
        return Optional.ofNullable(snapshot);
    }

    /**
     * 定时任务：每6小时对所有实体更新特征快照
     */
    @Scheduled(fixedDelayString = "${smart-wallet.intelligence.entity-snapshot-fixed-delay-ms:21600000}")
    public void refreshAllEntitySnapshots() {
        List<String> allEntityIds = entityMapMapper.selectList(new LambdaQueryWrapper<WalletEntityMapEntity>()
                        .select(WalletEntityMapEntity::getEntityId))
                .stream()
                .map(WalletEntityMapEntity::getEntityId)
                .distinct()
                .toList();

        log.info("[EntityFeatureSnapshotService] Refreshing snapshots for {} entities", allEntityIds.size());
        int success = 0;
        for (String entityId : allEntityIds) {
            try {
                if (computeAndSave(entityId) != null) {
                    success++;
                }
            } catch (Exception e) {
                log.warn("[EntityFeatureSnapshotService] Failed to compute snapshot for entityId={}: {}", entityId, e.getMessage());
            }
        }
        log.info("[EntityFeatureSnapshotService] Snapshot refresh done. success={}/{}", success, allEntityIds.size());
    }

    // ---- 私有方法 ----

    private AggregatedMetrics aggregate(List<SmartWalletProfileEntity> profiles) {
        // 按交易次数加权计算胜率和 ROI
        int totalTrades = profiles.stream()
                .mapToInt(p -> p.getTotalTrades() == null ? 0 : p.getTotalTrades())
                .sum();

        BigDecimal weightedWinRate = BigDecimal.ZERO;
        BigDecimal weightedRoi = BigDecimal.ZERO;
        BigDecimal totalPnl = BigDecimal.ZERO;

        for (SmartWalletProfileEntity p : profiles) {
            int trades = p.getTotalTrades() == null ? 0 : p.getTotalTrades();
            BigDecimal weight = totalTrades > 0
                    ? BigDecimal.valueOf(trades).divide(BigDecimal.valueOf(totalTrades), 6, RoundingMode.HALF_UP)
                    : BigDecimal.ONE.divide(BigDecimal.valueOf(profiles.size()), 6, RoundingMode.HALF_UP);

            if (p.getWinRate() != null) {
                weightedWinRate = weightedWinRate.add(p.getWinRate().multiply(weight));
            }
            if (p.getAverageRoiRate() != null) {
                weightedRoi = weightedRoi.add(p.getAverageRoiRate().multiply(weight));
            }
            if (p.getRealizedPnlUsdc() != null) {
                totalPnl = totalPnl.add(p.getRealizedPnlUsdc());
            }
        }

        // 实体评分：基于合并后的指标重新评分
        BigDecimal entityScore = computeEntityScore(weightedWinRate, weightedRoi, totalPnl, totalTrades, profiles);

        AggregatedMetrics metrics = new AggregatedMetrics();
        metrics.winRate = weightedWinRate.setScale(4, RoundingMode.HALF_UP);
        metrics.roi = weightedRoi.setScale(4, RoundingMode.HALF_UP);
        metrics.pnlUsdc = totalPnl.setScale(2, RoundingMode.HALF_UP);
        metrics.totalTrades = totalTrades;
        metrics.entityScore = entityScore.max(BigDecimal.ZERO).min(BigDecimal.ONE).setScale(4, RoundingMode.HALF_UP);
        return metrics;
    }

    private BigDecimal computeEntityScore(BigDecimal winRate, BigDecimal roi, BigDecimal pnl, int totalTrades,
                                           List<SmartWalletProfileEntity> profiles) {
        // 复用与 SmartWalletScoringService 相同的权重，但在实体维度计算
        BigDecimal winRateComponent = winRate.min(BigDecimal.ONE).max(BigDecimal.ZERO).multiply(new BigDecimal("0.45"));
        BigDecimal pnlCap = new BigDecimal("50000");
        BigDecimal pnlComponent = pnl.max(BigDecimal.ZERO).min(pnlCap)
                .divide(pnlCap, 6, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("0.20"));
        BigDecimal sampleCap = new BigDecimal("250");
        BigDecimal sampleComponent = BigDecimal.valueOf(Math.min(totalTrades, 250))
                .divide(sampleCap, 6, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("0.15"));
        BigDecimal roiComponent = roi.min(BigDecimal.ONE).max(BigDecimal.ZERO).multiply(new BigDecimal("0.20"));

        BigDecimal maxDrawdown = profiles.stream()
                .map(p -> p.getMaxDrawdownRate() == null ? BigDecimal.ZERO : p.getMaxDrawdownRate())
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
        BigDecimal drawdownPenalty = maxDrawdown.min(BigDecimal.ONE).max(BigDecimal.ZERO).multiply(new BigDecimal("0.20"));

        return winRateComponent.add(pnlComponent).add(sampleComponent).add(roiComponent).subtract(drawdownPenalty);
    }

    private String serializeAddresses(List<String> addresses) {
        try {
            return objectMapper.writeValueAsString(addresses);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static class AggregatedMetrics {
        BigDecimal winRate;
        BigDecimal roi;
        BigDecimal pnlUsdc;
        int totalTrades;
        BigDecimal entityScore;
    }
}
