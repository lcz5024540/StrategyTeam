package com.metaverse.coin.strategy.common.client.binance.debug;

import com.metaverse.coin.strategy.common.client.binance.service.BinanceSpotService;
import com.metaverse.coin.strategy.common.model.binance.entity.spot.BinanceAccountInfo;
import com.metaverse.coin.strategy.common.model.binance.entity.spot.BinanceOrder;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@Slf4j
public class BinanceSpotDebug extends BinanceDebugBase {

    private BinanceSpotService spotService;

    @BeforeEach
    public void setUp() {
        super.setUp();
        spotService = new BinanceSpotService(spotClient);
    }

    @Test
    public void testAccountInformation() {
        log.info("Testing Account Information...");
        BinanceAccountInfo accountInfo = spotService.account();
        log.info("Account Info: {}", accountInfo);
        assertNotNull(accountInfo);
    }

    @Test
    public void testNewOrder_TestOnly() {
        log.info("Testing New Order (Test Endpoint)...");
        // Using TestNet symbol
        String result = spotService.testNewOrder("BTCUSDT", "BUY", "LIMIT", "GTC", "0.001", "50000");
        log.info("Test Order Result: {}", result);
        assertNotNull(result);
    }

    @Test
    public void testPlaceOrder_Real() {
        log.info("Testing Place Real Order...");
        // WARNING: This places a REAL order
        try {
            BinanceOrder order = spotService.placeOrder("BTCUSDT", "BUY", "LIMIT", "GTC", "0.001", "40000");
            log.info("Order Placed: {}", order);
            assertNotNull(order.getOrderId());
            
            // Cleanup: Cancel the order
            log.info("Cancelling order: {}", order.getOrderId());
            String cancelResult = spotService.cancelOrder("BTCUSDT", order.getOrderId(), null);
            log.info("Cancel Result: {}", cancelResult);
        } catch (Exception e) {
            log.error("Order placement failed", e);
            throw e;
        }
    }

    @Test
    public void testGetOrder() {
        // Replace with a valid orderId if you want to test specific order
        Long orderId = null; 
        if (orderId != null) {
            log.info("Testing Get Order...");
            String order = spotService.getOrder("BTCUSDT", orderId, null);
            log.info("Order Details: {}", order);
        } else {
            log.info("Skipping Get Order (orderId is null)");
        }
    }
}
