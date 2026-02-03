package com.metaverse.coin.strategy.common.model.binance.entity.futures;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceFuturesCompositeIndexInfo {
    private String symbol;
    private Long time;
    private String component;
    private List<Constituent> baseAssetList;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Constituent {
        private String baseAsset;
        private String quoteAsset;
        private BigDecimal weightInQuantity;
        private BigDecimal weightInPercentage;
    }
}
