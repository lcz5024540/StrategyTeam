package com.metaverse.coin.strategy.job.engine.risk;

import com.metaverse.coin.strategy.job.engine.StrategyRegistry;
import com.metaverse.coin.strategy.job.engine.config.StrategyEngineProperties;
import com.metaverse.coin.strategy.job.engine.model.StrategyHealthSnapshot;
import com.metaverse.coin.strategy.job.engine.model.TradingSignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 跨策略风控服务。
 *
 * <p>在策略下单前进行全局层面的风控检查：</p>
 * <ul>
 *   <li>全局总暴露是否超限</li>
 *   <li>每日总亏损是否超限</li>
 *   <li>同市场跨策略冲突检测（告警，不阻断）</li>
 * </ul>
 *
 * <p><b>Phase 0</b>：框架就位，核心逻辑在 Phase 1（UnifiedPositionManager 适配后）完善。<br>
 * 当前实现提供基于 StrategyHealthSnapshot 的轻量级检查。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CrossStrategyRiskService {

    private static final String LOG_PREFIX = "[CROSS_RISK]";

    private final StrategyEngineProperties engineProperties;
    private final StrategyRegistry strategyRegistry;

    /**
     * 检查是否允许新的信号下单。
     * 目前基于所有策略的 healthSnapshot 汇总总暴露。
     *
     * @param signal 待执行的信号
     * @return true = 允许下单，false = 被风控拒绝
     */
    public boolean allowNewOrder(TradingSignal signal) {
        if (!engineProperties.getGlobalRisk().isEnableCrossStrategyExposureCheck()) {
            return true;
        }

        try {
            BigDecimal maxExposure = engineProperties.getGlobalRisk().getMaxTotalExposureUsdc();

            // 汇总所有策略的当前总暴露
            List<StrategyHealthSnapshot> snapshots = strategyRegistry.healthCheckAll();
            BigDecimal totalExposure = snapshots.stream()
                    .filter(s -> s.getTotalExposureUsdc() != null)
                    .map(StrategyHealthSnapshot::getTotalExposureUsdc)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal afterExposure = totalExposure.add(
                    signal.getStakeUsdc() != null ? signal.getStakeUsdc() : BigDecimal.ZERO);

            if (afterExposure.compareTo(maxExposure) > 0) {
                log.warn("{} 全局暴露超限，拒绝下单: strategy={} market={} stake={} currentTotal={} afterTotal={} limit={}",
                        LOG_PREFIX, signal.getStrategyId(), signal.getMarketId(),
                        signal.getStakeUsdc(), totalExposure, afterExposure, maxExposure);
                return false;
            }

            return true;
        } catch (Exception e) {
            log.error("{} 跨策略风控检查异常，放行: {}", LOG_PREFIX, e.getMessage(), e);
            return true; // fail-open，不因风控异常阻断策略运行
        }
    }

    /**
     * 检查每日总亏损是否超限。
     *
     * @return true = 未超限，false = 已超限
     */
    public boolean isDailyLossWithinLimit() {
        if (!engineProperties.getGlobalRisk().isEnableCrossStrategyExposureCheck()) {
            return true;
        }

        try {
            BigDecimal maxDailyLoss = engineProperties.getGlobalRisk().getMaxTotalDailyLossUsdc();

            List<StrategyHealthSnapshot> snapshots = strategyRegistry.healthCheckAll();
            BigDecimal totalDailyPnl = snapshots.stream()
                    .filter(s -> s.getTodayRealizedPnlUsdc() != null)
                    .map(StrategyHealthSnapshot::getTodayRealizedPnlUsdc)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // 如果总日 PNL 为负且绝对值超过限制
            if (totalDailyPnl.negate().compareTo(maxDailyLoss) > 0) {
                log.warn("{} 每日总亏损超限: todayPnl={} limit={}", LOG_PREFIX, totalDailyPnl, maxDailyLoss);
                return false;
            }

            return true;
        } catch (Exception e) {
            log.error("{} 每日亏损检查异常，放行: {}", LOG_PREFIX, e.getMessage(), e);
            return true;
        }
    }
}
