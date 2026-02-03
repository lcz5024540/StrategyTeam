package com.metaverse.coin.strategy.common.model.binance.entity.futures;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceFuturesAssetIndex {
    private String symbol;
    private Long time;
    private BigDecimal index;
    private BigDecimal bidBuffer;
    private BigDecimal askBuffer;
    private BigDecimal bidRate;
    private BigDecimal askRate;
    private BigDecimal autoExchangeBidBuffer;
    private BigDecimal autoExchangeAskBuffer;
    private BigDecimal autoExchangeBidRate;
    private BigDecimal autoExchangeAskRate;
}
