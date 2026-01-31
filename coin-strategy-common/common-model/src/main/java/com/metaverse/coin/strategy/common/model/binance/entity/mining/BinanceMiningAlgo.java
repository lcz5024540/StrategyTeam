package com.metaverse.coin.strategy.common.model.binance.entity.mining;

import lombok.Data;

@Data
public class BinanceMiningAlgo {
    private String algoName;
    private String algoId;
    private Long poolIndex;
    private String unit;
}
