package com.metaverse.coin.strategy.job.weather.service;

import com.metaverse.coin.strategy.common.dao.mapper.WeatherMarketParseLogMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.WeatherMarketParseLogEntity;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketApiClient;
import com.metaverse.coin.strategy.job.weather.config.WeatherArbitrageProperties;
import com.metaverse.coin.strategy.job.weather.model.WeatherParsedMarket;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class WeatherMarketScanService {

    private final WeatherArbitrageProperties properties;
    private final PolymarketApiClient polymarketApiClient;
    private final WeatherMarketTitleParser titleParser;
    private final WeatherMarketParseLogMapper parseLogMapper;

    public List<WeatherParsedMarket> scanMarkets() {
        List<WeatherParsedMarket> results = new ArrayList<>();
        List<PolymarketApiClient.MarketMetadata> activeMarkets = polymarketApiClient.fetchActiveMarkets(properties.getMaxMarketsPerScan());
        for (PolymarketApiClient.MarketMetadata market : activeMarkets) {
            if (!isWeatherCandidate(market)) {
                continue;
            }
            String yesToken = resolveYesToken(market);
            Optional<com.metaverse.coin.strategy.job.weather.model.WeatherMarketSpec> parsed = titleParser.parse(
                    market.getConditionId(),
                    yesToken,
                    market.getQuestion(),
                    resolvePrice(market, 0),
                    resolvePrice(market, 1),
                    firstPositive(market.getMinimumTickSize(), BigDecimal.ZERO),
                    market.getEndAt() == null ? 0L : market.getEndAt() / 1000L
            );
            if (parsed.isEmpty()) {
                logParse(market, "REJECTED", "title_unparsed", BigDecimal.ZERO);
                continue;
            }
            com.metaverse.coin.strategy.job.weather.model.WeatherMarketSpec spec = parsed.get();
            if (spec.getParseConfidence() != null && spec.getParseConfidence().compareTo(properties.getConfidenceFloor()) < 0) {
                logParse(market, "LOW_CONFIDENCE", spec.getParseReason(), spec.getParseConfidence());
                continue;
            }
            results.add(WeatherParsedMarket.builder()
                    .marketMetadata(market)
                    .spec(spec)
                    .build());
            logParse(market, "PARSED", spec.getParseReason(), spec.getParseConfidence());
        }
        return results;
    }

    private boolean isWeatherCandidate(PolymarketApiClient.MarketMetadata market) {
        String text = ((market.getCategory() == null ? "" : market.getCategory()) + " " + (market.getQuestion() == null ? "" : market.getQuestion())).toLowerCase();
        boolean categoryMatch = text.contains("weather");
        boolean cityMatch = properties.getCities().stream()
                .flatMap(rule -> rule.getAliases().stream())
                .anyMatch(alias -> text.contains(alias.toLowerCase()));
        return categoryMatch || cityMatch;
    }

    private void logParse(PolymarketApiClient.MarketMetadata market, String status, String reason, BigDecimal confidence) {
        parseLogMapper.insert(new WeatherMarketParseLogEntity()
                .setMarketId(market.getConditionId())
                .setQuestion(market.getQuestion())
                .setParseStatus(status)
                .setReason(reason)
                .setConfidence(confidence));
    }

    private String resolveYesToken(PolymarketApiClient.MarketMetadata market) {
        return market.getOutcomeTokenIds().entrySet().stream()
                .filter(entry -> "YES".equalsIgnoreCase(entry.getKey()))
                .map(java.util.Map.Entry::getValue)
                .findFirst()
                .orElseGet(() -> market.getOutcomeTokenIds().values().stream().findFirst().orElse(null));
    }

    private BigDecimal resolvePrice(PolymarketApiClient.MarketMetadata market, int index) {
        if (market.getOutcomePrices() == null || market.getOutcomePrices().size() <= index) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(market.getOutcomePrices().get(index));
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }

    private BigDecimal firstPositive(BigDecimal preferred, BigDecimal fallback) {
        if (preferred != null && preferred.compareTo(BigDecimal.ZERO) > 0) {
            return preferred;
        }
        return fallback;
    }
}
