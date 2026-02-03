package com.metaverse.coin.strategy.common.model.binance.entity.futures;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceFuturesSymbol {
    private String symbol;
    private String pair;
    private String contractType;
    private Long deliveryDate;
    private Long onboardDate;
    private String status;
    private BigDecimal maintMarginPercent;
    private BigDecimal requiredMarginPercent;
    private String baseAsset;
    private String quoteAsset;
    private String marginAsset;
    private Integer pricePrecision;
    private Integer quantityPrecision;
    private Integer baseAssetPrecision;
    private Integer quotePrecision;
    private String underlyingType;
    private List<String> underlyingSubType;
    private Integer settlePlan;
    private BigDecimal triggerProtect;
    private BigDecimal liquidationFee;
    private BigDecimal marketTakeBound;
    private List<Map<String, Object>> filters;
    private List<String> orderTypes;
    private List<String> timeInForce;
}
