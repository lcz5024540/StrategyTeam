package com.metaverse.coin.strategy.job.weather.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.job.weather.config.WeatherArbitrageProperties;
import com.metaverse.coin.strategy.job.weather.model.WeatherForecast;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class TomorrowIoWeatherForecastProvider implements WeatherForecastProvider {

    private static final String BASE_URL = "https://api.tomorrow.io/v4/weather/forecast";

    private final WeatherArbitrageProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public Optional<WeatherForecast> fetchForecast(String city, LocalDate targetDate) {
        if (!StringUtils.hasText(properties.getTomorrowIoApiKey())) {
            return Optional.empty();
        }
        RestTemplate restTemplate = new RestTemplateBuilder()
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(10))
                .build();
        String url = UriComponentsBuilder.fromHttpUrl(BASE_URL)
                .queryParam("location", city)
                .queryParam("timesteps", "1d")
                .queryParam("fields", "temperatureMax,temperatureMin,temperatureApparentAvg")
                .queryParam("apikey", properties.getTomorrowIoApiKey())
                .build()
                .toUriString();
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return Optional.empty();
            }
            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode daily = root.path("timelines").path("daily");
            if (!daily.isArray() || daily.isEmpty()) {
                return Optional.empty();
            }
            for (JsonNode node : daily) {
                LocalDate date = Instant.parse(node.path("time").asText()).atZone(java.time.ZoneOffset.UTC).toLocalDate();
                if (!targetDate.equals(date)) {
                    continue;
                }
                JsonNode values = node.path("values");
                BigDecimal maxF = BigDecimal.valueOf(values.path("temperatureMax").asDouble());
                BigDecimal minF = BigDecimal.valueOf(values.path("temperatureMin").asDouble());
                BigDecimal avgF = BigDecimal.valueOf(values.path("temperatureApparentAvg").asDouble((maxF.doubleValue() + minF.doubleValue()) / 2.0d));
                return Optional.of(WeatherForecast.builder()
                        .city(city)
                        .forecastDate(LocalDate.now())
                        .targetDate(targetDate)
                        .forecastTempMaxF(cToFIfNeeded(maxF))
                        .forecastTempMinF(cToFIfNeeded(minF))
                        .forecastTempAvgF(cToFIfNeeded(avgF))
                        .provider(providerName())
                        .leadHours((int) Duration.between(Instant.now(), targetDate.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()).toHours())
                        .dataSourceCount(1)
                        .fetchedAt(Instant.now())
                        .build());
            }
        } catch (Exception ex) {
            log.warn("[WeatherArbitrage] tomorrow.io fetch failed. city={}, targetDate={}", city, targetDate, ex);
        }
        return Optional.empty();
    }

    @Override
    public String providerName() {
        return "tomorrow.io";
    }

    private BigDecimal cToFIfNeeded(BigDecimal raw) {
        if (raw == null) {
            return BigDecimal.ZERO;
        }
        if (raw.compareTo(new BigDecimal("60")) > 0) {
            return raw;
        }
        return raw.multiply(new BigDecimal("9"))
                .divide(new BigDecimal("5"), 4, java.math.RoundingMode.HALF_UP)
                .add(new BigDecimal("32"));
    }
}
