package com.metaverse.coin.strategy.common.model.binance.entity.futures;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceFuturesPositionRisk {
    private String symbol;
    private BigDecimal positionAmt;
    private BigDecimal entryPrice;
    private BigDecimal markPrice;
    private BigDecimal unRealizedProfit;
    private BigDecimal liquidationPrice;
    private Integer leverage;
    private BigDecimal maxNotionalValue;
    private String marginType;
    private BigDecimal isolatedMargin;
    private Boolean isAutoAddMargin;
    private String positionSide;
    private BigDecimal notional;
    private BigDecimal isolatedWallet;
    private Long updateTime;
}
