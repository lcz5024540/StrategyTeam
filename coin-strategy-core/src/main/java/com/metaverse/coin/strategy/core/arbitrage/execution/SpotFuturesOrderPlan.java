package com.metaverse.coin.strategy.core.arbitrage.execution;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SpotFuturesOrderPlan {
    private String symbol;
    private String spotSymbol;
    private String futuresSymbol;
    private BigDecimal quantity;
    private BigDecimal spotPrice;
    private BigDecimal futuresPrice;
    private BigDecimal referencePrice;
    private BigDecimal basisSpread;
    private BigDecimal basisRate;
    private BigDecimal spotNotional;
    private BigDecimal futuresNotional;
    private BigDecimal quantityStep;
    private BigDecimal minQuantity;
    private BigDecimal minNotional;
    private BigDecimal fundingRate;
    private Integer leverage;

    public BigDecimal getRequiredFuturesMargin() {
        if (futuresNotional == null || leverage == null || leverage <= 0) {
            return BigDecimal.ZERO;
        }
        return futuresNotional.divide(BigDecimal.valueOf(leverage), 8, RoundingMode.UP);
    }
}
