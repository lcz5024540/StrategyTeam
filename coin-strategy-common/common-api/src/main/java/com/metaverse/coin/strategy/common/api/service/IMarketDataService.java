package com.metaverse.coin.strategy.common.api.service;

import com.metaverse.coin.strategy.common.api.model.UnifiedOrder;
import java.math.BigDecimal;

public interface IMarketDataService {
    
    BigDecimal getTickerPrice(String symbol);
    
    // Future expansion: getOrderBook, getKline, etc.
}
