package com.metaverse.coin.strategy.common.service.orders;

import java.math.BigDecimal;

/**
 * Tier B (第二十批 · 2026-05-16): 通用「平仓订单请求工厂」端口接口.
 *
 * <p>与 {@link OrderRequestPort} 是姊妹接口, 但 exit 的入参是「现有仓位 + 触发价」而非「信号」,
 * 签名截然不同, 因此独立成接口而非给 OrderRequestPort 加重载。</p>
 *
 * <p>跟单 {@code PolymarketOrderRequestFactory.prepareExit(position, requestedPrice, wallet)
 * -> PreparedPolymarketOrder} 是这个接口的具体生产实现。</p>
 *
 * <p><b>对开单效率 / 资金使用率 / 是否会开单 / 盈亏点 / 胜率影响</b>: ⚪ 无 — 仅声明类型,
 * 跟单生产代码完全不引用。</p>
 *
 * <p>详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第二十节 (Tier B).</p>
 *
 * @param <Position> 仓位实体类型 (跟单=SmartWalletPositionEntity, 天气=WeatherPositionEntity, ...)
 * @param <W>        执行钱包上下文类型
 * @param <P>        输出订单草稿类型
 */
public interface ExitOrderRequestPort<Position, W, P> {

    /**
     * 从现有仓位 + 期望成交价构造一个 <b>平仓</b> 订单草稿. 实现应:
     * <ul>
     *   <li>校验仓位字段完备 (marketId / outcome / quantity > 0), 缺失时抛 {@link IllegalStateException};</li>
     *   <li>从 metadata 服务取 token / tick 等执行参数;</li>
     *   <li>把 quantity 投影为可成交 size (考虑 tick / 最小成交单元);</li>
     *   <li>不触发 LIVE 下单 — 仅 build 草稿;</li>
     *   <li>记录 trace_id 友好的 log。</li>
     * </ul>
     *
     * @param position       现有仓位
     * @param requestedPrice 期望成交价 (null 时使用市场快照 mid)
     * @param wallet         执行钱包上下文
     * @return 订单草稿 (不允许 null)
     * @throws IllegalStateException 仓位字段不全 / 钱包不允许该 outcome
     * @throws RuntimeException      上游 metadata 服务异常
     */
    P prepareExit(Position position, BigDecimal requestedPrice, W wallet);
}
