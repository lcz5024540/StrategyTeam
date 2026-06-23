package com.metaverse.coin.strategy.job.weather.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.job.weather.config.WeatherArbitrageProperties;
import com.metaverse.coin.strategy.job.weather.model.WeatherRealtimeObservation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实时观测 + 逐时短临预报提供者。
 *
 * 用于 leadHours < 12 的超近期市场：
 * 1. 拉取当天过去 24 小时的逐时实测温度 → 计算当天已观测到的 max/min
 * 2. 拉取未来 12 小时的逐时预报 → 估算当天剩余时间的温度走势
 *
 * 当市场问 "NYC 今天最高温是否超过 80°F" 且当前已观测到 82°F 时，
 * fair value 接近 0.98（几乎确定 YES）。
 *
 * 数据源：Open-Meteo 的实况 + 短临预报 API（免费）。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OpenMeteoObservationProvider {

    private static final String FORECAST_BASE_URL = "https://api.open-meteo.com/v1/forecast";

    private final WeatherArbitrageProperties properties;
    private final ObjectMapper objectMapper;
    private final Map<String, CachedObs> cache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 120_000L; // 2 min（实时数据缓存更短）

    public Optional<WeatherRealtimeObservation> fetchCurrent(String city) {
        WeatherArbitrageProperties.CityRule cityRule = findCityRule(city);
        if (cityRule == null || cityRule.getLatitude() == null || cityRule.getLongitude() == null) {
            return Optional.empty();
        }

        String cacheKey = city.toLowerCase(Locale.ROOT);
        CachedObs cached = cache.get(cacheKey);
        if (cached != null && cached.fetchedAt.plusMillis(CACHE_TTL_MS).isAfter(Instant.now())) {
            return Optional.of(cached.data);
        }

        try {
            RestTemplate restTemplate = new RestTemplateBuilder()
                    .setConnectTimeout(Duration.ofSeconds(5))
                    .setReadTimeout(Duration.ofSeconds(10))
                    .build();

            String url = UriComponentsBuilder.fromHttpUrl(FORECAST_BASE_URL)
                    .queryParam("latitude", cityRule.getLatitude())
                    .queryParam("longitude", cityRule.getLongitude())
                    .queryParam("hourly", "temperature_2m")
                    .queryParam("current", "temperature_2m")
                    .queryParam("temperature_unit", "fahrenheit")
                    .queryParam("past_hours", 24)
                    .queryParam("forecast_hours", 24)  // 覆盖 12-24h 市场的短临预报
                    .build()
                    .toUriString();

            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return Optional.empty();
            }

            WeatherRealtimeObservation result = parseResponse(response.getBody(), city, cityRule);
            if (result != null) {
                cache.put(cacheKey, new CachedObs(result, Instant.now()));
            }
            return Optional.ofNullable(result);

        } catch (Exception ex) {
            log.warn("[WeatherObservation] fetch failed. city={}, error={}", city, ex.getMessage());
            return Optional.empty();
        }
    }

    private WeatherRealtimeObservation parseResponse(String body, String city,
                                                      WeatherArbitrageProperties.CityRule cityRule) {
        try {
            JsonNode root = objectMapper.readTree(body);
            ZoneId zone = cityRule.getTimeZone() != null ? ZoneId.of(cityRule.getTimeZone()) : ZoneId.of("UTC");
            LocalDate today = LocalDate.now(zone);

            // 1. 当前温度
            JsonNode current = root.path("current");
            BigDecimal currentTemp = BigDecimal.valueOf(current.path("temperature_2m").asDouble(Double.NaN));
            if (currentTemp.doubleValue() == Double.NaN) {
                return null;
            }

            // 2. 解析逐时数据
            JsonNode hourly = root.path("hourly");
            JsonNode timeArray = hourly.path("time");
            JsonNode tempArray = hourly.path("temperature_2m");

            if (!timeArray.isArray() || !tempArray.isArray()) return null;

            BigDecimal observedMax = currentTemp;
            BigDecimal observedMin = currentTemp;
            List<BigDecimal> remainingForecasts = new ArrayList<>();
            Instant now = Instant.now();

            for (int i = 0; i < timeArray.size() && i < tempArray.size(); i++) {
                String timeStr = timeArray.get(i).asText();
                double tempVal = tempArray.get(i).asDouble(Double.NaN);
                if (Double.isNaN(tempVal)) continue;

                BigDecimal temp = BigDecimal.valueOf(tempVal).setScale(2, RoundingMode.HALF_UP);
                Instant hourInstant = Instant.parse(timeStr.endsWith("Z") ? timeStr : timeStr + ":00Z");
                LocalDate hourDate = hourInstant.atZone(zone).toLocalDate();

                if (!hourDate.equals(today)) continue;

                if (hourInstant.isBefore(now)) {
                    // 过去的小时 = 实际观测
                    observedMax = observedMax.max(temp);
                    observedMin = observedMin.min(temp);
                } else {
                    // 未来的小时 = 短临预报
                    remainingForecasts.add(temp);
                }
            }

            return WeatherRealtimeObservation.builder()
                    .city(city)
                    .currentTempF(currentTemp.setScale(2, RoundingMode.HALF_UP))
                    .todayObservedMaxF(observedMax)
                    .todayObservedMinF(observedMin)
                    .remainingHourForecastsF(remainingForecasts)
                    .observedAt(Instant.now())
                    .source("open-meteo-observation")
                    .build();

        } catch (Exception ex) {
            log.warn("[WeatherObservation] parse failed. city={}, error={}", city, ex.getMessage());
            return null;
        }
    }

    private WeatherArbitrageProperties.CityRule findCityRule(String city) {
        return properties.getCities().stream()
                .filter(rule -> city.equalsIgnoreCase(rule.getName()))
                .findFirst()
                .orElse(null);
    }

    private record CachedObs(WeatherRealtimeObservation data, Instant fetchedAt) {}
}
