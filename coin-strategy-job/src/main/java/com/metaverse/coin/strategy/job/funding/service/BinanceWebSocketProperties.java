package com.metaverse.coin.strategy.job.funding.service;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Getter
@Component
public class BinanceWebSocketProperties {

    private boolean enabled = true;
    private boolean publicMarketEnabled = true;
    private boolean userDataEnabled = true;
    private long cacheTtlMs = 5000L;
    private boolean httpFallbackEnabled = true;
    private long reconnectDelayMs = 5000L;
    private long listenKeyKeepaliveMs = 1_800_000L;
    private String spotStreamUrl = "wss://stream.binance.com:9443";
    private String futuresStreamUrl = "wss://fstream.binance.com";
    private Set<String> symbols = new LinkedHashSet<>();

    public BinanceWebSocketProperties() {
    }

    @Autowired
    public BinanceWebSocketProperties(Environment environment) {
        enabled = getBoolean(environment, "binance.websocket.enabled", enabled);
        publicMarketEnabled = getBoolean(environment, "binance.websocket.public-market-enabled", publicMarketEnabled);
        userDataEnabled = getBoolean(environment, "binance.websocket.user-data-enabled", userDataEnabled);
        cacheTtlMs = getLong(environment, "binance.websocket.cache-ttl-ms", cacheTtlMs);
        httpFallbackEnabled = getBoolean(environment, "binance.websocket.http-fallback-enabled", httpFallbackEnabled);
        reconnectDelayMs = getLong(environment, "binance.websocket.reconnect-delay-ms", reconnectDelayMs);
        listenKeyKeepaliveMs = getLong(environment, "binance.websocket.listen-key-keepalive-ms", listenKeyKeepaliveMs);
        spotStreamUrl = environment.getProperty("binance.websocket.spot-stream-url", spotStreamUrl);
        futuresStreamUrl = environment.getProperty("binance.websocket.futures-stream-url", futuresStreamUrl);
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
}
