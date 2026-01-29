package com.metaverse.coin.strategy.common.client.binance;

import com.metaverse.coin.strategy.common.client.binance.service.BinanceMiningService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class BinanceMiningServiceTest {

    @Test
    public void testInitialization() {
        BinanceApiService apiService = new BinanceApiService("key", "secret");
        BinanceMiningService miningService = apiService.getMiningService();
        assertNotNull(miningService);
    }
}
