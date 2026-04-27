package com.metaverse.coin.strategy.job.weather.service;

import com.metaverse.coin.strategy.job.weather.config.WeatherArbitrageProperties;
import com.metaverse.coin.strategy.job.weather.model.WeatherMarketSpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a Polymarket market title into a WeatherMarketSpec.
 * Returns empty if the title cannot be understood.
 */
@Service
@RequiredArgsConstructor
public class WeatherMarketTitleParser {

    private static final Pattern HIGH_PATTERN = Pattern.compile(
            "(?:high|exceed|above|over|at least)[^0-9]*([0-9]+(?:\\.[0-9]+)?)[^a-z]*(?:°f|degrees|°)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LOW_PATTERN = Pattern.compile(
            "(?:low|below|under|at most)[^0-9]*([0-9]+(?:\\.[0-9]+)?)[^a-z]*(?:°f|degrees|°)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE_PATTERN = Pattern.compile(
            "(?:on\\s+)?([A-Z][a-z]+\\s+\\d{1,2})",
            Pattern.CASE_INSENSITIVE);

    private final WeatherArbitrageProperties properties;

    public Optional<WeatherMarketSpec> parse(String marketId,
                                             String tokenId,
                                             String question,
                                             BigDecimal yesPrice,
                                             BigDecimal noPrice,
                                             BigDecimal liquidityUsdc,
                                             long endTimestampSeconds) {
        if (!StringUtils.hasText(question)) {
            return Optional.empty();
        }
        String city = detectCity(question);
        if (city == null) {
            return Optional.empty();
        }
        LocalDate targetDate = resolveTargetDate(question, endTimestampSeconds, city);
        BigDecimal parseConfidence = new BigDecimal("0.60");

        Matcher highMatcher = HIGH_PATTERN.matcher(question);
        if (highMatcher.find()) {
            return Optional.of(WeatherMarketSpec.builder()
                    .marketId(marketId)
                    .tokenId(tokenId)
                    .question(question)
                    .city(city)
                    .temperatureType(WeatherMarketSpec.TemperatureType.MAX)
                    .thresholdF(new BigDecimal(highMatcher.group(1)))
                    .yesPrice(yesPrice)
                    .noPrice(noPrice)
                    .liquidityUsdc(liquidityUsdc)
                    .endTimestampSeconds(endTimestampSeconds)
                    .targetDate(targetDate)
                    .parseConfidence(parseConfidence.add(new BigDecimal("0.20")))
                    .parseReason("matched_high_pattern")
                    .build());
        }

        Matcher lowMatcher = LOW_PATTERN.matcher(question);
        if (lowMatcher.find()) {
            return Optional.of(WeatherMarketSpec.builder()
                    .marketId(marketId)
                    .tokenId(tokenId)
                    .question(question)
                    .city(city)
                    .temperatureType(WeatherMarketSpec.TemperatureType.MIN)
                    .thresholdF(new BigDecimal(lowMatcher.group(1)))
                    .yesPrice(yesPrice)
                    .noPrice(noPrice)
                    .liquidityUsdc(liquidityUsdc)
                    .endTimestampSeconds(endTimestampSeconds)
                    .targetDate(targetDate)
                    .parseConfidence(parseConfidence.add(new BigDecimal("0.20")))
                    .parseReason("matched_low_pattern")
                    .build());
        }

        return Optional.empty();
    }

    private String detectCity(String question) {
        String lower = question.toLowerCase();
        for (WeatherArbitrageProperties.CityRule cityRule : properties.getCities()) {
            if (cityRule.getAliases() == null) {
                continue;
            }
            for (String alias : cityRule.getAliases()) {
                if (lower.contains(alias.toLowerCase())) {
                    return cityRule.getName();
                }
            }
        }
        return null;
    }

    private LocalDate resolveTargetDate(String question, long endTimestampSeconds, String city) {
        String lower = question.toLowerCase();
        if (lower.contains("tomorrow")) {
            return cityNow(city).plusDays(1);
        }
        if (lower.contains("today")) {
            return cityNow(city);
        }
        Matcher matcher = DATE_PATTERN.matcher(question);
        if (matcher.find()) {
            try {
                java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("MMMM d yyyy");
                return LocalDate.parse(matcher.group(1) + " " + cityNow(city).getYear(), formatter);
            } catch (Exception ignored) {
                // fall through
            }
        }
        if (endTimestampSeconds > 0) {
            return Instant.ofEpochSecond(endTimestampSeconds).atZone(resolveZone(city)).toLocalDate();
        }
        return cityNow(city);
    }

    private LocalDate cityNow(String city) {
        return LocalDate.now(resolveZone(city));
    }

    private ZoneId resolveZone(String city) {
        return properties.getCities().stream()
                .filter(rule -> city.equals(rule.getName()) && StringUtils.hasText(rule.getTimeZone()))
                .findFirst()
                .map(rule -> ZoneId.of(rule.getTimeZone()))
                .orElse(ZoneId.of("UTC"));
    }
}
