package com.metaverse.coin.strategy.web.model.smartwallet;

import java.math.BigDecimal;

public record SmartWalletReportAttributionItem(
        String key,
        String label,
        Integer orderCount,
        BigDecimal tradedVolumeUsdc,
        BigDecimal realizedPnlUsdc,
        BigDecimal pendingSettlementPnlUsdc,
        BigDecimal feeUsdc,
        BigDecimal netPnlUsdc
) {
}
