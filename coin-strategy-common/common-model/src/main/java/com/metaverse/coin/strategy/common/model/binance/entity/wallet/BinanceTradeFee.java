package com.metaverse.coin.strategy.common.model.binance.entity.wallet;

import lombok.Data;

@Data
public class BinanceTradeFee {
    private String symbol;
    private String makerCommission;
    private String takerCommission;
}
