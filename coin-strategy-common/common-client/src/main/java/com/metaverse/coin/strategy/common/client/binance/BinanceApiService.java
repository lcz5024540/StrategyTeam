package com.metaverse.coin.strategy.common.client.binance;

import com.binance.connector.client.SpotClient;
import com.binance.connector.client.impl.SpotClientImpl;
import com.binance.connector.futures.client.impl.UMFuturesClientImpl;
import com.metaverse.coin.strategy.common.client.binance.service.*;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceAggTrade;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceAveragePrice;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceBookTicker;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceExchangeInfo;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceKline;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinancePriceTicker;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceTicker;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceTrade;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceOpenSymbol;
import com.metaverse.coin.strategy.common.model.binance.entity.margin.*;
import com.metaverse.coin.strategy.common.model.binance.entity.spot.BinanceAccountInfo;
import com.metaverse.coin.strategy.common.model.binance.entity.spot.BinanceOrder;
import com.metaverse.coin.strategy.common.model.binance.entity.spot.BinanceOrderList;
import com.metaverse.coin.strategy.common.model.binance.entity.spot.BinanceTradeHistory;
import com.metaverse.coin.strategy.common.model.binance.entity.wallet.BinanceApiKeyPermission;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

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
    private final BinanceCopyTradingService copyTradingService;
    private final BinanceMiningService miningService;
    private final BinanceSimpleEarnService simpleEarnService;
    private final BinanceAlphaService alphaService;

    public BinanceApiService(String apiKey, String secretKey) {
        this(apiKey, secretKey, null, null);
    }

    public BinanceApiService(String apiKey, String secretKey, String spotBaseUrl, String futuresBaseUrl) {
        this.spotClient = new SpotClientImpl(apiKey, secretKey, spotBaseUrl);
        this.futuresClient = new UMFuturesClientImpl(apiKey, secretKey, futuresBaseUrl);

        this.spotService = new BinanceSpotService(spotClient);
        this.futuresService = new BinanceFuturesService(futuresClient);
        this.walletService = new BinanceWalletService(spotClient, apiKey, secretKey, spotBaseUrl);
        this.marginService = new BinanceMarginService(spotClient, spotBaseUrl, apiKey, secretKey);
        this.marketDataService = new BinanceMarketDataService(spotClient);
        this.copyTradingService = new BinanceCopyTradingService(spotClient);
        this.miningService = new BinanceMiningService(spotBaseUrl, apiKey, secretKey);
        this.simpleEarnService = new BinanceSimpleEarnService(spotClient, spotBaseUrl, apiKey, secretKey);
        this.alphaService = new BinanceAlphaService(spotClient, spotBaseUrl, apiKey, secretKey);
    }

    // ================= DELEGATED METHODS (For backward compatibility) =================

    // ... (Optional: You can keep old methods calling the new services, or remove them. 
    // For now, I'll keep the key ones to avoid breaking existing tests immediately, 
    // or I can update the tests to use the services. Updating tests is better.)

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
