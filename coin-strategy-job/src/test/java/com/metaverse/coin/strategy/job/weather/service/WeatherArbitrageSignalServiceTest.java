package com.metaverse.coin.strategy.job.weather.service;

import com.metaverse.coin.strategy.job.weather.config.WeatherArbitrageProperties;
import com.metaverse.coin.strategy.job.weather.model.WeatherArbitrageSignal;
import com.metaverse.coin.strategy.job.weather.model.WeatherFairValueResult;
import com.metaverse.coin.strategy.job.weather.model.WeatherMarketSpec;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeatherArbitrageSignalServiceTest {

    private final WeatherArbitrageProperties properties = new WeatherArbitrageProperties();
    private final WeatherArbitrageSignalService service = new WeatherArbitrageSignalService(properties);

    @Test
    void evaluate_highConfidenceEdge_returnsBuySignal() {
        WeatherMarketSpec spec = WeatherMarketSpec.builder()
                .marketId("m1")
                .tokenId("t1")
                .city("London")
                .question("Will London high exceed 68°F tomorrow?")
                .yesPrice(new BigDecimal("0.10"))
                .noPrice(new BigDecimal("0.90"))
                .liquidityUsdc(new BigDecimal("100"))
                .parseConfidence(new BigDecimal("0.95"))
                .build();
        WeatherFairValueResult result = WeatherFairValueResult.builder()
                .actionable(true)
                .fairValue(new BigDecimal("0.75"))
                .confidence(new BigDecimal("0.80"))
                .forecastAgeMinutes(10)
                .leadHours(24)
                .calibrationVersion("v1")
                .build();

        WeatherArbitrageSignal signal = service.evaluate(spec, result);

        assertEquals(WeatherArbitrageSignal.Side.BUY_YES, signal.getSide());
        assertTrue(signal.getEdge().compareTo(properties.getEntryEdgeThreshold()) > 0);
    }

    @Test
    void evaluate_lowConfidence_returnsPass() {
        WeatherMarketSpec spec = WeatherMarketSpec.builder()
                .marketId("m1")
                .tokenId("t1")
                .city("London")
                .question("Will London high exceed 68°F tomorrow?")
                .yesPrice(new BigDecimal("0.10"))
                .noPrice(new BigDecimal("0.90"))
                .liquidityUsdc(new BigDecimal("100"))
                .parseConfidence(new BigDecimal("0.20"))
                .build();
        WeatherFairValueResult result = WeatherFairValueResult.builder()
                .actionable(true)
                .fairValue(new BigDecimal("0.75"))
                .confidence(new BigDecimal("0.80"))
                .forecastAgeMinutes(10)
                .leadHours(24)
                .calibrationVersion("v1")
                .build();

        WeatherArbitrageSignal signal = service.evaluate(spec, result);

        assertEquals(WeatherArbitrageSignal.Side.PASS, signal.getSide());
        assertEquals("low_parse_confidence", signal.getReason());
    }
}
