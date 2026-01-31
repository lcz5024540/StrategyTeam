package com.metaverse.coin.strategy.common.model.binance.entity.market;

import lombok.Data;

@Data
public class BinanceRateLimit {
    private String rateLimitType;
    private String interval;
    private Integer intervalNum;
    private Integer limit;
}
