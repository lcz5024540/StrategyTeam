package com.metaverse.coin.strategy.web.model.smartwallet;
import java.math.BigDecimal;
public record FollowTradeAttributionView(String groupKey, Integer orderCount, Integer winCount, Integer lossCount,
    BigDecimal winRate, BigDecimal realizedPnlUsdc, BigDecimal avgPnlUsdc) {}
