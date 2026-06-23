package com.metaverse.coin.strategy.web.service.smartwallet;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletClaimRetryMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletMarketInfoMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletClaimRetryEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketInfoEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.web.model.smartwallet.SettlementSourceAuditView;
import com.metaverse.coin.strategy.web.model.smartwallet.SlippagePostFactReportView;
import com.metaverse.coin.strategy.web.model.smartwallet.SlippagePostFactReportView.CategoryStats;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Web BFF 端 W1-T2 结算源审计 + W2-T4.5 滑点事后统计的只读聚合.
 *
 * 实现策略: 直查 DB, 与 job 端 SettlementSourceAuditService / SlippagePostFactReportService
 * 算法对齐(仅核心维度). 不调用 job 端 service, 不写库.
 *
 * 详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第十一节.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmartWalletAuditReportReadService {

    private static final long DEFAULT_RESOLUTION_DELAY_THRESHOLD_HOURS = 24L;
    private static final int DEFAULT_SLIPPAGE_LOOKBACK_DAYS = 7;
    private static final int DEFAULT_SLIPPAGE_MAX_ORDERS = 5000;
    private static final BigDecimal DEFAULT_SLIPPAGE_WARN_P95_BPS = new BigDecimal("300");

    private final SmartWalletMarketInfoMapper marketInfoMapper;
    private final SmartWalletPositionMapper positionMapper;
    private final SmartWalletClaimRetryMapper claimRetryMapper;
    private final SmartWalletOrderMapper orderMapper;
    private final SmartWalletTradeEventMapper tradeEventMapper;

    // ── W1-T2 settlement source audit ───────────────────────────

    public SettlementSourceAuditView buildSettlementSourceReport() {
        Date now = new Date();
        long thresholdMs = DEFAULT_RESOLUTION_DELAY_THRESHOLD_HOURS * 3600_000L;
        Date overdueAnchor = new Date(now.getTime() - thresholdMs);

        List<SmartWalletMarketInfoEntity> markets = marketInfoMapper.selectList(null);
        Map<String, Integer> lifecycleCount = new HashMap<>();
        int parseFailure = 0;
        Map<String, Integer> parseFailureByCategory = new HashMap<>();
        int resolutionDelayed = 0;

        for (SmartWalletMarketInfoEntity m : markets) {
            String lifecycle = m.getMarketLifecycle() == null ? "UNKNOWN" : m.getMarketLifecycle();
            lifecycleCount.merge(lifecycle, 1, Integer::sum);
            if ("RESOLVED".equals(lifecycle) && (m.getResolvedOutcome() == null || m.getResolvedOutcome().isBlank())) {
                parseFailure++;
                String cat = m.getNormalizedCategory() == null ? "UNKNOWN" : m.getNormalizedCategory();
                parseFailureByCategory.merge(cat, 1, Integer::sum);
            }
            if ("CLOSED_UNRESOLVED".equals(lifecycle)
                    && m.getLastMetadataSyncAt() != null
                    && m.getLastMetadataSyncAt().before(overdueAnchor)) {
                resolutionDelayed++;
            }
        }

        List<SmartWalletPositionEntity> overduePositions = positionMapper.selectList(
                new LambdaQueryWrapper<SmartWalletPositionEntity>()
                        .in(SmartWalletPositionEntity::getStatus, List.of("PENDING_SETTLEMENT", "RESOLVED_UNCLAIMED"))
                        .le(SmartWalletPositionEntity::getClosedAt, overdueAnchor));
        int pendingOverdue = 0;
        int unclaimedOverdue = 0;
        for (SmartWalletPositionEntity p : overduePositions) {
            if ("PENDING_SETTLEMENT".equals(p.getStatus())) pendingOverdue++;
            else if ("RESOLVED_UNCLAIMED".equals(p.getStatus())) unclaimedOverdue++;
        }

        List<SmartWalletClaimRetryEntity> claims = claimRetryMapper.selectList(null);
        int claimWithFailures = 0;
        int claimFailureMax = 0;
        long claimFailureSum = 0L;
        int claimFailureCounted = 0;
        for (SmartWalletClaimRetryEntity c : claims) {
            int fc = c.getFailureCount() == null ? 0 : c.getFailureCount();
            if (fc > 0) {
                claimWithFailures++;
                claimFailureSum += fc;
                claimFailureCounted++;
                if (fc > claimFailureMax) claimFailureMax = fc;
            }
        }
        double avg = claimFailureCounted == 0 ? 0.0 : ((double) claimFailureSum) / claimFailureCounted;

        boolean anomalous = parseFailure > 0
                || resolutionDelayed > 0
                || pendingOverdue > 0
                || claimFailureMax >= 5;

        return SettlementSourceAuditView.builder()
                .generatedAt(now)
                .resolutionDelayThresholdHours(DEFAULT_RESOLUTION_DELAY_THRESHOLD_HOURS)
                .totalMarkets(markets.size())
                .lifecycleCount(lifecycleCount)
                .parseFailureTotal(parseFailure)
                .parseFailureByCategory(parseFailureByCategory)
                .resolutionDelayedCount(resolutionDelayed)
                .positionPendingSettlementOverdue(pendingOverdue)
                .positionResolvedUnclaimedOverdue(unclaimedOverdue)
                .claimRetryCount(claims.size())
                .claimRetryWithFailures(claimWithFailures)
                .claimFailureMax(claimFailureMax)
                .claimFailureAvg(round2(avg))
                .anomalous(anomalous)
                .build();
    }

    // ── W2-T4.5 slippage post-fact report ─────────────────────────

    public SlippagePostFactReportView buildSlippageReport(Integer lookbackDays, Integer maxOrders) {
        int lookback = lookbackDays != null && lookbackDays > 0 ? lookbackDays : DEFAULT_SLIPPAGE_LOOKBACK_DAYS;
        int cap = maxOrders != null && maxOrders > 0 ? maxOrders : DEFAULT_SLIPPAGE_MAX_ORDERS;
        Date end = new Date();
        Date start = new Date(end.getTime() - lookback * 86_400_000L);

        // ENTRY 类型 + trace_id 非空 + filledAt 落入窗口
        List<SmartWalletOrderEntity> orders = orderMapper.selectList(
                new LambdaQueryWrapper<SmartWalletOrderEntity>()
                        .eq(SmartWalletOrderEntity::getOrderType, "ENTRY")
                        .isNotNull(SmartWalletOrderEntity::getTraceId)
                        .isNotNull(SmartWalletOrderEntity::getPrice)
                        .ge(SmartWalletOrderEntity::getFilledAt, start)
                        .le(SmartWalletOrderEntity::getFilledAt, end)
                        .orderByDesc(SmartWalletOrderEntity::getFilledAt));
        boolean truncated = orders.size() > cap;
        if (truncated) {
            orders = orders.subList(0, cap);
        }

        // 一次性按 traceId 批量反查 trade_event.price + market_info
        List<String> traceIds = orders.stream()
                .map(SmartWalletOrderEntity::getTraceId)
                .filter(Objects::nonNull).distinct()
                .collect(Collectors.toList());
        Map<String, BigDecimal> traceToLeaderPrice = new HashMap<>();
        if (!traceIds.isEmpty()) {
            tradeEventMapper.selectList(new LambdaQueryWrapper<SmartWalletTradeEventEntity>()
                            .in(SmartWalletTradeEventEntity::getTraceId, traceIds))
                    .forEach(e -> {
                        if (e.getTraceId() != null && e.getPrice() != null) {
                            traceToLeaderPrice.put(e.getTraceId(), e.getPrice());
                        }
                    });
        }
        Map<Long, SmartWalletMarketInfoEntity> miCache = new HashMap<>();
        List<Long> miIds = orders.stream()
                .map(SmartWalletOrderEntity::getMarketInfoId)
                .filter(Objects::nonNull).distinct()
                .collect(Collectors.toList());
        if (!miIds.isEmpty()) {
            marketInfoMapper.selectBatchIds(miIds).forEach(mi -> miCache.put(mi.getId(), mi));
        }

        int unmatched = 0;
        int zeroLeader = 0;
        int matched = 0;
        Map<String, List<BigDecimal>> byCategory = new LinkedHashMap<>();
        List<BigDecimal> overallList = new ArrayList<>();

        for (SmartWalletOrderEntity o : orders) {
            BigDecimal leaderPrice = traceToLeaderPrice.get(o.getTraceId());
            if (leaderPrice == null) { unmatched++; continue; }
            if (leaderPrice.signum() == 0) { zeroLeader++; continue; }
            BigDecimal slippageBps = o.getPrice().subtract(leaderPrice)
                    .multiply(new BigDecimal("10000"))
                    .divide(leaderPrice, 4, RoundingMode.HALF_UP);
            matched++;
            String cat = resolveCategory(o, miCache);
            byCategory.computeIfAbsent(cat, k -> new ArrayList<>()).add(slippageBps);
            overallList.add(slippageBps);
        }

        List<CategoryStats> catStats = byCategory.entrySet().stream()
                .map(e -> buildStats(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingInt(CategoryStats::getCount).reversed())
                .collect(Collectors.toList());
        CategoryStats overall = overallList.isEmpty() ? null : buildStats("OVERALL", overallList);

        return SlippagePostFactReportView.builder()
                .generatedAt(end)
                .windowStart(start)
                .windowEnd(end)
                .lookbackDays(lookback)
                .totalOrdersScanned(orders.size())
                .ordersTruncated(truncated)
                .unmatchedTraceCount(unmatched)
                .zeroLeaderPriceCount(zeroLeader)
                .matchedOrders(matched)
                .categoryStats(catStats)
                .overall(overall)
                .warnP95Bps(DEFAULT_SLIPPAGE_WARN_P95_BPS)
                .build();
    }

    private String resolveCategory(SmartWalletOrderEntity o, Map<Long, SmartWalletMarketInfoEntity> miCache) {
        if (o.getMarketInfoId() == null) return "OTHER";
        SmartWalletMarketInfoEntity mi = miCache.get(o.getMarketInfoId());
        if (mi == null) return "OTHER";
        if (mi.getNormalizedCategory() != null && !mi.getNormalizedCategory().isBlank()) return mi.getNormalizedCategory();
        if (mi.getSourceCategory() != null && !mi.getSourceCategory().isBlank()) return mi.getSourceCategory();
        return "OTHER";
    }

    private CategoryStats buildStats(String category, List<BigDecimal> samples) {
        List<BigDecimal> sorted = samples.stream().sorted().collect(Collectors.toList());
        int n = sorted.size();
        BigDecimal sum = sorted.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal mean = sum.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);
        BigDecimal median = sorted.get(n / 2);
        List<BigDecimal> absSorted = samples.stream()
                .map(BigDecimal::abs)
                .sorted()
                .collect(Collectors.toList());
        BigDecimal absP75 = absSorted.get(Math.min(n - 1, (int) Math.floor(n * 0.75)));
        BigDecimal absP95 = absSorted.get(Math.min(n - 1, (int) Math.floor(n * 0.95)));
        boolean exceeded = absP95.compareTo(DEFAULT_SLIPPAGE_WARN_P95_BPS) > 0;
        return CategoryStats.builder()
                .category(category)
                .count(n)
                .meanBps(mean)
                .medianBps(median)
                .absP75Bps(absP75)
                .absP95Bps(absP95)
                .p95Exceeded(exceeded)
                .build();
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
