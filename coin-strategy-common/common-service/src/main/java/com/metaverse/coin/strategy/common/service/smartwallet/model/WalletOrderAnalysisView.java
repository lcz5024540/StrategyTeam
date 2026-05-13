package com.metaverse.coin.strategy.common.service.smartwallet.model;

import lombok.Builder;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 单地址单笔订单分析视图。
 * <p>原为 {@code com.metaverse.coin.strategy.job.smartwallet.model.WalletOrderAnalysisView}。
 * Phase 4.4 下沉。</p>
 */
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
