package com.metaverse.coin.strategy.web.model.smartwallet;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

public record SmartWalletMarketMonitorItemView(
    String marketId, String question, String marketSlug,
    String outcomePrices, Long marketEndAt, String marketEndAtBjt,
    String marketState, String marketLifecycle,
    String sourceCategory, String normalizedCategory,
    Date lastMetadataSyncAt, Boolean metadataStale,
    Date lastSignalAt, String lastSignalAtBjt,
    Integer signalCount, Integer uniqueWalletCount,
    Integer pendingTaskCount, Integer openPositionCount,
    BigDecimal openCostUsdc, String positionDirection, String positionDirectionCode,
    BigDecimal realizedPnlUsdc, BigDecimal unrealizedPnlUsdc,
    Boolean activeMarket, Boolean effectiveMarket,
    Boolean concentrationAvailable, Integer observedHolders,
    BigDecimal top1Pct, BigDecimal top3Pct, BigDecimal top5Pct,
    Integer hhi, Boolean highlyConcentrated, Boolean extremelyConcentrated,
    String concentrationBand, Date lastConcentrationSnapshotAt,
    Boolean concentrationStale, List<String> topHolderAddresses) {}
