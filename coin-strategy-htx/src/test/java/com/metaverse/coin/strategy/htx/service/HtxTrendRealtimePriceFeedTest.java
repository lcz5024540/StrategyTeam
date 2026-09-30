package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.common.client.htx.ws.HtxWebSocketClient;
import com.metaverse.coin.strategy.htx.config.HtxTrendProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HtxTrendRealtimePriceFeedTest {

    @Test
    void disabledFeedDoesNotConnectOrReturnQuote() {
        HtxTrendProperties properties = new HtxTrendProperties();
        HtxWebSocketClient client = mock(HtxWebSocketClient.class);
        HtxTrendRealtimePriceFeed feed = new HtxTrendRealtimePriceFeed(properties, client, Runnable::run);

        feed.acceptPayload("BTC-USDT", "{\"ts\":1780000000000,\"tick\":{\"close\":\"65000.5\"}}");

        assertFalse(feed.latest("BTC-USDT").isPresent());
        verify(client, org.mockito.Mockito.never()).connect();
    }

    @Test
    void usesFreshSwapDetailCloseAndStartsConnectionWithoutBlockingCaller() {
        HtxTrendProperties properties = new HtxTrendProperties();
        properties.setRealtimePriceFeedEnabled(true);
        properties.setRealtimePriceMaxStaleMs(30_000L);
        HtxWebSocketClient client = mock(HtxWebSocketClient.class);
        when(client.isConnected()).thenReturn(false);
        HtxTrendRealtimePriceFeed feed = new HtxTrendRealtimePriceFeed(properties, client, Runnable::run);

        feed.acceptPayload("BTC-USDT", "{\"ts\":1780000000000,\"tick\":{\"close\":\"65000.5\"}}");
        Optional<HtxTrendRealtimePriceFeed.Quote> quote = feed.latest("BTC-USDT");

        assertTrue(quote.isPresent());
        assertEquals(new BigDecimal("65000.5"), quote.get().price());
        assertEquals("HTX_SWAP_WS_DETAIL", quote.get().source());
        verify(client).subscribe(org.mockito.ArgumentMatchers.argThat(subscription ->
                "market.BTC-USDT.detail".equals(subscription.getTopic())));
        verify(client).connect();
    }

    @Test
    void olderOutOfOrderPayloadCannotReplaceNewerPrice() {
        HtxTrendProperties properties = new HtxTrendProperties();
        properties.setRealtimePriceFeedEnabled(true);
        properties.setRealtimePriceMaxStaleMs(30_000L);
        HtxWebSocketClient client = mock(HtxWebSocketClient.class);
        when(client.isConnected()).thenReturn(true);
        HtxTrendRealtimePriceFeed feed = new HtxTrendRealtimePriceFeed(properties, client, Runnable::run);

        feed.acceptPayload("ETH-USDT", "{\"ts\":1780000000200,\"tick\":{\"close\":\"3000\"}}");
        feed.acceptPayload("ETH-USDT", "{\"ts\":1780000000100,\"tick\":{\"close\":\"2900\"}}");

        assertEquals(new BigDecimal("3000"), feed.latest("ETH-USDT").orElseThrow().price());
    }
}
