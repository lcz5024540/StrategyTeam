package com.metaverse.coin.strategy.htx.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.metaverse.coin.strategy.common.client.htx.ws.HtxSubscription;
import com.metaverse.coin.strategy.common.client.htx.ws.HtxWebSocketClient;
import com.metaverse.coin.strategy.htx.config.HtxTrendProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Public swap-price stream used only by the existing-position exit supervisor.
 *
 * <p>The feed never scans the whitelist and never creates an order.  A quote is returned only
 * while it is fresh; the exit service then falls back to its existing private position polling.
 * This makes a disconnect an explicit degradation rather than a reason to use an old price.</p>
 */
@Slf4j
@Service
@ConditionalOnBean(HtxTrendProperties.class)
public class HtxTrendRealtimePriceFeed {

    private final HtxTrendProperties properties;
    private final HtxWebSocketClient client;
    private final Executor connector;
    private final ConcurrentHashMap<String, Quote> quotes = new ConcurrentHashMap<>();
    private final Set<String> subscribedContracts = ConcurrentHashMap.newKeySet();

    @Autowired
    public HtxTrendRealtimePriceFeed(HtxTrendProperties properties) {
        this(properties, new HtxWebSocketClient(properties.getRealtimePriceWsUrl()),
                Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "htx-trend-price-ws");
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    HtxTrendRealtimePriceFeed(HtxTrendProperties properties, HtxWebSocketClient client, Executor connector) {
        this.properties = properties;
        this.client = client;
        this.connector = connector;
    }

    /** Returns only a fresh quote; callers must fall back to REST when this is empty. */
    public Optional<Quote> latest(String contractCode) {
        if (!properties.isRealtimePriceFeedEnabled() || contractCode == null || contractCode.isBlank()) {
            return Optional.empty();
        }
        ensureSubscribed(contractCode);
        ensureConnected();
        Quote quote = quotes.get(contractCode);
        if (quote == null || quote.receivedAtEpochMs() + Math.max(1L, properties.getRealtimePriceMaxStaleMs())
                < Instant.now().toEpochMilli()) {
            return Optional.empty();
        }
        return Optional.of(quote);
    }

    private void ensureSubscribed(String contractCode) {
        if (!subscribedContracts.add(contractCode)) {
            return;
        }
        String topic = topic(contractCode);
        client.subscribe(new HtxSubscription(topic, payload -> acceptPayload(contractCode, payload)));
    }

    private void ensureConnected() {
        if (client.isConnected()) {
            return;
        }
        connector.execute(() -> {
            if (client.isConnected()) {
                return;
            }
            try {
                client.connect();
                log.info("[HTX-TREND] realtime price stream connected contracts={}", subscribedContracts.size());
            } catch (RuntimeException ex) {
                // REST position polling remains the safety fallback.  Do not log credentials or payloads.
                log.warn("[HTX-TREND] realtime price stream unavailable; use REST fallback: {}",
                        ex.getClass().getSimpleName());
            }
        });
    }

    /** Package-visible test seam for the raw public market payload. */
    void acceptPayload(String contractCode, String payload) {
        try {
            JSONObject root = JSON.parseObject(payload);
            JSONObject tick = root == null ? null : root.getJSONObject("tick");
            BigDecimal price = tick == null ? null : tick.getBigDecimal("close");
            if (price == null || price.signum() <= 0) {
                return;
            }
            long sourceTs = root.getLongValue("ts");
            quotes.compute(contractCode, (ignored, previous) -> {
                if (previous != null && sourceTs > 0 && previous.sourceEpochMs() > sourceTs) {
                    return previous;
                }
                return new Quote(price, sourceTs > 0 ? sourceTs : Instant.now().toEpochMilli(),
                        Instant.now().toEpochMilli(), "HTX_SWAP_WS_DETAIL");
            });
        } catch (RuntimeException ex) {
            log.debug("[HTX-TREND] ignored malformed realtime price payload contract={}: {}",
                    contractCode, ex.getClass().getSimpleName());
        }
    }

    static String topic(String contractCode) {
        return "market." + contractCode + ".detail";
    }

    public record Quote(BigDecimal price, long sourceEpochMs, long receivedAtEpochMs, String source) {
    }
}
