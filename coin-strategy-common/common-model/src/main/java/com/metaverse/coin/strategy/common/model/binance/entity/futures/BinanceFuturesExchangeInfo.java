package com.metaverse.coin.strategy.common.model.binance.entity.futures;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceRateLimit;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceFuturesExchangeInfo {
    private String timezone;
    private Long serverTime;
    private List<BinanceRateLimit> rateLimits;
    private List<Map<String, Object>> exchangeFilters;
    private List<BinanceFuturesSymbol> symbols;
}
