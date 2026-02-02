package com.metaverse.coin.strategy.common.client.binance.debug;

import com.metaverse.coin.strategy.common.client.binance.service.BinanceFuturesService;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesAccount;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesOrder;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesPositionRisk;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@Slf4j
public class BinanceFuturesDebug extends BinanceDebugBase {

    private BinanceFuturesService futuresService;

    @BeforeEach
    public void setUp() {
        super.setUp();
        futuresService = new BinanceFuturesService(futuresClient);
    }

    @Test
    public void testAccountInformation() {
        log.info("Testing Futures Account Information...");
        BinanceFuturesAccount accountInfo = futuresService.account();
        log.info("Account Info: {}", accountInfo);
        assertNotNull(accountInfo);
    }

    @Test
    public void testGetPositionRisk() {
        log.info("Testing Position Risk...");
        List<BinanceFuturesPositionRisk> positionRisk = futuresService.getPositionRisk("BTCUSDT");
        log.info("Position Risk: {}", positionRisk);
        assertNotNull(positionRisk);
    }

    @Test
    public void testPlaceOrder_Limit_OpenLong() {
        log.info("Testing Place Limit Order (Open Long)...");
        try {
            // Adjust price/quantity as needed
            BinanceFuturesOrder response = futuresService.newOrder(
                "BTCUSDT", 
                "BUY", 
                "LONG", // Use null for One-way mode
                "LIMIT", 
                "GTC", 
                "0.001", 
                "40000", 
                false
            );
            log.info("Order Placed: {}", response);
            assertNotNull(response);
            
            // Note: You might want to parse response to get orderId for cancellation
        } catch (Exception e) {
            log.error("Order placement failed", e);
            throw e;
        }
    }

    @Test
    public void testPlaceOrder_Market_OpenShort() {
        log.info("Testing Place Market Order (Open Short)...");
        try {
            BinanceFuturesOrder response = futuresService.newOrder(
                "BTCUSDT", 
                "SELL", 
                "SHORT", // Use null for One-way mode
                "MARKET", 
                null, 
                "0.001", 
                null, 
                false
            );
            log.info("Order Placed: {}", response);
            assertNotNull(response);
        } catch (Exception e) {
            log.error("Order placement failed", e);
            throw e;
        }
    }

    @Test
    public void testCancelOrder() {
        Long orderId = null; // Set this to test cancellation
        if (orderId != null) {
            log.info("Testing Cancel Order...");
            BinanceFuturesOrder result = futuresService.cancelOrder("BTCUSDT", orderId, null);
            log.info("Cancel Result: {}", result);
        } else {
            log.info("Skipping Cancel Order (orderId is null)");
        }
    }
    
    @Test
    public void testChangeLeverage() {
        log.info("Testing Change Leverage...");
        BinanceFuturesPositionRisk result = futuresService.changeInitialLeverage("BTCUSDT", 10);
        log.info("Change Leverage Result: {}", result);
        assertNotNull(result);
    }
}
