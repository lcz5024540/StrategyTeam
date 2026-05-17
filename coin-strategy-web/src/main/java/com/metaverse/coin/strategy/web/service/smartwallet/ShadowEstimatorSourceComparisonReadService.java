package com.metaverse.coin.strategy.web.service.smartwallet;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletSignalShadowMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletSignalShadowEntity;
import com.metaverse.coin.strategy.web.model.smartwallet.ShadowEstimatorSourceComparisonView;
import com.metaverse.coin.strategy.web.model.smartwallet.ShadowEstimatorSourceComparisonView.SourceBucket;
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

/**
 * Web BFF 端 source 分桶对比 (第十五批 #1).
 *
 * 与 job 端 ShadowVsLiveWeeklyReportService.estimatorSourceBreakdown 算法等价, 让运维
 * 任意 lookback 都能拉到 VWAP vs PROXY 的实证对比, 不必等周一周报.
 *
 * 详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第二十二节.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShadowEstimatorSourceComparisonReadService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final int DEFAULT_LOOKBACK_DAYS = 7;
    private static final int MAX_LOOKBACK_DAYS = 90;

    private final SmartWalletSignalShadowMapper shadowMapper;

    public ShadowEstimatorSourceComparisonView buildComparison(Integer lookbackDays) {
        int days = lookbackDays == null || lookbackDays <= 0
                ? DEFAULT_LOOKBACK_DAYS
                : Math.min(lookbackDays, MAX_LOOKBACK_DAYS);
        Date now = new Date();
        Date since = new Date(now.getTime() - days * 86_400_000L);

        List<SmartWalletSignalShadowEntity> rows = shadowMapper.selectList(
                new LambdaQueryWrapper<SmartWalletSignalShadowEntity>()
                        .eq(SmartWalletSignalShadowEntity::getStatus, "SETTLED")
                        .ge(SmartWalletSignalShadowEntity::getShadowSettledAt, since)
                        .le(SmartWalletSignalShadowEntity::getShadowSettledAt, now));

        Map<String, int[]> countAndWin = new LinkedHashMap<>();   // [count, winCount]
        Map<String, BigDecimal> pnlBySource = new HashMap<>();
        Map<String, long[]> slipSumCnt = new HashMap<>();          // [sum, count]

        for (SmartWalletSignalShadowEntity row : rows) {
            String src = row.getEntryEstimatorSource() == null ? "UNKNOWN" : row.getEntryEstimatorSource();
            int[] cw = countAndWin.computeIfAbsent(src, k -> new int[2]);
            cw[0]++;
            BigDecimal pnl = row.getShadowPnlUsdc() == null ? ZERO : row.getShadowPnlUsdc();
            if (pnl.signum() > 0) cw[1]++;
            pnlBySource.merge(src, pnl, BigDecimal::add);
            if (row.getShadowEntrySlippageBps() != null) {
                long[] sc = slipSumCnt.computeIfAbsent(src, k -> new long[2]);
                sc[0] += Math.abs(row.getShadowEntrySlippageBps());
                sc[1]++;
            }
        }

        List<SourceBucket> buckets = new ArrayList<>();
        for (Map.Entry<String, int[]> e : countAndWin.entrySet()) {
            String src = e.getKey();
            int cnt = e.getValue()[0];
            int wins = e.getValue()[1];
            BigDecimal pnl = pnlBySource.getOrDefault(src, ZERO);
            long[] sc = slipSumCnt.get(src);
            BigDecimal avgBps = sc == null || sc[1] == 0 ? null
                    : BigDecimal.valueOf(sc[0]).divide(BigDecimal.valueOf(sc[1]), 2, RoundingMode.HALF_UP);
            BigDecimal winRate = cnt == 0 ? null
                    : BigDecimal.valueOf(wins).divide(BigDecimal.valueOf(cnt), 4, RoundingMode.HALF_UP);
            buckets.add(SourceBucket.builder()
                    .source(src)
                    .count(cnt)
                    .winCount(wins)
                    .winRate(winRate)
                    .shadowPnlUsdc(pnl.setScale(4, RoundingMode.HALF_UP))
                    .avgEntrySlippageBps(avgBps)
                    .build());
        }
        buckets.sort(Comparator.comparingInt(SourceBucket::getCount).reversed());

        String verdict = computeVerdict(buckets);

        return ShadowEstimatorSourceComparisonView.builder()
                .generatedAt(now)
                .windowStart(since)
                .windowEnd(now)
                .lookbackDays(days)
                .totalSettledShadows(rows.size())
                .buckets(buckets)
                .verdict(verdict)
                .build();
    }

    /** 总结性结论: 若 VWAP 与 PROXY 都有足够样本, 比较哪个的 shadowPnl 更负 (说明拒得更对). */
    private String computeVerdict(List<SourceBucket> buckets) {
        SourceBucket vwap = null, proxy = null;
        for (SourceBucket b : buckets) {
            if (b.getSource() != null && b.getSource().startsWith("VWAP") && (vwap == null || b.getCount() > vwap.getCount())) {
                vwap = b;
            } else if ("PROXY".equals(b.getSource())) {
                proxy = b;
            }
        }
        if (vwap == null || proxy == null || vwap.getCount() < 20 || proxy.getCount() < 20) {
            return null;
        }
        BigDecimal vwapPnl = vwap.getShadowPnlUsdc() == null ? ZERO : vwap.getShadowPnlUsdc();
        BigDecimal proxyPnl = proxy.getShadowPnlUsdc() == null ? ZERO : proxy.getShadowPnlUsdc();
        int cmp = vwapPnl.compareTo(proxyPnl);
        if (cmp < 0) {
            return String.format("VWAP rejects worse signals: VWAP shadowPnl=%s vs PROXY=%s (VWAP 拒得更对)",
                    vwapPnl, proxyPnl);
        } else if (cmp > 0) {
            return String.format("PROXY rejects worse signals: VWAP shadowPnl=%s vs PROXY=%s (PROXY 拒得更对, 考虑调整 VWAP)",
                    vwapPnl, proxyPnl);
        }
        return "VWAP and PROXY equivalent in shadowPnl";
    }
}
