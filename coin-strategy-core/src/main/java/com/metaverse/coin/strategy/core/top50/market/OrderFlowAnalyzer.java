package com.metaverse.coin.strategy.core.top50.market;

import java.math.BigDecimal;

public interface OrderFlowAnalyzer {

    /**
     * Get the order book imbalance ratio.
     * > 1 means more buy pressure, < 1 means more sell pressure.
     */
    BigDecimal getImbalance(String symbol);

    /**
     * Get the net trade flow (Buy Volume - Sell Volume) over a recent window.
     */
    BigDecimal getNetTradeFlow(String symbol);
}
