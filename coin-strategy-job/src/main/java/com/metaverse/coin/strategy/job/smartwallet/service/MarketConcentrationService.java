package com.metaverse.coin.strategy.job.smartwallet.service;
import com.metaverse.coin.strategy.common.service.smartwallet.model.ConcentrationSnapshot;

import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 市场持仓集中度分析服务
 *
 * 职责：
 *   - 通过 PolymarketApiClient.fetchMarketTopPositions() 获取市场 Top-N 持仓分布
 *   - 计算 CR_1 / CR_3 / CR_5（Top-N 集中度比率）和 HHI（赫芬达尔-赫希曼指数）
 *   - 结果按市场+方向缓存（TTL 由配置项 concentrationCacheTtlMs 控制，默认 60s）
 *   - 暴露 analyze(conditionId, outcome) 供 SmartWalletSignalRiskService 调用
 *
 * 集中度指标定义：
 *   CR_N = 前 N 名持仓量 / 所有观测持仓量之和
 *   HHI  = Σ(每个持仓者市场份额²) × 10000，范围 [0, 10000]
 *            < 1500：竞争性    1500-2500：中度集中    > 2500：高度集中
 *
 * 安全设计：
 *   - API 调用失败时返回 ConcentrationSnapshot.UNKNOWN（fail-open，不阻断信号）
 *   - 缓存 key：conditionId + ":" + normalizedOutcome（大写）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketConcentrationService {

    private final SmartWalletProperties properties;
    private final PolymarketApiClient apiClient;

    /** 缓存：key = conditionId:OUTCOME, value = 含过期时间戳的快照 */
    private final Map<String, CachedSnapshot> cache = new ConcurrentHashMap<>();

    /**
     * 分析指定市场和方向的持仓集中度。
     *
     * @param conditionId 市场 conditionId
     * @param outcome     "YES" / "NO"；传 null 或空串时分析双向合并数据
     * @return 集中度快照；API 失败时返回 {@link ConcentrationSnapshot#UNKNOWN}
     */
    public ConcentrationSnapshot analyze(String conditionId, String outcome) {
        if (!StringUtils.hasText(conditionId)) {
            return ConcentrationSnapshot.UNKNOWN;
        }
        String normalizedOutcome = StringUtils.hasText(outcome) ? outcome.trim().toUpperCase() : "ALL";
        String cacheKey = conditionId.trim() + ":" + normalizedOutcome;

        CachedSnapshot cached = cache.get(cacheKey);
        int ttlMs = properties.getSignalRisk().getConcentrationCacheTtlMs();
        if (cached != null && System.currentTimeMillis() - cached.capturedAt() < ttlMs) {
            return cached.snapshot();
        }

        ConcentrationSnapshot fresh = fetchAndCompute(conditionId, normalizedOutcome);
        cache.put(cacheKey, new CachedSnapshot(fresh, System.currentTimeMillis()));
        return fresh;
    }

    /** 主动淘汰缓存（执行前验证价格时使用） */
    public void invalidate(String conditionId) {
        if (!StringUtils.hasText(conditionId)) return;
        String prefix = conditionId.trim() + ":";
        cache.keySet().removeIf(k -> k.startsWith(prefix));
    }

    // ─────────────────────────────────────────────────────────────────────────

    private ConcentrationSnapshot fetchAndCompute(String conditionId, String normalizedOutcome) {
        SmartWalletProperties.SignalRisk risk = properties.getSignalRisk();
        int topN = risk.getConcentrationTopN();

        List<PolymarketApiClient.MarketPositionEntry> allEntries;
        try {
            allEntries = apiClient.fetchMarketTopPositions(conditionId, topN);
        } catch (Exception e) {
            log.warn("[Concentration] Failed to fetch positions for market={}: {}", conditionId, e.getMessage());
            return ConcentrationSnapshot.UNKNOWN;
        }

        if (allEntries == null || allEntries.isEmpty()) {
            log.debug("[Concentration] No position data for market={} outcome={}", conditionId, normalizedOutcome);
            return ConcentrationSnapshot.UNKNOWN;
        }

        // 按方向过滤（ALL = 合并双向，用 size 直接汇总）
        List<PolymarketApiClient.MarketPositionEntry> filtered;
        if ("ALL".equals(normalizedOutcome)) {
            filtered = new ArrayList<>(allEntries);
        } else {
            filtered = allEntries.stream()
                    .filter(e -> normalizedOutcome.equals(e.getOutcome()))
                    .collect(Collectors.toList());
        }

        if (filtered.isEmpty()) {
            return ConcentrationSnapshot.UNKNOWN;
        }

        // 按 size 降序排列（API 通常已排好，这里确保正确性）
        filtered.sort(Comparator.comparing(PolymarketApiClient.MarketPositionEntry::getSize,
                Comparator.nullsLast(Comparator.reverseOrder())));

        // 计算总持仓量（观测值，非真实流通量）
        BigDecimal totalSize = filtered.stream()
                .map(e -> e.getSize() != null ? e.getSize() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalSize.compareTo(BigDecimal.ZERO) <= 0) {
            return ConcentrationSnapshot.UNKNOWN;
        }

        // ── CR_N 计算 ────────────────────────────────────────────────────────
        BigDecimal cr1 = cumulativeShare(filtered, 1, totalSize);
        BigDecimal cr3 = cumulativeShare(filtered, 3, totalSize);
        BigDecimal cr5 = cumulativeShare(filtered, 5, totalSize);

        // ── HHI 计算 ─────────────────────────────────────────────────────────
        int hhi = computeHhi(filtered, totalSize);

        // ── Top 持仓者地址列表（供鲸鱼身份检测使用） ─────────────────────────
        List<String> topAddresses = filtered.stream()
                .limit(5)
                .map(PolymarketApiClient.MarketPositionEntry::getWalletAddress)
                .filter(StringUtils::hasText)
                .collect(Collectors.toList());

        // ── 是否高度集中判定 ─────────────────────────────────────────────────
        boolean extremelyConcentrated = isExtremelyConcentrated(cr1, cr3, cr5, hhi, filtered.size(), risk);
        boolean highlyConcentrated = extremelyConcentrated || isHighlyConcentrated(cr1, cr3, hhi, risk);

        ConcentrationSnapshot snapshot = new ConcentrationSnapshot(
                conditionId,
                normalizedOutcome,
                filtered.size(),
                cr1,
                cr3,
                cr5,
                hhi,
                extremelyConcentrated,
                highlyConcentrated,
                topAddresses,
                System.currentTimeMillis()
        );

        log.debug("[Concentration] market={} outcome={} holders={} CR1={} CR3={} CR5={} HHI={} extreme={} concentrated={}",
                conditionId, normalizedOutcome, filtered.size(),
                cr1.setScale(3, RoundingMode.HALF_UP),
                cr3.setScale(3, RoundingMode.HALF_UP),
                cr5.setScale(3, RoundingMode.HALF_UP),
                hhi, extremelyConcentrated, highlyConcentrated);

        return snapshot;
    }

    /** 计算前 N 名的累积份额 CR_N */
    private BigDecimal cumulativeShare(List<PolymarketApiClient.MarketPositionEntry> sorted,
                                        int n, BigDecimal totalSize) {
        BigDecimal topSum = sorted.stream()
                .limit(n)
                .map(e -> e.getSize() != null ? e.getSize() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return topSum.divide(totalSize, 6, RoundingMode.HALF_UP);
    }

    /** 计算 HHI = Σ(s_i²) × 10000，s_i 为各持仓者份额 */
    private int computeHhi(List<PolymarketApiClient.MarketPositionEntry> sorted, BigDecimal totalSize) {
        BigDecimal hhiRaw = BigDecimal.ZERO;
        for (PolymarketApiClient.MarketPositionEntry entry : sorted) {
            BigDecimal size = entry.getSize() != null ? entry.getSize() : BigDecimal.ZERO;
            if (size.compareTo(BigDecimal.ZERO) <= 0) continue;
            BigDecimal share = size.divide(totalSize, 8, RoundingMode.HALF_UP);
            hhiRaw = hhiRaw.add(share.multiply(share));
        }
        return hhiRaw.multiply(new BigDecimal("10000")).intValue();
    }

    private boolean isHighlyConcentrated(BigDecimal cr1, BigDecimal cr3, int hhi,
                                          SmartWalletProperties.SignalRisk risk) {
        if (cr1.compareTo(safe(risk.getMaxTop1ConcentrationPct())) > 0) return true;
        if (cr3.compareTo(safe(risk.getMaxTop3ConcentrationPct())) > 0) return true;
        if (risk.getMaxHhi() > 0 && hhi > risk.getMaxHhi()) return true;
        return false;
    }

    private boolean isExtremelyConcentrated(BigDecimal cr1,
                                            BigDecimal cr3,
                                            BigDecimal cr5,
                                            int hhi,
                                            int observedHolders,
                                            SmartWalletProperties.SignalRisk risk) {
        if (cr1.compareTo(safe(risk.getMaxTop1ExtremeConcentrationPct())) >= 0) return true;
        if (cr3.compareTo(safe(risk.getMaxTop3ExtremeConcentrationPct())) >= 0) return true;
        if (risk.getMaxExtremeHhi() > 0 && hhi >= risk.getMaxExtremeHhi()) return true;
        int holderThreshold = risk.getExtremeConcentrationMaxObservedHolders() == null
                ? 10
                : Math.max(1, risk.getExtremeConcentrationMaxObservedHolders());
        return observedHolders <= holderThreshold
                && cr5.compareTo(safe(risk.getMaxTop5ExtremeConcentrationPct())) >= 0;
    }

    private BigDecimal safe(BigDecimal val) {
        return val != null ? val : BigDecimal.ONE;
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 集中度快照（不可变数据对象）
     *
     * @param conditionId         市场 conditionId
     * @param outcome             分析的方向（YES / NO / ALL）
     * @param observedHolders     观测到的持仓人数
     * @param top1Pct             CR_1：头号持仓者占比
     * @param top3Pct             CR_3：前三持仓者合计占比
     * @param top5Pct             CR_5：前五持仓者合计占比
     * @param hhi                 HHI 指数（0-10000）
     * @param isExtremelyConcentrated 是否触发极端集中阈值
     * @param isHighlyConcentrated 是否触发集中度阈值
     * @param topHolderAddresses  前5持仓者地址（用于鲸鱼身份检测）
     * @param capturedAt          数据采集时间戳（ms）
     */

    private record CachedSnapshot(ConcentrationSnapshot snapshot, long capturedAt) {}
}
