package com.metaverse.coin.strategy.common.model.sunwk.entity;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SunwkOrder {
    @JsonProperty("order_id")
    private String orderId;
    
    @JsonProperty("client_order_id")
    private String clientOrderId;
    
    @JsonProperty("contract_code")
    private String contractCode;
    
    private String symbol;
    
    private BigDecimal price;
    
    private BigDecimal volume;
    
    @JsonProperty("trade_volume")
    private BigDecimal tradeVolume;
    
    @JsonProperty("trade_turnover")
    private BigDecimal tradeTurnover;
    
    private BigDecimal fee;
    
    // 1: open long, 2: open short, 3: close long, 4: close short ? Or string
    private String direction; 
    private String offset;
    
    @JsonProperty("lever_rate")
    private Integer leverRate;
    
    @JsonProperty("order_price_type")
    private String orderPriceType;
    
    private String status; // 0: submitted, 1: partial, 2: full, 3: cancelled
    
    @JsonProperty("created_at")
    private Long createdAt;
}
