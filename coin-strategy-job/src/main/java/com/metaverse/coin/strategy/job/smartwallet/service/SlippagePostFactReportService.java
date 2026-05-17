package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketInfoEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletMarketInfoService;
import com.metaverse.coin.strategy.job.engine.monitor.AlertType;
import com.metaverse.coin.strategy.job.smartwallet.audit.SmartWalletAuditAlertSink;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * W2-T4.5 滑点事后统计报表服务.
 *
 * <p>按市场类别 (politics / crypto / sports / weather / OTHER) 分桶, 统计真实成交价
 * (smart_wallet_order.price) 相对 leader 入场价 (smart_wallet_trade_event.price) 的偏离分布:
 * 计数 / 平均 / 中位数 / P75 / P95 (单位 bps, 取绝对值).</p>
 *
 * <p>用途:
 * <ul>
 *   <li>验证 W2-T4 三段式价格冲击门是否在真实环境下表现得当 (例如 100bps downgrade /
 *       300bps severe downgrade 是否真的对应到「真实滑点更糟」)</li>
 *   <li>识别哪个品类滑点最坏, 是否需要单独调阈值</li>
 *   <li>为后续 maker-first 路由覆盖率评估提供数据</li>
 * </ul></p>
 *
 * <p><b>对开单效率 / 资金使用率 / 是否会开单 / 盈亏点 / 胜率无任何直接影响</b> — 纯只读
 * 聚合 + 内存计算, 不写库不下单不调风控. 间接价值: 暴露真实滑点分布, 为门控阈值调整与
 * MAKER 路由策略调整提供量化依据.</p>
 *
 * <p>详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md (W2-T4.5).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SlippagePostFactReportService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal BPS_SCALE = new BigDecimal("10000");
    private static final int BPS_OUTPUT_SCALE = 0;
    private static final int MEAN_OUTPUT_SCALE = 1;

    /** 仅统计 ENTRY 方向的订单 (开仓), EXIT/CLAIM 单不参与 — 与 leader 入场价对比无意义. */
    private static final String ORDER_TYPE_ENTRY = "ENTRY";

    private final SmartWalletProperties properties;
    private final SmartWalletOrderMapper orderMapper;
    private final SmartWalletTradeEventMapper tradeEventMapper;
    private final SmartWalletMarketInfoService marketInfoService;
    /**
     * W1-T1.5 / W2-T4.5 收口告警通道. 仅在 {@link #runScheduled()} 检测到 P95 超阈值时
     * 触发, {@link #buildReport(int)} 纯只读路径不依赖 sink.
     */
    private final SmartWalletAuditAlertSink auditAlertSink;

    /**
     * 显式构建一份报表 (可被运维 / 调试入口主动调用).
     *
     * @param lookbackDays 回看窗口天数
     */
    public SlippagePostFactReport buildReport(int lookbackDays) {
        Instant generatedAt = Instant.now();
        int safeLookback = Math.max(1, lookbackDays);
        Date windowEnd = Date.from(generatedAt);
        Date windowStart = Date.from(generatedAt.minus(Duration.ofDays(safeLookback)));

        // 1. 拉取窗口内已成交的 ENTRY 订单 (有 trace_id 才能反查 leader 价格)
        SmartWalletProperties.Audit audit = properties.getAudit();
        Integer maxOrders = audit == null ? null : audit.getSlippagePostFactMaxOrders();
        int orderCap = maxOrders == null || maxOrders <= 0 ? 5000 : maxOrders;

        List<SmartWalletOrderEntity> orders = orderMapper.selectList(
                new LambdaQueryWrapper<SmartWalletOrderEntity>()
                        .isNotNull(SmartWalletOrderEntity::getFilledAt)
                        .isNotNull(SmartWalletOrderEntity::getPrice)
                        .isNotNull(SmartWalletOrderEntity::getTraceId)
                        .eq(SmartWalletOrderEntity::getOrderType, ORDER_TYPE_ENTRY)
                        .ge(SmartWalletOrderEntity::getFilledAt, windowStart)
                        .le(SmartWalletOrderEntity::getFilledAt, windowEnd)
                        .orderByAsc(SmartWalletOrderEntity::getFilledAt));

        boolean truncated = false;
        if (orders.size() > orderCap) {
            log.warn("[SlippagePostFactReport] order set truncated, total={} cap={} lookbackDays={}",
                    orders.size(), orderCap, safeLookback);
            orders = orders.subList(0, orderCap);
            truncated = true;
        }

        // 2. 批量填充 marketInfo (用 cache 内存友好)
        marketInfoService.populateOrders(orders);

        // 3. 收集 traceId → leader trade_event 入场价 (按同 trace_id, 同 side 优先取最早一笔)
        Set<String> traceIds = new HashSet<>();
        for (SmartWalletOrderEntity o : orders) {
            if (StringUtils.hasText(o.getTraceId())) {
                traceIds.add(o.getTraceId());
            }
        }

        Map<String, BigDecimal> leaderPriceByTrace = new HashMap<>();
        if (!traceIds.isEmpty()) {
            List<SmartWalletTradeEventEntity> events = tradeEventMapper.selectList(
                    new LambdaQueryWrapper<SmartWalletTradeEventEntity>()
                            .in(SmartWalletTradeEventEntity::getTraceId, traceIds)
                            .isNotNull(SmartWalletTradeEventEntity::getPrice));
            for (SmartWalletTradeEventEntity e : events) {
                if (e.getPrice() == null) {
                    continue;
                }
                // 同一 trace_id 可能有多笔事件 (拆单 / 衍生), 取首次出现的价格作为 leader 入场基准
                leaderPriceByTrace.putIfAbsent(e.getTraceId(), e.getPrice());
            }
        }

        // 4. 分桶聚合 (按品类) + 全局聚合
        Map<String, List<SlippageDatum>> byCategory = new LinkedHashMap<>();
        List<SlippageDatum> overall = new ArrayList<>();
        int unmatchedTrace = 0;
        int zeroLeaderPrice = 0;

        for (SmartWalletOrderEntity o : orders) {
            BigDecimal leaderPrice = leaderPriceByTrace.get(o.getTraceId());
            if (leaderPrice == null) {
                unmatchedTrace++;
                continue;
            }
            if (leaderPrice.signum() == 0) {
                zeroLeaderPrice++;
                continue;
            }
            BigDecimal myPrice = o.getPrice();
            if (myPrice == null || myPrice.signum() == 0) {
                continue;
            }
            BigDecimal slippageBps = myPrice.subtract(leaderPrice)
                    .multiply(BPS_SCALE)
                    .divide(leaderPrice, 2, RoundingMode.HALF_UP);
            String category = resolveCategoryBucket(o.getMarketInfo());
            SlippageDatum datum = new SlippageDatum(slippageBps, slippageBps.abs(), o.getSide());
            overall.add(datum);
            byCategory.computeIfAbsent(category, k -> new ArrayList<>()).add(datum);
        }

        Integer warnP95Cfg = audit == null ? null : audit.getSlippagePostFactWarnP95Bps();
        BigDecimal warnP95 = BigDecimal.valueOf(warnP95Cfg == null || warnP95Cfg <= 0 ? 300 : warnP95Cfg);

        List<SlippageCategoryStats> categoryStats = new ArrayList<>();
        for (Map.Entry<String, List<SlippageDatum>> e : byCategory.entrySet()) {
            SlippageCategoryStats stats = computeStats(e.getKey(), e.getValue());
            stats.setP95Exceeded(stats.getAbsP95Bps() != null
                    && stats.getAbsP95Bps().compareTo(warnP95) > 0);
            categoryStats.add(stats);
        }
        // 按样本数倒序, 便于看主要品类排前
        categoryStats.sort(Comparator.comparingInt(SlippageCategoryStats::getCount).reversed());

        SlippageCategoryStats overallStats = computeStats("ALL", overall);
        overallStats.setP95Exceeded(overallStats.getAbsP95Bps() != null
                && overallStats.getAbsP95Bps().compareTo(warnP95) > 0);

        return new SlippagePostFactReport()
                .setGeneratedAt(Date.from(generatedAt))
                .setWindowStart(windowStart)
                .setWindowEnd(windowEnd)
                .setLookbackDays(safeLookback)
                .setTotalOrdersScanned(orders.size())
                .setOrdersTruncated(truncated)
                .setUnmatchedTraceCount(unmatchedTrace)
                .setZeroLeaderPriceCount(zeroLeaderPrice)
                .setMatchedOrders(overall.size())
                .setCategoryStats(categoryStats)
                .setOverall(overallStats)
                .setWarnP95Bps(warnP95);
    }

    /** Job 调度的实际工作: 跑一次报表, 输出日志, 超阈值时 WARN. */
    public SlippagePostFactReport runScheduled() {
        SmartWalletProperties.Audit audit = properties.getAudit();
        int lookback = audit == null || audit.getSlippagePostFactLookbackDays() == null
                || audit.getSlippagePostFactLookbackDays() <= 0
                ? 7 : audit.getSlippagePostFactLookbackDays();
        SlippagePostFactReport report = buildReport(lookback);
        logReport(report);
        return report;
    }

    private void logReport(SlippagePostFactReport report) {
        boolean anyWarn = report.getOverall().isP95Exceeded()
                || report.getCategoryStats().stream().anyMatch(SlippageCategoryStats::isP95Exceeded);
        String banner = anyWarn
                ? "[SlippagePostFactReport][WARN] p95 abs slippage exceeded threshold"
                : "[SlippagePostFactReport] within threshold";
        log.info("{} windowStart={} windowEnd={} lookbackDays={} totalOrdersScanned={} matched={} unmatchedTrace={} zeroLeaderPrice={} truncated={} warnP95Bps={}",
                banner,
                report.getWindowStart(), report.getWindowEnd(), report.getLookbackDays(),
                report.getTotalOrdersScanned(), report.getMatchedOrders(),
                report.getUnmatchedTraceCount(), report.getZeroLeaderPriceCount(),
                report.isOrdersTruncated(), report.getWarnP95Bps());
        SlippageCategoryStats overall = report.getOverall();
        log.info("[SlippagePostFactReport]   overall count={} meanBps={} medianBps={} absP75Bps={} absP95Bps={} p95Exceeded={}",
                overall.getCount(), overall.getMeanBps(), overall.getMedianBps(),
                overall.getAbsP75Bps(), overall.getAbsP95Bps(), overall.isP95Exceeded());
        for (SlippageCategoryStats stats : report.getCategoryStats()) {
            log.info("[SlippagePostFactReport]   category={} count={} meanBps={} medianBps={} absP75Bps={} absP95Bps={} p95Exceeded={}",
                    stats.getCategory(), stats.getCount(), stats.getMeanBps(),
                    stats.getMedianBps(), stats.getAbsP75Bps(), stats.getAbsP95Bps(),
                    stats.isP95Exceeded());
        }

        // W1-T1.5: 任意 (overall 或 category) P95 超阈值 → 收口到统一审计告警 sink.
        if (anyWarn && auditAlertSink != null) {
            try {
                StringBuilder offenders = new StringBuilder();
                if (overall.isP95Exceeded()) {
                    offenders.append("OVERALL(absP95=").append(overall.getAbsP95Bps()).append(")");
                }
                for (SlippageCategoryStats stats : report.getCategoryStats()) {
                    if (stats.isP95Exceeded()) {
                        if (offenders.length() > 0) offenders.append(",");
                        offenders.append(stats.getCategory())
                                .append("(count=").append(stats.getCount())
                                .append(",absP95=").append(stats.getAbsP95Bps()).append(")");
                    }
                }
                String title = "滑点事后统计 P95 超阈值: " + offenders;
                String message = String.format(
                        "lookbackDays=%d totalOrdersScanned=%d matched=%d unmatchedTrace=%d zeroLeaderPrice=%d truncated=%s warnP95Bps=%s",
                        report.getLookbackDays(), report.getTotalOrdersScanned(),
                        report.getMatchedOrders(), report.getUnmatchedTraceCount(),
                        report.getZeroLeaderPriceCount(), report.isOrdersTruncated(),
                        report.getWarnP95Bps());
                auditAlertSink.publish(AlertType.AUDIT_SLIPPAGE_POSTFACT, title, message,
                        overall.getAbsP95Bps(), report.getWarnP95Bps());
            } catch (RuntimeException sinkErr) {
                log.warn("[SlippagePostFactReport] auditAlertSink failed: {}", sinkErr.toString(), sinkErr);
            }
        }
    }

    private SlippageCategoryStats computeStats(String category, List<SlippageDatum> data) {
        SlippageCategoryStats stats = new SlippageCategoryStats().setCategory(category).setCount(data.size());
        if (data.isEmpty()) {
            return stats;
        }
        // signed mean / median
        List<BigDecimal> signedSorted = data.stream()
                .map(SlippageDatum::getSlippageBps)
                .sorted()
                .toList();
        BigDecimal sum = ZERO;
        for (BigDecimal b : signedSorted) {
            sum = sum.add(b);
        }
        stats.setMeanBps(sum.divide(BigDecimal.valueOf(signedSorted.size()),
                MEAN_OUTPUT_SCALE, RoundingMode.HALF_UP));
        stats.setMedianBps(percentile(signedSorted, 50));

        // absolute distribution (P75 / P95)
        List<BigDecimal> absSorted = data.stream()
                .map(SlippageDatum::getAbsSlippageBps)
                .sorted()
                .toList();
        stats.setAbsP75Bps(percentile(absSorted, 75));
        stats.setAbsP95Bps(percentile(absSorted, 95));
        return stats;
    }

    /**
     * 简单分位: 线性插值; data 已排序. percentile 范围 [0, 100].
     */
    private BigDecimal percentile(List<BigDecimal> sorted, int percentile) {
        if (sorted == null || sorted.isEmpty()) {
            return null;
        }
        if (sorted.size() == 1) {
            return sorted.get(0).setScale(BPS_OUTPUT_SCALE, RoundingMode.HALF_UP);
        }
        double idx = (percentile / 100.0) * (sorted.size() - 1);
        int lo = (int) Math.floor(idx);
        int hi = (int) Math.ceil(idx);
        if (lo == hi) {
            return sorted.get(lo).setScale(BPS_OUTPUT_SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal loVal = sorted.get(lo);
        BigDecimal hiVal = sorted.get(hi);
        BigDecimal frac = BigDecimal.valueOf(idx - lo);
        BigDecimal interp = loVal.add(hiVal.subtract(loVal).multiply(frac));
        return interp.setScale(BPS_OUTPUT_SCALE, RoundingMode.HALF_UP);
    }

    private String resolveCategoryBucket(SmartWalletMarketInfoEntity marketInfo) {
        if (marketInfo == null) {
            return "OTHER";
        }
        String normalized = marketInfo.getNormalizedCategory();
        if (StringUtils.hasText(normalized)) {
            return normalized.trim().toUpperCase(Locale.ROOT);
        }
        String source = marketInfo.getSourceCategory();
        if (StringUtils.hasText(source)) {
            return source.trim().toUpperCase(Locale.ROOT);
        }
        return "OTHER";
    }

    @Data
    @Accessors(chain = true)
    private static class SlippageDatum {
        /** 有符号偏离: (myPrice - leaderPrice) / leaderPrice × 10000. 正数表示我方价更高. */
        private final BigDecimal slippageBps;
        /** |slippageBps|. */
        private final BigDecimal absSlippageBps;
        /** BUY / SELL — 留作后续按方向再分桶 (本轮聚合时仅记录). */
        private final String side;
    }

    /** 单一品类的滑点统计. */
    @Data
    @Accessors(chain = true)
    public static class SlippageCategoryStats {
        private String category;
        private int count;
        private BigDecimal meanBps;
        private BigDecimal medianBps;
        private BigDecimal absP75Bps;
        private BigDecimal absP95Bps;
        private boolean p95Exceeded;
    }

    /** 滑点事后统计报表 (W2-T4.5). */
    @Data
    @Accessors(chain = true)
    public static class SlippagePostFactReport {
        private Date generatedAt;
        private Date windowStart;
        private Date windowEnd;
        private int lookbackDays;
        private int totalOrdersScanned;
        private boolean ordersTruncated;
        /** 有 trace_id 但找不到 trade_event 的订单数 (可能是非跟单链路 / 数据缺失). */
        private int unmatchedTraceCount;
        /** trade_event.price 为 0 的订单数 (异常数据, 跳过). */
        private int zeroLeaderPriceCount;
        /** 实际进入聚合的订单数. */
        private int matchedOrders;
        /** 各品类统计 (按 count 倒序). */
        private List<SlippageCategoryStats> categoryStats = Collections.emptyList();
        /** 全局聚合. */
        private SlippageCategoryStats overall;
        /** 当前生效的 P95 警戒阈值 (bps). */
        private BigDecimal warnP95Bps;
    }
}
