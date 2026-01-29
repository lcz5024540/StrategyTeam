package com.metaverse.coin.strategy.common.client.binance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.common.model.binance.entity.alpha.BinanceAlphaOrder;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

public class BinanceAlphaOrderParsingTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void testDeserialize() throws Exception {
        String json = "{" +
                "\"algoId\": 12345," +
                "\"clientAlgoId\": \"test_algo_1\"," +
                "\"symbol\": \"BTCUSDT\"," +
                "\"side\": \"BUY\"," +
                "\"algoType\": \"VP\"," +
                "\"totalQty\": \"1.5\"," +
                "\"executedQty\": \"0.5\"," +
                "\"executedAmt\": \"25000.0\"," +
                "\"avgPrice\": \"50000.0\"," +
                "\"algoStatus\": \"WORKING\"," +
                "\"bookTime\": 1600000000000," +
                "\"endTime\": 1600001000000," +
                "\"success\": true," +
                "\"code\": \"200\"," +
                "\"msg\": \"OK\"" +
                "}";

        BinanceAlphaOrder order = mapper.readValue(json, BinanceAlphaOrder.class);

        assertEquals(12345L, order.getAlgoId());
        assertEquals("test_algo_1", order.getClientAlgoId());
        assertEquals("BTCUSDT", order.getSymbol());
        assertEquals("BUY", order.getSide());
        assertEquals("VP", order.getAlgoType());
        assertEquals(new BigDecimal("1.5"), order.getTotalQty());
        assertEquals(new BigDecimal("0.5"), order.getExecutedQty());
        assertEquals(new BigDecimal("25000.0"), order.getExecutedAmt());
        assertEquals(new BigDecimal("50000.0"), order.getAvgPrice());
        assertEquals("WORKING", order.getAlgoStatus());
        assertEquals(1600000000000L, order.getBookTime());
        assertEquals(1600001000000L, order.getEndTime());
        assertTrue(order.getSuccess());
    }
}
