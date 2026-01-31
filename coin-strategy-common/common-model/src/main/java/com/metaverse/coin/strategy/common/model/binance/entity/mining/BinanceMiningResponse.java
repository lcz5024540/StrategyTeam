package com.metaverse.coin.strategy.common.model.binance.entity.mining;

import lombok.Data;

@Data
public class BinanceMiningResponse<T> {
    private int code;
    private String msg;
    private T data;
}
