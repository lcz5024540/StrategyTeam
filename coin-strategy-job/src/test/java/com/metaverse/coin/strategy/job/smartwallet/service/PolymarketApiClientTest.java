package com.metaverse.coin.strategy.job.smartwallet.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
