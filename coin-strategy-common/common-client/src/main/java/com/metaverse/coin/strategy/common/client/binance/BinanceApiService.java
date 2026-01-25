package com.metaverse.coin.strategy.common.client.binance;

import com.binance.connector.client.SpotClient;
import com.binance.connector.client.impl.SpotClientImpl;
import com.binance.connector.futures.client.impl.UMFuturesClientImpl;
import com.metaverse.coin.strategy.common.client.binance.service.*;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Getter
public class BinanceApiService {

    private final SpotClient spotClient;
    private final UMFuturesClientImpl futuresClient;

    private final BinanceSpotService spotService;
    private final BinanceFuturesService futuresService;
    private final BinanceWalletService walletService;
    private final BinanceMarginService marginService;
    private final BinanceMarketDataService marketDataService;
    private final BinanceAlgoService algoService;
    private final BinanceCopyTradingService copyTradingService;

    public BinanceApiService(String apiKey, String secretKey) {
        this(apiKey, secretKey, null, null);
    }

    public BinanceApiService(String apiKey, String secretKey, String spotBaseUrl, String futuresBaseUrl) {
        this.spotClient = new SpotClientImpl(apiKey, secretKey, spotBaseUrl);
        this.futuresClient = new UMFuturesClientImpl(apiKey, secretKey, futuresBaseUrl);

        this.spotService = new BinanceSpotService(spotClient);
        this.futuresService = new BinanceFuturesService(futuresClient);
        this.walletService = new BinanceWalletService(spotClient);
        this.marginService = new BinanceMarginService(spotClient);
        this.marketDataService = new BinanceMarketDataService(spotClient);
        this.algoService = new BinanceAlgoService(spotClient);
        this.copyTradingService = new BinanceCopyTradingService(spotClient);
    }

    // ================= DELEGATED METHODS (For backward compatibility) =================

    // ... (Optional: You can keep old methods calling the new services, or remove them. 
    // For now, I'll keep the key ones to avoid breaking existing tests immediately, 
    // or I can update the tests to use the services. Updating tests is better.)

    // ================= SPOT API =================

    public String checkServerTime() {
        return spotService.checkServerTime();
    }

    public String account() {
        return spotService.account();
    }

    public String newOrder(String symbol, String side, String type, String timeInForce, String quantity, String price) {
        return spotService.newOrder(symbol, side, type, timeInForce, quantity, price);
    }

    public String spotBuy(String symbol, String type, String quantity, String price) {
        return spotService.buy(symbol, type, quantity, price);
    }

    public String spotSell(String symbol, String type, String quantity, String price) {
        return spotService.sell(symbol, type, quantity, price);
    }

    public String getOrder(String symbol, Long orderId, String origClientOrderId) {
        return spotService.getOrder(symbol, orderId, origClientOrderId);
    }
    
    public String cancelOrder(String symbol, Long orderId, String origClientOrderId) {
        return spotService.cancelOrder(symbol, orderId, origClientOrderId);
    }

    public String klines(String symbol, String interval, Integer limit) {
        // Delegating to marketDataService is also an option, but SpotService is fine for now if it wraps Trade/Market
        // Actually, klines is Market Data. Let's use marketDataService if possible, or SpotService if I put it there.
        // I put it in MarketDataService. Let's redirect.
        return marketDataService.klines(symbol, interval, limit, null, null);
    }

    // ================= FUTURES API (USDS-M) =================

    public String checkFuturesServerTime() {
        return futuresService.checkServerTime();
    }

    public String getFuturesAccount() {
        return futuresService.account();
    }

    public String changeInitialLeverage(String symbol, Integer leverage) {
        return futuresService.changeInitialLeverage(symbol, leverage);
    }

    public String changeMarginType(String symbol, String marginType) {
        return futuresService.changeMarginType(symbol, marginType);
    }

    public String placeFuturesOrder(String symbol, String side, String positionSide, String type, 
                                    String timeInForce, String quantity, String price, Boolean reduceOnly) {
        return futuresService.placeOrder(symbol, side, positionSide, type, timeInForce, quantity, price, reduceOnly);
    }

    public String openLong(String symbol, String type, String quantity, String price, String timeInForce) {
        return futuresService.openLong(symbol, type, quantity, price, timeInForce);
    }

    public String closeLong(String symbol, String type, String quantity, String price) {
        return futuresService.closeLong(symbol, type, quantity, price);
    }

    public String openShort(String symbol, String type, String quantity, String price, String timeInForce) {
        return futuresService.openShort(symbol, type, quantity, price, timeInForce);
    }

    public String closeShort(String symbol, String type, String quantity, String price) {
        return futuresService.closeShort(symbol, type, quantity, price);
    }

    public String cancelFuturesOrder(String symbol, Long orderId, String origClientOrderId) {
        return futuresService.cancelOrder(symbol, orderId, origClientOrderId);
    }

    public String getFuturesOpenOrders(String symbol) {
        return futuresService.currentAllOpenOrders(symbol);
    }

    public String getFuturesTickerPrice(String symbol) {
        return futuresService.tickerPrice(symbol);
    }

    public String getFuturesFundingRate(String symbol) {
        // Using Premium Index to get the current/last funding rate
        return futuresService.getPremiumIndex(symbol);
    }
}
