package com.metaverse.coin.strategy.core.arbitrage.risk;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SingleExchangeRiskSnapshotData {

    private BigDecimal spotUsdtBalance;
    private BigDecimal futuresAvailableBalance;
    private BigDecimal requiredMargin;
    private BigDecimal bufferedRequiredMargin;
    private BigDecimal marginRatio;
}
