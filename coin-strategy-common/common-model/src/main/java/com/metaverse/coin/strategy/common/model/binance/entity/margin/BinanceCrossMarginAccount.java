package com.metaverse.coin.strategy.common.model.binance.entity.margin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceCrossMarginAccount {
    private Boolean borrowEnabled;
    private BigDecimal marginLevel;
    private BigDecimal totalAssetOfBtc;
    private BigDecimal totalLiabilityOfBtc;
    private BigDecimal totalNetAssetOfBtc;
    private Boolean tradeEnabled;
    private Boolean transferEnabled;
    private List<BinanceMarginAssetBalance> userAssets;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BinanceMarginAssetBalance {
        private String asset;
        private BigDecimal borrowed;
        private BigDecimal free;
        private BigDecimal interest;
        private BigDecimal locked;
        private BigDecimal netAsset;
    }
}
