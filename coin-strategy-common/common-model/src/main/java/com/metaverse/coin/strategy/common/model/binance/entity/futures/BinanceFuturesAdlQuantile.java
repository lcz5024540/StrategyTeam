package com.metaverse.coin.strategy.common.model.binance.entity.futures;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceFuturesAdlQuantile {
    private String symbol;
    private AdlQuantile adlQuantile;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AdlQuantile {
        private Integer longQuantile;
        private Integer shortQuantile;
    }
}
