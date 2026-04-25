package com.metaverse.coin.strategy.job.smartwallet.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolymarketApiClientTest {

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
