package com.metaverse.coin.strategy.common.model.binance.entity.futures;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceFuturesIncome {
    private String symbol;
    private String incomeType;
    private BigDecimal income;
    private String asset;
    private String info;
    private Long time;
    private String tranId;
    private String tradeId;
}
