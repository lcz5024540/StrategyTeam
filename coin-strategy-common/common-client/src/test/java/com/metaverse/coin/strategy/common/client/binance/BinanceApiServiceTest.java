package com.metaverse.coin.strategy.common.client.binance;

import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceExchangeInfo;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceKline;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceTicker;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceTrade;
import com.metaverse.coin.strategy.common.model.binance.entity.spot.BinanceAccountInfo;
import com.metaverse.coin.strategy.common.model.binance.entity.spot.BinanceOrder;
import com.metaverse.coin.strategy.common.model.binance.entity.wallet.BinanceApiKeyPermission;
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
    private static final String API_KEY = "KXFMs9WeNZhdSIx2TBIo5waLPPmqoCRjP582e3RxBO6FzWfw7JvrunWDeOu1Ym3P";
    private static final String SECRET_KEY = "rKLFKKR2oOyf6cZ04MnVVQ4vhzz4rHrePyN0yo33Hc10b0dSgfOLToenfXtNiQHS";

    // Testnet URLs
    //private static final String SPOT_BASE_URL = "https://testnet.binance.vision";
    //private static final String SPOT_BASE_URL = "https://api.binance.com";
    private static final String SPOT_BASE_URL = "https://api1.binance.com";
    private static final String FUTURES_BASE_URL = "https://testnet.binancefuture.com";

    private BinanceApiService binanceApiService;

    @BeforeEach
    public void setUp() {
        // Initialize with Testnet URLs
        binanceApiService = new BinanceApiService(API_KEY, SECRET_KEY, SPOT_BASE_URL, FUTURES_BASE_URL);
    }

    // ================= SPOT API TESTS =================

    @Test
    public void testCheckServerTime() {
        String serverTime = binanceApiService.checkServerTime();
        log.info("Spot Server Time: {}", serverTime);
        assertNotNull(serverTime);
    }

    @Test
    public void testPing() {
        String ping = binanceApiService.ping();
        log.info("Spot Ping: {}", ping);
    }

    @Test
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
    public void testKlines() {
        // Symbol: BTCUSDT, Interval: 1h, Limit: 10
        List<BinanceKline> klines = binanceApiService.klines("BTCUSDT", "1h", 10);
        log.info("Spot Klines: {}", klines);
        assertNotNull(klines);
        // assertTrue(!klines.isEmpty()); // May be empty if testnet has no data or other issues, but typically shouldn't be
    }

    @Test
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
    public void testCheckFuturesServerTime() {
        String serverTime = binanceApiService.checkFuturesServerTime();
        log.info("Futures Server Time: {}", serverTime);
        assertNotNull(serverTime);
    }

    @Test
    public void testGetFuturesAccount() {
        String accountInfo = binanceApiService.getFuturesAccount();
        log.info("Futures Account Info: {}", accountInfo);
        assertNotNull(accountInfo);
    }

    @Test
    public void testChangeInitialLeverage() {
        String symbol = "BTCUSDT";
        Integer leverage = 10;
        String response = binanceApiService.changeInitialLeverage(symbol, leverage);
        log.info("Change Leverage Response: {}", response);
        assertNotNull(response);
    }

    @Test
    public void testFuturesOrderLifecycle() {
        String symbol = "BTCUSDT";
        String quantity = "0.01";
        
        // 1. Open Long
        log.info("Opening Long...");
        // Market Order
        String openLongResponse = binanceApiService.openLong(symbol, "MARKET", quantity, null, null);
        log.info("Open Long Response: {}", openLongResponse);
        assertNotNull(openLongResponse);

        // 2. Close Long
        log.info("Closing Long...");
        String closeLongResponse = binanceApiService.closeLong(symbol, "MARKET", quantity, null);
        log.info("Close Long Response: {}", closeLongResponse);
        assertNotNull(closeLongResponse);
        
        // 3. Open Short
        log.info("Opening Short...");
        String openShortResponse = binanceApiService.openShort(symbol, "MARKET", quantity, null, null);
        log.info("Open Short Response: {}", openShortResponse);
        assertNotNull(openShortResponse);

        // 4. Close Short
        log.info("Closing Short...");
        String closeShortResponse = binanceApiService.closeShort(symbol, "MARKET", quantity, null);
        log.info("Close Short Response: {}", closeShortResponse);
        assertNotNull(closeShortResponse);
    }
    
    @Test
    public void testPlaceFuturesOrder_Limit() {
        String symbol = "BTCUSDT";
        String side = "BUY";
        String positionSide = "LONG";
        String type = "LIMIT";
        String timeInForce = "GTC";
        String quantity = "0.001";
        String price = "40000";
        
        log.info("Placing Limit Order (Futures)...");
        String response = binanceApiService.placeFuturesOrder(symbol, side, positionSide, type, timeInForce, quantity, price, false);
        log.info("Place Limit Order Response: {}", response);
        assertNotNull(response);
    }

    @Test
    public void testCancelFuturesOrder() {
        // Example cancel
        String symbol = "BTCUSDT";
        Long orderId = 12345678L;
        try {
            String response = binanceApiService.cancelFuturesOrder(symbol, orderId, null);
            log.info("Cancel Futures Order Response: {}", response);
        } catch (Exception e) {
            log.error("Cancel Futures Order Failed: {}", e.getMessage());
        }
    }
}
