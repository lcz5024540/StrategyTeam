package com.metaverse.coin.strategy.common.client.gate;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GateApiServiceTest {

    @Test
    void testInstantiation() {
        GateApiService service = new GateApiService("key", "secret");
        assertNotNull(service);
        assertNotNull(service.getApiClient());
        assertNotNull(service.getSpotApi());
        assertNotNull(service.getFuturesApi());
        assertNotNull(service.getAccountService());
        assertNotNull(service.getTradeService());
        assertNotNull(service.getMarketDataService());
        assertNotNull(service.getWalletService());
    }
}
