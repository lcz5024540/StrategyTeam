package com.metaverse.coin.strategy.common.client.gate;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GateApiServiceTest {

    @Test
    void testInstantiation() {
        GateApiService service = GateApiService.create("key", "secret");
        assertNotNull(service);
        assertNotNull(service.getGateApi());
        assertNotNull(service.getAccountService());
        assertNotNull(service.getTradeService());
        assertNotNull(service.getMarketDataService());
        assertNotNull(service.getWalletService());
    }
}
