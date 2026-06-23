package com.metaverse.coin.strategy.job.weather.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.job.weather.config.WeatherArbitrageProperties;
import com.metaverse.coin.strategy.job.weather.model.WeatherMultiModelForecast;
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
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多模型确定性预报提供者。
 *
 * 从 Open-Meteo 拉取各国官方气象局模型的独立确定性预测：
 * - GFS (美国 NOAA/NCEP) — 全球 0.25° 分辨率
 * - ECMWF IFS (欧洲中期天气预报中心) — 全球顶尖精度
 * - DWD ICON (德国气象局) — 欧洲高分辨率
 * - JMA GSM (日本气象厅) — 亚太地区强项
 * - MeteoFrance Arpege (法国气象局) — 欧洲+北非
 * - GEM Global (加拿大环境部) — 极地+北美
 *
 * 这些是各国独立运行的数值天气预报模型，数据源完全独立。
 * 用于多源交叉验证：如果 6 个官方气象局模型一致预测 >80°F，则极高置信。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OpenMeteoMultiModelProvider {

    private static final String FORECAST_BASE_URL = "https://api.open-meteo.com/v1/forecast";

    /** 模型列表（Open-Meteo 支持的确定性模型） */
    private static final List<ModelConfig> MODELS = List.of(
            new ModelConfig("gfs_seamless", "GFS", "NOAA/NCEP (USA)"),
            new ModelConfig("ecmwf_ifs025", "ECMWF_IFS", "ECMWF (Europe)"),
            new ModelConfig("icon_seamless", "ICON", "DWD (Germany)"),
            new ModelConfig("jma_seamless", "JMA", "JMA (Japan)"),
            new ModelConfig("arpege_seamless", "ARPEGE", "MeteoFrance (France)"),
            new ModelConfig("gem_seamless", "GEM", "CMC (Canada)")
    );

    private final WeatherArbitrageProperties properties;
    private final ObjectMapper objectMapper;
    private final Map<String, CachedMultiModel> cache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 600_000L;

    public Optional<WeatherMultiModelForecast> fetchMultiModel(String city, LocalDate targetDate) {
        WeatherArbitrageProperties.CityRule cityRule = findCityRule(city);
        if (cityRule == null || cityRule.getLatitude() == null || cityRule.getLongitude() == null) {
            return Optional.empty();
        }

        String cacheKey = city.toLowerCase(Locale.ROOT) + "|" + targetDate;
        CachedMultiModel cached = cache.get(cacheKey);
        if (cached != null && cached.fetchedAt.plusMillis(CACHE_TTL_MS).isAfter(Instant.now())) {
            return Optional.of(cached.data);
        }

        try {
            RestTemplate restTemplate = new RestTemplateBuilder()
                    .setConnectTimeout(Duration.ofSeconds(8))
                    .setReadTimeout(Duration.ofSeconds(15))
                    .build();

            String modelNames = MODELS.stream()
                    .map(ModelConfig::openMeteoId)
                    .reduce((a, b) -> a + "," + b)
                    .orElse("");

            String url = UriComponentsBuilder.fromHttpUrl(FORECAST_BASE_URL)
                    .queryParam("latitude", cityRule.getLatitude())
                    .queryParam("longitude", cityRule.getLongitude())
                    .queryParam("daily", "temperature_2m_max,temperature_2m_min")
                    .queryParam("models", modelNames)
                    .queryParam("forecast_days", 7)
                    .queryParam("temperature_unit", "fahrenheit")
                    .build()
                    .toUriString();

            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return Optional.empty();
            }

            WeatherMultiModelForecast result = parseResponse(response.getBody(), city, targetDate, cityRule);
            if (result != null) {
                cache.put(cacheKey, new CachedMultiModel(result, Instant.now()));
            }
            return Optional.ofNullable(result);

        } catch (Exception ex) {
            log.warn("[WeatherMultiModel] fetch failed. city={}, error={}", city, ex.getMessage());
            return Optional.empty();
        }
    }

    private WeatherMultiModelForecast parseResponse(String body, String city,
                                                     LocalDate targetDate,
                                                     WeatherArbitrageProperties.CityRule cityRule) {
        try {
            JsonNode root = objectMapper.readTree(body);
            List<WeatherMultiModelForecast.ModelPoint> points = new ArrayList<>();

            for (ModelConfig modelConfig : MODELS) {
                // Open-Meteo 在多模型模式下，各模型数据在各自 key 下
                JsonNode modelNode = root.path(modelConfig.openMeteoId());
                JsonNode daily = modelNode.isMissingNode() ? root.path("daily") : modelNode.path("daily");
                if (daily.isMissingNode()) continue;

                JsonNode timeArray = daily.path("time");
                int targetIndex = findDateIndex(timeArray, targetDate);
                if (targetIndex < 0) continue;

                JsonNode maxArray = daily.path("temperature_2m_max");
                JsonNode minArray = daily.path("temperature_2m_min");

                if (maxArray.isArray() && maxArray.size() > targetIndex
                        && minArray.isArray() && minArray.size() > targetIndex) {
                    double maxVal = maxArray.get(targetIndex).asDouble(Double.NaN);
                    double minVal = minArray.get(targetIndex).asDouble(Double.NaN);
                    if (!Double.isNaN(maxVal) && !Double.isNaN(minVal)) {
                        points.add(WeatherMultiModelForecast.ModelPoint.builder()
                                .modelName(modelConfig.displayName())
                                .agency(modelConfig.agency())
                                .forecastMaxF(BigDecimal.valueOf(maxVal).setScale(2, RoundingMode.HALF_UP))
                                .forecastMinF(BigDecimal.valueOf(minVal).setScale(2, RoundingMode.HALF_UP))
                                .build());
                    }
                }
            }

            if (points.isEmpty()) return null;

            int leadHours = computeLeadHours(targetDate, cityRule.getTimeZone());
            return WeatherMultiModelForecast.builder()
                    .city(city)
                    .targetDate(targetDate)
                    .modelPoints(points)
                    .fetchedAt(Instant.now())
                    .leadHours(leadHours)
                    .build();

        } catch (Exception ex) {
            log.warn("[WeatherMultiModel] parse failed. city={}, error={}", city, ex.getMessage());
            return null;
        }
    }

    private int findDateIndex(JsonNode timeArray, LocalDate targetDate) {
        if (timeArray == null || !timeArray.isArray()) return -1;
        String target = targetDate.format(DateTimeFormatter.ISO_LOCAL_DATE);
        for (int i = 0; i < timeArray.size(); i++) {
            if (target.equals(timeArray.get(i).asText())) return i;
        }
        return -1;
    }

    private int computeLeadHours(LocalDate targetDate, String timeZone) {
        ZoneId zone = timeZone != null ? ZoneId.of(timeZone) : ZoneId.of("UTC");
        Instant targetEnd = targetDate.plusDays(1).atStartOfDay(zone).toInstant();
        return (int) Duration.between(Instant.now(), targetEnd).toHours();
    }

    private WeatherArbitrageProperties.CityRule findCityRule(String city) {
        return properties.getCities().stream()
                .filter(rule -> city.equalsIgnoreCase(rule.getName()))
                .findFirst()
                .orElse(null);
    }

    private record ModelConfig(String openMeteoId, String displayName, String agency) {}
    private record CachedMultiModel(WeatherMultiModelForecast data, Instant fetchedAt) {}
}
