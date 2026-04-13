package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFollowTaskMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFollowTaskEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StrategyHealthMonitorJob {

    private final SmartWalletProperties properties;
    private final SmartWalletFollowTaskMapper followTaskMapper;
    private final SmartWalletFollowTaskService followTaskService;
    private final SmartWalletAccountingService accountingService;

    /** 当可用资金低于预算的此比例时触发早期告警 */
    private static final BigDecimal LOW_CAPITAL_WARN_RATE = new BigDecimal("0.20");

    @Scheduled(fixedDelayString = "${polymarket.smart-wallet.health-monitor-delay-ms:3600000}")
    public void checkPositionHealth() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            checkCapital();
            checkPositionActivity();
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
        } else if (budget.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal utilizationRate = budget.subtract(available).divide(budget, 4, RoundingMode.HALF_UP);
            BigDecimal remainingRate = BigDecimal.ONE.subtract(utilizationRate);
            if (remainingRate.compareTo(LOW_CAPITAL_WARN_RATE) < 0) {
                log.warn("[HealthMonitor] LOW CAPITAL WARNING: availableCapital={} USDC " +
                                "({} % of budget remaining). Strategy may exhaust capital soon. " +
                                "budget={} USDC.",
                        available.setScale(2, RoundingMode.HALF_UP),
                        remainingRate.multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_UP),
                        budget.setScale(2, RoundingMode.HALF_UP));
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
                            "  openPositions      = {}/{}\n" +
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
                    summary.getOpenPositionCount(), summary.getMaxOpenPositions(),
                    summary.getQualifiedWatchlistCount(),
                    summary.getRecentTradeEventCount(),
                    summary.getTasksByStatus(),
                    summary.getTopRejectionCategories(),
                    summary.isConsensusGateEnabled(), summary.getConsensusWindowSeconds(),
                    summary.getMinDistinctEntities(), summary.getConsensusSignalCount(),
                    summary.isCriticalBlockDetected(), summary.getPrimaryBlockReason());

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

        } else {
            log.debug("[HealthMonitor] Strategy is healthy: followed1h={}, followed4h={}, followed24h={}",
                    followed1h, followed4h, followed24h);
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
