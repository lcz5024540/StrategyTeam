package com.metaverse.coin.strategy.htx.strategy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 做市纯定价与闸门逻辑（无 IO，可完全单测）。见 spec §2.2 不亏证明。
 * 不亏不变量：{@code (1−f)²·sellPrice ≥ buyPrice}，与成交量无关。
 */
public final class MarketMakerMath {

    private MarketMakerMath() {}

    /** btcusdt/ethusdt quote 报价精度。 */
    public static final int PRICE_SCALE = 2;
    /** 下单数量精度（保守取 btc(6)/eth(4) 交集）。 */
    public static final int QTY_SCALE = 4;

    private static final BigDecimal ONE = BigDecimal.ONE;

    /** buyPrice = P0·(1−δ)，含入到 PRICE_SCALE（HALF_UP）。 */
    public static BigDecimal buyPrice(BigDecimal p0, BigDecimal spreadPct) {
        return p0.multiply(ONE.subtract(spreadPct)).setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    }

    /** sellPrice = P0·(1+δ)，含入到 PRICE_SCALE（HALF_UP）。 */
    public static BigDecimal sellPrice(BigDecimal p0, BigDecimal spreadPct) {
        return p0.multiply(ONE.add(spreadPct)).setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    }

    /** qty = notional / buyPrice，含入到 QTY_SCALE（HALF_DOWN，宁小勿大不超名义）。 */
    public static BigDecimal qtyFor(BigDecimal notional, BigDecimal buyPrice) {
        return notional.divide(buyPrice, QTY_SCALE, RoundingMode.HALF_DOWN);
    }

    /** 不亏闸门：(1−f)²·sellPrice ≥ buyPrice。 */
    public static boolean isNoLoss(BigDecimal buyPrice, BigDecimal sellPrice, BigDecimal feeRatePerSide) {
        BigDecimal oneMinusF = ONE.subtract(feeRatePerSide);
        BigDecimal lhs = oneMinusF.multiply(oneMinusF).multiply(sellPrice);
        return lhs.compareTo(buyPrice) >= 0;
    }

    /**
     * min-gap 去重：候选买价与每个已挂买价的相对距离 |cand−e|/e 都 ≥ minGapPct 才算够远。
     * existingBuys 为空 → 直接够远。
     */
    public static boolean farEnough(BigDecimal candidateBuy, List<BigDecimal> existingBuys,
                                    BigDecimal minGapPct) {
        for (BigDecimal e : existingBuys) {
            BigDecimal rel = candidateBuy.subtract(e).abs()
                    .divide(e, 10, RoundingMode.HALF_UP);
            if (rel.compareTo(minGapPct) < 0) {
                return false;
            }
        }
        return true;
    }
}
