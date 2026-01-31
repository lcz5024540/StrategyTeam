package com.metaverse.coin.strategy.common.model.sunwk.entity;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SunwkTrade {
    private BigDecimal amount;
    private BigDecimal price;
    private String direction; // "buy" or "sell"
    private Long ts;
    private Long id;
}
