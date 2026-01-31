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
public class BinanceTrade {
    private Long id;
    private BigDecimal price;
    private BigDecimal qty;
    private BigDecimal quoteQty;
    private Long time;
    @JsonProperty("isBuyerMaker")
    private Boolean isBuyerMaker;
    @JsonProperty("isBestMatch")
    private Boolean isBestMatch;
}
