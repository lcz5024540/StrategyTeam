package com.metaverse.coin.strategy.common.model.sunwk.entity;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SunwkPositionHistory {
    private String symbol;
    
    @JsonProperty("contract_code")
    private String contractCode;
    
    private String direction; // "buy" or "sell"
    
    @JsonProperty("open_avg_price")
    private BigDecimal openAvgPrice;
    
    @JsonProperty("close_avg_price")
    private BigDecimal closeAvgPrice;
    
    private BigDecimal volume;
    
    @JsonProperty("profit_real")
    private BigDecimal profitReal;
    
    @JsonProperty("close_type")
    private String closeType;
    
    @JsonProperty("create_time")
    private Long createTime;
}
