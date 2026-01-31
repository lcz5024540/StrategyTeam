package com.metaverse.coin.strategy.common.model.sunwk.entity;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SunwkPlanOrder {
    
    @JsonProperty("order_id")
    private String orderId;
    
    @JsonProperty("client_order_id")
    private String clientOrderId;
    
    @JsonProperty("contract_code")
    private String contractCode;
    
    @JsonProperty("trigger_type")
    private String triggerType; // ge, le
    
    @JsonProperty("trigger_price")
    private BigDecimal triggerPrice;
    
    @JsonProperty("order_price")
    private BigDecimal orderPrice;
    
    @JsonProperty("volume")
    private BigDecimal volume;
    
    @JsonProperty("direction")
    private String direction; // buy, sell
    
    @JsonProperty("offset")
    private String offset; // open, close
    
    @JsonProperty("lever_rate")
    private Integer leverRate;
    
    @JsonProperty("status")
    private String status; // 0: init, 1: triggered, 2: cancelled, 3: fail
    
    @JsonProperty("created_at")
    private Long createdAt;
    
    @JsonProperty("triggered_at")
    private Long triggeredAt;
}
