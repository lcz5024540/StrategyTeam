package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletSignalShadowMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletSignalShadowEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.engine.monitor.AlertType;
import com.metaverse.coin.strategy.job.smartwallet.audit.SmartWalletAuditAlertSink;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * W1-T4.4 影子 vs 实盘 周报服务.
 *
 * <p>每周按 reject_layer 聚合 SETTLED 状态的影子 PnL, 与同窗口实盘 realizedPnlUsdc 对比,
 * 帮助识别"哪一层风控拒绝最贵的信号" / "哪一层放行不会更亏".</p>
 *
 * <p>纯只读分析, 不会触发任何下单/平仓动作; 对开单效率、资金使用率、是否开单、盈亏点、胜率
 * <b>均无任何直接影响</b>, 只产出审计数据用于后续门控调参回环 (人工或调度阅读).</p>
 *
 * <p>调度: 默认每周一 06:00 (cron 来自 {@code smart-wallet.audit.shadow-weekly-report-cron}),
 * 由 Spring 的全局 @EnableScheduling 提供. 开关默认开启, 不打算用频繁调用——窗口聚合涉及
 * 全表扫描.</p>
 *
 * <p>详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md (W1-T4).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShadowVsLiveWeeklyReportService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final int MONEY_SCALE = 6;

    private final SmartWalletProperties properties;
    private final SmartWalletSignalShadowMapper shadowMapper;
    private final SmartWalletPositionMapper positionMapper;
    /**
     * W1-T4.5 统一审计告警通道. 仅在 {@link #logReport} 发现 |shadow-live PnL| 超
     * {@code shadowWeeklyReportPnlDiffWarnUsdc} 时触发, 不参与 {@code buildReport} 的纯只读
     * 路径, 因此对 ad-hoc 调用与单元测试零影响.
     */
    private final SmartWalletAuditAlertSink auditAlertSink;

    /**
     * 周报调度入口. 默认每周一 06:00 (cron 由配置驱动), 触发一次 build + log.
     */
    @Scheduled(cron = "${smart-wallet.audit.shadow-weekly-report-cron:0 0 6 * * MON}",
            zone = "${smart-wallet.audit.shadow-weekly-report-zone:Asia/Shanghai}")
    public void scheduledRun() {
        if (!properties.isEnabled()) {
            return;
        }
        SmartWalletProperties.Audit audit = properties.getAudit();
        if (audit == null || !audit.isShadowWeeklyReportEnabled()) {
            return;
        }
        int lookbackDays = audit.getShadowWeeklyReportLookbackDays() == null
                || audit.getShadowWeeklyReportLookbackDays() <= 0
                ? 7 : audit.getShadowWeeklyReportLookbackDays();
        try {
            ShadowVsLiveReport report = buildReport(lookbackDays);
            logReport(report, audit);
        } catch (RuntimeException e) {
            log.error("[ShadowVsLiveWeeklyReport] failed: {}", e.toString(), e);
        }
    }

    /**
     * 显式构建一份报表 (可被运维 / 调试入口主动调用).
     *
     * @param lookbackDays 回看窗口天数
     */
    public ShadowVsLiveReport buildReport(int lookbackDays) {
        Instant generatedAt = Instant.now();
        Date windowEnd = Date.from(generatedAt);
        Date windowStart = Date.from(generatedAt.minus(Duration.ofDays(Math.max(1, lookbackDays))));

        // ── shadow 端: status=SETTLED 在窗口内的记录 ──
        List<SmartWalletSignalShadowEntity> shadows = shadowMapper.selectList(
                new LambdaQueryWrapper<SmartWalletSignalShadowEntity>()
                        .eq(SmartWalletSignalShadowEntity::getStatus, "SETTLED")
                        .ge(SmartWalletSignalShadowEntity::getShadowSettledAt, windowStart)
                        .le(SmartWalletSignalShadowEntity::getShadowSettledAt, windowEnd)
                        .orderByAsc(SmartWalletSignalShadowEntity::getShadowSettledAt));

        Map<String, ShadowLayerStats> layerStats = new LinkedHashMap<>();
        Map<String, ShadowEstimatorSourceStats> sourceStats = new LinkedHashMap<>();
        // 第十四批 #1: 累加 slippage bps (取绝对值, 用于看 VWAP/PROXY 的平均估算偏离)
        Map<String, Long> sourceSlippageSum = new LinkedHashMap<>();
        Map<String, Integer> sourceSlippageCount = new LinkedHashMap<>();
        BigDecimal shadowTotalPnl = ZERO;
        BigDecimal shadowTotalProceeds = ZERO;
        BigDecimal shadowTotalCost = ZERO;
        int shadowWinCount = 0;
        for (SmartWalletSignalShadowEntity row : shadows) {
            BigDecimal pnl = safe(row.getShadowPnlUsdc());
            shadowTotalPnl = shadowTotalPnl.add(pnl);
            shadowTotalProceeds = shadowTotalProceeds.add(safe(row.getShadowExitProceedsUsdc()));
            shadowTotalCost = shadowTotalCost.add(safe(row.getShadowEntryCostUsdc()));
            if (pnl.signum() > 0) shadowWinCount++;

            String layer = row.getRejectLayer() == null ? "UNKNOWN" : row.getRejectLayer();
            ShadowLayerStats stats = layerStats.computeIfAbsent(layer, k -> new ShadowLayerStats().setLayer(k));
            stats.setCount(stats.getCount() + 1)
                    .setShadowPnlUsdc(stats.getShadowPnlUsdc().add(pnl))
                    .setShadowProceedsUsdc(stats.getShadowProceedsUsdc().add(safe(row.getShadowExitProceedsUsdc())))
                    .setShadowCostUsdc(stats.getShadowCostUsdc().add(safe(row.getShadowEntryCostUsdc())));
            if (pnl.signum() > 0) stats.setWinCount(stats.getWinCount() + 1);

            // 第十四批 #1: 按 entry_estimator_source 分桶
            String source = row.getEntryEstimatorSource() == null ? "UNKNOWN" : row.getEntryEstimatorSource();
            ShadowEstimatorSourceStats ss = sourceStats.computeIfAbsent(source,
                    k -> new ShadowEstimatorSourceStats().setSource(k));
            ss.setCount(ss.getCount() + 1)
                    .setShadowPnlUsdc(ss.getShadowPnlUsdc().add(pnl));
            if (pnl.signum() > 0) ss.setWinCount(ss.getWinCount() + 1);
            if (row.getShadowEntrySlippageBps() != null) {
                sourceSlippageSum.merge(source, (long) Math.abs(row.getShadowEntrySlippageBps()), Long::sum);
                sourceSlippageCount.merge(source, 1, Integer::sum);
            }
        }
        // 平均 + 按 |pnl| 降序
        List<ShadowLayerStats> sortedLayers = new ArrayList<>(layerStats.values());
        for (ShadowLayerStats s : sortedLayers) {
            s.setWinRate(s.getCount() == 0 ? null
                    : new BigDecimal(s.getWinCount()).divide(new BigDecimal(s.getCount()), 4, RoundingMode.HALF_UP));
            s.setShadowPnlUsdc(scale(s.getShadowPnlUsdc()));
            s.setShadowProceedsUsdc(scale(s.getShadowProceedsUsdc()));
            s.setShadowCostUsdc(scale(s.getShadowCostUsdc()));
        }
        sortedLayers.sort(Comparator.comparing(
                (ShadowLayerStats s) -> s.getShadowPnlUsdc() == null ? ZERO : s.getShadowPnlUsdc().abs())
                .reversed());

        // 第十四批 #1: source 后处理 (win rate + avg |slippage bps|)
        List<ShadowEstimatorSourceStats> sortedSources = new ArrayList<>(sourceStats.values());
        for (ShadowEstimatorSourceStats s : sortedSources) {
            s.setWinRate(s.getCount() == 0 ? null
                    : new BigDecimal(s.getWinCount()).divide(new BigDecimal(s.getCount()), 4, RoundingMode.HALF_UP));
            s.setShadowPnlUsdc(scale(s.getShadowPnlUsdc()));
            Long sum = sourceSlippageSum.get(s.getSource());
            Integer cnt = sourceSlippageCount.get(s.getSource());
            if (sum != null && cnt != null && cnt > 0) {
                s.setAvgEntrySlippageBps(
                        BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(cnt), 2, RoundingMode.HALF_UP));
            }
        }
        // 按 count 降序: VWAP / PROXY / UNKNOWN 一般依次
        sortedSources.sort(Comparator.comparingInt(ShadowEstimatorSourceStats::getCount).reversed());

        // ── live 端: 在窗口内 closed/settled 的实盘仓位 ──
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(
                new LambdaQueryWrapper<SmartWalletPositionEntity>()
                        .in(SmartWalletPositionEntity::getStatus,
                                List.of("CLOSED", "RESOLVED_UNCLAIMED", "CLAIMED"))
                        .ge(SmartWalletPositionEntity::getClosedAt, windowStart)
                        .le(SmartWalletPositionEntity::getClosedAt, windowEnd));
        BigDecimal liveTotalPnl = ZERO;
        int liveCount = 0;
        int liveWinCount = 0;
        for (SmartWalletPositionEntity p : positions) {
            BigDecimal pnl = safe(p.getRealizedPnlUsdc());
            liveTotalPnl = liveTotalPnl.add(pnl);
            liveCount++;
            if (pnl.signum() > 0) liveWinCount++;
        }

        ShadowVsLiveReport report = new ShadowVsLiveReport()
                .setGeneratedAt(Date.from(generatedAt))
                .setWindowStart(windowStart)
                .setWindowEnd(windowEnd)
                .setShadowSettledCount(shadows.size())
                .setShadowTotalPnlUsdc(scale(shadowTotalPnl))
                .setShadowTotalProceedsUsdc(scale(shadowTotalProceeds))
                .setShadowTotalCostUsdc(scale(shadowTotalCost))
                .setShadowWinCount(shadowWinCount)
                .setShadowWinRate(shadows.isEmpty() ? null
                        : new BigDecimal(shadowWinCount).divide(new BigDecimal(shadows.size()), 4, RoundingMode.HALF_UP))
                .setLiveSettledCount(liveCount)
                .setLiveTotalPnlUsdc(scale(liveTotalPnl))
                .setLiveWinCount(liveWinCount)
                .setLiveWinRate(liveCount == 0 ? null
                        : new BigDecimal(liveWinCount).divide(new BigDecimal(liveCount), 4, RoundingMode.HALF_UP))
                .setShadowMinusLivePnlUsdc(scale(shadowTotalPnl.subtract(liveTotalPnl)))
                .setLayerBreakdown(sortedLayers)
                .setEstimatorSourceBreakdown(sortedSources);
        return report;
    }

    private void logReport(ShadowVsLiveReport report, SmartWalletProperties.Audit audit) {
        BigDecimal diff = safe(report.getShadowMinusLivePnlUsdc()).abs();
        BigDecimal warnThreshold = audit.getShadowWeeklyReportPnlDiffWarnUsdc() == null
                ? new BigDecimal("50") : audit.getShadowWeeklyReportPnlDiffWarnUsdc();
        boolean warn = diff.compareTo(warnThreshold) > 0;
        String banner = warn
                ? "[ShadowVsLiveWeeklyReport][WARN] shadow vs live PnL diff exceeds warn threshold"
                : "[ShadowVsLiveWeeklyReport] weekly summary";
        log.info("{} window=[{} ~ {}] shadowSettled={} shadowPnl={} liveSettled={} livePnl={} shadow-live={} shadowWinRate={} liveWinRate={}",
                banner, report.getWindowStart(), report.getWindowEnd(),
                report.getShadowSettledCount(), report.getShadowTotalPnlUsdc(),
                report.getLiveSettledCount(), report.getLiveTotalPnlUsdc(),
                report.getShadowMinusLivePnlUsdc(),
                report.getShadowWinRate(), report.getLiveWinRate());
        for (ShadowLayerStats layer : report.getLayerBreakdown()) {
            log.info("[ShadowVsLiveWeeklyReport]   layer={} count={} winRate={} shadowPnl={} shadowCost={} shadowProceeds={}",
                    layer.getLayer(), layer.getCount(), layer.getWinRate(),
                    layer.getShadowPnlUsdc(), layer.getShadowCostUsdc(), layer.getShadowProceedsUsdc());
        }
        // 第十四批 #1: VWAP / PROXY 分桶对比, 量化 VWAP estimator 的精度提升
        for (ShadowEstimatorSourceStats src : report.getEstimatorSourceBreakdown()) {
            log.info("[ShadowVsLiveWeeklyReport]   source={} count={} winRate={} shadowPnl={} avg|slipBps|={}",
                    src.getSource(), src.getCount(), src.getWinRate(),
                    src.getShadowPnlUsdc(), src.getAvgEntrySlippageBps());
        }
        // W1-T4.5: 超阈值时收口到统一审计告警 sink. sink 内部已 swallow exception, 这里再防御一层.
        if (warn && auditAlertSink != null) {
            try {
                String title = String.format(
                        "Shadow vs Live PnL diff 超阈值 |%s|>%s window=[%s,%s]",
                        report.getShadowMinusLivePnlUsdc(), warnThreshold,
                        report.getWindowStart(), report.getWindowEnd());
                String message = String.format(
                        "shadowSettled=%d shadowPnl=%s liveSettled=%d livePnl=%s shadowWinRate=%s liveWinRate=%s topLayers=%s",
                        report.getShadowSettledCount(), report.getShadowTotalPnlUsdc(),
                        report.getLiveSettledCount(), report.getLiveTotalPnlUsdc(),
                        report.getShadowWinRate(), report.getLiveWinRate(),
                        topLayersSummary(report));
                auditAlertSink.publish(AlertType.AUDIT_SHADOW_VS_LIVE, title, message, diff, warnThreshold);
            } catch (RuntimeException sinkErr) {
                log.warn("[ShadowVsLiveWeeklyReport] auditAlertSink failed: {}", sinkErr.toString(), sinkErr);
            }
        }
    }

    /** 截取 top 3 layer 摘要, 避免告警 message 过长. */
    private String topLayersSummary(ShadowVsLiveReport report) {
        if (report.getLayerBreakdown() == null || report.getLayerBreakdown().isEmpty()) {
            return "[]";
        }
        int limit = Math.min(3, report.getLayerBreakdown().size());
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < limit; i++) {
            ShadowLayerStats s = report.getLayerBreakdown().get(i);
            if (i > 0) sb.append(", ");
            sb.append(s.getLayer()).append("(count=").append(s.getCount())
                    .append(",pnl=").append(s.getShadowPnlUsdc()).append(")");
        }
        sb.append("]");
        return sb.toString();
    }

    private BigDecimal safe(BigDecimal v) {
        return v == null ? ZERO : v;
    }

    private BigDecimal scale(BigDecimal v) {
        return v == null ? null : v.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    // ── 视图模型 ──────────────────────────────────────────────────────────

    /** 整体周报输出. */
    @Data
    @Accessors(chain = true)
    public static class ShadowVsLiveReport {
        private Date generatedAt;
        private Date windowStart;
        private Date windowEnd;

        /** 窗口内 SETTLED 状态的影子记录数 (W1-T4.3 已回填). */
        private int shadowSettledCount;
        /** 窗口内影子记录的累计 PnL (若放行的影子 PnL). */
        private BigDecimal shadowTotalPnlUsdc;
        private BigDecimal shadowTotalProceedsUsdc;
        private BigDecimal shadowTotalCostUsdc;
        private int shadowWinCount;
        private BigDecimal shadowWinRate;

        /** 窗口内实盘 closed/settled 仓位数. */
        private int liveSettledCount;
        /** 窗口内实盘累计 realized PnL. */
        private BigDecimal liveTotalPnlUsdc;
        private int liveWinCount;
        private BigDecimal liveWinRate;

        /** shadow - live (>0 表示放行更赚; <0 表示门控替我们避坑). */
        private BigDecimal shadowMinusLivePnlUsdc;

        /** 按 reject_layer 分桶的明细. */
        private List<ShadowLayerStats> layerBreakdown = new ArrayList<>();

        /**
         * 第十四批 #1: 按 entry_estimator_source 分桶, 量化 VWAP (第十三批接入)
         * 与既有 PROXY 算法的胜率 / 影子 PnL 差异. UNKNOWN bucket 表示 null source
         * (老数据 / 未启用门控).
         */
        private List<ShadowEstimatorSourceStats> estimatorSourceBreakdown = new ArrayList<>();
    }

    /** 按 reject_layer 维度的影子统计. */
    @Data
    @Accessors(chain = true)
    public static class ShadowLayerStats {
        private String layer;
        private int count;
        private int winCount;
        private BigDecimal winRate;
        private BigDecimal shadowPnlUsdc = ZERO;
        private BigDecimal shadowProceedsUsdc = ZERO;
        private BigDecimal shadowCostUsdc = ZERO;
    }

    /** 第十四批 #1: 按 entry_estimator_source 维度的影子统计. */
    @Data
    @Accessors(chain = true)
    public static class ShadowEstimatorSourceStats {
        /** "VWAP(OK)" / "VWAP(DRY_FALLBACK)" / "PROXY" / "UNKNOWN" */
        private String source;
        private int count;
        private int winCount;
        private BigDecimal winRate;
        private BigDecimal shadowPnlUsdc = ZERO;
        /** 平均 entry slippage bps, 用于看 VWAP / PROXY 估算差异. */
        private BigDecimal avgEntrySlippageBps;
    }
}
