package com.metaverse.coin.strategy.common.model.sunwk.entity;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SunwkDepth {
    private List<List<BigDecimal>> asks; // [price, volume]
    private List<List<BigDecimal>> bids;
    private Long ts;
    private Long version;
}
