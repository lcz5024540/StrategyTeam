package com.metaverse.coin.strategy.job.weather.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.job.weather.config.WeatherArbitrageProperties;
import com.metaverse.coin.strategy.job.weather.model.WeatherEnsembleForecast;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Open-Meteo Ensemble API 数据源提供者。
 *
 * 调用 Open-Meteo 免费集成 API，获取多个数值天气模型的原始集成成员数据：
 * - GFS Seamless (NOAA/NCEP, 美国): 31 成员
 * - ECMWF IFS (欧洲中期天气预报中心): 51 成员
 * - ICON Seamless (DWD, 德国气象局): 40 成员
 * - GEM Global (CMC, 加拿大环境部): 21 成员
 * - BOM ACCESS (澳大利亚气象局): 18 成员
 *
 * 合计 ~161 个独立集成成员预测值，构建温度概率分布。
 * 无需 API key，免费限额 10,000 调用/天。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OpenMeteoEnsembleForecastProvider {

    private static final String ENSEMBLE_BASE_URL = "https://ensemble-api.open-meteo.com/v1/ensemble";
    private static final List<String> ENSEMBLE_MODELS = List.of(
            "gfs_seamless", "ecmwf_ifs025", "icon_seamless", "gem_global", "bom_access_global_ensemble"
    );
    private static final Map<String, String> MODEL_AGENCY = Map.of(
            "gfs_seamless", "NOAA/NCEP (USA)",
            "ecmwf_ifs025", "ECMWF (Europe)",
            "icon_seamless", "DWD (Germany)",
            "gem_global", "CMC (Canada)",
            "bom_access_global_ensemble", "BOM (Australia)"
    );

    private final WeatherArbitrageProperties properties;
    private final ObjectMapper objectMapper;

    /** 缓存：city+date → (ensemble, fetchedAt)。同一天同一城市 10 分钟内不重复请求。 */
    private final Map<String, CachedEnsemble> cache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 600_000L; // 10 min

    public Optional<WeatherEnsembleForecast> fetchEnsemble(String city, LocalDate targetDate) {
        WeatherArbitrageProperties.CityRule cityRule = findCityRule(city);
        if (cityRule == null || cityRule.getLatitude() == null || cityRule.getLongitude() == null) {
            log.warn("[WeatherEnsemble] No coordinates for city={}", city);
            return Optional.empty();
        }

        String cacheKey = city.toLowerCase(Locale.ROOT) + "|" + targetDate;
        CachedEnsemble cached = cache.get(cacheKey);
        if (cached != null && cached.fetchedAt.plusMillis(CACHE_TTL_MS).isAfter(Instant.now())) {
            return Optional.of(cached.data);
        }

        try {
            RestTemplate restTemplate = new RestTemplateBuilder()
                    .setConnectTimeout(Duration.ofSeconds(8))
                    .setReadTimeout(Duration.ofSeconds(15))
                    .build();

            String url = UriComponentsBuilder.fromHttpUrl(ENSEMBLE_BASE_URL)
                    .queryParam("latitude", cityRule.getLatitude())
                    .queryParam("longitude", cityRule.getLongitude())
                    .queryParam("daily", "temperature_2m_max,temperature_2m_min")
                    .queryParam("models", String.join(",", ENSEMBLE_MODELS))
                    .queryParam("forecast_days", 7)
                    .queryParam("temperature_unit", "fahrenheit")
                    .build()
                    .toUriString();

            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("[WeatherEnsemble] API returned status={} for city={}", response.getStatusCode(), city);
                return Optional.empty();
            }

            WeatherEnsembleForecast result = parseEnsembleResponse(response.getBody(), city, targetDate, cityRule);
            if (result != null) {
                cache.put(cacheKey, new CachedEnsemble(result, Instant.now()));
            }
            return Optional.ofNullable(result);

        } catch (Exception ex) {
            log.warn("[WeatherEnsemble] fetch failed. city={}, targetDate={}, error={}", city, targetDate, ex.getMessage());
            return Optional.empty();
        }
    }

    private WeatherEnsembleForecast parseEnsembleResponse(String body, String city,
                                                           LocalDate targetDate,
                                                           WeatherArbitrageProperties.CityRule cityRule) {
        try {
            JsonNode root = objectMapper.readTree(body);
            List<BigDecimal> allMaxMembers = new ArrayList<>();
            List<BigDecimal> allMinMembers = new ArrayList<>();
            Map<String, Integer> memberCountByModel = new LinkedHashMap<>();

            // Open-Meteo ensemble API 返回按模型分组的 daily 数据
            // 每个模型返回多个成员的时间序列
            for (String model : ENSEMBLE_MODELS) {
                JsonNode modelDaily = root.path(model).path("daily");
                if (modelDaily.isMissingNode()) {
                    // 尝试直接在 daily 下找（部分模型合并返回）
                    continue;
                }

                JsonNode timeArray = modelDaily.path("time");
                int targetIndex = findDateIndex(timeArray, targetDate);
                if (targetIndex < 0) continue;

                // 集成成员数据格式：temperature_2m_max_member01, temperature_2m_max_member02, ...
                int memberCount = 0;
                for (Iterator<String> fields = modelDaily.fieldNames(); fields.hasNext(); ) {
                    String fieldName = fields.next();
                    if (fieldName.startsWith("temperature_2m_max")) {
                        JsonNode values = modelDaily.path(fieldName);
                        if (values.isArray() && values.size() > targetIndex) {
                            double val = values.get(targetIndex).asDouble(Double.NaN);
                            if (!Double.isNaN(val)) {
                                allMaxMembers.add(BigDecimal.valueOf(val).setScale(2, RoundingMode.HALF_UP));
                                memberCount++;
                            }
                        }
                    } else if (fieldName.startsWith("temperature_2m_min")) {
                        JsonNode values = modelDaily.path(fieldName);
                        if (values.isArray() && values.size() > targetIndex) {
                            double val = values.get(targetIndex).asDouble(Double.NaN);
                            if (!Double.isNaN(val)) {
                                allMinMembers.add(BigDecimal.valueOf(val).setScale(2, RoundingMode.HALF_UP));
                            }
                        }
                    }
                }
                if (memberCount > 0) {
                    memberCountByModel.put(model, memberCount);
                }
            }

            // 回退解析方式：如果按模型分组没有数据，尝试统一 daily 格式
            if (allMaxMembers.isEmpty()) {
                allMaxMembers = parseUnifiedDaily(root, "temperature_2m_max", targetDate);
                allMinMembers = parseUnifiedDaily(root, "temperature_2m_min", targetDate);
                if (!allMaxMembers.isEmpty()) {
                    memberCountByModel.put("unified", allMaxMembers.size());
                }
            }

            if (allMaxMembers.isEmpty()) {
                log.debug("[WeatherEnsemble] No ensemble members found for city={} date={}", city, targetDate);
                return null;
            }

            int leadHours = computeLeadHours(targetDate, cityRule.getTimeZone());

            return WeatherEnsembleForecast.builder()
                    .city(city)
                    .targetDate(targetDate)
                    .maxTempMembersF(allMaxMembers)
                    .minTempMembersF(allMinMembers)
                    .totalMembers(allMaxMembers.size())
                    .modelCount(memberCountByModel.size())
                    .fetchedAt(Instant.now())
                    .leadHours(leadHours)
                    .memberCountByModel(memberCountByModel)
                    .build();

        } catch (Exception ex) {
            log.warn("[WeatherEnsemble] parse failed. city={}, error={}", city, ex.getMessage());
            return null;
        }
    }

    private List<BigDecimal> parseUnifiedDaily(JsonNode root, String prefix, LocalDate targetDate) {
        List<BigDecimal> members = new ArrayList<>();
        JsonNode daily = root.path("daily");
        if (daily.isMissingNode()) return members;

        JsonNode timeArray = daily.path("time");
        int targetIndex = findDateIndex(timeArray, targetDate);
        if (targetIndex < 0) return members;

        for (Iterator<String> fields = daily.fieldNames(); fields.hasNext(); ) {
            String fieldName = fields.next();
            if (fieldName.startsWith(prefix)) {
                JsonNode values = daily.path(fieldName);
                if (values.isArray() && values.size() > targetIndex) {
                    double val = values.get(targetIndex).asDouble(Double.NaN);
                    if (!Double.isNaN(val)) {
                        members.add(BigDecimal.valueOf(val).setScale(2, RoundingMode.HALF_UP));
                    }
                }
            }
        }
        return members;
    }

    private int findDateIndex(JsonNode timeArray, LocalDate targetDate) {
        if (timeArray == null || !timeArray.isArray()) return -1;
        String target = targetDate.format(DateTimeFormatter.ISO_LOCAL_DATE);
        for (int i = 0; i < timeArray.size(); i++) {
            if (target.equals(timeArray.get(i).asText())) {
                return i;
            }
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

    private record CachedEnsemble(WeatherEnsembleForecast data, Instant fetchedAt) {}
}
