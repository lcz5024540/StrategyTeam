package com.metaverse.coin.strategy.job.funding.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.common.client.binance.BinanceApiService;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesOrder;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesPremiumIndex;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceOrderBook;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinancePriceTicker;
import com.metaverse.coin.strategy.common.model.binance.entity.spot.BinanceOrder;
import com.metaverse.coin.strategy.core.arbitrage.config.SingleExchangeSpotFuturesProperties;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BinanceWebSocketStreamServiceTest {

    @Test
    void parsesPublicMarketMessagesIntoCaches() {
        BinanceWebSocketStreamService service = service();

        service.handlePublicMessage("""
                {"stream":"btcusdt@markPrice@1s","data":{"e":"markPriceUpdate","E":1000,"s":"BTCUSDT","p":"50010.50","i":"50009.10","r":"0.0006","T":2000}}
                """, true);
        service.handlePublicMessage("""
                {"stream":"btcusdt@bookTicker","data":{"u":1,"s":"BTCUSDT","b":"50000","B":"1.2","a":"50002","A":"1.1"}}
                """, false);
        service.handlePublicMessage("""
                {"stream":"btcusdt@depth20@100ms","data":{"lastUpdateId":2,"bids":[["49999","1"]],"asks":[["50003","2"]]}}
                """, false);

        BinanceFuturesPremiumIndex premiumIndex = service.getCachedPremiumIndex("BTCUSDT");
        BinancePriceTicker spotPrice = service.getCachedSpotPrice("BTCUSDT");
        BinanceOrderBook spotBook = service.getCachedSpotOrderBook("BTCUSDT");

        assertNotNull(premiumIndex);
        assertEquals(new BigDecimal("0.0006"), premiumIndex.getLastFundingRate());
        assertEquals(new BigDecimal("50010.50"), service.getCachedFuturesPrice("BTCUSDT").getPrice());
        assertEquals(new BigDecimal("50001.00000000"), spotPrice.getPrice());
        assertEquals("49999", spotBook.getBids().get(0).get(0));
    }

    @Test
    void parsesUserDataOrderMessagesIntoCaches() {
        BinanceWebSocketStreamService service = service();

        service.handleUserMessage("""
                {"e":"executionReport","E":1000,"s":"BTCUSDT","c":"spot-client","S":"BUY","o":"MARKET","f":"GTC","q":"0.01","p":"0","X":"FILLED","i":101,"z":"0.01","Z":"500","O":900}
                """, false);
        service.handleUserMessage("""
                {"e":"ORDER_TRADE_UPDATE","E":1000,"o":{"s":"BTCUSDT","c":"futures-client","S":"SELL","o":"MARKET","f":"GTC","q":"0.01","p":"0","X":"FILLED","i":202,"z":"0.01","Z":"501","ap":"50100","ps":"SHORT","T":1000}}
                """, true);

        BinanceOrder spotOrder = service.getCachedSpotOrder("BTCUSDT", 101L, "spot-client");
        BinanceFuturesOrder futuresOrder = service.getCachedFuturesOrder("BTCUSDT", 202L, "futures-client");

        assertNotNull(spotOrder);
        assertEquals("FILLED", spotOrder.getStatus());
        assertNotNull(futuresOrder);
        assertEquals(new BigDecimal("50100"), futuresOrder.getAvgPrice());
    }

    @Test
    void parsesSpotWebSocketApiUserDataEventWrapper() {
        BinanceWebSocketStreamService service = service();

        service.handleUserMessage("""
                {"subscriptionId":0,"event":{"e":"executionReport","E":1000,"s":"BTCUSDT","c":"spot-client","S":"BUY","o":"MARKET","f":"GTC","q":"0.01","p":"0","X":"FILLED","i":101,"z":"0.01","Z":"500","O":900}}
                """, false);

        BinanceOrder spotOrder = service.getCachedSpotOrder("BTCUSDT", 101L, "spot-client");

        assertNotNull(spotOrder);
        assertEquals("FILLED", spotOrder.getStatus());
    }

    @Test
    void buildsWebSocketApiUserDataRequests() throws Exception {
        BinanceWebSocketStreamService service = service();
        ObjectMapper objectMapper = new ObjectMapper();

        String spotSubscribe = service.buildSpotUserDataSubscribeRequest();
        String futuresStart = service.buildFuturesUserDataApiRequest("userDataStream.start");

        assertEquals("userDataStream.subscribe.signature", objectMapper.readTree(spotSubscribe).path("method").asText());
        assertEquals("test-api-key", objectMapper.readTree(spotSubscribe).path("params").path("apiKey").asText());
        assertTrue(objectMapper.readTree(spotSubscribe).path("params").hasNonNull("timestamp"));
        assertTrue(objectMapper.readTree(spotSubscribe).path("params").hasNonNull("signature"));
        assertEquals("userDataStream.start", objectMapper.readTree(futuresStart).path("method").asText());
        assertEquals("test-api-key", objectMapper.readTree(futuresStart).path("params").path("apiKey").asText());
    }

    @Test
    void aggregatesPublicStreamsAndSplitsByStreamLimit() throws Exception {
        BinanceWebSocketProperties properties = new BinanceWebSocketProperties();
        properties.setMaxStreamsPerConnection(4);
        properties.setOutboundMessageLimitPerSecond(1000);
        TestableService service = service(properties);

        service.subscribeSymbol("BTCUSDT");
        service.subscribeSymbol("ETHUSDT");
        service.subscribeSymbol("BNBUSDT");

        BinanceWebSocketStreamService.BinanceWebSocketStats stats = service.getStats();
        assertEquals(2, stats.getSpotPublicConnections());
        assertEquals(3, stats.getFuturesPublicConnections());
        assertEquals(3, stats.getSpotSubscribedSymbols());
        assertEquals(3, stats.getFuturesSubscribedSymbols());
        assertEquals(5, service.captures.size());
        assertTrue(service.captures.get(0).url.contains("btcusdt@bookTicker"));

        Capture firstSpotConnection = service.captures.get(0);
        firstSpotConnection.listener.onOpen(firstSpotConnection.socket, null);
        assertTrue(firstSpotConnection.socket.awaitSend());
        assertTrue(firstSpotConnection.socket.sentMessages.stream()
                .anyMatch(message -> message.contains("\"method\":\"SUBSCRIBE\"")
                        && message.contains("ethusdt@bookTicker")
                        && message.contains("ethusdt@depth20@100ms")));
    }

    @Test
    void userDataWebSocketApiSendsThroughLimiterOnOpen() throws Exception {
        BinanceWebSocketProperties properties = new BinanceWebSocketProperties();
        properties.setPublicMarketEnabled(false);
        properties.setOutboundMessageLimitPerSecond(1000);
        TestableService service = service(properties);

        service.start();

        assertEquals(2, service.captures.size());
        Capture spotApi = service.captures.get(0);
        Capture futuresApi = service.captures.get(1);
        spotApi.listener.onOpen(spotApi.socket, null);
        futuresApi.listener.onOpen(futuresApi.socket, null);

        assertTrue(spotApi.socket.awaitSend());
        assertTrue(futuresApi.socket.awaitSend());
        assertTrue(spotApi.socket.sentMessages.get(0).contains("userDataStream.subscribe.signature"));
        assertTrue(futuresApi.socket.sentMessages.get(0).contains("userDataStream.start"));
    }

    private BinanceWebSocketStreamService service() {
        BinanceWebSocketProperties properties = new BinanceWebSocketProperties();
        properties.setEnabled(false);
        return service(properties);
    }

    private TestableService service(BinanceWebSocketProperties properties) {
        BinanceApiService binanceApiService = mock(BinanceApiService.class);
        when(binanceApiService.getApiKey()).thenReturn("test-api-key");
        when(binanceApiService.signHmacSha256Hex(org.mockito.ArgumentMatchers.anyString())).thenReturn("test-signature");
        return new TestableService(
                properties,
                new SingleExchangeSpotFuturesProperties(),
                binanceApiService,
                new ObjectMapper()
        );
    }

    private static class TestableService extends BinanceWebSocketStreamService {
        private final List<Capture> captures = new ArrayList<>();

        private TestableService(BinanceWebSocketProperties properties,
                                SingleExchangeSpotFuturesProperties spotFuturesProperties,
                                BinanceApiService binanceApiService,
                                ObjectMapper objectMapper) {
            super(properties, spotFuturesProperties, binanceApiService, objectMapper);
        }

        @Override
        WebSocket newWebSocket(String url, WebSocketListener listener) {
            FakeWebSocket socket = new FakeWebSocket(url);
            captures.add(new Capture(url, listener, socket));
            return socket;
        }
    }

    private record Capture(String url, WebSocketListener listener, FakeWebSocket socket) {
    }

    private static class FakeWebSocket implements WebSocket {
        private final Request request;
        private final List<String> sentMessages = new CopyOnWriteArrayList<>();

        private FakeWebSocket(String url) {
            this.request = new Request.Builder().url(url).build();
        }

        private boolean awaitSend() throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() < deadline) {
                if (!sentMessages.isEmpty()) {
                    return true;
                }
                TimeUnit.MILLISECONDS.sleep(10);
            }
            return false;
        }

        @Override
        public Request request() {
            return request;
        }

        @Override
        public long queueSize() {
            return 0;
        }

        @Override
        public boolean send(String text) {
            sentMessages.add(text);
            return true;
        }

        @Override
        public boolean send(ByteString bytes) {
            return true;
        }

        @Override
        public boolean close(int code, String reason) {
            return true;
        }

        @Override
        public void cancel() {
        }
    }
}
