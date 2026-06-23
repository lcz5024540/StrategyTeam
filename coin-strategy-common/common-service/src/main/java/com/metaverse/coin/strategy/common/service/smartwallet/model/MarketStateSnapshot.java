package com.metaverse.coin.strategy.common.service.smartwallet.model;

import lombok.Builder;

import java.util.Date;

/**
 * 市场状态快照，从 smart_wallet_market_info 表组装而来。
 *
 * <p>原为 {@code com.metaverse.coin.strategy.job.smartwallet.service.MarketStateSnapshot}
 * 的嵌套 record。Phase 3 下沉到 common-service 是为了让 common-service 端的
 * {@code SmartWalletMarketMonitoringReadService} 能复用。</p>
 *
 * <p>原嵌套 record 里引用的三个常量（{@code LIFECYCLE_ACTIVE}、{@code LIFECYCLE_RESOLVED}、
 * {@code CATEGORY_OTHER}）一并提为 public 常量，job 端原 service 保留这些常量，两边须保持一致。</p>
 */
@Builder
public record MarketStateSnapshot(
        String marketId,
        String question,
        String marketSlug,
        Long marketEndAt,
        String sourceCategory,
        String normalizedCategory,
        String marketLifecycle,
        boolean closed,
        boolean acceptingOrders,
        String resolvedOutcome,
        Date lastMetadataSyncAt
) {
    public static final String LIFECYCLE_ACTIVE = "ACTIVE";
    public static final String LIFECYCLE_CLOSED_UNRESOLVED = "CLOSED_UNRESOLVED";
    public static final String LIFECYCLE_RESOLVED = "RESOLVED";
    public static final String CATEGORY_OTHER = "OTHER";

    public static MarketStateSnapshot unknown(String marketId) {
        return MarketStateSnapshot.builder()
                .marketId(marketId)
                .normalizedCategory(CATEGORY_OTHER)
                .marketLifecycle(LIFECYCLE_ACTIVE)
                .acceptingOrders(true)
                .build();
    }

    public boolean isActive() {
        return LIFECYCLE_ACTIVE.equalsIgnoreCase(marketLifecycle);
    }

    public String blockingReasonCode() {
        if (LIFECYCLE_RESOLVED.equalsIgnoreCase(marketLifecycle)) {
            return "MARKET_RESOLVED";
        }
        if (!acceptingOrders) {
            return "MARKET_NOT_ACCEPTING_ORDERS";
        }
        return "MARKET_CLOSED";
    }

    public String blockingReasonMessage() {
        return switch (blockingReasonCode()) {
            case "MARKET_RESOLVED" -> "[MARKET_RESOLVED] Market is already resolved.";
            case "MARKET_NOT_ACCEPTING_ORDERS" -> "[MARKET_NOT_ACCEPTING_ORDERS] Market is no longer accepting orders.";
            default -> "[MARKET_CLOSED] Market is already closed.";
        };
    }
}
