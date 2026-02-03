package com.metaverse.coin.strategy.common.model.binance.entity.futures;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceFuturesAccountRatio {
    private String symbol;
    private BigDecimal longShortRatio;
    private BigDecimal longAccount;
    private BigDecimal shortAccount;
    private Long timestamp;
}
