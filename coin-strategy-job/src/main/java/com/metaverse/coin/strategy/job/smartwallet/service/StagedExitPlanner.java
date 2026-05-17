package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * F-P1-6 (2026-05-17 第六轮): 分批出场比例计算器。
 *
 * <p><b>目标</b>: 当 take-profit / stop-loss / dynamic-exit 触发时，将"一次性全平"
 * 拆分为多档（默认 [0.30, 0.30, 0.40]）SELL 单，分多个扫描周期逐档执行，降低单次大单
 * 对薄市场盘口的滑点冲击；同时保留紧急回滚开关，可一键退回旧的全平行为。</p>
 *
 * <p><b>状态推断</b>: 不引入新表 / sidecar 存储，完全基于 {@link SmartWalletPositionEntity}
 * 的 {@code originalCostBasisUsdc} / {@code costBasisUsdc} 比值反推"已退出比例"：
 * <pre>
 *   executedFraction = 1 - currentCostBasis / originalCostBasis
 *   legIndex = 第一个使 累计ratio &gt; executedFraction 的档位
 *   effectiveRatioOfRemaining = legRatio / (1 - cumBeforeThisLeg)
 * </pre>
 * 这样多次重启 / 扫描周期之间状态自然一致（仓位状态本身就是事实）。</p>
 *
 * <p><b>与 {@link FollowPositionAdjustmentService} 共享语义</b>:
 * 两者都消费 {@code SmartWalletProperties.ExecutionWallet#partialExitRatios} 配置，
 * 但 FollowPositionAdjustmentService 走 leader-driven exit (按 leader 卖出比例)；
 * 本 planner 走"系统自动 exit"语义（按预设固定档位）。两条路径相互独立、不冲突。</p>
 *
 * <p><b>失败 / 退化语义</b>:
 * <ul>
 *   <li>{@code enableStagedExit=false} → 始终返回 {@link Plan#FULL_EXIT}（旧行为）；</li>
 *   <li>{@code originalCostBasisUsdc} 缺失或 ≤ 0 → 视为新仓 / 历史仓，回退全平；</li>
 *   <li>所有档位都已执行（executedFraction ≥ 1 - minResidualRatio）→ 全平残余；</li>
 *   <li>计算出的 leg notional &lt; stagedExitMinLegNotionalUsdc → 回退全平。</li>
 * </ul>
 * 任何异常都 swallow 并回退全平，绝不阻塞 exit hot path。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StagedExitPlanner {

    private static final String LOG_PREFIX = "[StagedExitPlanner]";

    private final SmartWalletProperties properties;

    /**
     * 计算给定仓位的下一档退出计划。
     *
     * @param position    当前持仓 (必须 populate marketInfo, 且 quantity > 0)
     * @param markPrice   即将下单的参考价（用于估算 leg notional, 校验最小单量）；可为 null（跳过校验）
     * @return 退出计划: FULL_EXIT 表示走旧 executeLiveExit；PARTIAL 时携带 ratio / legIndex / legCount
     */
    public Plan planNext(SmartWalletPositionEntity position, BigDecimal markPrice) {
        if (position == null) {
            return Plan.FULL_EXIT;
        }
        SmartWalletProperties.ExecutionWallet cfg = properties == null ? null : properties.getExecutionWallet();
        if (cfg == null || !cfg.isEnableStagedExit()) {
            return Plan.FULL_EXIT;
        }
        List<BigDecimal> ratios = cfg.getPartialExitRatios();
        if (ratios == null || ratios.isEmpty()) {
            return Plan.FULL_EXIT;
        }

        BigDecimal originalCost = position.getOriginalCostBasisUsdc();
        BigDecimal currentCost = position.getCostBasisUsdc();
        if (originalCost == null || originalCost.compareTo(BigDecimal.ZERO) <= 0
                || currentCost == null || currentCost.compareTo(BigDecimal.ZERO) <= 0) {
            // 没有 original snapshot 或当前 cost 异常，回退到全平避免数据驱动错误
            return Plan.FULL_EXIT;
        }
        if (currentCost.compareTo(originalCost) > 0) {
            // 异常情况：currentCost > originalCost (可能有加仓未刷新 originalCost)，按全平兜底
            return Plan.FULL_EXIT;
        }

        BigDecimal executedFraction = BigDecimal.ONE.subtract(
                currentCost.divide(originalCost, 6, RoundingMode.HALF_UP));
        BigDecimal minResidual = cfg.getStagedExitMinResidualRatio() == null
                ? new BigDecimal("0.05")
                : cfg.getStagedExitMinResidualRatio();

        // 残余太小 → 直接全平
        BigDecimal remainingFraction = BigDecimal.ONE.subtract(executedFraction);
        if (remainingFraction.compareTo(minResidual) <= 0) {
            return Plan.FULL_EXIT;
        }

        // 累计 ratio 找到第一个未完成的档
        BigDecimal cumBefore = BigDecimal.ZERO;
        int legIndex = -1;
        BigDecimal legRatio = null;
        // 容忍小数误差: 已执行档若 executedFraction 已经超过 cumAfter - epsilon, 视为完成
        BigDecimal epsilon = new BigDecimal("0.001");
        for (int i = 0; i < ratios.size(); i++) {
            BigDecimal r = ratios.get(i);
            if (r == null || r.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal cumAfter = cumBefore.add(r);
            if (executedFraction.add(epsilon).compareTo(cumAfter) < 0) {
                legIndex = i;
                legRatio = r;
                break;
            }
            cumBefore = cumAfter;
        }
        if (legIndex < 0 || legRatio == null) {
            // 所有 leg 已完成 (或配置 sum < executedFraction): 余量全平
            return Plan.FULL_EXIT;
        }

        // 是否最后一档 (剩余非零 ratio 中的最后一个)
        boolean lastLeg = isLastNonZeroLeg(ratios, legIndex);

        // 计算"占剩余仓位"的比例
        BigDecimal denominator = BigDecimal.ONE.subtract(cumBefore);
        if (denominator.compareTo(BigDecimal.ZERO) <= 0) {
            return Plan.FULL_EXIT;
        }
        BigDecimal ratioOfRemaining = legRatio.divide(denominator, 6, RoundingMode.HALF_UP);
        // 最后一档 / cumulative 接近 1 时强制全平避免留下零头
        if (lastLeg || ratioOfRemaining.compareTo(BigDecimal.ONE) >= 0) {
            return Plan.FULL_EXIT;
        }

        // 估算 leg notional 检查最小可下单金额
        BigDecimal minLegNotional = cfg.getStagedExitMinLegNotionalUsdc() == null
                ? new BigDecimal("5")
                : cfg.getStagedExitMinLegNotionalUsdc();
        BigDecimal posQty = position.getQuantity();
        BigDecimal legQty = posQty == null ? BigDecimal.ZERO
                : posQty.multiply(ratioOfRemaining).setScale(6, RoundingMode.DOWN);
        BigDecimal legNotional = (markPrice != null && legQty.compareTo(BigDecimal.ZERO) > 0)
                ? legQty.multiply(markPrice).setScale(2, RoundingMode.HALF_UP)
                : null;
        if (legNotional != null && legNotional.compareTo(minLegNotional) < 0) {
            log.debug("{} action=fallback-full-exit reason=LEG_NOTIONAL_TOO_SMALL positionId={} legNotional={} min={}",
                    LOG_PREFIX, position.getId(), legNotional, minLegNotional);
            return Plan.FULL_EXIT;
        }

        log.info("{} action=plan-partial positionId={} legIndex={}/{} legRatio={} ratioOfRemaining={} executedFraction={} legNotional={}",
                LOG_PREFIX, position.getId(), legIndex + 1, ratios.size(),
                legRatio, ratioOfRemaining, executedFraction, legNotional);
        return Plan.partial(legIndex, ratios.size(), ratioOfRemaining, legRatio);
    }

    private boolean isLastNonZeroLeg(List<BigDecimal> ratios, int idx) {
        for (int j = idx + 1; j < ratios.size(); j++) {
            BigDecimal r = ratios.get(j);
            if (r != null && r.compareTo(BigDecimal.ZERO) > 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 退出计划 —— 要么全平（FULL_EXIT singleton），要么按 ratio 走部分退出。
     */
    public static final class Plan {
        /** 表示该次走 executeLiveExit 全平（旧行为，零回归）。 */
        public static final Plan FULL_EXIT = new Plan(true, -1, 0, null, null);

        private final boolean fullExit;
        private final int legIndex;
        private final int legCount;
        private final BigDecimal ratioOfRemaining;
        private final BigDecimal originalLegRatio;

        private Plan(boolean fullExit, int legIndex, int legCount,
                     BigDecimal ratioOfRemaining, BigDecimal originalLegRatio) {
            this.fullExit = fullExit;
            this.legIndex = legIndex;
            this.legCount = legCount;
            this.ratioOfRemaining = ratioOfRemaining;
            this.originalLegRatio = originalLegRatio;
        }

        static Plan partial(int legIndex, int legCount,
                            BigDecimal ratioOfRemaining, BigDecimal originalLegRatio) {
            return new Plan(false, legIndex, legCount, ratioOfRemaining, originalLegRatio);
        }

        public boolean isFullExit() {
            return fullExit;
        }

        /** 当前 leg 索引 (0-based)；FULL_EXIT 时为 -1。 */
        public int getLegIndex() {
            return legIndex;
        }

        /** 配置中的总 leg 数；FULL_EXIT 时为 0。 */
        public int getLegCount() {
            return legCount;
        }

        /** 应作用于"当前剩余仓位"的卖出比例 (0, 1)；FULL_EXIT 时为 null。 */
        public BigDecimal getRatioOfRemaining() {
            return ratioOfRemaining;
        }

        /** 该 leg 在配置中的原始比例 (对应原始 quantity 的占比)；FULL_EXIT 时为 null。 */
        public BigDecimal getOriginalLegRatio() {
            return originalLegRatio;
        }

        @Override
        public String toString() {
            return fullExit ? "Plan{FULL_EXIT}"
                    : "Plan{leg=" + (legIndex + 1) + "/" + legCount
                        + ", ratioOfRemaining=" + ratioOfRemaining
                        + ", originalLegRatio=" + originalLegRatio + "}";
        }
    }
}
