package com.metaverse.coin.strategy.common.model.binance.entity.mining;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class BinanceHashrateResaleDetail {
    private Long time;
    private BigDecimal profitAmount;
    private String coinName;
}
