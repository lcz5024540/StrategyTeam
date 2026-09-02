package com.metaverse.coin.strategy.web.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public final class FundingRateMatrixViews {

    private FundingRateMatrixViews() {
    }

    public record Envelope<T>(String asOf, String timezone, String source, boolean partial,
                              List<String> missingExchanges, T data) {
    }

    public record Matrix(List<String> exchanges, List<Row> items) {
    }

    public record Row(String symbol, Map<String, BigDecimal> ratesByExchange,
                      Map<String, Integer> intervalHoursByExchange,
                      String longExchange, String shortExchange, BigDecimal hourlyFundingSpread,
                      BigDecimal estimatedNetYield, BigDecimal executableNotional, String status,
                      String rejectionReason, String sampledAt) {
    }
}
