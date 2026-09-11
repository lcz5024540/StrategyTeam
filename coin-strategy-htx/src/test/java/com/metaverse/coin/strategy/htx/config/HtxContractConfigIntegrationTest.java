package com.metaverse.coin.strategy.htx.config;

import java.math.BigDecimal;

import com.metaverse.coin.strategy.common.client.htx.HtxUsdtSwapApiService;
import com.metaverse.coin.strategy.htx.controller.HtxContractController;
import com.metaverse.coin.strategy.htx.service.HtxContractTradingService;
import com.metaverse.coin.strategy.htx.strategy.HtxDeltaHedgeSnapshotProvider;
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
    @Autowired HtxDeltaHedgeSnapshotProvider hedgeSnapshotProvider;
    @Autowired HtxContractProperties properties;

    @Test
    void enabledDryRunModeWiresContractRuntimeWithXautDefault() {
        assertNotNull(api);
        assertNotNull(tradingService);
        assertNotNull(controller);
        assertNotNull(hedgeSnapshotProvider);
        assertTrue(properties.isDryRun());
        assertEquals("https://api.hbdm.com", properties.getBaseUrl());
        assertTrue(properties.getContractCodes().contains("XAUT-USDT"));
        assertEquals(5, properties.getLeverage());
        assertEquals(5, properties.getMaxLeverage());
        assertEquals(new BigDecimal("20"), properties.getAutoTestQuoteNotional());
        assertFalse(properties.isAutoFastIocEnabled());
        assertTrue(properties.isAutoProfitGridEnabled());
        assertEquals(new BigDecimal("0.0010"), properties.getMinNetProfitRate());
        assertEquals(new BigDecimal("1.0"), properties.getMaxDailyRealizedLossUsdt());
        assertEquals(3, properties.getFastIocCloseRetryAttempts());
        assertFalse(properties.isAutoTestEnabled());
        assertEquals(2L, properties.getAutoTestVolume());
        assertEquals(5_000L, properties.getAutoTestIntervalMs());
        assertEquals(68, properties.getUidQueryLimit());
        assertEquals(68, properties.getUidTradeLimit());
        assertEquals(3_000L, properties.getUidRateWindowMs());
        assertFalse(properties.isHedgeSupervisorEnabled());
        assertTrue(properties.isHedgePlannerEnabled());
        assertEquals(new BigDecimal("0.5"), properties.getHedgeRatio());
        assertEquals(new BigDecimal("0.05"), properties.getHedgeMaxShortBase());
        assertEquals(new BigDecimal("10"), properties.getHedgeMinimumMarginAvailable());
        assertEquals(new BigDecimal("0.0010"), properties.getHedgeMaxAbsFundingRate());
        assertEquals(new BigDecimal("0.0020"), properties.getHedgeMaxBasisRate());
        assertEquals(new BigDecimal("0.1000"), properties.getHedgeMinimumLiquidationDistanceRate());
        assertTrue(properties.isHedgeSupervisorShadowOnly());
        assertFalse(properties.isHedgeAutoSendEnabled());
        assertEquals(30_000L, properties.getHedgeSupervisorInitialDelayMs());
        assertEquals(60_000L, properties.getHedgeSupervisorIntervalMs());
        assertEquals(20, properties.getHedgeSupervisorBatchSize());
    }
}
