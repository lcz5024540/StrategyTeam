package com.metaverse.coin.strategy.common.model.sunwk.entity;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SunwkPosition {
    private String symbol;
    
    @JsonProperty("contract_code")
    private String contractCode;
    
    private BigDecimal volume;
    
    private BigDecimal available;
    
    @JsonProperty("frozen_volume")
    private BigDecimal frozenVolume;
    
    @JsonProperty("cost_open")
    private BigDecimal costOpen;
    
    @JsonProperty("cost_hold")
    private BigDecimal costHold;
    
    @JsonProperty("profit_unreal")
    private BigDecimal profitUnreal;
    
    @JsonProperty("profit_rate")
    private BigDecimal profitRate;
    
    @JsonProperty("lever_rate")
    private Integer leverRate;
    
    private String direction; // "buy" or "sell"
    
    @JsonProperty("last_price")
    private BigDecimal lastPrice;
}
