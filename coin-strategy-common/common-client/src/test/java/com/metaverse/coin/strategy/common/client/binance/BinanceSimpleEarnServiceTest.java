package com.metaverse.coin.strategy.common.client.binance;

import com.metaverse.coin.strategy.common.client.binance.service.BinanceSimpleEarnService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class BinanceSimpleEarnServiceTest {

    @Test
    public void testInitialization() {
        BinanceApiService apiService = new BinanceApiService("key", "secret");
        BinanceSimpleEarnService simpleEarnService = apiService.getSimpleEarnService();
        assertNotNull(simpleEarnService);
    }
}
