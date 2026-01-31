package com.metaverse.coin.strategy.common.model.sunwk.entity;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SunwkSymbol {
    private String symbol;
    
    @JsonProperty("contract_code")
    private String contractCode;
    
    @JsonProperty("contract_type")
    private String contractType; // this_week, next_week, quarter
    
    @JsonProperty("contract_size")
    private BigDecimal contractSize;
    
    @JsonProperty("price_tick")
    private BigDecimal priceTick;
    
    @JsonProperty("delivery_date")
    private String deliveryDate;
    
    @JsonProperty("create_date")
    private String createDate;
    
    @JsonProperty("contract_status")
    private Integer contractStatus;
}
