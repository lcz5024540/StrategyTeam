package com.metaverse.coin.strategy.common.service.risk;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Tier B (第十六批) 通用风控门责任链编排器.
 *
 * <p>给定一组 {@link SignalRiskGate}, 按 {@link SignalRiskGate#order()} 升序执行, 聚合每个门的
 * {@link SignalRiskGateDecision} 输出, 产出最终决策包.</p>
 *
 * <p><b>聚合规则</b>:
 * <ul>
 *   <li><b>停止条件</b>: 任一 gate 返回 REJECT 时, 默认<b>继续</b>跑其它 gate (收集所有失败原因),
 *       但 {@link #evaluate} 的整体 {@link Result#isAccepted()} 永远 false;
 *       传入 {@code stopOnFirstReject=true} 可改为 fail-fast.</li>
 *   <li><b>仓位倍率累乘</b>: 所有 DOWNGRADE / ACCEPT 的 {@code stakeMultiplier} 累乘,
 *       任一 REJECT 强制设为 ZERO. 最终仓位倍率 ∈ [0, 1].</li>
 *   <li><b>执行提示</b>: 各 gate 的 executionHint 收集到 list, 由调用方按业务语义解读
 *       (例如跟单 priceImpact 的 MAKER_FIRST / MAKER_ONLY).</li>
 * </ul></p>
 *
 * <p><b>线程安全</b>: 本类无状态, 同一实例可被多线程并发调用.
 * 各 gate 实现自身需保证 reentrant.</p>
 *
 * <p>当前阶段未在 {@code SmartWalletSignalRiskService} 中真正接入 — 跟单的 G1-G11 内联实现保持
 * 不变. 本类为天气 / BTC 策略和未来跟单重构提供"参考骨架".</p>
 *
 * <p>详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第十二节 Tier C.</p>
 *
 * @param <C> 策略上下文类型
 */
public final class SignalRiskPipeline<C> {

    private final List<SignalRiskGate<C>> gates;
    private final boolean stopOnFirstReject;

    public SignalRiskPipeline(List<SignalRiskGate<C>> gates) {
        this(gates, false);
    }

    public SignalRiskPipeline(List<SignalRiskGate<C>> gates, boolean stopOnFirstReject) {
        if (gates == null) {
            throw new IllegalArgumentException("gates required");
        }
        // 复制 + 按 order 升序排序, 隔离外部修改
        List<SignalRiskGate<C>> sorted = new ArrayList<>(gates);
        sorted.sort(Comparator.comparingInt(SignalRiskGate::order));
        this.gates = List.copyOf(sorted);
        this.stopOnFirstReject = stopOnFirstReject;
    }

    /** 执行责任链. */
    public Result evaluate(C context) {
        BigDecimal stakeMultiplier = BigDecimal.ONE;
        boolean accepted = true;
        String firstRejectGate = null;
        List<SignalRiskGateDecision> decisions = new ArrayList<>(gates.size());
        List<String> allReasons = new ArrayList<>();
        List<String> executionHints = new ArrayList<>();

        for (SignalRiskGate<C> gate : gates) {
            SignalRiskGateDecision decision;
            try {
                if (!gate.enabled(context)) {
                    continue;
                }
                decision = gate.evaluate(context);
                if (decision == null) {
                    // 防御性: 实现违反契约时按 ACCEPT 处理
                    decision = SignalRiskGateDecision.accept(gate.name());
                }
            } catch (RuntimeException ex) {
                // 实现违反"不抛异常"契约 — 视为 ACCEPT 让责任链继续
                decision = SignalRiskGateDecision.accept(
                        gate.name(),
                        List.of("exception:" + ex.getClass().getSimpleName()));
            }
            decisions.add(decision);
            if (!decision.reasons().isEmpty()) {
                allReasons.addAll(decision.reasons());
            }
            if (decision.executionHint() != null && !decision.executionHint().isBlank()) {
                executionHints.add(decision.executionHint());
            }
            if (decision.isReject()) {
                accepted = false;
                stakeMultiplier = BigDecimal.ZERO;
                if (firstRejectGate == null) firstRejectGate = gate.name();
                if (stopOnFirstReject) break;
            } else if (decision.isDowngrade()) {
                BigDecimal m = decision.stakeMultiplier();
                if (m != null && m.signum() > 0) {
                    stakeMultiplier = stakeMultiplier.multiply(m);
                }
            }
        }

        return new Result(accepted, firstRejectGate, stakeMultiplier,
                List.copyOf(decisions), List.copyOf(allReasons), List.copyOf(executionHints));
    }

    /** 聚合后的决策包. */
    public static final class Result {
        private final boolean accepted;
        private final String firstRejectGate;
        private final BigDecimal stakeMultiplier;
        private final List<SignalRiskGateDecision> decisions;
        private final List<String> reasons;
        private final List<String> executionHints;

        public Result(boolean accepted, String firstRejectGate, BigDecimal stakeMultiplier,
                      List<SignalRiskGateDecision> decisions, List<String> reasons,
                      List<String> executionHints) {
            this.accepted = accepted;
            this.firstRejectGate = firstRejectGate;
            this.stakeMultiplier = stakeMultiplier == null ? BigDecimal.ONE : stakeMultiplier;
            this.decisions = decisions == null ? List.of() : decisions;
            this.reasons = reasons == null ? List.of() : reasons;
            this.executionHints = executionHints == null ? List.of() : executionHints;
        }

        public boolean isAccepted() { return accepted; }
        public String firstRejectGate() { return firstRejectGate; }
        public BigDecimal stakeMultiplier() { return stakeMultiplier; }
        public List<SignalRiskGateDecision> decisions() { return decisions; }
        public List<String> reasons() { return reasons; }
        public List<String> executionHints() { return executionHints; }
    }
}
