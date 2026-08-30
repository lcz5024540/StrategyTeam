package com.metaverse.coin.strategy.common.client.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Shared unauthenticated JSON transport for exchange public market-data adapters. */
public class PublicJsonHttpClient {
    public static class RateLimitException extends IllegalStateException {
        public RateLimitException(String message) {
            super(message);
        }
    }

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public PublicJsonHttpClient() {
        this(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(), new ObjectMapper());
    }

    PublicJsonHttpClient(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    public JsonNode get(URI uri) {
        return execute(HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", "CoinStrategy/1.0")
                .GET()
                .build(), uri);
    }

    /** Sends a public JSON POST request without any signing or account credentials. */
    public JsonNode post(URI uri, String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("public JSON request body is required");
        }
        return execute(HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", "CoinStrategy/1.0")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), uri);
    }

    private JsonNode execute(HttpRequest request, URI uri) {
        try {
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) {
                throw new RateLimitException("HTTP 429 from " + uri.getHost());
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("HTTP " + response.statusCode()
                        + " from " + uri.getHost());
            }
            return objectMapper.readTree(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("request interrupted: " + uri, exception);
        } catch (IOException exception) {
            throw new IllegalStateException("request failed: " + uri, exception);
        }
    }
}
