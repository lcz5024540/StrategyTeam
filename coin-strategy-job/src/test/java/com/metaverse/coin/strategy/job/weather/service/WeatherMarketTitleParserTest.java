package com.metaverse.coin.strategy.job.weather.service;

import com.metaverse.coin.strategy.job.weather.model.WeatherMarketSpec;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class WeatherMarketTitleParserTest {

    private final WeatherMarketTitleParser parser = new WeatherMarketTitleParser();

    @Test
    void parse_londonHighExceed_returnsMaxSpec() {
        Optional<WeatherMarketSpec> result = parser.parse(
                "mkt-001", "tok-001",
                "Will London high exceed 68°F tomorrow?",
                new BigDecimal("0.05"), new BigDecimal("0.95"),
                new BigDecimal("200"), System.currentTimeMillis() / 1000 + 3600 * 24);

        assertTrue(result.isPresent());
        WeatherMarketSpec spec = result.get();
        assertEquals("London", spec.getCity());
        assertEquals(WeatherMarketSpec.TemperatureType.MAX, spec.getTemperatureType());
        assertEquals(new BigDecimal("68"), spec.getThresholdF());
    }

    @Test
    void parse_nycLowBelow_returnsMinSpec() {
        Optional<WeatherMarketSpec> result = parser.parse(
                "mkt-002", "tok-002",
                "NYC low below 45°F on April 23?",
                new BigDecimal("0.07"), new BigDecimal("0.93"),
                new BigDecimal("100"), System.currentTimeMillis() / 1000 + 3600 * 30);

        assertTrue(result.isPresent());
        WeatherMarketSpec spec = result.get();
        assertEquals("New York", spec.getCity());
        assertEquals(WeatherMarketSpec.TemperatureType.MIN, spec.getTemperatureType());
        assertEquals(new BigDecimal("45"), spec.getThresholdF());
    }

    @Test
    void parse_seoulTitle_returnsSeoul() {
        Optional<WeatherMarketSpec> result = parser.parse(
                "mkt-003", "tok-003",
                "Will Seoul high exceed 75°F tomorrow?",
                new BigDecimal("0.04"), new BigDecimal("0.96"),
                new BigDecimal("80"), System.currentTimeMillis() / 1000 + 3600 * 20);

        assertTrue(result.isPresent());
        assertEquals("Seoul", result.get().getCity());
    }

    @Test
    void parse_unrecognizedCity_returnsEmpty() {
        Optional<WeatherMarketSpec> result = parser.parse(
                "mkt-004", "tok-004",
                "Will Tokyo high exceed 80°F tomorrow?",
                new BigDecimal("0.05"), new BigDecimal("0.95"),
                new BigDecimal("100"), System.currentTimeMillis() / 1000 + 3600 * 20);

        assertTrue(result.isEmpty());
    }

    @Test
    void parse_noTemperatureNumber_returnsEmpty() {
        Optional<WeatherMarketSpec> result = parser.parse(
                "mkt-005", "tok-005",
                "Will London be hot tomorrow?",
                new BigDecimal("0.05"), new BigDecimal("0.95"),
                new BigDecimal("100"), System.currentTimeMillis() / 1000 + 3600 * 20);

        assertTrue(result.isEmpty());
    }
}
