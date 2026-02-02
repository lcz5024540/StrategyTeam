package com.metaverse.coin.strategy.common.client.binance.mock;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.common.model.binance.entity.simpleearn.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class BinanceSimpleEarnModelTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    public void testFlexibleProductListParsing() throws IOException {
        String json = "{\n" +
                "  \"rows\": [\n" +
                "    {\n" +
                "      \"asset\": \"BTC\",\n" +
                "      \"productId\": \"BTC001\",\n" +
                "      \"latestAnnualPercentageRate\": \"0.05\",\n" +
                "      \"tierAnnualPercentageRate\": {\n" +
                "        \"0-5\": \"0.05\",\n" +
                "        \"5-10\": \"0.03\"\n" +
                "      },\n" +
                "      \"canPurchase\": true,\n" +
                "      \"canRedeem\": true,\n" +
                "      \"isSoldOut\": false,\n" +
                "      \"hot\": true,\n" +
                "      \"minPurchaseAmount\": \"0.001\",\n" +
                "      \"maxPurchaseAmount\": \"100\",\n" +
                "      \"minRedeemAmount\": \"0.001\",\n" +
                "      \"status\": \"SUBSCRIBABLE\"\n" +
                "    }\n" +
                "  ],\n" +
                "  \"total\": 1\n" +
                "}";

        BinanceSimpleEarnListResponse<BinanceSimpleEarnFlexibleProduct> response = objectMapper.readValue(json, new TypeReference<BinanceSimpleEarnListResponse<BinanceSimpleEarnFlexibleProduct>>() {});
        
        assertNotNull(response);
        assertEquals(1L, response.getTotal());
        assertNotNull(response.getRows());
        assertEquals(1, response.getRows().size());
        
        BinanceSimpleEarnFlexibleProduct product = response.getRows().get(0);
        assertEquals("BTC", product.getAsset());
        assertEquals("BTC001", product.getProductId());
        assertEquals(new BigDecimal("0.05"), product.getLatestAnnualPercentageRate());
        assertEquals(new BigDecimal("0.05"), product.getTierAnnualPercentageRate().get("0-5"));
        assertTrue(product.getCanPurchase());
        assertTrue(product.getCanRedeem());
        assertFalse(product.getIsSoldOut());
        assertTrue(product.getHot());
        assertEquals(new BigDecimal("0.001"), product.getMinPurchaseAmount());
        assertEquals("SUBSCRIBABLE", product.getStatus());
    }

    @Test
    public void testLockedProductListParsing() throws IOException {
        String json = "{\n" +
                "  \"rows\": [\n" +
                "    {\n" +
                "      \"projectId\": \"BTC001\",\n" +
                "      \"asset\": \"BTC\",\n" +
                "      \"rewardAsset\": \"BTC\",\n" +
                "      \"duration\": 30,\n" +
                "      \"interestRate\": \"0.05\",\n" +
                "      \"extraRewardAsset\": \"BNB\",\n" +
                "      \"extraRewardAPR\": \"0.01\",\n" +
                "      \"minPurchaseAmount\": \"0.001\",\n" +
                "      \"maxPurchaseAmount\": \"100\",\n" +
                "      \"status\": \"SUBSCRIBABLE\",\n" +
                "      \"autoSubscribe\": false\n" +
                "    }\n" +
                "  ],\n" +
                "  \"total\": 1\n" +
                "}";

        BinanceSimpleEarnListResponse<BinanceSimpleEarnLockedProduct> response = objectMapper.readValue(json, new TypeReference<BinanceSimpleEarnListResponse<BinanceSimpleEarnLockedProduct>>() {});
        
        assertNotNull(response);
        assertEquals(1L, response.getTotal());
        
        BinanceSimpleEarnLockedProduct product = response.getRows().get(0);
        assertEquals("BTC001", product.getProjectId());
        assertEquals("BTC", product.getAsset());
        assertEquals(30, product.getDuration());
        assertEquals(new BigDecimal("0.05"), product.getInterestRate());
        assertEquals("BNB", product.getExtraRewardAsset());
        assertEquals(new BigDecimal("0.01"), product.getExtraRewardAPR());
    }

    @Test
    public void testAccountParsing() throws IOException {
        String json = "{\n" +
                "  \"totalAmountInBTC\": \"0.01234567\",\n" +
                "  \"totalAmountInUSDT\": \"1234.56\",\n" +
                "  \"totalFlexibleAmountInBTC\": \"0.01\",\n" +
                "  \"totalFlexibleAmountInUSDT\": \"1000.00\",\n" +
                "  \"totalLockedAmountInBTC\": \"0.00234567\",\n" +
                "  \"totalLockedAmountInUSDT\": \"234.56\"\n" +
                "}";

        BinanceSimpleEarnAccount account = objectMapper.readValue(json, BinanceSimpleEarnAccount.class);
        
        assertNotNull(account);
        assertEquals(new BigDecimal("0.01234567"), account.getTotalAmountInBTC());
        assertEquals(new BigDecimal("1234.56"), account.getTotalAmountInUSDT());
        assertEquals(new BigDecimal("0.01"), account.getTotalFlexibleAmountInBTC());
        assertEquals(new BigDecimal("0.00234567"), account.getTotalLockedAmountInBTC());
    }
}
