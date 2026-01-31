package com.metaverse.coin.strategy.common.model.binance.entity.wallet;

import lombok.Data;

@Data
public class BinanceWithdrawHistory {
    private String address;
    private String amount;
    private String applyTime;
    private String coin;
    private String id;
    private String withdrawOrderId;
    private String network;
    private Integer transferType;
    private Integer status;
    private String transactionFee;
    private Integer confirmNo;
    private String info;
    private String txId;
}
