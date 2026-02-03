package com.metaverse.coin.strategy.common.model.binance.entity.futures;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceFuturesOpenInterest {
    private String symbol;
    private BigDecimal openInterest;
    private Long time;
}
