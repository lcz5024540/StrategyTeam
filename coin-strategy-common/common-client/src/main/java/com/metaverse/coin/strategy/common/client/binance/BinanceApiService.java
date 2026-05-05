package com.metaverse.coin.strategy.common.client.binance;

import com.binance.connector.client.SpotClient;
import com.binance.connector.client.impl.SpotClientImpl;
import com.binance.connector.futures.client.impl.CMFuturesClientImpl;
import com.binance.connector.futures.client.impl.UMFuturesClientImpl;
import com.metaverse.coin.strategy.common.client.binance.service.*;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesAccount;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesOrder;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesPositionRisk;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesPremiumIndex;
import com.metaverse.coin.strategy.common.model.binance.entity.margin.*;
import com.metaverse.coin.strategy.common.model.binance.entity.market.*;
import com.metaverse.coin.strategy.common.model.binance.entity.spot.BinanceAccountInfo;
import com.metaverse.coin.strategy.common.model.binance.entity.spot.BinanceOrder;
import com.metaverse.coin.strategy.common.model.binance.entity.spot.BinanceTradeHistory;
import com.metaverse.coin.strategy.common.model.binance.entity.wallet.BinanceApiKeyPermission;
import com.metaverse.coin.strategy.common.model.binance.entity.wallet.BinanceUniversalTransferResult;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Credentials;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

@Slf4j
@Getter
public class BinanceApiService {

    private final SpotClient spotClient;
    private final UMFuturesClientImpl futuresClient;
    private final CMFuturesClientImpl coinFuturesClient;

    private final BinanceSpotService spotService;
    private final BinanceFuturesService futuresService;
    private final BinanceCoinFuturesService coinFuturesService;
    private final BinanceWalletService walletService;
    private final BinanceMarginService marginService;
    private final BinanceMarketDataService marketDataService;
    private final BinanceCopyTradingService copyTradingService;
    private final BinanceMiningService miningService;
    private final BinanceSimpleEarnService simpleEarnService;
    private final BinanceAlphaService alphaService;

    public BinanceApiService(String apiKey, String secretKey) {
        this(apiKey, secretKey, null, null);
    }

    public BinanceApiService(String apiKey, String secretKey, String spotBaseUrl, String futuresBaseUrl) {
        this(apiKey, secretKey, spotBaseUrl, futuresBaseUrl, null, 0, null, null, null);
    }

    public BinanceApiService(String apiKey,
                             String secretKey,
                             String spotBaseUrl,
                             String futuresBaseUrl,
                             String proxyHost,
                             int proxyPort,
                             String proxyType,
                             String proxyUsername,
                             String proxyPassword) {
        SpotClientImpl spotClient = new SpotClientImpl(apiKey, secretKey, spotBaseUrl);
        UMFuturesClientImpl futuresClient = new UMFuturesClientImpl(apiKey, secretKey, futuresBaseUrl);
        CMFuturesClientImpl coinFuturesClient = new CMFuturesClientImpl(apiKey, secretKey, futuresBaseUrl != null ? futuresBaseUrl : "https://dapi.binance.com");
        applyProxyIfConfigured(spotClient, futuresClient, coinFuturesClient, proxyHost, proxyPort, proxyType, proxyUsername, proxyPassword);

        this.spotClient = spotClient;
        this.futuresClient = futuresClient;
        this.coinFuturesClient = coinFuturesClient;

        this.spotService = new BinanceSpotService(spotClient, apiKey, spotBaseUrl, null);
        this.futuresService = new BinanceFuturesService(futuresClient, futuresBaseUrl, apiKey, secretKey);
        this.coinFuturesService = new BinanceCoinFuturesService(coinFuturesClient, futuresBaseUrl != null ? futuresBaseUrl : "https://dapi.binance.com", apiKey, secretKey);
        this.walletService = new BinanceWalletService(spotClient, apiKey, secretKey, spotBaseUrl);
        this.marginService = new BinanceMarginService(spotClient, spotBaseUrl, apiKey, secretKey);
        this.marketDataService = new BinanceMarketDataService(spotClient);
        this.copyTradingService = new BinanceCopyTradingService(spotClient);
        this.miningService = new BinanceMiningService(spotBaseUrl, apiKey, secretKey);
        this.simpleEarnService = new BinanceSimpleEarnService(spotClient, spotBaseUrl, apiKey, secretKey);
        this.alphaService = new BinanceAlphaService(spotClient, spotBaseUrl, apiKey, secretKey);
    }

    private static void applyProxyIfConfigured(SpotClientImpl spotClient,
                                               UMFuturesClientImpl futuresClient,
                                               CMFuturesClientImpl coinFuturesClient,
                                               String proxyHost,
                                               int proxyPort,
                                               String proxyType,
                                               String proxyUsername,
                                               String proxyPassword) {
        if (!hasText(proxyHost)) {
            return;
        }

        ProxySettings proxySettings = normalizeProxySettings(proxyHost, proxyPort, proxyType);
        Proxy proxy = new Proxy(proxySettings.type(), new InetSocketAddress(proxySettings.host(), proxySettings.port()));
        okhttp3.Authenticator authenticator = buildProxyAuthenticator(proxyUsername, proxyPassword);

        spotClient.setProxy(new com.binance.connector.client.utils.ProxyAuth(proxy, authenticator));
        futuresClient.setProxy(new com.binance.connector.futures.client.utils.ProxyAuth(proxy, authenticator));
        coinFuturesClient.setProxy(new com.binance.connector.futures.client.utils.ProxyAuth(proxy, authenticator));

        log.info("Configured Binance clients to use {} proxy {}:{}", proxy.type(), proxySettings.host(), proxySettings.port());
    }

    private static Proxy.Type resolveProxyType(String proxyType) {
        if ("SOCKS".equalsIgnoreCase(proxyType) || "SOCKS5".equalsIgnoreCase(proxyType)) {
            return Proxy.Type.SOCKS;
        }
        return Proxy.Type.HTTP;
    }

    private static okhttp3.Authenticator buildProxyAuthenticator(String proxyUsername, String proxyPassword) {
        if (!hasText(proxyUsername)) {
            return (route, response) -> null;
        }
        String password = proxyPassword == null ? "" : proxyPassword;
        return (route, response) -> response.request().newBuilder()
                .header("Proxy-Authorization", Credentials.basic(proxyUsername, password))
                .build();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static ProxySettings normalizeProxySettings(String proxyHost, int proxyPort, String proxyType) {
        String trimmedProxyHost = proxyHost.trim();
        if (!trimmedProxyHost.contains("://")) {
            if (proxyPort <= 0) {
                throw new IllegalArgumentException("Proxy port must be greater than 0 when proxy-host does not include a port");
            }
            return new ProxySettings(trimmedProxyHost, proxyPort, resolveProxyType(proxyType));
        }

        try {
            URI proxyUri = new URI(trimmedProxyHost);
            String normalizedHost = proxyUri.getHost();
            int normalizedPort = proxyUri.getPort() > 0 ? proxyUri.getPort() : proxyPort;
            if (!hasText(normalizedHost)) {
                throw new IllegalArgumentException("Proxy host is empty");
            }
            if (normalizedPort <= 0) {
                throw new IllegalArgumentException("Proxy port is missing");
            }
            Proxy.Type normalizedType = hasText(proxyUri.getScheme()) ? resolveProxyType(proxyUri.getScheme()) : resolveProxyType(proxyType);
            return new ProxySettings(normalizedHost, normalizedPort, normalizedType);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid proxy host: " + proxyHost, e);
        }
    }

    private record ProxySettings(String host, int port, Proxy.Type type) {
    }

    // ================= DELEGATED METHODS (For backward compatibility) =================

    // ================= SPOT API =================

    public String ping() {
        return marketDataService.ping();
    }

    public String checkServerTime() {
        return spotService.checkServerTime();
    }

    public BinanceExchangeInfo exchangeInfo(String symbol, List<String> symbols) {
        return marketDataService.getExchangeInfo(symbol, symbols);
    }
    
    public BinanceExchangeInfo exchangeInfo() {
        return marketDataService.getExchangeInfo(null, null);
    }

    public BinanceAccountInfo account() {
        return spotService.account();
    }

    public BinanceAccountInfo getAccountInfo() {
        return spotService.account();
    }
    
    public BinanceOrder placeSpotOrder(String symbol, String side, String type, String timeInForce, String quantity, String price) {
        return spotService.placeOrder(symbol, side, type, timeInForce, quantity, price);
    }

    public String testSpotOrder(String symbol, String side, String type, String timeInForce, String quantity, String price) {
        return spotService.testNewOrder(symbol, side, type, timeInForce, quantity, price);
    }
    
    public BinanceOrder getSpotOrder(String symbol, Long orderId, String origClientOrderId) {
        return spotService.getOrderObject(symbol, orderId, origClientOrderId);
    }
    
    public BinanceOrder cancelSpotOrder(String symbol, Long orderId, String origClientOrderId) {
        return spotService.cancelOrderObject(symbol, orderId, origClientOrderId);
    }
    
    public List<BinanceOrder> cancelAllSpotOpenOrders(String symbol) {
        return spotService.cancelAllOpenOrders(symbol);
    }
    
    public List<BinanceOrder> getSpotOpenOrders(String symbol) {
        return spotService.getOpenOrders(symbol);
    }
    
    public List<BinanceOrder> getAllSpotOrders(String symbol, Long orderId, Integer limit, Long startTime, Long endTime) {
        return spotService.getAllOrders(symbol, orderId, limit, startTime, endTime);
    }
    
    public List<BinanceTradeHistory> getSpotMyTrades(String symbol, Long orderId, Integer limit, Long fromId, Long startTime, Long endTime) {
        return spotService.getMyTrades(symbol, orderId, limit, fromId, startTime, endTime);
    }

    public String startSpotUserDataStream() {
        return spotService.startUserDataStream();
    }

    public void keepAliveSpotUserDataStream(String listenKey) {
        spotService.keepAliveUserDataStream(listenKey);
    }

    public void closeSpotUserDataStream(String listenKey) {
        spotService.closeUserDataStream(listenKey);
    }

    public BinanceOrder newOrder(String symbol, String side, String type, String timeInForce, String quantity, String price) {
        return spotService.placeOrder(symbol, side, type, timeInForce, quantity, price);
    }

    public BinanceOrder spotBuy(String symbol, String type, String quantity, String price) {
        return spotService.placeOrder(symbol, "BUY", type, null, quantity, price);
    }

    public BinanceOrder spotSell(String symbol, String type, String quantity, String price) {
        return spotService.placeOrder(symbol, "SELL", type, null, quantity, price);
    }

    public BinanceOrder getOrder(String symbol, Long orderId, String origClientOrderId) {
        return spotService.getOrderObject(symbol, orderId, origClientOrderId);
    }
    
    public BinanceOrder cancelOrder(String symbol, Long orderId, String origClientOrderId) {
        return spotService.cancelOrderObject(symbol, orderId, origClientOrderId);
    }

    public List<BinanceOrder> openOrders(String symbol) {
        return spotService.getOpenOrders(symbol);
    }

    public List<BinanceTrade> getTrades(String symbol, Integer limit) {
        return marketDataService.getTrades(symbol, limit);
    }

    public List<BinanceTrade> getHistoricalTrades(String symbol, Integer limit, Long fromId) {
        return marketDataService.getHistoricalTrades(symbol, limit, fromId);
    }

    public List<BinanceAggTrade> getAggTrades(String symbol, String fromId, Long startTime, Long endTime, Integer limit) {
        return marketDataService.getAggTrades(symbol, fromId, startTime, endTime, limit);
    }
    
    public BinanceTicker getTicker(String symbol, String windowSize) {
        return marketDataService.getTicker(symbol, windowSize);
    }

    public List<BinanceKline> klines(String symbol, String interval, Integer limit) {
        return marketDataService.getKlines(symbol, interval, limit, null, null);
    }

    public BinanceTicker getTradingDayTicker(String symbol, String timeZone) {
        return marketDataService.getTradingDayTicker(symbol, timeZone);
    }
    
    public List<BinanceTicker> getTradingDayTickers(List<String> symbols, String timeZone) {
        return marketDataService.getTradingDayTickers(symbols, timeZone);
    }

    public List<BinanceKline> getUiKlines(String symbol, String interval, Integer limit) {
        return marketDataService.getUiKlines(symbol, interval, limit, null, null);
    }

    public BinanceAveragePrice getAveragePrice(String symbol) {
        return marketDataService.getAveragePrice(symbol);
    }

    public BinanceTicker getTicker24H(String symbol) {
        return marketDataService.getTicker24H(symbol);
    }

    public List<BinanceTicker> getAllTicker24H() {
        return marketDataService.getAllTicker24H();
    }
    
    public BinancePriceTicker getTickerPrice(String symbol) {
        return marketDataService.getTickerPrice(symbol);
    }
    
    public List<BinancePriceTicker> getAllTickerPrice() {
        return marketDataService.getAllTickerPrice();
    }
    
    public BinanceBookTicker getTickerBook(String symbol) {
        return marketDataService.getTickerBook(symbol);
    }
    
    public List<BinanceBookTicker> getAllTickerBook() {
        return marketDataService.getAllTickerBook();
    }
    
    public List<BinanceOpenSymbol> getOpenSymbolList() {
        return marketDataService.getOpenSymbolList();
    }

    public BinanceApiKeyPermission getApiKeyPermission() {
        return walletService.getApiKeyPermission();
    }

    public BinanceUniversalTransferResult universalTransfer(String type, String asset, String amount) {
        return walletService.universalTransfer(type, asset, amount, null, null);
    }

    public BinanceUniversalTransferResult universalTransfer(String type, String asset, String amount, String fromSymbol, String toSymbol) {
        return walletService.universalTransfer(type, asset, amount, fromSymbol, toSymbol);
    }

    // ================= FUTURES API (USDS-M) =================

    public Long checkFuturesServerTime() {
        return futuresService.checkServerTime();
    }

    public BinanceFuturesAccount getFuturesAccount() {
        return futuresService.account();
    }

    public BinanceFuturesPositionRisk changeInitialLeverage(String symbol, Integer leverage) {
        return futuresService.changeInitialLeverage(symbol, leverage);
    }

    public String changeMarginType(String symbol, String marginType) {
        return futuresService.changeMarginType(symbol, marginType);
    }

    public BinanceFuturesOrder placeFuturesOrder(String symbol, String side, String positionSide, String type, 
                                    String timeInForce, String quantity, String price, Boolean reduceOnly) {
        return futuresService.placeOrder(symbol, side, positionSide, type, timeInForce, quantity, price, reduceOnly);
    }

    public void testFuturesOrder(String symbol, String side, String positionSide, String type,
                                 String timeInForce, String quantity, String price, Boolean reduceOnly) {
        futuresService.testNewOrder(symbol, side, positionSide, type, timeInForce, quantity, price, reduceOnly);
    }

    public BinanceFuturesOrder openLong(String symbol, String type, String quantity, String price, String timeInForce) {
        return futuresService.openLong(symbol, type, quantity, price, timeInForce);
    }

    public BinanceFuturesOrder closeLong(String symbol, String type, String quantity, String price) {
        return futuresService.closeLong(symbol, type, quantity, price);
    }

    public BinanceFuturesOrder openShort(String symbol, String type, String quantity, String price, String timeInForce) {
        return futuresService.openShort(symbol, type, quantity, price, timeInForce);
    }

    public BinanceFuturesOrder closeShort(String symbol, String type, String quantity, String price) {
        return futuresService.closeShort(symbol, type, quantity, price);
    }

    public BinanceFuturesOrder cancelFuturesOrder(String symbol, Long orderId, String origClientOrderId) {
        return futuresService.cancelOrder(symbol, orderId, origClientOrderId);
    }

    public List<BinanceFuturesOrder> getFuturesOpenOrders(String symbol) {
        return futuresService.getOpenOrders(symbol);
    }

    public BinancePriceTicker getFuturesTickerPrice(String symbol) {
        return futuresService.getTickerPrice(symbol);
    }

    public BinanceFuturesPremiumIndex getFuturesFundingRate(String symbol) {
        // Using Premium Index to get the current/last funding rate
        return futuresService.getPremiumIndex(symbol);
    }

    public List<BinanceFuturesPremiumIndex> getFuturesFundingRates() {
        return futuresService.getPremiumIndices();
    }

    // ================= MARGIN API =================

    public BinanceCrossMarginAccount getCrossMarginAccountDetails() {
        return marginService.getCrossMarginAccountDetails();
    }

    public BinanceIsolatedMarginAccount getIsolatedMarginAccountInfo(String symbols) {
        return marginService.getIsolatedMarginAccountInfo(symbols);
    }

    public String adjustCrossMarginMaxLeverage(Integer maxLeverage) {
        return marginService.adjustCrossMarginMaxLeverage(maxLeverage);
    }

    public String disableIsolatedMarginAccount(String symbol) {
        return marginService.disableIsolatedMarginAccount(symbol);
    }

    public String enableIsolatedMarginAccount(String symbol) {
        return marginService.enableIsolatedMarginAccount(symbol);
    }

    public List<BinanceBnbBurnStatus> getBnbBurnStatus() {
        return marginService.getBnbBurnStatus();
    }

    public BinanceMarginTradeCoeff getMarginTradeCoeff() {
        return marginService.getMarginTradeCoeff();
    }

    public List<BinanceMarginCapitalFlow> getMarginCapitalFlow(String asset, String symbol, String type, Long startTime, Long endTime, Long fromId, Integer limit) {
        return marginService.getCapitalFlow(asset, symbol, type, startTime, endTime, fromId, limit);
    }

    public List<BinanceMarginFeeData> getCrossMarginFeeData(String coin, Integer vipLevel) {
        return marginService.getCrossMarginFeeData(coin, vipLevel);
    }

    public List<BinanceIsolatedMarginFeeData> getIsolatedMarginFeeData(String symbol, Integer vipLevel) {
        return marginService.getIsolatedMarginFeeData(symbol, vipLevel);
    }

    public BinanceIsolatedMarginAccountLimit getIsolatedMarginAccountLimit() {
        return marginService.getIsolatedMarginAccountLimit();
    }

    public BinanceMarginTransaction marginBorrow(String asset, String amount, String symbol, Boolean isIsolated) {
        return marginService.borrow(asset, amount, symbol, isIsolated);
    }

    public BinanceMarginTransaction marginRepay(String asset, String amount, String symbol, Boolean isIsolated) {
        return marginService.repay(asset, amount, symbol, isIsolated);
    }

    public List<BinanceMarginBorrowRepayRecord> getMarginBorrowRepayRecords(String asset, String isolatedSymbol, Long txId, Long startTime, Long endTime, Integer current, Integer size, String type) {
        return marginService.getBorrowRepayRecords(asset, isolatedSymbol, txId, startTime, endTime, current, size, type);
    }

    public List<BinanceMarginInterestHistory> getMarginInterestHistory(String asset, String isolatedSymbol, Long startTime, Long endTime, Integer current, Integer size) {
        return marginService.getInterestHistory(asset, isolatedSymbol, startTime, endTime, current, size);
    }

    public List<BinanceMarginInterestRateHistory> getMarginInterestRateHistory(String asset, Integer vipLevel, Long startTime, Long endTime) {
        return marginService.getInterestRateHistory(asset, vipLevel, startTime, endTime);
    }

    public List<BinanceMarginNextHourlyInterestRate> getMarginFutureHourlyInterestRate(String assets, Boolean isIsolated) {
        return marginService.getFutureHourlyInterestRate(assets, isIsolated);
    }

    public BinanceMarginMaxBorrowable getMarginMaxBorrowable(String asset, String isolatedSymbol) {
        return marginService.getMaxBorrowable(asset, isolatedSymbol);
    }

    public List<BinanceMarginAsset> getMarginAssets() {
        return marginService.getMarginAssets();
    }

    public List<BinanceMarginPair> getMarginPairs() {
        return marginService.getMarginPairs();
    }

    public BinanceMarginPriceIndex getMarginPriceIndex(String symbol) {
        return marginService.getMarginPriceIndex(symbol);
    }

    public List<BinanceIsolatedMarginSymbol> getIsolatedMarginSymbols(String symbol) {
        return marginService.getIsolatedMarginSymbols(symbol);
    }

    public BinanceIsolatedMarginSymbol getIsolatedMarginSymbol(String symbol) {
        return marginService.getIsolatedMarginSymbol(symbol);
    }

    public List<BinanceCrossMarginCollateralRatio> getCrossMarginCollateralRatio() {
        return marginService.getCrossMarginCollateralRatio();
    }

    public List<BinanceMarginDelistSchedule> getMarginDelistSchedule() {
        return marginService.getDelistSchedule();
    }

    public BinanceMarginOrder marginNewOrder(String symbol, String side, String type, String quantity, String price, String isIsolated, String sideEffectType, String timeInForce) {
        return marginService.newOrder(symbol, side, type, quantity, price, isIsolated, sideEffectType, timeInForce);
    }

    public BinanceMarginOrder marginCancelOrder(String symbol, Long orderId, String origClientOrderId, Boolean isIsolated) {
        return marginService.cancelOrder(symbol, orderId, origClientOrderId, isIsolated);
    }

    public List<BinanceMarginOrder> getMarginOpenOrders(String symbol, Boolean isIsolated) {
        return marginService.getOpenOrders(symbol, isIsolated);
    }

    public List<BinanceMarginOrder> getMarginAllOrders(String symbol, Boolean isIsolated, Long startTime, Long endTime, Integer limit) {
        return marginService.getAllOrders(symbol, isIsolated, startTime, endTime, limit);
    }

    public List<BinanceMarginForceLiquidationRecord> getMarginForceLiquidationRecord(Long startTime, Long endTime, String isolatedSymbol, Integer current, Integer size) {
        return marginService.getForceLiquidationRecord(startTime, endTime, isolatedSymbol, current, size);
    }

    public BinanceMarginOrder getMarginOrder(String symbol, Long orderId, String origClientOrderId, Boolean isIsolated) {
        return marginService.getOrder(symbol, orderId, origClientOrderId, isIsolated);
    }

    public List<BinanceMarginTrade> getMarginMyTrades(String symbol, Boolean isIsolated, Long startTime, Long endTime, Long fromId, Integer limit) {
        return marginService.getMyTrades(symbol, isIsolated, startTime, endTime, fromId, limit);
    }

    public String startMarginUserDataStream() {
        return marginService.startUserDataStream();
    }

    public String keepAliveMarginUserDataStream(String listenKey) {
        return marginService.keepAliveUserDataStream(listenKey);
    }

    public String closeMarginUserDataStream(String listenKey) {
        return marginService.closeUserDataStream(listenKey);
    }
}
