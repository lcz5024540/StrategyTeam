package com.metaverse.coin.strategy.common.model.binance.entity.margin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceMarginTradeCoeff {
    private BigDecimal forceLiquidationBar;
    private BigDecimal marginCallBar;
    private BigDecimal normalBar;
}
