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
public class SingleExchangeLiquidityAssessment {

    private boolean passed;
    private boolean fallbackUsed;
    private String phase;
    private BigDecimal spotExpectedPrice;
    private BigDecimal futuresExpectedPrice;
    private BigDecimal estimatedSlippageRate;
    private String rejectionReason;
    private boolean spotTopLevelEnough;
    private boolean futuresTopLevelEnough;
}
