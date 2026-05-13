package com.metaverse.coin.strategy.job.smartwallet.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
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
    void shouldRequestRecentUserTradesByTimestampDescAndSortResponseDefensively() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<Map<String, String>> requests = new ArrayList<>();
        server.createContext("/trades", exchange -> {
            requests.add(parseQuery(exchange.getRequestURI().getRawQuery()));
            byte[] bytes = """
                    [
                      {"id":"old","conditionId":"market-1","outcome":"Yes","side":"BUY","price":"0.40","size":"10","timestamp":1700000000000,"status":"CONFIRMED"},
                      {"id":"new","conditionId":"market-2","outcome":"No","side":"SELL","price":"0.60","size":"20","timestamp":1700000020000,"status":"CONFIRMED"},
                      {"id":"mid","conditionId":"market-3","outcome":"Yes","side":"BUY","price":"0.50","size":"15","timestamp":1700000010000,"status":"CONFIRMED"}
                    ]
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            SmartWalletProperties properties = new SmartWalletProperties();
            properties.getPolymarket().setDataApiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            PolymarketApiClient client = new PolymarketApiClient(properties, new ObjectMapper());

            List<PolymarketApiClient.UserTrade> trades = client.fetchUserTrades("0xwallet", 3, 0);

            assertEquals(List.of("new", "mid", "old"), trades.stream().map(PolymarketApiClient.UserTrade::getTradeId).toList());
            assertEquals("TIMESTAMP", requests.get(0).get("sortBy"));
            assertEquals("DESC", requests.get(0).get("sortDirection"));
            assertEquals("true", requests.get(0).get("takerOnly"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldKeepRecentUserTradeHistorySortedChronologicallyAndStopAtLookbackCutoff() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<Integer> offsets = new ArrayList<>();
        long now = System.currentTimeMillis();
        long recent = now - java.util.concurrent.TimeUnit.DAYS.toMillis(5);
        long mid = now - java.util.concurrent.TimeUnit.DAYS.toMillis(10);
        long stale = now - java.util.concurrent.TimeUnit.DAYS.toMillis(91);
        server.createContext("/trades", exchange -> {
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            offsets.add(Integer.parseInt(query.get("offset")));
            String response = switch (query.get("offset")) {
                case "0" -> """
                        [
                          {"id":"new","conditionId":"market-2","outcome":"No","side":"SELL","price":"0.60","size":"20","timestamp":%d,"status":"CONFIRMED"},
                          {"id":"mid","conditionId":"market-3","outcome":"Yes","side":"BUY","price":"0.50","size":"15","timestamp":%d,"status":"CONFIRMED"}
                        ]
                        """.formatted(recent, mid);
                case "2" -> """
                        [
                          {"id":"stale","conditionId":"market-1","outcome":"Yes","side":"BUY","price":"0.40","size":"10","timestamp":%d,"status":"CONFIRMED"},
                          {"id":"older","conditionId":"market-0","outcome":"Yes","side":"BUY","price":"0.30","size":"8","timestamp":%d,"status":"CONFIRMED"}
                        ]
                        """.formatted(stale, stale - 1_000L);
                default -> "[]";
            };
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            SmartWalletProperties properties = new SmartWalletProperties();
            properties.getPolymarket().setDataApiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            PolymarketApiClient client = new PolymarketApiClient(properties, new ObjectMapper());

            List<PolymarketApiClient.UserTrade> trades = client.fetchAllUserTrades("0xwallet", 2);

            assertEquals(List.of("mid", "new"), trades.stream().map(PolymarketApiClient.UserTrade::getTradeId).toList());
            assertEquals(List.of(0, 2), offsets);
        } finally {
            server.stop(0);
        }
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
    void shouldInferResolvedOutcomeFromTerminalFinalPrices() throws Exception {
        PolymarketApiClient client = new PolymarketApiClient(new SmartWalletProperties(), new ObjectMapper());
        JsonNode market = new ObjectMapper().readTree("""
                {
                  "conditionId": "condition-1",
                  "id": "market-1",
                  "question": "Will oracle wait?",
                  "closed": true,
                  "acceptingOrders": false,
                  "outcomes": ["Yes", "No"],
                  "clobTokenIds": ["yes-token", "no-token"],
                  "outcomePrices": ["1.0", "0.0"]
                }
                """);

        Method method = PolymarketApiClient.class.getDeclaredMethod("parseMarketMetadata", JsonNode.class);
        method.setAccessible(true);
        PolymarketApiClient.MarketMetadata metadata = (PolymarketApiClient.MarketMetadata) method.invoke(client, market);

        assertTrue(metadata.isClosed());
        assertFalse(metadata.isAcceptingOrders());
        assertEquals("YES", metadata.getResolvedOutcome());
        assertEquals("yes-token", metadata.getWinningTokenId());
        assertEquals("FINAL_PRICE_INFERENCE", metadata.getResolutionSource());
        assertTrue(metadata.isResolved());
    }

    @Test
    void shouldParseTokenWinnerAsResolvedOutcome() throws Exception {
        PolymarketApiClient client = new PolymarketApiClient(new SmartWalletProperties(), new ObjectMapper());
        JsonNode market = new ObjectMapper().readTree("""
                {
                  "conditionId": "condition-token-winner",
                  "id": "market-token-winner",
                  "question": "Did home win?",
                  "closed": false,
                  "acceptingOrders": false,
                  "outcomes": ["Yes", "No"],
                  "tokens": [
                    {"outcome": "Yes", "token_id": "yes-token", "winner": true},
                    {"outcome": "No", "token_id": "no-token", "winner": false}
                  ]
                }
                """);

        Method method = PolymarketApiClient.class.getDeclaredMethod("parseMarketMetadata", JsonNode.class);
        method.setAccessible(true);
        PolymarketApiClient.MarketMetadata metadata = (PolymarketApiClient.MarketMetadata) method.invoke(client, market);

        assertEquals("YES", metadata.getResolvedOutcome());
        assertEquals("yes-token", metadata.getWinningTokenId());
        assertEquals("TOKEN_WINNER", metadata.getResolutionSource());
        assertTrue(metadata.isResolved());
    }

    @Test
    void shouldParseStringifiedTokensAsResolvedOutcome() throws Exception {
        PolymarketApiClient client = new PolymarketApiClient(new SmartWalletProperties(), new ObjectMapper());
        JsonNode market = new ObjectMapper().readTree("""
                {
                  "conditionId": "condition-stringified-token-winner",
                  "id": "market-stringified-token-winner",
                  "question": "Did home win?",
                  "closed": false,
                  "acceptingOrders": false,
                  "outcomes": "[\\"Yes\\",\\"No\\"]",
                  "outcomePrices": "[\\"0\\",\\"1\\"]",
                  "tokens": "[{\\"outcome\\":\\"Yes\\",\\"token_id\\":\\"yes-token\\",\\"winner\\":false},{\\"outcome\\":\\"No\\",\\"token_id\\":\\"no-token\\",\\"winner\\":true}]"
                }
                """);

        Method method = PolymarketApiClient.class.getDeclaredMethod("parseMarketMetadata", JsonNode.class);
        method.setAccessible(true);
        PolymarketApiClient.MarketMetadata metadata = (PolymarketApiClient.MarketMetadata) method.invoke(client, market);

        assertEquals("NO", metadata.getResolvedOutcome());
        assertEquals("no-token", metadata.getWinningTokenId());
        assertEquals("TOKEN_WINNER", metadata.getResolutionSource());
        assertEquals(MarketResolutionResolver.STATUS_RESOLVED, metadata.getResolutionStatus());
        assertTrue(metadata.isResolved());
    }

    @Test
    void shouldMapTopLevelWinningTokenIdToResolvedOutcome() throws Exception {
        PolymarketApiClient client = new PolymarketApiClient(new SmartWalletProperties(), new ObjectMapper());
        JsonNode market = new ObjectMapper().readTree("""
                {
                  "conditionId": "condition-winning-token",
                  "id": "market-winning-token",
                  "question": "Did away win?",
                  "closed": false,
                  "acceptingOrders": false,
                  "outcomes": ["Yes", "No"],
                  "clobTokenIds": ["yes-token", "no-token"],
                  "winningTokenId": "no-token"
                }
                """);

        Method method = PolymarketApiClient.class.getDeclaredMethod("parseMarketMetadata", JsonNode.class);
        method.setAccessible(true);
        PolymarketApiClient.MarketMetadata metadata = (PolymarketApiClient.MarketMetadata) method.invoke(client, market);

        assertEquals("NO", metadata.getResolvedOutcome());
        assertEquals("no-token", metadata.getWinningTokenId());
        assertEquals("WINNING_TOKEN_ID", metadata.getResolutionSource());
        assertTrue(metadata.isResolved());
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

    private Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return result;
        }
        for (String part : rawQuery.split("&")) {
            String[] pieces = part.split("=", 2);
            if (pieces.length == 2) {
                result.put(
                        URLDecoder.decode(pieces[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(pieces[1], StandardCharsets.UTF_8));
            }
        }
        return result;
    }
}
