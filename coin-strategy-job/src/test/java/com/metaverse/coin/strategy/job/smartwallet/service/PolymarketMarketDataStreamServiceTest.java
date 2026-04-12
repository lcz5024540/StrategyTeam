package com.metaverse.coin.strategy.job.smartwallet.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolymarketMarketDataStreamServiceTest {

    private PolymarketMarketDataStreamService service;

    @BeforeEach
    void setUp() {
        SmartWalletProperties properties = new SmartWalletProperties();
        properties.getExecutionWallet().setWsEnabled(true);
        properties.getExecutionWallet().setWsCacheTtlMs(30_000L);
        service = new PolymarketMarketDataStreamService(properties, new ObjectMapper());
    }

    @Test
    void shouldBuildCachedSnapshotFromBookAndPriceEvents() {
        long now = System.currentTimeMillis();
        service.handleMessage("""
                {"event_type":"book","asset_id":"token-1","bids":[{"price":"0.44","size":"100"}],"asks":[{"price":"0.46","size":"80"}],"timestamp":%d}
                """.formatted(now));
        service.handleMessage("""
                {"event_type":"price_change","asset_id":"token-1","price":"0.45","timestamp":%d}
                """.formatted(now + 500));

        PolymarketMarketDataStreamService.MarketDataSnapshot snapshot = service.getCachedSnapshot("token-1");

        assertNotNull(snapshot);
        assertEquals(new BigDecimal("0.45"), snapshot.getLastTradePrice());
        assertEquals(0, new BigDecimal("0.45").compareTo(snapshot.getOrderBook().getMidpoint()));
        assertEquals(new BigDecimal("444.44440000"), snapshot.getOrderBook().getSpread().setScale(8));
        assertEquals(1, snapshot.getOrderBook().getBids().size());
        assertEquals(1, snapshot.getOrderBook().getAsks().size());

        PolymarketMarketDataStreamService.MarketDataStreamStats stats = service.getStats();
        assertEquals(2L, stats.getMessagesReceived());
        assertEquals(1L, stats.getBookEventsReceived());
        assertEquals(1L, stats.getPriceEventsReceived());
        assertEquals(1, stats.getCachedTokenCount());
        assertNotNull(stats.getLastEventAt());
        assertTrue(stats.getLastEventAt().after(new Date(now - 1_000L)));
    }

    @Test
    void shouldIgnoreStaleCacheSnapshots() throws InterruptedException {
        SmartWalletProperties properties = new SmartWalletProperties();
        properties.getExecutionWallet().setWsEnabled(true);
        properties.getExecutionWallet().setWsCacheTtlMs(1L);
        service = new PolymarketMarketDataStreamService(properties, new ObjectMapper());

        service.handleMessage("""
                {"event_type":"book","asset_id":"token-2","bids":[{"price":"0.40","size":"10"}],"asks":[{"price":"0.60","size":"10"}],"timestamp":%d}
                """.formatted(System.currentTimeMillis()));

        Thread.sleep(10L);

        assertNull(service.getCachedSnapshot("token-2"));
    }

    @Test
    void shouldExposeCompatAliasesForLegacyCallers() {
        service.ingestMockEvent("""
                {"event_type":"book","asset_id":"token-3","bids":[{"price":"0.49","size":"10"}],"asks":[{"price":"0.51","size":"10"}],"timestamp":%d}
                """.formatted(System.currentTimeMillis()));

        assertNotNull(service.getSnapshot("token-3"));
        assertNotNull(service.getCachedSnapshot("token-3"));
    }
}
