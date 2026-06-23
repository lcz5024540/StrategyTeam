package com.metaverse.coin.strategy.web.service.smartwallet;

import com.metaverse.coin.strategy.web.model.smartwallet.CopyTradingHealthView;
import com.metaverse.coin.strategy.web.model.smartwallet.LeaderDriftAggregationView;
import com.metaverse.coin.strategy.web.model.smartwallet.LeaderRollingAggregationHistoryView;
import com.metaverse.coin.strategy.web.model.smartwallet.LiveReplayDiffHistoryView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Web BFF 端 第十九批 #5: 统一 copy-trading-health 端点.
 *
 * <p>把三个独立审计端点聚合为单一入口, 让运维 / 前端 dashboard 一次 curl 同时拿:
 * <ul>
 *   <li>W2-T2 LeaderRolling 灰度历史 ({@link LeaderRollingAggregationHistoryReadService})</li>
 *   <li>W2-T6 LeaderDrift 实时聚合 ({@link SmartWalletAggregateAuditReadService#leaderDriftAggregation})</li>
 *   <li>W1-T1 LiveReplayDiff 最新一日 ({@link LiveReplayDiffHistoryReadService#latest})</li>
 * </ul>
 *
 * <p><b>降级容错</b>: 任一子源异常 → 字段为 null + errors 中记录原因, 不阻塞另外两源.
 * 这是 BFF 聚合服务标准模式 (前端可单独刷子源详情).</p>
 *
 * <p><b>对开单效率 / 资金使用率 / 是否会开单 / 盈亏点 / 胜率影响</b>: 全部 ⚪ 无 — 仅读 + 聚合 + 不写库, 不参与下单 / 资金决策路径.</p>
 *
 * <p>详见 docs/polymarket/copy-trading-improvement-decision-2026-05-17.md 第九节.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CopyTradingHealthReadService {

    private static final int DEFAULT_HISTORY_DAYS = 14;
    private static final int MAX_HISTORY_DAYS = 90;
    private static final int DEFAULT_DRIFT_LOOKBACK_HOURS = 24;
    private static final String HEALTH_OK = "OK";
    private static final String HEALTH_WARN = "WARN";

    private final LeaderRollingAggregationHistoryReadService leaderRollingHistoryReadService;
    private final SmartWalletAggregateAuditReadService aggregateAuditReadService;
    private final LiveReplayDiffHistoryReadService liveReplayDiffHistoryReadService;

    /**
     * @param historyDays         W2-T2 LeaderRolling 回看天数 (默认 14, 上限 90)
     * @param driftLookbackHours  W2-T6 LeaderDrift 实时聚合回看小时数 (默认 24)
     */
    public CopyTradingHealthView health(Integer historyDays, Integer driftLookbackHours) {
        int hDays = historyDays == null || historyDays <= 0
                ? DEFAULT_HISTORY_DAYS
                : Math.min(historyDays, MAX_HISTORY_DAYS);
        int driftHours = driftLookbackHours == null || driftLookbackHours <= 0
                ? DEFAULT_DRIFT_LOOKBACK_HOURS
                : driftLookbackHours;

        List<String> errors = new ArrayList<>();

        CopyTradingHealthView.LeaderRollingSummary rollingSummary = null;
        try {
            LeaderRollingAggregationHistoryView trend =
                    leaderRollingHistoryReadService.history(hDays, null);
            rollingSummary = summarizeRolling(trend, hDays);
        } catch (RuntimeException ex) {
            log.warn("[CopyTradingHealth] leader-rolling history failed: {}", ex.toString(), ex);
            errors.add("leader-rolling:" + ex.getClass().getSimpleName());
        }

        CopyTradingHealthView.LeaderDriftSummary driftSummary = null;
        try {
            LeaderDriftAggregationView drift =
                    aggregateAuditReadService.leaderDriftAggregation(driftHours);
            driftSummary = summarizeDrift(drift, driftHours);
        } catch (RuntimeException ex) {
            log.warn("[CopyTradingHealth] leader-drift aggregation failed: {}", ex.toString(), ex);
            errors.add("leader-drift:" + ex.getClass().getSimpleName());
        }

        CopyTradingHealthView.LiveReplayDiffSummary diffSummary = null;
        try {
            LiveReplayDiffHistoryView latest = liveReplayDiffHistoryReadService.latest();
            diffSummary = summarizeLiveReplayDiff(latest);
        } catch (RuntimeException ex) {
            log.warn("[CopyTradingHealth] live-replay-diff latest failed: {}", ex.toString(), ex);
            errors.add("live-replay-diff:" + ex.getClass().getSimpleName());
        }

        String overall = computeOverallStatus(rollingSummary, driftSummary, diffSummary);

        return CopyTradingHealthView.builder()
                .generatedAt(new Date())
                .overallStatus(overall)
                .errors(errors.isEmpty() ? null : errors)
                .leaderRolling(rollingSummary)
                .leaderDrift(driftSummary)
                .liveReplayDiff(diffSummary)
                .build();
    }

    // ───────────────────────── summarize helpers ─────────────────────────

    /**
     * 从 LeaderRolling trend 抽取最新一行 + 统计最近 historyDays 内 WARN_* 出现天数.
     * 该字段是「W2-T2 阈值是否需要调整」的运维核心信号.
     */
    private CopyTradingHealthView.LeaderRollingSummary summarizeRolling(
            LeaderRollingAggregationHistoryView trend, int historyDays) {
        if (trend == null) {
            return CopyTradingHealthView.LeaderRollingSummary.builder()
                    .historyDays(historyDays)
                    .build();
        }
        int warnDays = 0;
        if (trend.getRows() != null) {
            for (LeaderRollingAggregationHistoryView.Row r : trend.getRows()) {
                String s = r.getHealthStatus();
                if (s != null && !HEALTH_OK.equalsIgnoreCase(s)) warnDays++;
            }
        }
        // 拿 latest row (按 controller 端排序 asOfDate desc, 第一条即最新, 但同日多 lookbackDays 取首条)
        LeaderRollingAggregationHistoryView.Row latestRow = null;
        if (trend.getRows() != null && !trend.getRows().isEmpty()) {
            latestRow = trend.getRows().get(0);
        }
        return CopyTradingHealthView.LeaderRollingSummary.builder()
                .latestHealthStatus(trend.getLatestHealthStatus())
                .latestAsOfDate(trend.getLatestAsOfDate())
                .recentWarnDays(warnDays)
                .historyDays(historyDays)
                .latestCoolingRatio(latestRow == null ? null : latestRow.getCoolingRatio())
                .latestOverallWinRate(latestRow == null ? null : latestRow.getOverallWinRate())
                .latestEvaluatedCount(latestRow == null ? null : latestRow.getEvaluatedCount())
                .latestActiveCount(latestRow == null ? null : latestRow.getActiveCount())
                .latestCoolingCount(latestRow == null ? null : latestRow.getCoolingCount())
                .build();
    }

    /**
     * LeaderDrift 告警条件与 job 端 LeaderDriftAggregationService 对齐:
     *   (drifted + severe) / evaluated >= 0.15 OR severe >= 3 → WARN.
     */
    private CopyTradingHealthView.LeaderDriftSummary summarizeDrift(
            LeaderDriftAggregationView drift, int driftHours) {
        if (drift == null) {
            return CopyTradingHealthView.LeaderDriftSummary.builder()
                    .lookbackHours(driftHours)
                    .status(HEALTH_OK)
                    .build();
        }
        int evaluated = drift.getEvaluated();
        int driftedTotal = drift.getDriftedCount() + drift.getDriftedSevereCount();
        BigDecimal ratio = evaluated == 0
                ? null
                : new BigDecimal(driftedTotal).divide(new BigDecimal(evaluated), 4, RoundingMode.HALF_UP);
        boolean ratioWarn = ratio != null && ratio.compareTo(new BigDecimal("0.15")) >= 0;
        boolean severeWarn = drift.getDriftedSevereCount() >= 3;
        String status = (ratioWarn || severeWarn) ? HEALTH_WARN : HEALTH_OK;
        return CopyTradingHealthView.LeaderDriftSummary.builder()
                .status(status)
                .lookbackHours(drift.getLookbackHours())
                .activeLeaders(drift.getActiveLeaders())
                .evaluated(evaluated)
                .driftedCount(drift.getDriftedCount())
                .driftedSevereCount(drift.getDriftedSevereCount())
                .coldStartCount(drift.getColdStartCount())
                .driftedRatio(ratio)
                .truncated(drift.isTruncated())
                .build();
    }

    private CopyTradingHealthView.LiveReplayDiffSummary summarizeLiveReplayDiff(
            LiveReplayDiffHistoryView v) {
        if (v == null) {
            // 表无数据 (Job 还没跑) → 视为 OK (灰度初期常见)
            return CopyTradingHealthView.LiveReplayDiffSummary.builder()
                    .status(HEALTH_OK)
                    .build();
        }
        boolean pnlExceeded = Boolean.TRUE.equals(v.getPnlDiffExceeded());
        boolean signalExceeded = Boolean.TRUE.equals(v.getSignalMissExceeded());
        String status = (pnlExceeded || signalExceeded) ? HEALTH_WARN : HEALTH_OK;
        return CopyTradingHealthView.LiveReplayDiffSummary.builder()
                .status(status)
                .asOfDate(v.getAsOfDate())
                .pnlDiffUsdc(v.getPnlDiffUsdc())
                .pnlDiffBps(v.getPnlDiffBps())
                .signalMissPct(v.getSignalMissPct())
                .pnlDiffExceeded(v.getPnlDiffExceeded())
                .signalMissExceeded(v.getSignalMissExceeded())
                .build();
    }

    /**
     * 三个子状态汇总:
     *   - 任一子状态以 WARN 开头 (含 WARN_HIGH_COOLING 等) → overall WARN
     *   - 否则 → OK
     *
     * 注意: 子源为 null (调用异常) 时跳过, 由 errors 字段提示运维; 不污染 overall.
     */
    private String computeOverallStatus(
            CopyTradingHealthView.LeaderRollingSummary rolling,
            CopyTradingHealthView.LeaderDriftSummary drift,
            CopyTradingHealthView.LiveReplayDiffSummary diff) {
        if (rolling != null && rolling.getLatestHealthStatus() != null
                && !HEALTH_OK.equalsIgnoreCase(rolling.getLatestHealthStatus())) {
            return HEALTH_WARN;
        }
        if (drift != null && HEALTH_WARN.equalsIgnoreCase(drift.getStatus())) {
            return HEALTH_WARN;
        }
        if (diff != null && HEALTH_WARN.equalsIgnoreCase(diff.getStatus())) {
            return HEALTH_WARN;
        }
        return HEALTH_OK;
    }
}
