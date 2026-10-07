package com.metaverse.coin.strategy.htx.persistence.model;

import lombok.Data;

import java.math.BigDecimal;

/** Aggregate of all filled order legs for one completed trend position. */
@Data
public class HtxTrendOrderLedgerPnl {
    private Long filledOrderCount;
    private BigDecimal grossProfit;
    private BigDecimal totalFee;
    private BigDecimal netProfit;
}
