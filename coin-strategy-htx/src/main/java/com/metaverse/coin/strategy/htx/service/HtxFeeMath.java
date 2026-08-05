package com.metaverse.coin.strategy.htx.service;

import java.math.BigDecimal;

/**
 * 手续费/可卖量计算（纯函数）。
 * <p>HTX 买单手续费扣在 base 币（买 BTC → 扣 BTC），因此下卖单前必须从成交 base 中扣掉手续费，
 * 否则按请求量挂卖会超出账户余额（超卖）。若开启 HT/点卡抵扣，filledFees 可能非 base 计价，
 * 本期按订单级聚合做保守扣减（见 spec §2 手续费粒度决策），精度截断由 HTX 侧兜底。
 */
public final class HtxFeeMath {
    private HtxFeeMath() {}

    public static BigDecimal sellableBase(BigDecimal filledBase, BigDecimal baseFee) {
        BigDecimal filled = filledBase == null ? BigDecimal.ZERO : filledBase;
        BigDecimal fee = baseFee == null ? BigDecimal.ZERO : baseFee;
        BigDecimal net = filled.subtract(fee);
        return net.signum() < 0 ? BigDecimal.ZERO : net;
    }
}
