package com.metaverse.coin.strategy.common.model.binance.entity.margin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceCrossMarginCollateralRatio {
    private List<CollateralRatio> collaterals;
    private List<String> assetNames;
    
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CollateralRatio {
        private BigDecimal minUsdValue;
        private BigDecimal maxUsdValue;
        private BigDecimal discountRate;
    }
}
