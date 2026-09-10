package com.metaverse.coin.strategy.htx.strategy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 做市纯定价与闸门逻辑（无 IO，可完全单测）。见 spec §2.2 不亏证明。
 * 不亏不变量：{@code (1−f)²·sellPrice ≥ buyPrice}，与成交量无关。
 */
public final class MarketMakerMath {

    private MarketMakerMath() {}

    /** 当前现货白名单均使用 2 位 USDT 报价精度。 */
    public static final int PRICE_SCALE = 2;
    /** 下单数量精度（保守取 btc(6)/eth(4) 交集）。 */
    public static final int QTY_SCALE = 4;

    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    /** buyPrice = P0·(1−δ)，含入到 PRICE_SCALE（HALF_UP）。 */
    public static BigDecimal buyPrice(BigDecimal p0, BigDecimal spreadPct) {
        return p0.multiply(ONE.subtract(spreadPct)).setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 等比买价梯：L_k = anchor·(1−stepPct)^k，k=0..levels−1，每档含入到 scale（HALF_UP，与 buyPrice 同构）。
     * 截断掉 &lt; floorPrice 的档位（绝对价地板），返回自高到低的买价列表；首档锚定 bestBid，
     * 后续档位逐级向下。floorPrice 传 null 或 ≤0 视为不设地板。
     */
    public static List<BigDecimal> gridBuyLevels(BigDecimal anchor, BigDecimal stepPct,
                                                 int levels, BigDecimal floorPrice, int scale) {
        List<BigDecimal> result = new ArrayList<>();
        boolean hasFloor = floorPrice != null && floorPrice.signum() > 0;
        BigDecimal factor = ONE.subtract(stepPct);
        BigDecimal power = ONE;
        for (int k = 0; k < levels; k++) {
            BigDecimal level = anchor.multiply(power).setScale(scale, RoundingMode.HALF_UP);
            if (hasFloor && level.compareTo(floorPrice) < 0) {
                break;
            }
            result.add(level);
            power = power.multiply(factor);
        }
        return result;
    }

    /** sellPrice = P0·(1+δ)，含入到 PRICE_SCALE（HALF_UP）。 */
    public static BigDecimal sellPrice(BigDecimal p0, BigDecimal spreadPct) {
        return p0.multiply(ONE.add(spreadPct)).setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 从买价推卖价：sellPrice = buyPrice·(1 + 2·δ)，含入到 PRICE_SCALE（HALF_UP）。
     * 买腿贴 bestBid 后，卖腿相对买价上抬 2δ 以维持总利差并满足不亏损。
     */
    public static BigDecimal sellFromBuy(BigDecimal buyPrice, BigDecimal spreadPct) {
        return buyPrice.multiply(ONE.add(TWO.multiply(spreadPct)))
                .setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    }

    /** HTX 实际成交均价 = filled-cash-amount / filled-amount；字段缺失时回退原买单限价。 */
    public static BigDecimal actualBuyPrice(BigDecimal filledCashAmount, BigDecimal filledAmount,
                                            BigDecimal fallbackBuyPrice) {
        if (filledCashAmount == null || filledAmount == null
                || filledCashAmount.signum() <= 0 || filledAmount.signum() <= 0) {
            return fallbackBuyPrice.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
        }
        return filledCashAmount.divide(filledAmount, PRICE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 跟价重挂闸门：买价相对最优买价漂移 |bestBid − currentBuy| / bestBid ≥ thresholdPct 即需重挂。
     */
    public static boolean shouldReprice(BigDecimal bestBid, BigDecimal currentBuy, BigDecimal thresholdPct) {
        BigDecimal rel = bestBid.subtract(currentBuy).abs()
                .divide(bestBid, 10, RoundingMode.HALF_UP);
        return rel.compareTo(thresholdPct) >= 0;
    }

    /** qty = notional / buyPrice，含入到 QTY_SCALE（HALF_DOWN，宁小勿大不超名义）。 */
    public static BigDecimal qtyFor(BigDecimal notional, BigDecimal buyPrice) {
        return notional.divide(buyPrice, QTY_SCALE, RoundingMode.HALF_DOWN);
    }

    /** 做市白名单标的的 HTX 现货数量精度；卖出时按该精度向下截断，避免手续费后超卖。 */
    public static int quantityScale(String symbol) {
        if ("btcusdt".equalsIgnoreCase(symbol) || "xautusdt".equalsIgnoreCase(symbol)) {
            return 6;
        }
        return 4;
    }

    /**
     * 下单数量归一：按标的精度上限截断（DOWN 宁小勿超），再剥尾零使输出为最小精确表示。
     * 修复 P1：DB 读回的 req_quantity 为 decimal(36,18)（scale 18），直发超 HTX per-symbol
     * amount-precision 上限被拒。归一后与 open 路径 qtyFor 的 scale-4 输出（如 "0.0003"）一致。
     */
    public static BigDecimal normalizeQty(BigDecimal qty, String symbol) {
        return qty.setScale(quantityScale(symbol), RoundingMode.DOWN).stripTrailingZeros();
    }

    /** 不亏闸门：(1−f)²·sellPrice ≥ buyPrice。 */
    public static boolean isNoLoss(BigDecimal buyPrice, BigDecimal sellPrice, BigDecimal feeRatePerSide) {
        BigDecimal oneMinusF = ONE.subtract(feeRatePerSide);
        BigDecimal lhs = oneMinusF.multiply(oneMinusF).multiply(sellPrice);
        return lhs.compareTo(buyPrice) >= 0;
    }

    /**
     * min-gap 去重：候选价与每个已挂价的相对距离 |cand−e|/e 都 ≥ minGapPct 才算够远。
     * existingPrices 为空 → 直接够远。
     */
    public static boolean farEnough(BigDecimal candidatePrice, List<BigDecimal> existingPrices,
                                    BigDecimal minGapPct) {
        for (BigDecimal e : existingPrices) {
            BigDecimal rel = candidatePrice.subtract(e).abs()
                    .divide(e, 10, RoundingMode.HALF_UP);
            if (rel.compareTo(minGapPct) < 0) {
                return false;
            }
        }
        return true;
    }

    /** 含双边手续费的保本卖价地板：sell >= buy/(1-f)^2，正数价格一律向上取整。 */
    public static BigDecimal sellFloor(BigDecimal buyPrice, BigDecimal feeRatePerSide) {
        BigDecimal oneMinusF = ONE.subtract(feeRatePerSide);
        return buyPrice.divide(oneMinusF.multiply(oneMinusF), PRICE_SCALE, RoundingMode.CEILING);
    }

    /** 常态目标卖价 = max(含费保本地板, bestAsk)，恒 ≥ bestAsk（maker）且不亏手续费。 */
    public static BigDecimal desiredSellPrice(BigDecimal buyPrice, BigDecimal bestAsk,
                                              BigDecimal feeRatePerSide) {
        return sellFloor(buyPrice, feeRatePerSide).max(bestAsk)
                .setScale(PRICE_SCALE, RoundingMode.CEILING);
    }

    /** 卖腿跟价闸门：|desiredSell − currentSell| / currentSell ≥ thresholdPct（含等号）。 */
    public static boolean shouldRepriceSell(BigDecimal desiredSell, BigDecimal currentSell, BigDecimal thresholdPct) {
        BigDecimal rel = desiredSell.subtract(currentSell).abs()
                .divide(currentSell, 10, RoundingMode.HALF_UP);
        return rel.compareTo(thresholdPct) >= 0;
    }

    /** 止损闸门：bestBid ≤ buyPrice·(1 − stopLossPct)。 */
    public static boolean shouldStopLoss(BigDecimal bestBid, BigDecimal buyPrice, BigDecimal stopLossPct) {
        BigDecimal line = buyPrice.multiply(ONE.subtract(stopLossPct));
        return bestBid.compareTo(line) <= 0;
    }

    /**
     * taker 保本(容忍微亏)退出闸门：(1−f)²·bestBid ≥ buyPrice·(1 − lossTolerancePct)。
     * bestBid 扣双边手续费后的净回款 ≥ 买入成本打 (1−容忍) 折即可吃单退出。
     * lossTolerancePct=0 退化为严格保本（比 isNoLoss 更紧，因用 bestBid 而非挂单卖价）。
     */
    public static boolean isTakerExitAcceptable(BigDecimal buyPrice, BigDecimal bestBid,
                                                BigDecimal feeRatePerSide, BigDecimal lossTolerancePct) {
        BigDecimal oneMinusF = ONE.subtract(feeRatePerSide);
        BigDecimal lhs = oneMinusF.multiply(oneMinusF).multiply(bestBid);
        BigDecimal rhs = buyPrice.multiply(ONE.subtract(lossTolerancePct));
        return lhs.compareTo(rhs) >= 0;
    }

    /**
     * taker 买入价差闸门：bestAsk ≤ bestBid·(1 + maxCrossPct)。
     * 盘口价差（ask 相对 bid）在容忍范围内才穿价吃单，防止在宽价差稀薄币上被宰。
     * bestBid/bestAsk/maxCrossPct 的合法性（≤0 过滤）由调用方 currentTopOfBook 保证。
     */
    public static boolean isTakerBuyAcceptable(BigDecimal bestBid, BigDecimal bestAsk,
                                               BigDecimal maxCrossPct) {
        BigDecimal ceiling = bestBid.multiply(ONE.add(maxCrossPct));
        return bestAsk.compareTo(ceiling) <= 0;
    }
}
