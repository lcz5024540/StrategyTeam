package com.metaverse.coin.strategy.common.client.binance.mock.service;

import com.binance.connector.futures.client.impl.UMFuturesClientImpl;
import com.metaverse.coin.strategy.common.client.binance.service.BinanceFuturesService;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesOrder;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Real Integration Test for Binance Futures.
 * <p>
 * Requires valid API Key and Secret Key to be set in Environment Variables or System Properties.
 * <p>
 * Usage:
 * mvn test -Dtest=BinanceFuturesIntegrationTest -Dbinance.api.key=YOUR_KEY -Dbinance.secret.key=YOUR_SECRET
 */
@Slf4j
public class BinanceFuturesIntegrationTest {

    private BinanceFuturesService binanceFuturesService;
    private String apiKey;
    private String secretKey;

    @BeforeEach
    public void setUp() {
        // Try to get keys from System Properties first, then Environment Variables
        apiKey = System.getProperty("binance.api.key");
        if (apiKey == null) {
            apiKey = System.getenv("BINANCE_API_KEY");
        }

        secretKey = System.getProperty("binance.secret.key");
        if (secretKey == null) {
            secretKey = System.getenv("BINANCE_SECRET_KEY");
        }

        if (apiKey == null || secretKey == null) {
            log.warn("API Key or Secret Key not found. Skipping integration test initialization.");
            // We don't throw here to allow test methods to decide whether to skip or fail
            return;
        }

        // Initialize Real Client
        // You can change the base URL to Testnet if needed: "https://testnet.binancefuture.com"
        UMFuturesClientImpl client = new UMFuturesClientImpl(apiKey, secretKey, "https://fapi.binance.com");
        binanceFuturesService = new BinanceFuturesService(client);
    }

    @Test
    @Disabled("Real API Call - Enable manually by removing @Disabled or running with specific profile")
    public void testPlaceOrder_Real() {
        if (binanceFuturesService == null) {
            log.warn("Skipping testPlaceOrder_Real because service is not initialized (missing keys).");
            return;
        }

        String symbol = "BTCUSDT";
        String side = "BUY";
        String positionSide = "LONG"; // For Hedge Mode. Use null or empty for One-way Mode if needed.
        String type = "MARKET";
        String quantity = "0.001"; // Ensure this is above min quantity (approx 5-10 USDT)

        log.info("Sending Real Order: {} {} {} {}", symbol, side, type, quantity);

        try {
            // Note: Ensure your account is in Hedge Mode if using positionSide="LONG/SHORT"
            // Or change to One-way mode and remove positionSide.
            // This example assumes Hedge Mode.
            BinanceFuturesOrder response = binanceFuturesService.newOrder(
                    symbol,
                    side,
                    positionSide,
                    type,
                    null, // timeInForce
                    quantity,
                    null, // price
                    null  // reduceOnly
            );

            log.info("Order Placed Successfully! Response: {}", response);
            assertNotNull(response);

        } catch (Exception e) {
            log.error("Failed to place order: {}", e.getMessage(), e);
            throw e;
        }
    }
    
    @Test
    @Disabled("Real API Call - Check Server Time")
    public void testCheckServerTime_Real() {
        if (binanceFuturesService == null) {
             return;
        }
        Long time = binanceFuturesService.checkServerTime();
        log.info("Server Time: {}", time);
        assertNotNull(time);
    }
}
