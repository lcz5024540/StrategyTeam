package com.metaverse.coin.strategy.common.client.binance.debug;

import com.metaverse.coin.strategy.common.client.binance.BinanceApiService;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceExchangeInfo;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceKline;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinancePriceTicker;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceTicker;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceTrade;
import com.metaverse.coin.strategy.common.model.binance.entity.spot.BinanceAccountInfo;
import com.metaverse.coin.strategy.common.model.binance.entity.spot.BinanceOrder;
import com.metaverse.coin.strategy.common.model.binance.entity.wallet.BinanceApiKeyPermission;
import com.metaverse.coin.strategy.common.model.binance.entity.alpha.BinanceAlphaOrder;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.*;
import com.metaverse.coin.strategy.common.model.binance.entity.simpleearn.*;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
public class BinanceApiServiceTest {

    // TODO: Please replace with your actual API Key and Secret Key
    // You can use Binance Testnet keys for safety: https://testnet.binance.vision/
    private static final String API_KEY = "";
    private static final String SECRET_KEY = "";

    // Testnet URLs
    //private static final String SPOT_BASE_URL = "https://testnet.binance.vision";
    //private static final String SPOT_BASE_URL = "https://api.binance.com";
    private static final String SPOT_BASE_URL = "https://api1.binance.com";
    private static final String FUTURES_BASE_URL = "https://fapi.binance.com";
    //private static final String FUTURES_BASE_URL = "https://testnet.binancefuture.com";

    private BinanceApiService binanceApiService;

    @BeforeEach
    public void setUp() {
        // Initialize with Testnet URLs
        binanceApiService = new BinanceApiService(API_KEY, SECRET_KEY, SPOT_BASE_URL, FUTURES_BASE_URL);
    }

    // ================= SPOT API TESTS =================

    @Test
    @Disabled("Integration test - requires network")
    public void testCheckServerTime() {
        String serverTime = binanceApiService.checkServerTime();
        log.info("Spot Server Time: {}", serverTime);
        assertNotNull(serverTime);
    }

    @Test
    @Disabled("Integration test - requires network")
    public void testPing() {
        String ping = binanceApiService.ping();
        log.info("Spot Ping: {}", ping);
    }

    @Test
    @Disabled("Integration test - requires network")
    public void testExchangeInfo() {
        BinanceExchangeInfo exchangeInfo = binanceApiService.exchangeInfo();
        // log.info("Exchange Info: {}", exchangeInfo); // Too large to log
        assertNotNull(exchangeInfo);
        
        // Test with specific symbol
        BinanceExchangeInfo symbolInfo = binanceApiService.exchangeInfo("BTCUSDT", null);
        log.info("Symbol Info (BTCUSDT): {}", symbolInfo);
        assertNotNull(symbolInfo);
    }
    
    @Test
    @Disabled("Integration test - requires network")
    public void testMarketDataEndpoints() {
        // Trades
        List<BinanceTrade> trades = binanceApiService.getTrades("BTCUSDT", 5);
        log.info("Trades: {}", trades);
        assertNotNull(trades);

        // Ticker
        BinanceTicker ticker = binanceApiService.getTicker("BTCUSDT", "1h");
        log.info("Ticker (1h): {}", ticker);
        assertNotNull(ticker);
    }

    @Test
    @Disabled("Integration test - requires network")
    public void testAccount() {
        BinanceAccountInfo accountInfo = binanceApiService.account();
        log.info("Spot Account Info: {}", accountInfo);
        assertNotNull(accountInfo);
    }

    @Test
    @org.junit.jupiter.api.Disabled("Requires valid API keys and network access")
    public void testApiKeyPermission() {
        BinanceApiKeyPermission permission = binanceApiService.getApiKeyPermission();
        log.info("API Key Permission: {}", permission);
        assertNotNull(permission);
    }

    @Test
    @Disabled("Integration test - requires network")
    public void testKlines() {
        // Symbol: BTCUSDT, Interval: 1h, Limit: 10
        List<BinanceKline> klines = binanceApiService.klines("BTCUSDT", "1h", 10);
        log.info("Spot Klines: {}", klines);
        assertNotNull(klines);
        // assertTrue(!klines.isEmpty()); // May be empty if testnet has no data or other issues, but typically shouldn't be
    }

    @Test
    @Disabled("Integration test - requires network")
    public void testSpotOrderLifecycle() {
        // 1. Place a Limit Order
        // Note: Prices and Quantities must be valid for the symbol on Testnet
        String symbol = "BTCUSDT";
        String side = "BUY";
        String type = "LIMIT";
        String timeInForce = "GTC";
        String quantity = "0.0001";
        String price = "85000"; // Ensure this price is away from market to not fill immediately if you want to cancel

        log.info("Placing Spot Order...");
        BinanceOrder orderResponse = binanceApiService.newOrder(symbol, side, type, timeInForce, quantity, price);
        log.info("Place Order Response: {}", orderResponse);
        assertNotNull(orderResponse);

        // Extract Order ID
        Long orderId = orderResponse.getOrderId();
        log.info("Order ID: {}", orderId);
        
        // 2. Query the Order
        if (orderId != null) {
            // ... implementation to query or cancel using ID
        }
        
        // To make this test runnable and cancel the order, we would need to parse the response.
        // For now, we will demonstrate the cancel call with a dummy ID or comment it out.
        
        log.info("Canceling Spot Order...");
        if (orderId != null) {
            BinanceOrder cancelResponse = binanceApiService.cancelOrder(symbol, orderId, null);
            log.info("Cancel Order Response: {}", cancelResponse);
        }
    }
    
    @Test
    @Disabled("Integration test - requires network")
    public void testCancelSpotOrder() {
        // Example of cancelling an order
        // You need to provide a valid orderId
        Long orderId = 55930035987L; 
        String symbol = "BTCUSDT";
        try {
            BinanceOrder response = binanceApiService.cancelOrder(symbol, orderId, null);
            log.info("Cancel Order Response: {}", response);
        } catch (Exception e) {
            log.error("Cancel Order Failed (Expected if order doesn't exist): {}", e.getMessage());
        }
    }

    @Test
    @Disabled("Integration test - requires network")
    public void testGetSpotOrder() {
        // Example of getting an order
        Long orderId = 55930035987L;
        String symbol = "BTCUSDT";
        try {
            BinanceOrder response = binanceApiService.getOrder(symbol, orderId, null);
            log.info("Get Order Response: {}", response);
        } catch (Exception e) {
            log.error("Get Order Failed (Expected if order doesn't exist): {}", e.getMessage());
        }
    }


    // ================= FUTURES API TESTS =================

    @Test
    @Disabled("Integration test - requires network")
    public void testCheckFuturesServerTime() {
        Long serverTime = binanceApiService.checkFuturesServerTime();
        log.info("Futures Server Time: {}", serverTime);
        assertNotNull(serverTime);
    }

    @Test
    @Disabled("Integration test - requires network")
    public void testGetFuturesAccount() {
        BinanceFuturesAccount accountInfo = binanceApiService.getFuturesAccount();
        log.info("Futures Account Info: {}", accountInfo);
        assertNotNull(accountInfo);
    }

    @Test
    @Disabled("Integration test - requires network")
    public void testChangeInitialLeverage() {
        String symbol = "ETHUSDT";
        Integer leverage = 10;
        BinanceFuturesPositionRisk response = binanceApiService.changeInitialLeverage(symbol, leverage);
        log.info("Change Leverage Response: {}", response);
        assertNotNull(response);
    }

    @Test
    @Disabled("Integration test - requires network")
    public void testFuturesOrderLifecycle() {
        String symbol = "BTCUSDT";
        String quantity = "0.01";
        
        // 1. Open Long
        log.info("Opening Long...");
        // Market Order
        BinanceFuturesOrder openLongResponse = binanceApiService.openLong(symbol, "MARKET", quantity, null, null);
        log.info("Open Long Response: {}", openLongResponse);
        assertNotNull(openLongResponse);

        // 2. Close Long
        log.info("Closing Long...");
        BinanceFuturesOrder closeLongResponse = binanceApiService.closeLong(symbol, "MARKET", quantity, null);
        log.info("Close Long Response: {}", closeLongResponse);
        assertNotNull(closeLongResponse);
        
        // 3. Open Short
        log.info("Opening Short...");
        com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesOrder openShortResponse = binanceApiService.openShort(symbol, "MARKET", quantity, null, null);
        log.info("Open Short Response: {}", openShortResponse);
        assertNotNull(openShortResponse);

        // 4. Close Short
        log.info("Closing Short...");
        BinanceFuturesOrder closeShortResponse = binanceApiService.closeShort(symbol, "MARKET", quantity, null);
        log.info("Close Short Response: {}", closeShortResponse);
        assertNotNull(closeShortResponse);
    }
    
    @Test
    @Disabled("Integration test - requires network")
    public void testPlaceFuturesOrder_Limit() {
        String symbol = "BTCUSDT";
        String side = "BUY";
        String positionSide = "LONG";
        String type = "LIMIT";
        String timeInForce = "GTC";
        String quantity = "0.001";
        String price = "40000";
        
        log.info("Placing Limit Order (Futures)...");
        BinanceFuturesOrder response = binanceApiService.placeFuturesOrder(symbol, side, positionSide, type, timeInForce, quantity, price, false);
        log.info("Place Limit Order Response: {}", response);
        assertNotNull(response);
    }

    @Test
    @Disabled("Integration test - requires network")
    public void testCancelFuturesOrder() {
        // Example cancel
        String symbol = "BTCUSDT";
        Long orderId = 12345678L;
        try {
            BinanceFuturesOrder response = binanceApiService.cancelFuturesOrder(symbol, orderId, null);
            log.info("Cancel Futures Order Response: {}", response);
        } catch (Exception e) {
            log.error("Cancel Futures Order Failed: {}", e.getMessage());
        }
    }

    @Test
    @Disabled("Integration test - requires network")
    public void testFuturesMarketData() {
        String symbol = "BTCUSDT";
        
        // Ticker Price
        BinancePriceTicker tickerPrice = binanceApiService.getFuturesTickerPrice(symbol);
        log.info("Futures Ticker Price: {}", tickerPrice);
        assertNotNull(tickerPrice);
        
        // Funding Rate (Premium Index)
        BinanceFuturesPremiumIndex fundingRate = binanceApiService.getFuturesFundingRate(symbol);
        log.info("Futures Funding Rate: {}", fundingRate);
        assertNotNull(fundingRate);
    }
    
    @Test
    @Disabled("Integration test - requires network")
    public void testFuturesOrderOperations() {
        String symbol = "BTCUSDT";
        
        // Get Open Orders
        List<BinanceFuturesOrder> openOrders = binanceApiService.getFuturesOpenOrders(symbol);
        log.info("Futures Open Orders: {}", openOrders);
        assertNotNull(openOrders);
        
        // Cancel All Open Orders (Be careful!)
        // String cancelAllResponse = binanceApiService.getFuturesService().cancelAllOpenOrders(symbol);
        // log.info("Cancel All Futures Orders Response: {}", cancelAllResponse);
    }

    // ================= ALPHA API TESTS =================

    @Test
    @Disabled("Integration test - requires network")
    public void testAlphaOrderOperations() {
        // Get Open Orders
        List<BinanceAlphaOrder> openOrders = binanceApiService.getAlphaService().getOpenOrders();
        log.info("Alpha Open Orders: {}", openOrders);
        assertNotNull(openOrders);
        
        // Get Historical Orders
        try {
            List<BinanceAlphaOrder> history = binanceApiService.getAlphaService().getHistoricalOrders("BTCUSDT", null, null, null, 1, 10);
            log.info("Alpha Historical Orders: {}", history);
            assertNotNull(history);
        } catch (Exception e) {
            log.error("Failed to get Alpha history (might be empty or not supported on testnet): {}", e.getMessage());
        }
    }

    // ================= EARN API TESTS =================

    @Test
    @Disabled("Integration test - requires network")
    public void testSimpleEarnFlexible() {
        // List Flexible Products
        try {
            BinanceSimpleEarnListResponse<BinanceSimpleEarnFlexibleProduct> products = binanceApiService.getSimpleEarnService().getFlexibleProductList("USDT", 1L, 10);
            log.info("Flexible Products: {}", products);
            assertNotNull(products);
            
            // Get Position
            BinanceSimpleEarnListResponse<BinanceSimpleEarnFlexiblePosition> positions = binanceApiService.getSimpleEarnService().getFlexibleProductPosition("USDT", null, 1L, 10);
            log.info("Flexible Positions: {}", positions);
            assertNotNull(positions);
        } catch (Exception e) {
            log.error("Simple Earn Flexible Test Failed: {}", e.getMessage());
        }
    }
    
    @Test
    @Disabled("Integration test - requires network")
    public void testSimpleEarnLocked() {
        // List Locked Products
        try {
            BinanceSimpleEarnListResponse<BinanceSimpleEarnLockedProduct> products = binanceApiService.getSimpleEarnService().getLockedProductList("USDT", 1L, 10);
            log.info("Locked Products: {}", products);
            assertNotNull(products);
            
            // Get Position
            BinanceSimpleEarnListResponse<BinanceSimpleEarnLockedPosition> positions = binanceApiService.getSimpleEarnService().getLockedProductPosition("USDT", null, null, 1L, 10);
            log.info("Locked Positions: {}", positions);
            assertNotNull(positions);
        } catch (Exception e) {
            log.error("Simple Earn Locked Test Failed: {}", e.getMessage());
        }
    }
    
    @Test
    @Disabled("Integration test - requires network")
    public void testSimpleEarnAccount() {
        try {
            BinanceSimpleEarnAccount account = binanceApiService.getSimpleEarnService().getAccount();
            log.info("Simple Earn Account: {}", account);
            assertNotNull(account);
        } catch (Exception e) {
            log.error("Simple Earn Account Test Failed: {}", e.getMessage());
        }
    }
}
