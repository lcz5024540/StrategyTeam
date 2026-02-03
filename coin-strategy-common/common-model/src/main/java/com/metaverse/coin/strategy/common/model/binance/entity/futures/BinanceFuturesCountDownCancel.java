package com.metaverse.coin.strategy.common.model.binance.entity.futures;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceFuturesCountDownCancel {
    private String symbol;
    private Long countdownTime;
}
