package com.metaverse.coin.strategy.common.client.htx.ws;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.zip.GZIPInputStream;

/**
 * Minimal HTX WebSocket client for public market streams.
 * <p>
 * v1 scope: bootstrap only. Provides connection / gzip inflate / ping-pong /
 * subscription registration. Payloads are handed off to caller consumers as raw JSON strings —
 * this keeps the SDK usable without committing to a strict DTO now (HTX evolves stream schemas).
 * <p>
 * Not covered in v1 (see {@code HTX_SPOT_EXECUTION_SDK_V1.md} §8): auto-reconnect, order-topic auth,
 * back-pressure handling — those will land when a strategy first consumes the stream.
 */
@Slf4j
public class HtxWebSocketClient {

    private final String wsUrl;
    private final Map<String, HtxSubscription> subscriptions = new ConcurrentHashMap<>();
    private volatile WebSocket ws;

    public HtxWebSocketClient() {
        this("wss://api.huobi.pro/ws");
    }

    public HtxWebSocketClient(String wsUrl) {
        this.wsUrl = wsUrl;
    }

    /**
     * Register (or replace) a subscription for a topic. If already connected, the SUB frame is
     * sent immediately; otherwise it is deferred until {@link #connect()} completes.
     */
    public void subscribe(HtxSubscription sub) {
        subscriptions.put(sub.getTopic(), sub);
        if (ws != null) {
            sendSub(sub.getTopic());
        }
    }

    public void unsubscribe(String topic) {
        subscriptions.remove(topic);
        if (ws != null) {
            ws.sendText("{\"unsub\":\"" + topic + "\"}", true);
        }
    }

    public synchronized void connect() {
        if (ws != null) {
            return;
        }
        HttpClient http = HttpClient.newHttpClient();
        this.ws = http.newWebSocketBuilder()
                .buildAsync(URI.create(wsUrl), new Listener())
                .join();
        subscriptions.keySet().forEach(this::sendSub);
    }

    public synchronized void close() {
        if (ws != null) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
            ws = null;
        }
    }

    private void sendSub(String topic) {
        ws.sendText("{\"sub\":\"" + topic + "\",\"id\":\"" + topic + "\"}", true);
    }

    private void dispatch(String json) {
        JSONObject obj = JSON.parseObject(json);
        // ping/pong keepalive
        Long ping = obj.getLong("ping");
        if (ping != null) {
            ws.sendText("{\"pong\":" + ping + "}", true);
            return;
        }
        String ch = obj.getString("ch");
        if (ch == null) {
            return;
        }
        HtxSubscription sub = subscriptions.get(ch);
        if (sub != null) {
            try {
                sub.getCallback().accept(json);
            } catch (RuntimeException e) {
                log.warn("HTX WS callback for {} threw: {}", ch, e.toString());
            }
        }
    }

    private static String inflate(ByteBuffer data) {
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bytes));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = gzip.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("HTX WS gzip inflate failed: {}", e.toString());
            return null;
        }
    }

    private class Listener implements WebSocket.Listener {
        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            String json = inflate(data);
            if (json != null) {
                dispatch(json);
            }
            webSocket.request(1);
            return CompletableFuture.completedStage(null);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            // HTX normally uses binary gzip, but some paths (private orders) send plain text
            dispatch(data.toString());
            webSocket.request(1);
            return CompletableFuture.completedStage(null);
        }
    }
}
