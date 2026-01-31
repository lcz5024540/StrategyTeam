package com.metaverse.coin.strategy.core.top50.market;

import java.math.BigDecimal;
import java.util.Map;

public interface VolatilityMonitor {

    /**
     * Get the current volatility for a symbol.
     * @param symbol The trading pair symbol.
     * @return Volatility metric (e.g. standard deviation or ATR).
     */
    BigDecimal getVolatility(String symbol);

    /**
     * Get volatility metrics for all monitored symbols.
     */
    Map<String, BigDecimal> getAllVolatilities();
}
