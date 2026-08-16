package com.metaverse.coin.strategy.htx.strategy.grid;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Pure fee-aware grid pricing implementation with no exchange or persistence dependencies. */
@Component
public class FeeAwareSpotGridStrategy implements SpotGridStrategy {

    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    @Override
    public GridQuote quote(BigDecimal buyPrice,
                           BigDecimal spreadPct,
                           BigDecimal buyFeeRate,
                           BigDecimal sellFeeRate,
                           int priceScale) {
        validatePriceAndRates(buyPrice, buyFeeRate, sellFeeRate);
        if (spreadPct == null || spreadPct.signum() < 0) {
            throw new IllegalArgumentException("spreadPct must be non-negative");
        }
        BigDecimal normalizedBuy = buyPrice.setScale(priceScale, RoundingMode.HALF_UP);
        BigDecimal spreadTarget = normalizedBuy.multiply(ONE.add(TWO.multiply(spreadPct)))
                .setScale(priceScale, RoundingMode.CEILING);
        BigDecimal feeFloor = feeFloor(normalizedBuy, buyFeeRate, sellFeeRate, priceScale);
        BigDecimal sellPrice = strictlyAboveBuy(normalizedBuy, spreadTarget.max(feeFloor), priceScale);
        return new GridQuote(normalizedBuy, sellPrice, feeFloor);
    }

    @Override
    public BigDecimal sellPriceAfterFill(BigDecimal filledQuote,
                                         BigDecimal filledBase,
                                         BigDecimal actualBuyFeeBase,
                                         BigDecimal fallbackBuyPrice,
                                         BigDecimal bestAsk,
                                         BigDecimal buyFeeRate,
                                         BigDecimal sellFeeRate,
                                         int priceScale) {
        validatePriceAndRates(fallbackBuyPrice, buyFeeRate, sellFeeRate);
        BigDecimal grossBase = positiveOr(filledBase, BigDecimal.ZERO);
        BigDecimal quoteCost = positiveOr(filledQuote, grossBase.multiply(fallbackBuyPrice));
        if (grossBase.signum() <= 0 || quoteCost.signum() <= 0) {
            throw new IllegalArgumentException("filled amount and quote cost must be positive");
        }

        BigDecimal observedSellable = grossBase.subtract(nonNegative(actualBuyFeeBase));
        BigDecimal feeProtectedSellable = grossBase.multiply(ONE.subtract(buyFeeRate));
        BigDecimal protectedSellable = observedSellable.min(feeProtectedSellable);
        if (protectedSellable.signum() <= 0) {
            throw new IllegalArgumentException("buy fees consume the filled base amount");
        }
        BigDecimal netSellFactor = protectedSellable.multiply(ONE.subtract(sellFeeRate));
        BigDecimal feeFloor = quoteCost.divide(netSellFactor, priceScale, RoundingMode.CEILING);
        BigDecimal marketTarget = positiveOr(bestAsk, feeFloor).setScale(priceScale, RoundingMode.CEILING);
        return strictlyAboveBuy(fallbackBuyPrice.setScale(priceScale, RoundingMode.HALF_UP),
                marketTarget.max(feeFloor), priceScale);
    }

    @Override
    public boolean coversFees(BigDecimal buyPrice,
                              BigDecimal sellPrice,
                              BigDecimal buyFeeRate,
                              BigDecimal sellFeeRate) {
        validatePriceAndRates(buyPrice, buyFeeRate, sellFeeRate);
        if (sellPrice == null || sellPrice.compareTo(buyPrice) <= 0) {
            return false;
        }
        BigDecimal netProceeds = sellPrice.multiply(ONE.subtract(buyFeeRate))
                .multiply(ONE.subtract(sellFeeRate));
        return netProceeds.compareTo(buyPrice) >= 0;
    }

    private static BigDecimal feeFloor(BigDecimal buyPrice,
                                       BigDecimal buyFeeRate,
                                       BigDecimal sellFeeRate,
                                       int priceScale) {
        BigDecimal netFactor = ONE.subtract(buyFeeRate).multiply(ONE.subtract(sellFeeRate));
        return buyPrice.divide(netFactor, priceScale, RoundingMode.CEILING);
    }

    private static BigDecimal strictlyAboveBuy(BigDecimal buyPrice, BigDecimal candidate, int priceScale) {
        BigDecimal oneTick = ONE.movePointLeft(priceScale);
        BigDecimal strictFloor = buyPrice.add(oneTick).setScale(priceScale, RoundingMode.CEILING);
        return candidate.max(strictFloor).setScale(priceScale, RoundingMode.CEILING);
    }

    private static BigDecimal positiveOr(BigDecimal value, BigDecimal fallback) {
        return value != null && value.signum() > 0 ? value : fallback;
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value == null || value.signum() < 0 ? BigDecimal.ZERO : value;
    }

    private static void validatePriceAndRates(BigDecimal buyPrice,
                                              BigDecimal buyFeeRate,
                                              BigDecimal sellFeeRate) {
        if (buyPrice == null || buyPrice.signum() <= 0) {
            throw new IllegalArgumentException("buyPrice must be positive");
        }
        validateRate("buyFeeRate", buyFeeRate);
        validateRate("sellFeeRate", sellFeeRate);
    }

    private static void validateRate(String name, BigDecimal rate) {
        if (rate == null || rate.signum() < 0 || rate.compareTo(ONE) >= 0) {
            throw new IllegalArgumentException(name + " must be in [0, 1)");
        }
    }
}
