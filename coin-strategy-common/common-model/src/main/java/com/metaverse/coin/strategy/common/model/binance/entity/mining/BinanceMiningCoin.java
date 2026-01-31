package com.metaverse.coin.strategy.common.model.binance.entity.mining;

import lombok.Data;

@Data
public class BinanceMiningCoin {
    private String coinName;
    private String coinId;
    private Long poolIndex;
    private String algoId;
    private String algoName;
}
