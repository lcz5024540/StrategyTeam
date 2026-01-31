package com.metaverse.coin.strategy.common.model.sunwk.entity;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SunwkFeeRate {
    
    @JsonProperty("symbol")
    private String symbol;
    
    @JsonProperty("maker_fee_rate")
    private BigDecimal makerFeeRate;
    
    @JsonProperty("taker_fee_rate")
    private BigDecimal takerFeeRate;
    
    // Add other fields as per API documentation if available
}
