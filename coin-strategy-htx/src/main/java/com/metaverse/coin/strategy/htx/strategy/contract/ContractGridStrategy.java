package com.metaverse.coin.strategy.htx.strategy.contract;

import java.math.BigDecimal;

/** Exchange-neutral pricing policy for fee-aware perpetual grid exits. */
public interface ContractGridStrategy {

    ExitQuote exitQuote(PositionCost longPosition,
                        PositionCost shortPosition,
                        BigDecimal closeFeeRate,
                        BigDecimal fundingBufferRate,
                        BigDecimal safetyBufferRate,
                        BigDecimal minNetProfitRate,
                        BigDecimal priceTick);

    boolean entrySpreadAllowed(BigDecimal bestBid, BigDecimal bestAsk, BigDecimal maxSpreadRate);

    record PositionCost(BigDecimal averageOpenPrice,
                        BigDecimal contractSize,
                        long volume,
                        BigDecimal actualOpenFee) {
    }

    record ExitQuote(BigDecimal longClosePrice, BigDecimal shortClosePrice) {
    }
}
