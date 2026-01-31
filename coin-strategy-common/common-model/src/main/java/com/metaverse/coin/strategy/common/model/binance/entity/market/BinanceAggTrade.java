package com.metaverse.coin.strategy.common.model.binance.entity.market;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceAggTrade {
    @JsonProperty("a")
    private Long aggTradeId;
    
    @JsonProperty("p")
    private BigDecimal price;
    
    @JsonProperty("q")
    private BigDecimal quantity;
    
    @JsonProperty("f")
    private Long firstTradeId;
    
    @JsonProperty("l")
    private Long lastTradeId;
    
    @JsonProperty("T")
    private Long time;
    
    @JsonProperty("m")
    private Boolean isBuyerMaker;
    
    @JsonProperty("M")
    private Boolean isBestMatch;
}
