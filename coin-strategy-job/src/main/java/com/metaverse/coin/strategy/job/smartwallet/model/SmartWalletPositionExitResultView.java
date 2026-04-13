package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class SmartWalletPositionExitResultView {
    private Long positionId;
    private Long orderId;
    private String executionAddress;
    private String marketId;
    private String outcome;
    private String previousStatus;
    private String currentStatus;
    private BigDecimal exitPrice;
    private BigDecimal exitQuantity;
    private BigDecimal exitProceedsUsdc;
    private BigDecimal realizedPnlDeltaUsdc;
    private BigDecimal totalRealizedPnlUsdc;
    private BigDecimal remainingQuantity;
    private String message;
}
