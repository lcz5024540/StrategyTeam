package com.metaverse.coin.strategy.common.model.binance.entity.wallet;

import lombok.Data;

@Data
public class BinanceDepositAddress {
    private String address;
    private String coin;
    private String tag;
    private String url;
}
