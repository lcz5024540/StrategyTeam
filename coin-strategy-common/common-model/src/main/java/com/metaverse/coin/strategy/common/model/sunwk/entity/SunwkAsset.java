package com.metaverse.coin.strategy.common.model.sunwk.entity;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SunwkAsset {
    private String currency;
    private BigDecimal balance;
    private BigDecimal available;
    private BigDecimal frozen;
}
