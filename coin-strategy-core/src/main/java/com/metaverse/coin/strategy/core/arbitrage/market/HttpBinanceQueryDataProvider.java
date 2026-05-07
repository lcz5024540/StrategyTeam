package com.metaverse.coin.strategy.core.arbitrage.market;

import com.metaverse.coin.strategy.common.client.binance.BinanceApiService;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesAccount;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesOrder;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesOrderBook;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesPositionRisk;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesPremiumIndex;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceOrderBook;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinancePriceTicker;
import com.metaverse.coin.strategy.common.model.binance.entity.spot.BinanceOrder;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@ConditionalOnBean(BinanceApiService.class)
@ConditionalOnMissingBean(BinanceQueryDataProvider.class)
public class HttpBinanceQueryDataProvider implements BinanceQueryDataProvider {

    private final BinanceApiService binanceApiService;

    @Override
    public BinancePriceTicker getSpotPrice(String symbol) {
        return binanceApiService.getTickerPrice(symbol);
    }

    @Override
    public BinancePriceTicker getFuturesPrice(String symbol) {
        return binanceApiService.getFuturesTickerPrice(symbol);
    }

    @Override
    public BinanceFuturesPremiumIndex getPremiumIndex(String symbol) {
        return binanceApiService.getFuturesFundingRate(symbol);
    }

    @Override
    public List<BinanceFuturesPremiumIndex> getPremiumIndices() {
        return binanceApiService.getFuturesFundingRates();
    }

    @Override
    public BinanceOrderBook getSpotOrderBook(String symbol, Integer limit) {
        return binanceApiService.getMarketDataService().getOrderBook(symbol, limit);
    }

    @Override
    public BinanceFuturesOrderBook getFuturesOrderBook(String symbol, Integer limit) {
        return binanceApiService.getFuturesService().getOrderBook(symbol, limit);
    }

    @Override
    public BinanceOrder getSpotOrder(String symbol, Long orderId, String origClientOrderId) {
        return binanceApiService.getSpotOrder(symbol, orderId, origClientOrderId);
    }

    @Override
    public BinanceFuturesOrder getFuturesOrder(String symbol, Long orderId, String origClientOrderId) {
        return binanceApiService.getFuturesService().getOrder(symbol, orderId, origClientOrderId);
    }

    @Override
    public BinanceFuturesAccount getFuturesAccount() {
        return binanceApiService.getFuturesService().account();
    }

    @Override
    public List<BinanceFuturesPositionRisk> getPositionRisk(String symbol) {
        return binanceApiService.getFuturesService().getPositionRisk(symbol);
    }
}
