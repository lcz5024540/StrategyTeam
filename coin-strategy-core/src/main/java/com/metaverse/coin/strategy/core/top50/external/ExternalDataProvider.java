package com.metaverse.coin.strategy.core.top50.external;

import java.math.BigDecimal;
import java.util.Map;

public interface ExternalDataProvider {

    /**
     * Get market sentiment score (0-100).
     * e.g. Fear and Greed Index.
     */
    int getSentimentScore();

    /**
     * Get Open Interest change for a symbol.
     */
    BigDecimal getOpenInterestChange(String symbol);
    
    /**
     * Get extra external signals.
     */
    Map<String, Object> getExternalSignals(String symbol);
}
