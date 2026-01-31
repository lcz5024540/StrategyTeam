package com.metaverse.coin.strategy.common.model.sunwk.entity;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SunwkBillRecord {
    
    @JsonProperty("id")
    private Long id;
    
    @JsonProperty("amount")
    private BigDecimal amount;
    
    @JsonProperty("asset")
    private String asset; // Currency like USDT
    
    @JsonProperty("type")
    private String type; // e.g. TRANSFER, FEE
    
    @JsonProperty("time")
    private Long time;
    
    // Add other fields as per API documentation if available
}
