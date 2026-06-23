package com.metaverse.coin.strategy.common.service.risk;

/**
 * Tier B (第十六批) 通用风控门接口.
 *
 * <p>策略中每个独立风险维度（流动性 / 价差 / 滑点 / leader 信用 / 时间窗 / 行为漂移 / …）
 * 实现本接口为一个 {@code SignalRiskGate<C>}, 通过 {@link SignalRiskPipeline} 串联成责任链.</p>
 *
 * <p><b>当前阶段（W6 之前）</b>: 跟单 {@code SmartWalletSignalRiskService} 仍保持单体 G1-G11
 * 内联实现, 本接口仅作为"未来跨策略复用 + 天气/BTC 策略组合"的契约预留. 不强制要求立即重构.</p>
 *
 * <p><b>设计约束</b>:
 * <ol>
 *   <li>实现必须<b>纯函数</b> — 无副作用, 不写库, 不发告警, 不修改 context;</li>
 *   <li>{@link #name()} 必须恒定唯一, 用于 trace 日志归因;</li>
 *   <li>{@link #order()} 越小越先执行, 同 order 内执行顺序不保证;</li>
 *   <li>{@link #enabled(Object)} 应判断本策略 / 配置是否启用本 gate, 关闭时直接返回 ACCEPT;</li>
 *   <li>异常不应抛出 — 内部 catch 后返回 ACCEPT(原因="exception:..."), 让责任链继续.</li>
 * </ol></p>
 *
 * <p><b>泛型参数 C</b>: 各策略自定义的"信号上下文". 跟单可以是 SmartWalletSignalContext 对象,
 * 天气可以是 WeatherSignalContext, 等. 接口本身对上下文无要求, 只把"决策"标准化.</p>
 *
 * <p>详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第十二节 Tier C
 * "SmartWalletSignalRiskService 的 G1-G11 多门控 → 抽 SignalRiskGate 接口 + 责任链".</p>
 *
 * @param <C> 策略上下文类型
 */
public interface SignalRiskGate<C> {

    /** 门控名称 (例如 "G5_LIQUIDITY"), 必须恒定且唯一. */
    String name();

    /**
     * 执行顺序, 越小越先. 同 order 内顺序不保证, 因此 reject-fast 类应当 order 更小.
     * 建议跨度: 0-100 (前置 / 准入), 100-500 (主要风险维度), 500-999 (微调 / 加成).
     */
    default int order() {
        return 100;
    }

    /**
     * 是否启用本 gate. 关闭时 {@link SignalRiskPipeline} 应当直接跳过, 不调 {@link #evaluate}.
     * 默认 {@code true}, 实现可根据 context 中的配置 / feature flag 动态决定.
     */
    default boolean enabled(C context) {
        return true;
    }

    /**
     * 执行评估, 返回该门独立决策. 实现内部必须 try/catch, 不应抛出异常.
     */
    SignalRiskGateDecision evaluate(C context);
}
