package com.metaverse.coin.strategy.common.client.htx.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * A single price level in HTX order book.
 * Amount is expressed in base asset (e.g. BTC in BTCUSDT).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HtxPriceLevel {
    private BigDecimal price;
    private BigDecimal amount;
}
