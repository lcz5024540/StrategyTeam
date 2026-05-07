package com.metaverse.coin.strategy.core.arbitrage.monitor;

import com.metaverse.coin.strategy.common.client.binance.BinanceApiService;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesOrderBook;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceOrderBook;
import com.metaverse.coin.strategy.core.arbitrage.config.SingleExchangeSpotFuturesProperties;
import com.metaverse.coin.strategy.core.arbitrage.market.BinanceQueryDataProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnExpression("'${binance.api.api-key:}' != '' && '${binance.api.secret-key:}' != ''")
@Slf4j
@RequiredArgsConstructor
public class SingleExchangeDepthMonitor {

    private static final int DEPTH_LIMIT = 20;

    private final BinanceApiService binanceApiService;
    private final SingleExchangeSpotFuturesProperties properties;
    @Autowired(required = false)
    private BinanceQueryDataProvider queryDataProvider;

    public SingleExchangeDepthSnapshot getDepthSnapshot(String symbol) {
        int retryCount = properties.getLiquidityRetryCount() == null || properties.getLiquidityRetryCount() <= 0
                ? 1
                : properties.getLiquidityRetryCount();
        long retryIntervalMillis = properties.getLiquidityRetryIntervalMillis() == null || properties.getLiquidityRetryIntervalMillis() < 0
                ? 0L
                : properties.getLiquidityRetryIntervalMillis();

        for (int attempt = 1; attempt <= retryCount; attempt++) {
            try {
                BinanceOrderBook spotOrderBook = getQueryDataProvider().getSpotOrderBook(symbol, DEPTH_LIMIT);
                BinanceFuturesOrderBook futuresOrderBook = getQueryDataProvider().getFuturesOrderBook(symbol, DEPTH_LIMIT);
                if (isValid(spotOrderBook) && isValid(futuresOrderBook)) {
                    return SingleExchangeDepthSnapshot.builder()
                            .fallbackUsed(false)
                            .spotOrderBook(spotOrderBook)
                            .futuresOrderBook(futuresOrderBook)
                            .build();
                }
                log.warn("Depth snapshot is incomplete for {} on attempt {}/{}", symbol, attempt, retryCount);
            } catch (Exception e) {
                log.warn("Failed to fetch depth snapshot for {} on attempt {}/{}: {}",
                        symbol, attempt, retryCount, e.getMessage());
            }

            if (attempt < retryCount && retryIntervalMillis > 0) {
                try {
                    Thread.sleep(retryIntervalMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        return SingleExchangeDepthSnapshot.builder()
                .fallbackUsed(true)
                .build();
    }

    private boolean isValid(BinanceOrderBook orderBook) {
        return orderBook != null
                && hasValidLevels(orderBook.getBids())
                && hasValidLevels(orderBook.getAsks());
    }

    private boolean isValid(BinanceFuturesOrderBook orderBook) {
        return orderBook != null
                && hasValidLevels(orderBook.getBids())
                && hasValidLevels(orderBook.getAsks());
    }

    private boolean hasValidLevels(List<List<String>> levels) {
        return levels != null
                && !levels.isEmpty()
                && levels.stream().anyMatch(level -> level != null && level.size() >= 2);
    }

    private BinanceQueryDataProvider getQueryDataProvider() {
        if (queryDataProvider != null) {
            return queryDataProvider;
        }
        return new com.metaverse.coin.strategy.core.arbitrage.market.HttpBinanceQueryDataProvider(binanceApiService);
    }
}
