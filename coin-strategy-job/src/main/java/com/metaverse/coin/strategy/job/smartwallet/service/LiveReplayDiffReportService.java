package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.LiveReplayDiffHistoryMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFollowTaskMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.LiveReplayDiffHistoryEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFollowTaskEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.engine.monitor.AlertType;
import com.metaverse.coin.strategy.job.smartwallet.audit.SmartWalletAuditAlertSink;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletReplayStrategyMetrics;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletReplayWindowResult;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

/**
 * W1-T1.2 Live↔Replay Diff 报表服务.
 *
 * <p>为指定 UTC 自然日生成「replay 应有结果 vs live 真实结果」的对账报表:
 * <ul>
 *   <li>信号差: replay 应接受 vs live 实际接受 (follow_task 非 SKIPPED 计数)</li>
 *   <li>成交差: 通过 replay 端 acceptedWithLabels vs live 端非 SKIPPED follow_task 数</li>
 *   <li>PnL 差: replay 端 realizedPnlUsdc vs live 端 position.realizedPnlUsdc 之和</li>
 * </ul>
 *
 * <p><b>对开单效率 / 资金使用率 / 是否会开单 / 盈亏点 / 胜率均无任何直接影响</b> — 纯只读
 * 聚合 + 内存计算, 不写库不下单不调风控. 间接价值: 暴露"replay 与 live 漂移点", 为后续
 * 阈值调参提供量化依据.</p>
 *
 * <p>详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md (W1-T1).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiveReplayDiffReportService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final int MONEY_SCALE = 4;
    private static final ZoneId UTC = ZoneId.of("UTC");

    /**
     * 视为"已尝试下单"的 follow_task 状态. 不区分实际成交与否, 因为 SKIPPED 才意味着风控
     * 拦截 — 我们关心的是"信号被风控放行的比例", 而不是"成交比例".
     */
    private static final List<String> TASK_NON_SKIPPED_STATUSES = List.of(
            "PENDING", "EXECUTING", "EXECUTED", "FAILED", "PARTIAL_FILLED");

    private final SmartWalletProperties properties;
    private final SmartWalletReplayBacktestService replayBacktestService;
    private final SmartWalletFollowTaskMapper followTaskMapper;
    private final SmartWalletPositionMapper positionMapper;
    /** 下一轮 #7: 把每日 diff 落 live_replay_diff_history 表, 供 web BFF 查 trend. */
    private final LiveReplayDiffHistoryMapper historyMapper;
    /**
     * W1-T1.5 统一审计告警通道. 仅在 {@link #runDailyDiff()} 检测到 PnL 或信号缺失超阈值时
     * 调用 {@code publish(AUDIT_LIVE_REPLAY_DIFF, ...)}; 所有 {@code buildReport} 等只读
     * 入口完全不依赖 sink, 不影响测试与 ad-hoc 调用.
     */
    private final SmartWalletAuditAlertSink auditAlertSink;

    /** 显式构建一份 diff 报表 (供 Job 调度入口或运维 / 调试主动调用). */
    public LiveReplayDiffReport buildReport(LocalDate date) {
        LocalDate safeDate = date == null ? LocalDate.now(UTC).minusDays(1) : date;
        Date windowStart = Date.from(safeDate.atStartOfDay(UTC).toInstant());
        Date windowEnd = Date.from(safeDate.plusDays(1).atStartOfDay(UTC).toInstant());

        // 1. replay 端
        SmartWalletReplayWindowResult replay = replayBacktestService.replayLiveWindow(safeDate);
        SmartWalletReplayStrategyMetrics candidate = replay == null
                ? SmartWalletReplayStrategyMetrics.builder().build()
                : replay.getCandidate();
        int replayScanned = safeInt(replay == null ? null : replay.getScannedEvents());
        int replayAccepted = safeInt(candidate.getAcceptedEvents());
        BigDecimal replayPnl = safe(candidate.getRealizedPnlUsdc());

        // 2. live 端 - follow_task 信号侧
        List<SmartWalletFollowTaskEntity> tasks = followTaskMapper.selectList(
                new LambdaQueryWrapper<SmartWalletFollowTaskEntity>()
                        .ge(SmartWalletFollowTaskEntity::getCreatedAt, windowStart)
                        .lt(SmartWalletFollowTaskEntity::getCreatedAt, windowEnd));
        int liveTaskTotal = tasks.size();
        int liveTaskNonSkipped = 0;
        int liveTaskSkipped = 0;
        for (SmartWalletFollowTaskEntity t : tasks) {
            String status = t.getStatus() == null ? "" : t.getStatus().toUpperCase();
            if ("SKIPPED".equals(status)) {
                liveTaskSkipped++;
            } else if (TASK_NON_SKIPPED_STATUSES.contains(status)) {
                liveTaskNonSkipped++;
            }
        }

        // 3. live 端 - position PnL 侧
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(
                new LambdaQueryWrapper<SmartWalletPositionEntity>()
                        .ge(SmartWalletPositionEntity::getOpenedAt, windowStart)
                        .lt(SmartWalletPositionEntity::getOpenedAt, windowEnd));
        BigDecimal livePnl = ZERO;
        int liveClosed = 0;
        for (SmartWalletPositionEntity p : positions) {
            BigDecimal pnl = safe(p.getRealizedPnlUsdc());
            livePnl = livePnl.add(pnl);
            if (p.getClosedAt() != null) {
                liveClosed++;
            }
        }

        // 4. diff
        int signalAcceptedDiff = replayAccepted - liveTaskNonSkipped;
        BigDecimal signalMissPct = replayAccepted == 0 ? ZERO
                : new BigDecimal(Math.abs(signalAcceptedDiff))
                        .divide(new BigDecimal(replayAccepted), 4, RoundingMode.HALF_UP);
        BigDecimal pnlDiffUsdc = replayPnl.subtract(livePnl).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal liveAbsPnl = livePnl.abs();
        BigDecimal pnlDiffBps;
        if (liveAbsPnl.compareTo(BigDecimal.ZERO) == 0) {
            pnlDiffBps = pnlDiffUsdc.compareTo(BigDecimal.ZERO) == 0
                    ? ZERO
                    : new BigDecimal("99999"); // 哨兵: live=0 但 replay 有量, 视为最大差异
        } else {
            pnlDiffBps = pnlDiffUsdc.abs()
                    .multiply(new BigDecimal("10000"))
                    .divide(liveAbsPnl, 0, RoundingMode.HALF_UP);
        }

        SmartWalletProperties.Audit audit = properties.getAudit();
        long pnlDiffWarnBps = audit == null || audit.getLiveReplayPnlDiffBps() == null
                ? 100L : audit.getLiveReplayPnlDiffBps();
        BigDecimal signalMissWarnPct = audit == null || audit.getLiveReplaySignalMissPct() == null
                ? new BigDecimal("0.05") : audit.getLiveReplaySignalMissPct();
        boolean pnlDiffExceeded = pnlDiffBps.compareTo(BigDecimal.valueOf(pnlDiffWarnBps)) > 0;
        boolean signalMissExceeded = signalMissPct.compareTo(signalMissWarnPct) > 0;

        return new LiveReplayDiffReport()
                .setDate(safeDate)
                .setWindowStart(windowStart)
                .setWindowEnd(windowEnd)
                .setReplayScannedEvents(replayScanned)
                .setReplayAcceptedEvents(replayAccepted)
                .setReplayRealizedPnlUsdc(scale(replayPnl))
                .setReplayPolicyTag(candidate.getPolicyTag())
                .setLiveTaskTotal(liveTaskTotal)
                .setLiveTaskNonSkipped(liveTaskNonSkipped)
                .setLiveTaskSkipped(liveTaskSkipped)
                .setLivePositionsOpened(positions.size())
                .setLivePositionsClosed(liveClosed)
                .setLiveRealizedPnlUsdc(scale(livePnl))
                .setSignalAcceptedDiff(signalAcceptedDiff)
                .setSignalMissPct(signalMissPct)
                .setPnlDiffUsdc(pnlDiffUsdc)
                .setPnlDiffBps(pnlDiffBps)
                .setPnlDiffExceeded(pnlDiffExceeded)
                .setSignalMissExceeded(signalMissExceeded);
    }

    /** Job 调度的实际工作: 跑 T-1 日 diff, 输出日志, 超阈值时 WARN. */
    public LiveReplayDiffReport runDailyDiff() {
        LocalDate yesterday = LocalDate.now(UTC).minusDays(1);
        LiveReplayDiffReport report = buildReport(yesterday);
        boolean warn = report.isPnlDiffExceeded() || report.isSignalMissExceeded();
        String banner = warn
                ? "[LiveReplayDiff][WARN] daily diff exceeded threshold"
                : "[LiveReplayDiff] daily diff within threshold";
        log.info("{} date={} replayScanned={} replayAccepted={} liveTaskNonSkipped={} liveTaskSkipped={} signalAcceptedDiff={} signalMissPct={} replayPnl={} livePnl={} pnlDiffUsdc={} pnlDiffBps={}",
                banner, report.getDate(),
                report.getReplayScannedEvents(), report.getReplayAcceptedEvents(),
                report.getLiveTaskNonSkipped(), report.getLiveTaskSkipped(),
                report.getSignalAcceptedDiff(), report.getSignalMissPct(),
                report.getReplayRealizedPnlUsdc(), report.getLiveRealizedPnlUsdc(),
                report.getPnlDiffUsdc(), report.getPnlDiffBps());
        // W1-T1.5: 超阈值时把 WARN 收口到统一审计告警 sink (StrategyAlertService 落库 + 日志).
        // sink 内部已经吞了异常, 这里再裹一层防御以防接口违约破坏 Job 主流程.
        if (warn && auditAlertSink != null) {
            try {
                String title = String.format("Live↔Replay diff 超阈值 date=%s", report.getDate());
                String message = String.format(
                        "signalAcceptedDiff=%d signalMissPct=%s pnlDiffUsdc=%s pnlDiffBps=%s replayPnl=%s livePnl=%s",
                        report.getSignalAcceptedDiff(), report.getSignalMissPct(),
                        report.getPnlDiffUsdc(), report.getPnlDiffBps(),
                        report.getReplayRealizedPnlUsdc(), report.getLiveRealizedPnlUsdc());
                BigDecimal metric = report.getPnlDiffBps();
                BigDecimal threshold = report.getPnlDiffBps() != null && report.isPnlDiffExceeded()
                        ? new BigDecimal(properties.getAudit() != null && properties.getAudit().getLiveReplayPnlDiffBps() != null
                                ? properties.getAudit().getLiveReplayPnlDiffBps() : 100L)
                        : null;
                auditAlertSink.publish(AlertType.AUDIT_LIVE_REPLAY_DIFF, title, message, metric, threshold);
            } catch (RuntimeException sinkErr) {
                log.warn("[LiveReplayDiff] auditAlertSink failed: {}", sinkErr.toString(), sinkErr);
            }
        }
        // 下一轮 #7: 把 report 落 live_replay_diff_history 表 (按 as_of_date 唯一 upsert).
        // 失败仅 WARN, 不阻塞 Job 主流程 (报表已经在日志里有了).
        try {
            persistHistory(report);
        } catch (RuntimeException ex) {
            log.warn("[LiveReplayDiff] history persist failed date={} reason={}",
                    report.getDate(), ex.toString());
        }
        return report;
    }

    /** upsert by as_of_date 写入历史表. */
    private void persistHistory(LiveReplayDiffReport report) {
        if (report == null || report.getDate() == null) return;
        Date now = new Date();
        LiveReplayDiffHistoryEntity existing = historyMapper.selectOne(
                new LambdaQueryWrapper<LiveReplayDiffHistoryEntity>()
                        .eq(LiveReplayDiffHistoryEntity::getAsOfDate, report.getDate())
                        .last("limit 1"));
        LiveReplayDiffHistoryEntity row = existing == null
                ? new LiveReplayDiffHistoryEntity().setCreatedAt(now)
                : existing;
        Integer pnlThresholdBps = properties.getAudit() != null
                && properties.getAudit().getLiveReplayPnlDiffBps() != null
                ? properties.getAudit().getLiveReplayPnlDiffBps().intValue()
                : null;
        BigDecimal signalMissThreshold = properties.getAudit() != null
                ? properties.getAudit().getLiveReplaySignalMissPct()
                : null;
        row.setAsOfDate(report.getDate())
                .setWindowStart(report.getWindowStart())
                .setWindowEnd(report.getWindowEnd())
                .setReplayScannedEvents(report.getReplayScannedEvents())
                .setReplayAcceptedEvents(report.getReplayAcceptedEvents())
                .setReplayRealizedPnlUsdc(report.getReplayRealizedPnlUsdc())
                .setReplayPolicyTag(report.getReplayPolicyTag())
                .setLiveTaskTotal(report.getLiveTaskTotal())
                .setLiveTaskNonSkipped(report.getLiveTaskNonSkipped())
                .setLiveTaskSkipped(report.getLiveTaskSkipped())
                .setLiveRealizedPnlUsdc(report.getLiveRealizedPnlUsdc())
                .setSignalAcceptedDiff(report.getSignalAcceptedDiff())
                .setSignalMissPct(report.getSignalMissPct())
                .setPnlDiffUsdc(report.getPnlDiffUsdc())
                .setPnlDiffBps(report.getPnlDiffBps())
                .setPnlDiffThresholdBps(pnlThresholdBps)
                .setSignalMissThresholdPct(signalMissThreshold)
                .setPnlDiffExceeded(report.isPnlDiffExceeded())
                .setSignalMissExceeded(report.isSignalMissExceeded())
                .setUpdatedAt(now);
        if (existing == null) {
            historyMapper.insert(row);
        } else {
            historyMapper.updateById(row);
        }
    }

    private BigDecimal safe(BigDecimal v) {
        return v == null ? ZERO : v;
    }

    private BigDecimal scale(BigDecimal v) {
        return v == null ? null : v.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private int safeInt(Integer v) {
        return v == null ? 0 : v;
    }

    /** Live↔Replay 单日 diff 报表. */
    @Data
    @Accessors(chain = true)
    public static class LiveReplayDiffReport {
        private LocalDate date;
        private Date windowStart;
        private Date windowEnd;

        // ── replay 侧 ──
        private int replayScannedEvents;
        private int replayAcceptedEvents;
        private BigDecimal replayRealizedPnlUsdc;
        private String replayPolicyTag;

        // ── live 侧 ──
        private int liveTaskTotal;
        private int liveTaskNonSkipped;
        private int liveTaskSkipped;
        private int livePositionsOpened;
        private int livePositionsClosed;
        private BigDecimal liveRealizedPnlUsdc;

        // ── diff ──
        /** replayAccepted - liveNonSkipped (>0 表示 replay 比 live 多出信号即门控漂移). */
        private int signalAcceptedDiff;
        /** |signalAcceptedDiff| / replayAccepted, 用于与 audit.liveReplaySignalMissPct 对比. */
        private BigDecimal signalMissPct;
        private BigDecimal pnlDiffUsdc;
        /** |pnlDiffUsdc| / |livePnl| × 10000 (bps), live=0 时哨兵 99999. */
        private BigDecimal pnlDiffBps;
        private boolean pnlDiffExceeded;
        private boolean signalMissExceeded;
    }
}
