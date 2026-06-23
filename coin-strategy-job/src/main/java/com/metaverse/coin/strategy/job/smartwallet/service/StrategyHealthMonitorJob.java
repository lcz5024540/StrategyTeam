package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFollowTaskMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFollowTaskEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.engine.monitor.AlertType;
import com.metaverse.coin.strategy.job.engine.monitor.StrategyAlertService;
import com.metaverse.coin.strategy.job.smartwallet.audit.SmartWalletAuditAlertSink;
import com.metaverse.coin.strategy.job.smartwallet.model.SignalHealthSummaryView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;

/**
 * 策略健康监控 Job
 *
 * 每小时检查一次过去 1h/4h/24h 内是否有成功开仓（FOLLOWED 任务）；
 * 同时检查可用资金是否触发低水位预警。
 *
 * 告警级别：
 *   - 资金低于预算 20%                → WARN（资金消耗过快，可能很快归零）
 *   - 资金 ≤ 0                        → ERROR（策略已完全阻断）
 *   - 4h 无开仓                       → WARN
 *   - 24h 无开仓                      → ERROR + 完整诊断 dump
 *
 * <p>P1-4：除写本地日志外，WARN/CRITICAL 分支同时通过 {@link SmartWalletAuditAlertSink}
 * 外发（落 strategy_alert 表 + 飞书），避免“零开单”长时间无人感知。外发失败降级为日志，
 * 不阻断健康检查。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StrategyHealthMonitorJob {

    private final SmartWalletProperties properties;
    private final SmartWalletFollowTaskMapper followTaskMapper;
    private final SmartWalletFollowTaskService followTaskService;
    private final SmartWalletAccountingService accountingService;
    private final SmartWalletAuditAlertSink alertSink;
    private final StrategyAlertService strategyAlertService;
    private final MarketSnapshotCollectorService marketSnapshotCollectorService;

    /** 当可用资金低于预算的此比例时触发早期告警 */
    private static final BigDecimal LOW_CAPITAL_WARN_RATE = new BigDecimal("0.20");
    /** R3：盘口快照超过该时长无新数据视为管道停滞（采集每 ~45s 一次，留足容差）。 */
    private static final long SNAPSHOT_STALE_MS = 5L * 60_000L;
    /** R3：结算积压（已 resolved 却卡 OPEN）超过该笔数即告警。 */
    private static final long SETTLEMENT_BACKLOG_THRESHOLD = 5L;

    @Scheduled(fixedDelayString = "${polymarket.smart-wallet.health-monitor-delay-ms:3600000}")
    public void checkPositionHealth() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            checkCapital();
            checkPositionActivity();
            checkSnapshotPipeline();
            checkSettlementBacklog();
        } catch (Exception e) {
            log.error("[HealthMonitor] Unexpected error during health check: {}", e.getMessage(), e);
        }
    }

    // ── 资金健康检查 ─────────────────────────────────────────────────────────

    private void checkCapital() {
        BigDecimal available = accountingService.availableCapitalUsdc();
        BigDecimal budget = safe(properties.getSignalRisk().getMaxTotalOpenCostUsdc());

        if (available.compareTo(BigDecimal.ZERO) <= 0) {
            log.error("[HealthMonitor] CAPITAL DEPLETED: availableCapital={} USDC. " +
                            "All new signals are blocked. Check realizedPnl losses or locked claim proceeds. " +
                            "budget={} USDC.",
                    available.setScale(2, RoundingMode.HALF_UP),
                    budget.setScale(2, RoundingMode.HALF_UP));
            safePublish(AlertType.HEALTH_CAPITAL_DEPLETED, "跟单策略可用资金耗尽",
                    String.format("可用资金耗尽，所有新信号被阻断。available=%s USDC, budget=%s USDC",
                            available.setScale(2, RoundingMode.HALF_UP),
                            budget.setScale(2, RoundingMode.HALF_UP)),
                    available, budget);
            return;
        }

        // 可用资金已 > 0：资金耗尽条件不再满足，自动恢复 DEPLETED 告警
        autoResolve(AlertType.HEALTH_CAPITAL_DEPLETED);

        if (budget.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal utilizationRate = budget.subtract(available).divide(budget, 4, RoundingMode.HALF_UP);
            BigDecimal remainingRate = BigDecimal.ONE.subtract(utilizationRate);
            if (remainingRate.compareTo(LOW_CAPITAL_WARN_RATE) < 0) {
                log.warn("[HealthMonitor] LOW CAPITAL WARNING: availableCapital={} USDC " +
                                "({} % of budget remaining). Strategy may exhaust capital soon. " +
                                "budget={} USDC.",
                        available.setScale(2, RoundingMode.HALF_UP),
                        remainingRate.multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_UP),
                        budget.setScale(2, RoundingMode.HALF_UP));
                safePublish(AlertType.HEALTH_CAPITAL_LOW, "跟单策略可用资金偏低",
                        String.format("可用资金偏低，预算剩余 %s%%。available=%s USDC, budget=%s USDC",
                                remainingRate.multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_UP),
                                available.setScale(2, RoundingMode.HALF_UP),
                                budget.setScale(2, RoundingMode.HALF_UP)),
                        available, budget);
            } else {
                // 资金已回到预算 20% 以上：资金偏低条件不再满足，自动恢复 LOW 告警
                autoResolve(AlertType.HEALTH_CAPITAL_LOW);
            }
        }
    }

    // ── 开仓活跃度检查 ───────────────────────────────────────────────────────

    private void checkPositionActivity() {
        long followed1h  = countFollowed(1);
        long followed4h  = countFollowed(4);
        long followed24h = countFollowed(24);

        if (followed24h == 0) {
            // 24h 无开仓：最高告警，附带完整诊断
            SignalHealthSummaryView summary = followTaskService.getSignalHealthSummary(24);
            log.error("[HealthMonitor] CRITICAL: Zero positions opened in the last 24 hours! " +
                            "Strategy may be structurally blocked. Detailed diagnostics follow.\n" +
                            "  availableCapital   = {}\n" +
                            "  realizedPnl        = {}\n" +
                            "  maxTotalOpenCost   = {}\n" +
                            "  openCost           = {}\n" +
                            "  openPositions      = {}\n" +
                            "  qualifiedWatchlist = {}\n" +
                            "  recentTradeEvents  = {}\n" +
                            "  tasksByStatus      = {}\n" +
                            "  topRejections      = {}\n" +
                            "  consensusGate      = enabled={}, windowSec={}, minEntities={}, activeSignals={}\n" +
                            "  criticalBlock      = {} ({})",
                    summary.getAvailableCapitalUsdc(),
                    summary.getRealizedPnlUsdc(),
                    summary.getMaxTotalOpenCostUsdc(),
                    summary.getOpenCostUsdc(),
                    summary.getOpenPositionCount(),
                    summary.getQualifiedWatchlistCount(),
                    summary.getRecentTradeEventCount(),
                    summary.getTasksByStatus(),
                    summary.getTopRejectionCategories(),
                    summary.isConsensusGateEnabled(), summary.getConsensusWindowSeconds(),
                    summary.getMinDistinctEntities(), summary.getConsensusSignalCount(),
                    summary.isCriticalBlockDetected(), summary.getPrimaryBlockReason());

            safePublish(AlertType.HEALTH_NO_OPEN_24H, "跟单策略 24h 零开仓",
                    String.format("过去 24h 零开仓，策略可能结构性卡死。available=%s, openPositions=%s, "
                                    + "qualifiedWallets=%s, recentEvents=%s, topRejections=%s, primaryBlock=%s",
                            summary.getAvailableCapitalUsdc(),
                            summary.getOpenPositionCount(),
                            summary.getQualifiedWatchlistCount(),
                            summary.getRecentTradeEventCount(),
                            summary.getTopRejectionCategories(),
                            summary.getPrimaryBlockReason()),
                    BigDecimal.ZERO, null);

        } else if (followed4h == 0) {
            // 4h 无开仓：中级告警，摘要即可
            SignalHealthSummaryView summary = followTaskService.getSignalHealthSummary(4);
            log.warn("[HealthMonitor] WARNING: Zero positions opened in the last 4 hours. " +
                            "Possible consensus gate tightness or capital depletion. " +
                            "available={} USDC, qualifiedWallets={}, activeConsensusSignals={}, " +
                            "topRejections={}",
                    summary.getAvailableCapitalUsdc(),
                    summary.getQualifiedWatchlistCount(),
                    summary.getConsensusSignalCount(),
                    summary.getTopRejectionCategories());

            safePublish(AlertType.HEALTH_NO_OPEN_4H, "跟单策略 4h 零开仓",
                    String.format("过去 4h 零开仓。available=%s USDC, qualifiedWallets=%s, "
                                    + "activeConsensusSignals=%s, topRejections=%s",
                            summary.getAvailableCapitalUsdc(),
                            summary.getQualifiedWatchlistCount(),
                            summary.getConsensusSignalCount(),
                            summary.getTopRejectionCategories()),
                    BigDecimal.ZERO, null);
            // 过去 24h 内已有开仓 → 24h 零开仓条件不再满足，自动恢复对应告警
            autoResolve(AlertType.HEALTH_NO_OPEN_24H);

        } else {
            // 过去 4h 内已有开仓，策略恢复活跃 → 自动恢复 4h / 24h 零开仓告警
            autoResolve(AlertType.HEALTH_NO_OPEN_4H);
            autoResolve(AlertType.HEALTH_NO_OPEN_24H);
            log.debug("[HealthMonitor] Strategy is healthy: followed1h={}, followed4h={}, followed24h={}",
                    followed1h, followed4h, followed24h);
        }
    }

    // ── 告警外发 ─────────────────────────────────────────────────────────────

    /**
     * 把健康告警外发到已有的策略审计告警 Sink（默认 {@code FeishuStrategyAuditAlertSink}：
     * 先落 strategy_alert 表 + 日志，再按开关 / 严重级别 / 去重窗口决定是否推送飞书）。
     *
     * <p>外发失败必须降级为日志、绝不能抛断 HealthMonitor 主流程——Sink 实现本身已约定
     * swallow 异常，这里再包一层 try/catch 作为兜底防御。</p>
     */
    // ── R3 盘口快照管道健康（2026-06-03 全盘风险审计）─────────────────────────
    // 快照采集器每 ~45s 跑一次；若 smart_wallet_market_snapshot 长时间无新数据(或为空)，
    // 说明盘口管道停滞，开单/估值会退化为现价——这正是本次会话里静默发生过的故障。
    private void checkSnapshotPipeline() {
        java.util.Date latest = marketSnapshotCollectorService.latestSnapshotAt();
        long ageMs = latest == null ? Long.MAX_VALUE : (System.currentTimeMillis() - latest.getTime());
        if (latest == null || ageMs > SNAPSHOT_STALE_MS) {
            String latestStr = latest == null ? "EMPTY" : String.valueOf(latest);
            log.error("[HealthMonitor] SNAPSHOT PIPELINE STALE: latestSnapshotAt={}, age={}min > {}min. "
                            + "开单/估值将退化为现价，请排查 MarketSnapshotCollector / Polymarket CLOB /book。",
                    latestStr, ageMs == Long.MAX_VALUE ? "∞" : (ageMs / 60_000),
                    SNAPSHOT_STALE_MS / 60_000);
            BigDecimal ageMin = ageMs == Long.MAX_VALUE
                    ? BigDecimal.valueOf(999999)
                    : BigDecimal.valueOf(ageMs / 60_000);
            safePublish(AlertType.HEALTH_SNAPSHOT_STALE, "盘口快照管道停滞/为空",
                    String.format("快照管道停滞，开单/估值退化为现价。latestSnapshotAt=%s, ageMin=%s, 阈值=%dmin",
                            latestStr, ageMs == Long.MAX_VALUE ? "∞" : String.valueOf(ageMs / 60_000),
                            SNAPSHOT_STALE_MS / 60_000),
                    ageMin, BigDecimal.valueOf(SNAPSHOT_STALE_MS / 60_000));
        } else {
            autoResolve(AlertType.HEALTH_SNAPSHOT_STALE);
        }
    }

    // ── R3 结算积压健康 ──────────────────────────────────────────────────────
    private void checkSettlementBacklog() {
        long stuck = accountingService.openInResolvedMarketCount();
        if (stuck > SETTLEMENT_BACKLOG_THRESHOLD) {
            log.warn("[HealthMonitor] SETTLEMENT BACKLOG: {} OPEN positions whose market already resolved (stuck OPEN). "
                    + "请排查结算同步(SmartWalletSettlementSyncService / 本地权威结算)。", stuck);
            safePublish(AlertType.HEALTH_SETTLEMENT_BACKLOG, "结算积压(已结算市场仓位卡 OPEN)",
                    String.format("有 %d 笔 OPEN 持仓所在市场已 resolved 却未入账，疑似结算卡死。", stuck),
                    BigDecimal.valueOf(stuck), BigDecimal.valueOf(SETTLEMENT_BACKLOG_THRESHOLD));
        } else {
            autoResolve(AlertType.HEALTH_SETTLEMENT_BACKLOG);
        }
    }

    private void safePublish(AlertType alertType, String title, String message,
                             BigDecimal metricValue, BigDecimal threshold) {
        try {
            alertSink.publish(alertType, title, message, metricValue, threshold);
        } catch (RuntimeException e) {
            log.warn("[HealthMonitor] alert publish failed (swallowed) type={} err={}",
                    alertType, e.toString());
        }
    }

    /**
     * 当某类健康问题的触发条件已不再满足时，把 strategy_alert 中该类型所有未恢复告警标记为
     * resolved，形成「检测 → 外发 → 条件恢复后自动清除」闭环，避免告警长期挂在未恢复状态
     * （此前全仓无任何把 {@code resolved} 置 1 的逻辑，告警只增不减）。
     *
     * <p>外发 / 落库失败必须 swallow、绝不能抛断 HealthMonitor 主流程。</p>
     */
    private void autoResolve(AlertType alertType) {
        try {
            strategyAlertService.autoResolveByStrategy(SmartWalletAuditAlertSink.STRATEGY_ID, alertType);
        } catch (RuntimeException e) {
            log.warn("[HealthMonitor] alert auto-resolve failed (swallowed) type={} err={}",
                    alertType, e.toString());
        }
    }

    private long countFollowed(int hours) {
        Date since = new Date(System.currentTimeMillis() - (long) hours * 3600_000L);
        return followTaskMapper.selectCount(
                new LambdaQueryWrapper<SmartWalletFollowTaskEntity>()
                        .eq(SmartWalletFollowTaskEntity::getStatus, "FOLLOWED")
                        .ge(SmartWalletFollowTaskEntity::getUpdatedAt, since));
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
