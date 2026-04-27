package com.metaverse.coin.strategy.core.arbitrage.monitor;

import com.metaverse.coin.strategy.common.client.binance.BinanceApiService;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesPremiumIndex;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Funding Rate Monitor
 * Monitors funding rates across exchanges (currently focusing on Binance).
 */
@Service
@ConditionalOnExpression("'${binance.api.api-key:}' != '' && '${binance.api.secret-key:}' != ''")
@Slf4j
@RequiredArgsConstructor
public class SingleExchangeFundingRateMonitor {

    private final BinanceApiService binanceApiService;

    /**
     * Get real-time funding rates for all symbols.
     * @return Map of Symbol -> Funding Rate (e.g., "BTCUSDT" -> 0.0001)
     */
    public Map<String, BigDecimal> getBinanceFundingRates() {
        try {
            List<BinanceFuturesPremiumIndex> premiumIndices = binanceApiService.getFuturesFundingRates();
            if (premiumIndices == null) {
                return new HashMap<>();
            }
            return premiumIndices.stream()
                    .filter(i -> i.getSymbol() != null && i.getLastFundingRate() != null)
                    .collect(Collectors.toMap(
                            BinanceFuturesPremiumIndex::getSymbol,
                            BinanceFuturesPremiumIndex::getLastFundingRate,
                            (v1, v2) -> v1 // In case of duplicate keys, keep first
                    ));
        } catch (Exception e) {
            log.error("Failed to fetch Binance funding rates", e);
            return new HashMap<>();
        }
    }

    /**
     * Get specific symbol funding rate info including predicted rate if available.
     */
    public BinanceFuturesPremiumIndex getBinanceFundingRate(String symbol) {
        try {
            return binanceApiService.getFuturesFundingRate(symbol);
        } catch (Exception e) {
            log.error("Failed to fetch Binance funding rate for {}", symbol, e);
            return null;
        }
    }
}
