package com.metaverse.coin.strategy.common.service.risk;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/**
 * Tier B (第十六批) 通用风控门决策 DTO.
 *
 * <p>所有策略（跟单 / 天气 / BTC 5min）的单个 gate 都返回本类型, 由调用方串联成"责任链".
 * 与跟单的 {@code SignalRiskDecision} 不同, 本类型只表达<b>单个门</b>的判定结果, 不是"全局决策";
 * 全局决策由 {@link SignalRiskPipeline} (或各策略自行编排) 通过聚合多个 GateDecision 产出.</p>
 *
 * <p><b>语义</b>:
 * <ul>
 *   <li>{@link Outcome#ACCEPT} — 该门放行, 不修改仓位倍率;</li>
 *   <li>{@link Outcome#DOWNGRADE} — 该门放行但要求降级, 仓位倍率为 {@link #stakeMultiplier};</li>
 *   <li>{@link Outcome#REJECT} — 该门拒绝, 下游 gate 可继续但最终全局决策应拒绝.</li>
 * </ul></p>
 *
 * <p>各策略 / 各 gate 在自己模块内决定 ACCEPT / DOWNGRADE / REJECT 的具体判定逻辑, 本类型只承担数据契约.</p>
 *
 * <p><b>不变量</b>:
 * <ol>
 *   <li>{@code outcome != null};</li>
 *   <li>当 outcome=DOWNGRADE 时, {@code stakeMultiplier ∈ (0, 1]};</li>
 *   <li>{@code gateName} 非空 (用于 trace 日志和告警归因);</li>
 *   <li>{@code reasons} 不可变 (调用 {@link #reasons()} 返回 unmodifiable list).</li>
 * </ol></p>
 *
 * <p>详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第十二节 Tier B/C.</p>
 */
public final class SignalRiskGateDecision {

    public enum Outcome { ACCEPT, DOWNGRADE, REJECT }

    private final String gateName;
    private final Outcome outcome;
    private final BigDecimal stakeMultiplier;
    private final List<String> reasons;
    private final String executionHint;

    private SignalRiskGateDecision(String gateName, Outcome outcome,
                                   BigDecimal stakeMultiplier,
                                   List<String> reasons, String executionHint) {
        if (gateName == null || gateName.isBlank()) {
            throw new IllegalArgumentException("gateName required");
        }
        if (outcome == null) {
            throw new IllegalArgumentException("outcome required");
        }
        this.gateName = gateName;
        this.outcome = outcome;
        this.stakeMultiplier = stakeMultiplier;
        this.reasons = reasons == null ? Collections.emptyList() : List.copyOf(reasons);
        this.executionHint = executionHint;
    }

    public static SignalRiskGateDecision accept(String gateName) {
        return new SignalRiskGateDecision(gateName, Outcome.ACCEPT, BigDecimal.ONE, null, null);
    }

    public static SignalRiskGateDecision accept(String gateName, List<String> reasons) {
        return new SignalRiskGateDecision(gateName, Outcome.ACCEPT, BigDecimal.ONE, reasons, null);
    }

    public static SignalRiskGateDecision downgrade(String gateName,
                                                    BigDecimal multiplier,
                                                    List<String> reasons) {
        if (multiplier == null || multiplier.signum() <= 0 || multiplier.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("downgrade multiplier must be in (0, 1]");
        }
        return new SignalRiskGateDecision(gateName, Outcome.DOWNGRADE, multiplier, reasons, null);
    }

    public static SignalRiskGateDecision downgrade(String gateName,
                                                    BigDecimal multiplier,
                                                    List<String> reasons,
                                                    String executionHint) {
        if (multiplier == null || multiplier.signum() <= 0 || multiplier.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("downgrade multiplier must be in (0, 1]");
        }
        return new SignalRiskGateDecision(gateName, Outcome.DOWNGRADE, multiplier, reasons, executionHint);
    }

    public static SignalRiskGateDecision reject(String gateName, List<String> reasons) {
        return new SignalRiskGateDecision(gateName, Outcome.REJECT, BigDecimal.ZERO, reasons, null);
    }

    public String gateName() { return gateName; }
    public Outcome outcome() { return outcome; }
    public BigDecimal stakeMultiplier() { return stakeMultiplier; }
    public List<String> reasons() { return reasons; }
    public String executionHint() { return executionHint; }

    public boolean isAccept() { return outcome == Outcome.ACCEPT; }
    public boolean isDowngrade() { return outcome == Outcome.DOWNGRADE; }
    public boolean isReject() { return outcome == Outcome.REJECT; }
}
