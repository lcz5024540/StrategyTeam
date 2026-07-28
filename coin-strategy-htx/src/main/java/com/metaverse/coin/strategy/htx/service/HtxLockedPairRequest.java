package com.metaverse.coin.strategy.htx.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Locked-pair trade request. See {@code HTX_SPOT_EXECUTION_SDK_V1.md} §4 flow diagram.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HtxLockedPairRequest {
    private String symbol;
    private BigDecimal quantity;
    private BigDecimal buyPrice;
    private BigDecimal sellPrice;
    /** Optional override; falls back to {@link HtxTradeProperties.DepthGuard#getSafetyRatio()}. */
    private BigDecimal safetyRatio;
    /** Optional override; falls back to {@link HtxTradeProperties.Trade#getFillTimeoutMs()}. */
    private Long waitFillTimeoutMs;
    /**
     * Optional client-side idempotency key (v1.0.1). When set, the executor derives per-leg
     * client-order-ids ({@code $clientOrderId-BUY} / {@code $clientOrderId-SELL}) and forwards
     * them to HTX. HTX returns the original order for duplicate ids — safe to retry submit
     * on network timeout without double-buying. Max 64 chars, hyphen-safe.
     */
    private String clientOrderId;
}
