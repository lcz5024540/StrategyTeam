package com.metaverse.coin.strategy.web.model.smartwallet;

import java.math.BigDecimal;
import java.util.Date;

public record SmartWalletPnlTimelinePoint(
        Date bucketAt,
        BigDecimal profitPnlUsdc,
        BigDecimal lossPnlUsdc,
        BigDecimal realizedPnlUsdc,
        BigDecimal feeUsdc,
        BigDecimal netPnlUsdc,
        BigDecimal cumulativeLossPnlUsdc,
        BigDecimal cumulativeRealizedPnlUsdc,
        BigDecimal cumulativeFeeUsdc,
        BigDecimal cumulativeNetPnlUsdc
) {
}
