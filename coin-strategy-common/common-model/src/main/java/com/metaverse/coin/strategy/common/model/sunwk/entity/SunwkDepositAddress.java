package com.metaverse.coin.strategy.common.model.sunwk.entity;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SunwkDepositAddress {
    private String currency;
    private String address;
    private String chain;
}
