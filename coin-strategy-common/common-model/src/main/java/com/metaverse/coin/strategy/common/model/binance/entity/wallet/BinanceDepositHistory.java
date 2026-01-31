package com.metaverse.coin.strategy.common.model.binance.entity.wallet;

import lombok.Data;

@Data
public class BinanceDepositHistory {
    private String amount;
    private String coin;
    private String network;
    private Integer status;
    private String address;
    private String addressTag;
    private String txId;
    private Long insertTime;
    private Integer transferType;
    private String unlockConfirm;
    private String confirmTimes;
}
