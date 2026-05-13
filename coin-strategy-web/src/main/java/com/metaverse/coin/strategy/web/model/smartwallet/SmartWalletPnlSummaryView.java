package com.metaverse.coin.strategy.web.model.smartwallet;

import java.math.BigDecimal;

public record SmartWalletPnlSummaryView(
    Integer totalOrders, Integer filledOrders, Integer openPositions,
    BigDecimal tradedVolumeUsdc, BigDecimal feeUsdc,
    BigDecimal realizedPnlUsdc, BigDecimal unrealizedPnlUsdc,
    BigDecimal pendingSettlementPnlUsdc, BigDecimal compositePnlUsdc,
    BigDecimal netPnlUsdc, BigDecimal winRate) {

    public BigDecimal getTradedVolumeUsdc() { return tradedVolumeUsdc; }
    public BigDecimal getFeeUsdc() { return feeUsdc; }
}
