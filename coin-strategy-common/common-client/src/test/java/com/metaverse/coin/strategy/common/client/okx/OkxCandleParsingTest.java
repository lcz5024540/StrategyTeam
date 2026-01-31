package com.metaverse.coin.strategy.common.client.okx;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.market.OkxCandle;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

public class OkxCandleParsingTest {

    @Test
    public void testDeserialization() throws IOException {
        // 8 elements (Option style or missing quote)
        String json = "[\"1597026383085\",\"3.721\",\"3.743\",\"3.677\",\"3.708\",\"8422410\",\"3129479.12\",\"1\"]";
        ObjectMapper mapper = new ObjectMapper();
        OkxCandle candle = mapper.readValue(json, OkxCandle.class);

        assertNotNull(candle);
        assertEquals(1597026383085L, candle.getTs());
        assertEquals(new BigDecimal("3.721"), candle.getO());
        assertEquals(new BigDecimal("3.743"), candle.getH());
        assertEquals(new BigDecimal("3.677"), candle.getL());
        assertEquals(new BigDecimal("3.708"), candle.getC());
        assertEquals(new BigDecimal("8422410"), candle.getVol());
        assertEquals(new BigDecimal("3129479.12"), candle.getVolCcy());
        assertEquals("1", candle.getConfirm());
        assertNull(candle.getVolCcyQuote());
    }

    @Test
    public void testDeserializationWithQuote() throws IOException {
        // 9 elements (Spot/Margin style)
        String json = "[\"1597026383085\",\"3.721\",\"3.743\",\"3.677\",\"3.708\",\"8422410\",\"3129479.12\",\"100.5\",\"1\"]";
        ObjectMapper mapper = new ObjectMapper();
        OkxCandle candle = mapper.readValue(json, OkxCandle.class);

        assertNotNull(candle);
        assertEquals(new BigDecimal("3129479.12"), candle.getVolCcy());
        assertEquals(new BigDecimal("100.5"), candle.getVolCcyQuote());
        assertEquals("1", candle.getConfirm());
    }
}
