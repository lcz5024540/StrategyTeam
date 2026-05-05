package com.metaverse.coin.strategy.job.funding.service;

import com.metaverse.coin.strategy.common.client.binance.BinanceApiService;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesAccount;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesOrder;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesOrderBook;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesPositionRisk;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesPremiumIndex;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceOrderBook;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinancePriceTicker;
import com.metaverse.coin.strategy.common.model.binance.entity.spot.BinanceOrder;
import com.metaverse.coin.strategy.core.arbitrage.market.BinanceQueryDataProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Primary
@Slf4j
@RequiredArgsConstructor
@ConditionalOnBean(BinanceApiService.class)
public class BinanceWebSocketQueryDataProvider implements BinanceQueryDataProvider {

    private final BinanceWebSocketProperties properties;
    private final BinanceWebSocketStreamService streamService;
    private final BinanceApiService binanceApiService;

    @Override
    public BinancePriceTicker getSpotPrice(String symbol) {
        BinancePriceTicker cached = properties.isEnabled() ? streamService.getCachedSpotPrice(symbol) : null;
        if (cached != null) {
            return cached;
        }
        BinancePriceTicker fallback = fallback("spot price", () -> binanceApiService.getTickerPrice(symbol));
        streamService.cacheSpotPrice(fallback);
        return fallback;
    }

    @Override
    public BinancePriceTicker getFuturesPrice(String symbol) {
        BinancePriceTicker cached = properties.isEnabled() ? streamService.getCachedFuturesPrice(symbol) : null;
        if (cached != null) {
            return cached;
        }
        BinancePriceTicker fallback = fallback("futures price", () -> binanceApiService.getFuturesTickerPrice(symbol));
        streamService.cacheFuturesPrice(fallback);
        return fallback;
    }

    @Override
    public BinanceFuturesPremiumIndex getPremiumIndex(String symbol) {
        BinanceFuturesPremiumIndex cached = properties.isEnabled() ? streamService.getCachedPremiumIndex(symbol) : null;
        if (cached != null) {
            return cached;
        }
        BinanceFuturesPremiumIndex fallback = fallback("premium index", () -> binanceApiService.getFuturesFundingRate(symbol));
        streamService.cachePremiumIndex(fallback);
        return fallback;
    }

    @Override
    public List<BinanceFuturesPremiumIndex> getPremiumIndices() {
        List<BinanceFuturesPremiumIndex> cached = properties.isEnabled() ? streamService.getCachedPremiumIndices() : List.of();
        if (!cached.isEmpty()) {
            return cached;
        }
        List<BinanceFuturesPremiumIndex> fallback = fallback("premium indices", binanceApiService::getFuturesFundingRates);
        if (fallback != null) {
            fallback.forEach(streamService::cachePremiumIndex);
        }
        return fallback;
    }

    @Override
    public BinanceOrderBook getSpotOrderBook(String symbol, Integer limit) {
        BinanceOrderBook cached = properties.isEnabled() ? streamService.getCachedSpotOrderBook(symbol) : null;
        if (cached != null) {
            return cached;
        }
        BinanceOrderBook fallback = fallback("spot depth", () -> binanceApiService.getMarketDataService().getOrderBook(symbol, limit));
        streamService.cacheSpotOrderBook(symbol, fallback);
        return fallback;
    }

    @Override
    public BinanceFuturesOrderBook getFuturesOrderBook(String symbol, Integer limit) {
        BinanceFuturesOrderBook cached = properties.isEnabled() ? streamService.getCachedFuturesOrderBook(symbol) : null;
        if (cached != null) {
            return cached;
        }
        BinanceFuturesOrderBook fallback = fallback("futures depth", () -> binanceApiService.getFuturesService().getOrderBook(symbol, limit));
        streamService.cacheFuturesOrderBook(symbol, fallback);
        return fallback;
    }

    @Override
    public BinanceOrder getSpotOrder(String symbol, Long orderId, String origClientOrderId) {
        BinanceOrder cached = properties.isEnabled() ? streamService.getCachedSpotOrder(symbol, orderId, origClientOrderId) : null;
        if (cached != null) {
            return cached;
        }
        BinanceOrder fallback = fallback("spot order", () -> binanceApiService.getSpotOrder(symbol, orderId, origClientOrderId));
        streamService.cacheSpotOrder(fallback);
        return fallback;
    }

    @Override
    public BinanceFuturesOrder getFuturesOrder(String symbol, Long orderId, String origClientOrderId) {
        BinanceFuturesOrder cached = properties.isEnabled() ? streamService.getCachedFuturesOrder(symbol, orderId, origClientOrderId) : null;
        if (cached != null) {
            return cached;
        }
        BinanceFuturesOrder fallback = fallback("futures order", () -> binanceApiService.getFuturesService().getOrder(symbol, orderId, origClientOrderId));
        streamService.cacheFuturesOrder(fallback);
        return fallback;
    }

    @Override
    public BinanceFuturesAccount getFuturesAccount() {
        BinanceFuturesAccount cached = properties.isEnabled() ? streamService.getCachedFuturesAccount() : null;
        if (cached != null) {
            return cached;
        }
        BinanceFuturesAccount fallback = fallback("futures account", () -> binanceApiService.getFuturesService().account());
        streamService.cacheFuturesAccount(fallback);
        return fallback;
    }

    @Override
    public List<BinanceFuturesPositionRisk> getPositionRisk(String symbol) {
        List<BinanceFuturesPositionRisk> cached = properties.isEnabled() ? streamService.getCachedPositionRisk(symbol) : null;
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        List<BinanceFuturesPositionRisk> fallback = fallback("position risk", () -> binanceApiService.getFuturesService().getPositionRisk(symbol));
        streamService.cachePositionRisk(symbol, fallback);
        return fallback;
    }

    private <T> T fallback(String label, FallbackSupplier<T> supplier) {
        if (!properties.isHttpFallbackEnabled()) {
            throw new IllegalStateException("Binance websocket cache miss for " + label + " and HTTP fallback is disabled");
        }
        log.debug("Binance websocket cache miss/stale for {}, using HTTP fallback", label);
        return supplier.get();
    }

    private interface FallbackSupplier<T> {
        T get();
    }
}
