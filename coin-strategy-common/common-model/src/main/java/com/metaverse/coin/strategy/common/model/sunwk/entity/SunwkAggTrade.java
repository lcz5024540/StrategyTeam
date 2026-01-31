package com.metaverse.coin.strategy.common.model.sunwk.entity;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SunwkAggTrade {
    private Long id;
    private BigDecimal price;
    private BigDecimal amount;
    private String direction; // "buy" or "sell"
    private Long ts;
}
