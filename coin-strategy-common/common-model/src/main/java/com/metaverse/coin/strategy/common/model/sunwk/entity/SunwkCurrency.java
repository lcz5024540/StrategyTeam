package com.metaverse.coin.strategy.common.model.sunwk.entity;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SunwkCurrency {
    
    @JsonProperty("currency")
    private String currency;
    
    @JsonProperty("chains")
    private String[] chains; // or List<String>
    
    @JsonProperty("status")
    private Integer status; // 1: normal, etc.
}
