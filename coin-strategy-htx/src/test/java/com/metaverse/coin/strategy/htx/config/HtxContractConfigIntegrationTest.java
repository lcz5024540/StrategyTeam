package com.metaverse.coin.strategy.htx.config;

import java.math.BigDecimal;

import com.metaverse.coin.strategy.common.client.htx.HtxUsdtSwapApiService;
import com.metaverse.coin.strategy.htx.controller.HtxContractController;
import com.metaverse.coin.strategy.htx.service.HtxContractTradingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "htx.api.access-key=test-access",
        "htx.api.secret-key=test-secret",
        "htx.contract.enabled=true",
        "htx.contract.dry-run=true"
})
class HtxContractConfigIntegrationTest {

    @Autowired HtxUsdtSwapApiService api;
    @Autowired HtxContractTradingService tradingService;
    @Autowired HtxContractController controller;
    @Autowired HtxContractProperties properties;

    @Test
    void enabledDryRunModeWiresContractRuntimeWithXautDefault() {
        assertNotNull(api);
        assertNotNull(tradingService);
        assertNotNull(controller);
        assertTrue(properties.isDryRun());
        assertEquals("https://api.hbdm.com", properties.getBaseUrl());
        assertTrue(properties.getContractCodes().contains("XAUT-USDT"));
        assertEquals(75, properties.getLeverage());
        assertEquals(75, properties.getMaxLeverage());
        assertEquals(new BigDecimal("500"), properties.getAutoTestQuoteNotional());
        assertTrue(properties.isAutoFastIocEnabled());
        assertEquals(3, properties.getFastIocCloseRetryAttempts());
        assertFalse(properties.isAutoTestEnabled());
        assertEquals(2L, properties.getAutoTestVolume());
        assertEquals(500L, properties.getAutoTestIntervalMs());
        assertEquals(68, properties.getUidQueryLimit());
        assertEquals(68, properties.getUidTradeLimit());
        assertEquals(3_000L, properties.getUidRateWindowMs());
    }
}
