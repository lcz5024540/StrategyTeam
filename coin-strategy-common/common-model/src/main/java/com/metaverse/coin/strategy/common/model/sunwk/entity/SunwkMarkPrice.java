package com.metaverse.coin.strategy.common.model.sunwk.entity;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SunwkMarkPrice {
    private String symbol;
    
    @JsonProperty("contract_code")
    private String contractCode;
    
    @JsonProperty("mark_price")
    private BigDecimal markPrice;
    
    @JsonProperty("index_price")
    private BigDecimal indexPrice;
    
    private Long ts;
}
