package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;

import java.math.BigDecimal;
import java.util.Date;

@Builder
public record WalletOrderAnalysisView(
        String walletAddress,
        String marketId,
        String question,
        String outcome,
        Date openedAt,
        Date closedAt,
        Date settlementAt,
        Long marketEndAt,
        BigDecimal investedUsdc,
        BigDecimal realizedPnlUsdc,
        BigDecimal roiRate,
        BigDecimal holdMinutes,
        String exitStyle,
        BigDecimal exitLeadMinutes,
        Boolean isWin,
        BigDecimal sourceConfidence,
        Boolean pendingSettlement,
        Boolean earlySettlement
) {
}
