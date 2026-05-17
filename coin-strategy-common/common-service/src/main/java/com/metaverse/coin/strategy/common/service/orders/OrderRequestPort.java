package com.metaverse.coin.strategy.common.service.orders;

/**
 * Tier B (第二十批 · 2026-05-16): 通用「订单请求工厂」端口接口.
 *
 * <p>跟单 {@code PolymarketOrderRequestFactory.prepare(signal, wallet) -> PreparedPolymarketOrder}
 * 是这个接口的具体生产实现; 抽出来让天气 / BTC 5min 等未来策略「第一天就能 implements」
 * 不必先做大重构.</p>
 *
 * <p><b>设计原则</b>:
 * <ul>
 *   <li>纯函数 — 实现不应触发 DB 写入 / RPC; 副作用 (例: 真正下单) 由 ExecutionGateway 承担;</li>
 *   <li>抛异常允许 — 调用方需 catch 并降级为 SKIP / REJECT, 不应蔓延到 hot path;</li>
 *   <li>泛型三参:
 *     <ul>
 *       <li><b>S</b> = 输入信号 (跟单=SmartWalletSignal, 天气=WeatherEnterSignal, ...)</li>
 *       <li><b>W</b> = 执行钱包上下文 (跟单=ExecutionWalletContext, 天气=同一类型可复用)</li>
 *       <li><b>P</b> = 输出订单草稿 (跟单=PreparedPolymarketOrder, 天气=PreparedWeatherOrder, ...)</li>
 *     </ul>
 *   </li>
 *   <li>仅声明 <b>开仓 (entry)</b> 路径; 平仓 (exit) 由独立的 {@link ExitOrderRequestPort}
 *     承担 (因为 exit 的入参是「现有仓位 + 触发价」而非「信号」, 接口签名截然不同)。</li>
 * </ul></p>
 *
 * <p><b>为何先抽接口不重构跟单生产代码</b>: 与第十六批 {@code SignalRiskGate} 接口预留同节奏 —
 * 跟单 {@code PolymarketOrderRequestFactory} 已 600+ 行, 注入它的下游 ≥ 10 处, 切换接口需要逐方法回归测试,
 * 属 W6 严格保留事项, 不在本批越界。接口先备好后, 天气策略可第一天起 implements OrderRequestPort,
 * 跟单未来 W6 真正实施时不会出现「跟单和天气各自再发明一遍 prepare 概念」的双重实现。</p>
 *
 * <p><b>对开单效率 / 资金使用率 / 是否会开单 / 盈亏点 / 胜率影响</b>: ⚪ 无 — 仅声明类型,
 * 跟单生产代码完全不引用; 即使整个接口删除, 跟单系统行为完全等价。</p>
 *
 * <p>详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第二十节 (Tier B).</p>
 *
 * @param <S> 输入信号类型
 * @param <W> 执行钱包上下文类型
 * @param <P> 输出订单草稿类型
 */
public interface OrderRequestPort<S, W, P> {

    /**
     * 从信号 + 钱包上下文构造一个 <b>开仓</b> 订单草稿. 实现应:
     * <ul>
     *   <li>校验信号合法性 (marketId / outcome / stake 等), 缺失时抛 {@link IllegalStateException};</li>
     *   <li>校验钱包是否允许该 outcome (例: YES/NO 黑名单), 失败时抛 {@link IllegalStateException};</li>
     *   <li>从 metadata / 行情服务取实时 token / tick / negRisk 等执行参数, 取数失败应抛
     *     {@link RuntimeException} (调用方负责降级);</li>
     *   <li>计算 limitPrice / size / stakeUsdc 等 6 维订单字段填入返回的 P;</li>
     *   <li><b>不</b>触发 LIVE 下单 — 仅 build 草稿, LIVE 路径由 ExecutionGateway 决定;</li>
     *   <li>记录足够的 log (含 signalId / marketId / outcome) 便于 trace_id 反查。</li>
     * </ul>
     *
     * @param signal 入场信号
     * @param wallet 执行钱包上下文
     * @return 订单草稿 (不允许 null)
     * @throws IllegalStateException 信号 / 钱包 / metadata 任何一项不满足执行条件
     * @throws RuntimeException      上游 metadata / snapshot 服务异常
     */
    P prepare(S signal, W wallet);
}
