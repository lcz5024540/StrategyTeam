package com.metaverse.coin.strategy.common.client.htx.model;

import lombok.Data;

import java.math.BigDecimal;

/**
 * HTX spot account balance entry.
 * type is one of: trade / frozen / loan / interest / lock / bank.
 */
@Data
public class HtxBalance {
    private String currency;
    private String type;
    private BigDecimal balance;
}
