package com.metaverse.coin.strategy.web.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.web.config.DashboardProperties;
import com.metaverse.coin.strategy.web.model.StrategyConflictRowView;
import com.metaverse.coin.strategy.web.model.StrategyDashboardOverviewView;
import com.metaverse.coin.strategy.web.model.StrategyDetailView;
import com.metaverse.coin.strategy.web.model.StrategyFactorView;
import com.metaverse.coin.strategy.web.model.StrategyOrderRowView;
import com.metaverse.coin.strategy.web.model.StrategyPositionRowView;
import com.metaverse.coin.strategy.web.model.StrategySummaryDetailView;
import com.metaverse.coin.strategy.web.model.StrategyTimelinePointView;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

@Component
public class HttpStrategyPlatformQueryClient implements StrategyPlatformQueryClient {

    private final DashboardProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public HttpStrategyPlatformQueryClient(DashboardProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public StrategyDashboardOverviewView getOverview(String mode) {
        return getJson("/strategy/api/strategy-platform/strategy/overview" + modeQuery(mode), StrategyDashboardOverviewView.class);
    }

    @Override
    public StrategyDetailView getStrategyDetail(String strategyCode, String mode) {
        return getJson("/strategy/api/strategy-platform/strategy/" + strategyCode + modeQuery(mode), StrategyDetailView.class);
    }

    @Override
    public StrategySummaryDetailView getStrategySummary(String strategyCode, String mode) {
        return getJson("/strategy/api/strategy-platform/strategy/" + strategyCode + "/summary" + modeQuery(mode),
                StrategySummaryDetailView.class);
    }

    @Override
    public List<StrategyPositionRowView> getStrategyPositions(String strategyCode, String mode) {
        return getJsonList("/strategy/api/strategy-platform/strategy/" + strategyCode + "/positions" + modeQuery(mode),
                StrategyPositionRowView.class);
    }

    @Override
    public List<StrategyPositionRowView> getStrategyNextSettlements(String strategyCode, Integer limit, String mode) {
        return getJsonList("/strategy/api/strategy-platform/strategy/" + strategyCode + "/next-settlements"
                        + appendQuery(modeQuery(mode), "limit", limit),
                StrategyPositionRowView.class);
    }

    @Override
    public List<StrategyOrderRowView> getStrategyOrders(String strategyCode, Integer limit, String mode) {
        return getJsonList("/strategy/api/strategy-platform/strategy/" + strategyCode + "/orders"
                        + appendQuery(modeQuery(mode), "limit", limit),
                StrategyOrderRowView.class);
    }

    @Override
    public List<StrategyTimelinePointView> getStrategyTimeline(String strategyCode, Integer days, String mode) {
        return getJsonList("/strategy/api/strategy-platform/strategy/" + strategyCode + "/timeline"
                        + appendQuery(modeQuery(mode), "days", days),
                StrategyTimelinePointView.class);
    }

    @Override
    public List<StrategyConflictRowView> getStrategyConflicts(String strategyCode, Integer limit, String mode) {
        return getJsonList("/strategy/api/strategy-platform/strategy/" + strategyCode + "/conflicts"
                        + appendQuery(modeQuery(mode), "limit", limit),
                StrategyConflictRowView.class);
    }

    @Override
    public List<StrategyFactorView> getStrategyFactors(String strategyCode, String mode, String window) {
        String path = appendQuery(modeQuery(mode), "window", window);
        return getJsonList("/strategy/api/strategy-platform/strategy/" + strategyCode + "/factors" + path,
                StrategyFactorView.class);
    }

    @Override
    public StrategyFactorView getStrategyFactor(String strategyCode, String factorCode, String mode, String window) {
        String path = appendQuery(modeQuery(mode), "window", window);
        return getJson("/strategy/api/strategy-platform/strategy/" + strategyCode + "/factors/" + factorCode + path,
                StrategyFactorView.class);
    }

    private String modeQuery(String mode) {
        if (mode == null || mode.isBlank()) {
            return "";
        }
        String normalized = mode.trim().toUpperCase(Locale.ROOT);
        if (!"PAPER".equals(normalized) && !"LIVE".equals(normalized)) {
            return "";
        }
        return "?mode=" + normalized;
    }

    private String appendQuery(String existingQuery, String key, Object value) {
        String normalized = existingQuery == null ? "" : existingQuery;
        if (key == null || value == null) {
            return normalized;
        }
        String prefix = normalized.isBlank() ? "?" : "&";
        return normalized + prefix + key + "=" + value;
    }

    private <T> T getJson(String path, Class<T> type) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getJobBaseUrl() + path))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Request failed: " + path + " status=" + response.statusCode());
            }
            return objectMapper.readValue(response.body(), type);
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("Failed to fetch strategy-platform data: " + path, e);
        }
    }

    private <T> List<T> getJsonList(String path, Class<T> type) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getJobBaseUrl() + path))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Request failed: " + path + " status=" + response.statusCode());
            }
            return objectMapper.readValue(
                    response.body(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, type)
            );
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("Failed to fetch strategy-platform data: " + path, e);
        }
    }
}
