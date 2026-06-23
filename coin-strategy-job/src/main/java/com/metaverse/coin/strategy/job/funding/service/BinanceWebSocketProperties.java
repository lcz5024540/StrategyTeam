package com.metaverse.coin.strategy.job.funding.service;

import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Data
@Component
public class BinanceWebSocketProperties {

    private boolean enabled = true;
    private boolean publicMarketEnabled = true;
    private long cacheTtlMs = 5000L;
    private boolean httpFallbackEnabled = true;
    private long reconnectDelayMs = 5000L;
    private String spotStreamUrl = "wss://stream.binance.com";
    private String futuresStreamUrl = "wss://fstream.binance.com";
    private String proxyHost;
    private int proxyPort;
    private String proxyType = "HTTP";
    private String proxyUsername;
    private String proxyPassword;
    private long connectTimeoutMs = 15_000L;
    private long readTimeoutMs = 0L;
    private long writeTimeoutMs = 15_000L;
    private long pingIntervalMs = 0L;
    private int outboundMessageLimitPerSecond = 4;
    private int connectionRequestLimit = 300;
    private long connectionRequestWindowMs = 300_000L;
    private int maxStreamsPerConnection = 1024;
    private int publicSubscribeBatchSize = 0;
    private Set<String> symbols = new LinkedHashSet<>();

    public BinanceWebSocketProperties() {
    }

    @Autowired
    public BinanceWebSocketProperties(Environment environment) {
        enabled = getBoolean(environment, "binance.websocket.enabled", enabled);
        publicMarketEnabled = getBoolean(environment, "binance.websocket.public-market-enabled", publicMarketEnabled);
        cacheTtlMs = getLong(environment, "binance.websocket.cache-ttl-ms", cacheTtlMs);
        httpFallbackEnabled = getBoolean(environment, "binance.websocket.http-fallback-enabled", httpFallbackEnabled);
        reconnectDelayMs = getLong(environment, "binance.websocket.reconnect-delay-ms", reconnectDelayMs);
        spotStreamUrl = environment.getProperty("binance.websocket.spot-stream-url", spotStreamUrl);
        futuresStreamUrl = environment.getProperty("binance.websocket.futures-stream-url", futuresStreamUrl);
        proxyHost = environment.getProperty("binance.websocket.proxy-host",
                environment.getProperty("binance.api.proxy-host", ""));
        proxyPort = getInteger(environment, "binance.websocket.proxy-port",
                getInteger(environment, "binance.api.proxy-port", 0));
        proxyType = environment.getProperty("binance.websocket.proxy-type",
                environment.getProperty("binance.api.proxy-type", proxyType));
        proxyUsername = environment.getProperty("binance.websocket.proxy-username",
                environment.getProperty("binance.api.proxy-username", ""));
        proxyPassword = environment.getProperty("binance.websocket.proxy-password",
                environment.getProperty("binance.api.proxy-password", ""));
        connectTimeoutMs = getLong(environment, "binance.websocket.connect-timeout-ms", connectTimeoutMs);
        readTimeoutMs = getLong(environment, "binance.websocket.read-timeout-ms", readTimeoutMs);
        writeTimeoutMs = getLong(environment, "binance.websocket.write-timeout-ms", writeTimeoutMs);
        pingIntervalMs = getLong(environment, "binance.websocket.ping-interval-ms", pingIntervalMs);
        outboundMessageLimitPerSecond = getInteger(environment, "binance.websocket.outbound-message-limit-per-second", outboundMessageLimitPerSecond);
        connectionRequestLimit = getInteger(environment, "binance.websocket.connection-request-limit", connectionRequestLimit);
        connectionRequestWindowMs = getLong(environment, "binance.websocket.connection-request-window-ms", connectionRequestWindowMs);
        maxStreamsPerConnection = getInteger(environment, "binance.websocket.max-streams-per-connection", maxStreamsPerConnection);
        publicSubscribeBatchSize = getInteger(environment, "binance.websocket.public-subscribe-batch-size", publicSubscribeBatchSize);
        symbols = parseSymbols(environment.getProperty("binance.websocket.symbols", ""));
    }

    private Set<String> parseSymbols(String value) {
        if (value == null || value.isBlank()) {
            return new LinkedHashSet<>();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .map(String::toUpperCase)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private long getLong(Environment environment, String key, long defaultValue) {
        String value = environment.getProperty(key);
        return value == null ? defaultValue : Long.parseLong(value);
    }

    private boolean getBoolean(Environment environment, String key, boolean defaultValue) {
        String value = environment.getProperty(key);
        return value == null ? defaultValue : Boolean.parseBoolean(value);
    }

    private int getInteger(Environment environment, String key, int defaultValue) {
        String value = environment.getProperty(key);
        return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value);
    }
}
