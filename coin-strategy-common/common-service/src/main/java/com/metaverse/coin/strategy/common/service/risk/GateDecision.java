package com.metaverse.coin.strategy.common.service.risk;

/**
 * 第十七批 deprecation shim.
 *
 * <p>本类是 (第十七批) 临时实现, 实际通用风控门决策 DTO 已由先前批次落地为
 * {@link SignalRiskGateDecision} (字段更完整: gateName / reasons list / executionHint 等).
 * 本类仅作 deprecation pointer, 所有新代码应直接使用
 * {@link SignalRiskGateDecision}.</p>
 *
 * <p>未保留任何 API 入口, 仅作 javadoc 指引; 不在编译期破坏任何调用方
 * (本类无既有引用方).</p>
 *
 * <p>详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第十二节 Tier B/C
 * 与 {@link SignalRiskGateDecision} 的 javadoc.</p>
 *
 * @deprecated 请改用 {@link SignalRiskGateDecision}
 */
@Deprecated
public final class GateDecision {
    private GateDecision() {
        // 不允许实例化
    }
}
