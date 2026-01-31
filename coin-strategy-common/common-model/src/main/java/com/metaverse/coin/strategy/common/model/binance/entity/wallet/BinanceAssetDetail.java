package com.metaverse.coin.strategy.common.model.binance.entity.wallet;

import lombok.Data;

@Data
public class BinanceAssetDetail {
    private String minWithdrawAmount;
    private Boolean depositStatus;
    private String withdrawFee;
    private Boolean withdrawStatus;
    private String depositTip;
}
