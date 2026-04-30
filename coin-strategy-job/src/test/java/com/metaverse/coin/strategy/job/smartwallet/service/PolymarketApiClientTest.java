package com.metaverse.coin.strategy.job.smartwallet.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolymarketApiClientTest {

    @Test
    void shouldSkipUserTradesWhenOffsetExceedsPolymarketHistoryWindow() {
        PolymarketApiClient client = new PolymarketApiClient(new SmartWalletProperties(), new ObjectMapper());

        List<PolymarketApiClient.UserTrade> trades = client.fetchUserTrades(
                "0xdc876e6873772d38716fda7f2452a78d426d7ab6",
                30,
                PolymarketApiClient.MAX_USER_TRADE_HISTORY_OFFSET + 30);

        assertTrue(trades.isEmpty());
    }

    @Test
    void shouldClampExplicitMarketMetadataChunkSizeToConfiguredGammaChunkSize() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<Integer> requestChunkSizes = new ArrayList<>();
        server.createContext("/markets", exchange -> {
            String rawQuery = exchange.getRequestURI().getRawQuery();
            String conditionIds = Arrays.stream(rawQuery.split("&"))
                    .map(part -> part.split("=", 2))
                    .filter(parts -> parts.length == 2 && "condition_ids".equals(parts[0]))
                    .map(parts -> URLDecoder.decode(parts[1], StandardCharsets.UTF_8))
                    .findFirst()
                    .orElse("");
            List<String> ids = conditionIds.isBlank() ? List.of() : Arrays.asList(conditionIds.split(","));
            requestChunkSizes.add(ids.size());
            String response = ids.stream()
                    .map(id -> """
                            {"conditionId":"%s","question":"Question %s","outcomes":["Yes","No"],"outcomePrices":["0.5","0.5"]}
                            """.formatted(id, id))
                    .collect(Collectors.joining(",", "[", "]"));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            SmartWalletProperties properties = new SmartWalletProperties();
            properties.getPolymarket().setGammaApiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.getMarketMonitoring().getMetadataRefresh().setGammaChunkSize(5);
            PolymarketApiClient client = new PolymarketApiClient(properties, new ObjectMapper());

            Map<String, PolymarketApiClient.MarketMetadata> result = client.fetchMarketMetadataBatch(List.of(
                    "condition-1", "condition-2", "condition-3", "condition-4", "condition-5", "condition-6",
                    "condition-7", "condition-8", "condition-9", "condition-10", "condition-11", "condition-12"
            ), 20);

            assertEquals(12, result.size());
            assertEquals(List.of(5, 5, 2), requestChunkSizes);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldEnterEndpointCooldownAfterConsecutiveMetadataFailures() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requestCount = new AtomicInteger();
        server.createContext("/markets", exchange -> {
            requestCount.incrementAndGet();
            byte[] bytes = "{\"error\":\"temporary\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(500, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            SmartWalletProperties properties = new SmartWalletProperties();
            properties.getPolymarket().setGammaApiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.getMarketMonitoring().getMetadataRefresh().setEndpointFailureThreshold(2);
            properties.getMarketMonitoring().getMetadataRefresh().setMetadataEndpointCooldownMs(300_000L);
            PolymarketApiClient client = new PolymarketApiClient(properties, new ObjectMapper());

            client.fetchMarketMetadataBatch(List.of("condition-a"), 1);
            assertFalse(client.isMetadataEndpointCoolingDown());

            client.fetchMarketMetadataBatch(List.of("condition-b"), 1);
            assertTrue(client.isMetadataEndpointCoolingDown());

            client.fetchMarketMetadataBatch(List.of("condition-c"), 1);
            assertEquals(2, requestCount.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldNotInferResolvedOutcomeOnlyFromClosedMarketPrices() throws Exception {
        PolymarketApiClient client = new PolymarketApiClient(new SmartWalletProperties(), new ObjectMapper());
        JsonNode market = new ObjectMapper().readTree("""
                {
                  "conditionId": "condition-1",
                  "id": "market-1",
                  "question": "Will oracle wait?",
                  "closed": true,
                  "acceptingOrders": false,
                  "outcomes": ["Yes", "No"],
                  "outcomePrices": ["1.0", "0.0"]
                }
                """);

        Method method = PolymarketApiClient.class.getDeclaredMethod("parseMarketMetadata", JsonNode.class);
        method.setAccessible(true);
        PolymarketApiClient.MarketMetadata metadata = (PolymarketApiClient.MarketMetadata) method.invoke(client, market);

        assertTrue(metadata.isClosed());
        assertFalse(metadata.isAcceptingOrders());
        assertNull(metadata.getResolvedOutcome());
        assertFalse(metadata.isResolved());
    }

    @Test
    void shouldParseUmaResolutionFieldsFromMarketMetadata() throws Exception {
        PolymarketApiClient client = new PolymarketApiClient(new SmartWalletProperties(), new ObjectMapper());
        JsonNode market = new ObjectMapper().readTree("""
                {
                  "conditionId": "condition-2",
                  "id": "market-2",
                  "questionID": "0xquestion",
                  "question": "Will UMA phase be visible?",
                  "closed": false,
                  "acceptingOrders": false,
                  "umaResolutionStatus": "disputed",
                  "umaEndDate": "2026-04-13T00:00:00Z",
                  "outcomes": ["Yes", "No"],
                  "outcomePrices": ["0.55", "0.45"]
                }
                """);

        Method method = PolymarketApiClient.class.getDeclaredMethod("parseMarketMetadata", JsonNode.class);
        method.setAccessible(true);
        PolymarketApiClient.MarketMetadata metadata = (PolymarketApiClient.MarketMetadata) method.invoke(client, market);

        assertEquals("0xquestion", metadata.getQuestionId());
        assertEquals("disputed", metadata.getUmaResolutionStatus());
        assertEquals(1776038400000L, metadata.getUmaEndAt());
    }

    @Test
    void shouldFlattenMarketPositionsResponseFromNewEndpoint() throws Exception {
        PolymarketApiClient client = new PolymarketApiClient(new SmartWalletProperties(), new ObjectMapper());
        JsonNode body = new ObjectMapper().readTree("""
                [
                  {
                    "token": "yes-token",
                    "positions": [
                      {
                        "proxyWallet": "0x1111111111111111111111111111111111111111",
                        "outcome": "Yes",
                        "size": 15,
                        "avgPrice": 0.61,
                        "totalBought": 9.15
                      }
                    ]
                  },
                  {
                    "token": "no-token",
                    "positions": [
                      {
                        "proxyWallet": "0x2222222222222222222222222222222222222222",
                        "outcome": "No",
                        "size": 30,
                        "avgPrice": 0.42,
                        "totalBought": 12.6
                      }
                    ]
                  }
                ]
                """);

        Method method = PolymarketApiClient.class.getDeclaredMethod("parseMarketTopPositions", JsonNode.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<PolymarketApiClient.MarketPositionEntry> entries =
                (List<PolymarketApiClient.MarketPositionEntry>) method.invoke(client, body);

        assertEquals(2, entries.size());
        assertEquals("0x2222222222222222222222222222222222222222", entries.get(0).getWalletAddress());
        assertEquals("NO", entries.get(0).getOutcome());
        assertEquals(0, new BigDecimal("30").compareTo(entries.get(0).getSize()));
        assertEquals(0, new BigDecimal("12.6").compareTo(entries.get(0).getInitialValue()));
        assertEquals("YES", entries.get(1).getOutcome());
    }

    @Test
    void shouldRejectNonJsonResponseBodiesBeforeParsing() throws Exception {
        PolymarketApiClient client = new PolymarketApiClient(new SmartWalletProperties(), new ObjectMapper());
        Method method = PolymarketApiClient.class.getDeclaredMethod("parseJsonBody", String.class, URI.class, String.class);
        method.setAccessible(true);

        InvocationTargetException error = assertThrows(InvocationTargetException.class,
                () -> method.invoke(client,
                        "error code: 524",
                        URI.create("https://gamma-api.polymarket.com/markets?condition_ids=condition-3"),
                        "curl fallback"));

        assertTrue(error.getCause() instanceof IOException);
        assertTrue(error.getCause().getMessage().contains("non-JSON response"));
        assertTrue(error.getCause().getMessage().contains("error code: 524"));
    }

    @Test
    void shouldParseValidJsonResponseBodiesThroughSharedHelper() throws Exception {
        PolymarketApiClient client = new PolymarketApiClient(new SmartWalletProperties(), new ObjectMapper());
        Method method = PolymarketApiClient.class.getDeclaredMethod("parseJsonBody", String.class, URI.class, String.class);
        method.setAccessible(true);

        JsonNode parsed = (JsonNode) method.invoke(client,
                """
                [
                  {
                    "conditionId": "condition-4"
                  }
                ]
                """,
                URI.create("https://gamma-api.polymarket.com/markets?condition_ids=condition-4"),
                "http");

        assertTrue(parsed.isArray());
        assertEquals("condition-4", parsed.get(0).get("conditionId").asText());
    }
}
