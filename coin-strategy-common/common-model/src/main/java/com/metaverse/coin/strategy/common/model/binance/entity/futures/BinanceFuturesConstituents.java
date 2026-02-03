package com.metaverse.coin.strategy.common.model.binance.entity.futures;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceFuturesConstituents {
    private String symbol;
    private Long time;
    private List<Constituent> constituents;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Constituent {
        private String exchange;
        private String symbol;
    }
}
