package com.metaverse.coin.strategy.web.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.web.config.DashboardProperties;
import com.metaverse.coin.strategy.web.model.StrategyDashboardOverviewView;
import com.metaverse.coin.strategy.web.model.StrategyDetailView;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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

    private <T> T getJson(String path, Class<T> type) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getSmartWalletBaseUrl() + path))
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
}
