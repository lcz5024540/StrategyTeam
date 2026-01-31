package com.metaverse.coin.strategy.common.model.binance.entity.market;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class BinanceExchangeInfo {
    private String timezone;
    private Long serverTime;
    private List<BinanceRateLimit> rateLimits;
    private List<Map<String, Object>> exchangeFilters;
    private List<BinanceSymbol> symbols;
    private List<BinanceSor> sors;
}
