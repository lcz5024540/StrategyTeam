package com.metaverse.coin.strategy.common.model.sunwk.entity;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SunwkOpenInterest {
    private String symbol;
    
    @JsonProperty("contract_code")
    private String contractCode;
    
    @JsonProperty("open_interest")
    private BigDecimal openInterest;
    
    @JsonProperty("open_interest_amount")
    private BigDecimal openInterestAmount;
    
    private Long ts;
}
