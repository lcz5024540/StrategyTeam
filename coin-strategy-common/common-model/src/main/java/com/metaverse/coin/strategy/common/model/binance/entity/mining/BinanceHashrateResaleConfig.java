package com.metaverse.coin.strategy.common.model.binance.entity.mining;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class BinanceHashrateResaleConfig {
    private Integer configId;
    private String poolUsername;
    private String toPoolUsername;
    private String algoName;
    private Long endDate;
    private Long startDate;
    private BigDecimal hashRate;
    private Integer status;
}
