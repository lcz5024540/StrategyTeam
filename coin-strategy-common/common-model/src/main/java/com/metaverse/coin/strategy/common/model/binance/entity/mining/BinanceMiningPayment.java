package com.metaverse.coin.strategy.common.model.binance.entity.mining;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class BinanceMiningPayment {
    private Long time;
    private Integer type;
    private BigDecimal amount;
    private String coinName;
    private BigDecimal hashrate;
}
