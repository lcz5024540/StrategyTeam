package com.metaverse.coin.strategy.common.model.okx.entity.rest.market;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.math.BigDecimal;

public class OkxCandleDeserializer extends JsonDeserializer<OkxCandle> {
    @Override
    public OkxCandle deserialize(JsonParser p, DeserializationContext ctxt) throws IOException, JsonProcessingException {
        JsonNode node = p.getCodec().readTree(p);
        if (node.isArray()) {
            OkxCandle candle = new OkxCandle();
            if (node.size() > 0) candle.setTs(Long.parseLong(node.get(0).asText()));
            if (node.size() > 1) candle.setO(new BigDecimal(node.get(1).asText()));
            if (node.size() > 2) candle.setH(new BigDecimal(node.get(2).asText()));
            if (node.size() > 3) candle.setL(new BigDecimal(node.get(3).asText()));
            if (node.size() > 4) candle.setC(new BigDecimal(node.get(4).asText()));
            if (node.size() > 5) candle.setVol(new BigDecimal(node.get(5).asText()));
            if (node.size() > 6) candle.setVolCcy(new BigDecimal(node.get(6).asText()));
            
            if (node.size() >= 9) {
                candle.setVolCcyQuote(new BigDecimal(node.get(7).asText()));
                candle.setConfirm(node.get(8).asText());
            } else if (node.size() == 8) {
                // Assuming missing volCcyQuote for Options
                candle.setConfirm(node.get(7).asText());
            }
            return candle;
        }
        return null;
    }
}
