package com.metaverse.coin.strategy.job.weather.service;

import com.metaverse.coin.strategy.job.weather.model.WeatherMarketSpec;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a Polymarket market title into a WeatherMarketSpec.
 * Returns empty if the title cannot be understood.
 */
@Service
public class WeatherMarketTitleParser {

    /** keyword → canonical city name (checked case-insensitively against title) */
    private static final Map<String, String> CITY_KEYWORDS = new LinkedHashMap<>();

    static {
        CITY_KEYWORDS.put("london",   "London");
        CITY_KEYWORDS.put("new york", "New York");
        CITY_KEYWORDS.put("nyc",      "New York");
        CITY_KEYWORDS.put("seoul",    "Seoul");
    }

    // Matches patterns like "exceed 68°F", "above 68°F", "high 68 degrees"
    private static final Pattern HIGH_PATTERN = Pattern.compile(
            "(?:high|exceed|above|over)[^0-9]*([0-9]+(?:\\.[0-9]+)?)[^a-z]*(?:°f|degrees|°)",
            Pattern.CASE_INSENSITIVE);
    // Matches patterns like "low below 45°F", "below 45°F", "under 45 degrees"
    private static final Pattern LOW_PATTERN = Pattern.compile(
            "(?:low|below|under)[^0-9]*([0-9]+(?:\\.[0-9]+)?)[^a-z]*(?:°f|degrees|°)",
            Pattern.CASE_INSENSITIVE);

    public Optional<WeatherMarketSpec> parse(String marketId,
                                             String tokenId,
                                             String question,
                                             BigDecimal yesPrice,
                                             BigDecimal noPrice,
                                             BigDecimal liquidityUsdc,
                                             long endTimestampSeconds) {
        String city = detectCity(question);
        if (city == null) return Optional.empty();

        // Try MAX (high/exceed/above)
        Matcher highMatcher = HIGH_PATTERN.matcher(question);
        if (highMatcher.find()) {
            return Optional.of(WeatherMarketSpec.builder()
                    .marketId(marketId).tokenId(tokenId).question(question)
                    .city(city)
                    .temperatureType(WeatherMarketSpec.TemperatureType.MAX)
                    .thresholdF(new BigDecimal(highMatcher.group(1)))
                    .yesPrice(yesPrice).noPrice(noPrice)
                    .liquidityUsdc(liquidityUsdc)
                    .endTimestampSeconds(endTimestampSeconds)
                    .build());
        }

        // Try MIN (low/below/under)
        Matcher lowMatcher = LOW_PATTERN.matcher(question);
        if (lowMatcher.find()) {
            return Optional.of(WeatherMarketSpec.builder()
                    .marketId(marketId).tokenId(tokenId).question(question)
                    .city(city)
                    .temperatureType(WeatherMarketSpec.TemperatureType.MIN)
                    .thresholdF(new BigDecimal(lowMatcher.group(1)))
                    .yesPrice(yesPrice).noPrice(noPrice)
                    .liquidityUsdc(liquidityUsdc)
                    .endTimestampSeconds(endTimestampSeconds)
                    .build());
        }

        return Optional.empty();
    }

    private String detectCity(String question) {
        String lower = question.toLowerCase();
        for (Map.Entry<String, String> entry : CITY_KEYWORDS.entrySet()) {
            if (lower.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }
}
