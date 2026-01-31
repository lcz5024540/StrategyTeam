package com.metaverse.coin.strategy.common.model.binance.entity.market;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class BinanceSymbol {
    private String symbol;
    private String status;
    private String baseAsset;
    private Integer baseAssetPrecision;
    private String quoteAsset;
    private Integer quotePrecision;
    private Integer quoteAssetPrecision;
    private Integer baseCommissionPrecision;
    private Integer quoteCommissionPrecision;
    private List<String> orderTypes;
    private Boolean icebergAllowed;
    private Boolean ocoAllowed;
    private Boolean otoAllowed;
    private Boolean opoAllowed;
    private Boolean quoteOrderQtyMarketAllowed;
    private Boolean allowTrailingStop;
    private Boolean cancelReplaceAllowed;
    private Boolean amendAllowed;
    private Boolean pegInstructionsAllowed;
    private Boolean isSpotTradingAllowed;
    private Boolean isMarginTradingAllowed;
    
    // Using Map to handle various filter types dynamically
    private List<Map<String, Object>> filters; 
    
    private List<String> permissions;
    private List<List<String>> permissionSets;
    private String defaultSelfTradePreventionMode;
    private List<String> allowedSelfTradePreventionModes;
}
