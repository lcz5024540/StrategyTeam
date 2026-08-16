package com.metaverse.coin.strategy.htx.strategy.grid;

import java.math.BigDecimal;

/** Exchange-neutral spot grid pricing policy. */
public interface SpotGridStrategy {

    GridQuote quote(BigDecimal buyPrice,
                    BigDecimal spreadPct,
                    BigDecimal buyFeeRate,
                    BigDecimal sellFeeRate,
                    int priceScale);

    BigDecimal sellPriceAfterFill(BigDecimal filledQuote,
                                  BigDecimal filledBase,
                                  BigDecimal actualBuyFeeBase,
                                  BigDecimal fallbackBuyPrice,
                                  BigDecimal bestAsk,
                                  BigDecimal buyFeeRate,
                                  BigDecimal sellFeeRate,
                                  int priceScale);

    boolean coversFees(BigDecimal buyPrice,
                       BigDecimal sellPrice,
                       BigDecimal buyFeeRate,
                       BigDecimal sellFeeRate);

    record GridQuote(BigDecimal buyPrice, BigDecimal sellPrice, BigDecimal feeFloor) {
    }
}
