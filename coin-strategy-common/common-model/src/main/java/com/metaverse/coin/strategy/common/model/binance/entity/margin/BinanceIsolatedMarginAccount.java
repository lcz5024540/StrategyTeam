package com.metaverse.coin.strategy.common.model.binance.entity.margin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceIsolatedMarginAccount {
    private List<IsolatedMarginAsset> assets;
    private BigDecimal totalAssetOfBtc;
    private BigDecimal totalLiabilityOfBtc;
    private BigDecimal totalNetAssetOfBtc;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IsolatedMarginAsset {
        private String baseAsset;
        private String quoteAsset;
        private String symbol;
        private Boolean borrowEnabled;
        private BigDecimal borrowed;
        private BigDecimal free;
        private BigDecimal interest;
        private BigDecimal locked;
        private BigDecimal netAsset;
        private BigDecimal netAssetOfBtc;
        private Boolean repayEnabled;
        private BigDecimal totalAsset;
        
        private BigDecimal marginLevel;
        private BigDecimal marginRatio;
        private BigDecimal indexPrice;
        private BigDecimal liquidatePrice;
        private BigDecimal liquidateRate;
        private Boolean tradeEnabled;
        
        private IsolatedAssetDetail baseAssetDetail;
        private IsolatedAssetDetail quoteAssetDetail;
    }
    
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IsolatedAssetDetail {
        private String asset;
        private BigDecimal borrowed;
        private BigDecimal free;
        private BigDecimal interest;
        private BigDecimal locked;
        private BigDecimal netAsset;
    }
}
