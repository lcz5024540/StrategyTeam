package com.metaverse.coin.strategy.common.model.binance.entity.mining;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class BinanceMiningStat {
    private BigDecimal fifteenMinHashRate;
    private BigDecimal dayHashRate;
    private Integer validNum;
    private Integer invalidNum;
    private BigDecimal profitToday;
    private BigDecimal profitYesterday;
    private String userName;
    private String unit;
    private String algo;
}
