package com.metaverse.coin.strategy.common.model.sunwk.entity;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SunwkTicker {
    private String symbol;
    
    @JsonProperty("contract_code")
    private String contractCode;
    
    private BigDecimal open;
    private BigDecimal close;
    private BigDecimal low;
    private BigDecimal high;
    private BigDecimal amount; // volume
    private BigDecimal vol; // turnover?
    private Integer count; 
    private BigDecimal bid;
    private BigDecimal ask;
}
