package com.metaverse.coin.strategy.common.model.sunwk.entity;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SunwkFundingRate {
    private String symbol;
    
    @JsonProperty("contract_code")
    private String contractCode;
    
    @JsonProperty("funding_rate")
    private BigDecimal fundingRate;
    
    @JsonProperty("funding_time")
    private Long fundingTime;
    
    @JsonProperty("mark_price")
    private BigDecimal markPrice;
}
