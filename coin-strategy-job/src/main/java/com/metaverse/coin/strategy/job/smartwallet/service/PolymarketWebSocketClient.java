package com.metaverse.coin.strategy.job.smartwallet.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * F-P1-7 / F-P1-8 第十轮：真实 Polymarket WebSocket 客户端。
 *
 * <p>用 OkHttp 的 {@link WebSocket} 维护到 Polymarket 两个 channel 的长连接：
 * <ul>
 *   <li><b>Market channel</b>（{@code wss://ws-subscriptions-clob.polymarket.com/ws/market}）：
 *       book / price_change 事件 → 路由到 {@link PolymarketMarketDataStreamService#handleMessage(String)}
 *       → 写入 {@code snapshotCache}，让 {@code MarketSnapshotCollectorService.findFreshSnapshot}
 *       的 hot-path 读到亚秒级数据；</li>
 *   <li><b>User channel</b>（{@code wss://ws-subscriptions-clob.polymarket.com/ws/user}）：
 *       trade 事件 → 路由到 {@link PolymarketSmartWalletMonitorService#ingestWsTradeEvent(String)}
 *       → 透传到 {@code recordTradeEvent}，让 leader trade 30s polling → < 2s 推流。</li>
 * </ul>
 *
 * <p>设计要点（与 {@code BtcPriceFeedService} 同一套范式）：
 * <ol>
 *   <li>主开关 {@code polymarket.enable-ws-client=false}（默认 OFF）：
 *       未开时 {@code @PostConstruct} 直接返回，不创建 OkHttp / scheduler / 线程；
 *       连消费侧（hot-path / trade-event push）开关一起 OFF 时与历史行为完全等价，零回归；</li>
 *   <li>双连接独立 lifecycle：每个 channel 一个 {@link Channel} 内部对象，
 *       {@code reconnectCount} / {@code lastFailureAt} / 退避都隔离，不会因一侧抖动拖垮另一侧；</li>
 *   <li>退避策略：初始 {@code wsClientReconnectInitialDelayMs}（2s）→ 指数 ×2，封顶
 *       {@code wsClientReconnectMaxDelayMs}（60s），重试次数到 {@code wsClientMaxReconnectAttempts}（30）
 *       后保持最大退避慢重连（不放弃，避免长断线后无法自愈）；</li>
 *   <li>消息路由通过载荷 {@code event_type} 字段判定：book/price_change/last_trade_price → market，
 *       trade/order → user；未识别的 event_type 仅 log.debug 不告警（Polymarket 偶发 ping/pong/welcome）；</li>
 *   <li>所有 onMessage / onFailure 异常 swallow，绝不抛回 OkHttp 线程
 *       （OkHttp WS 线程抛异常会断开连接，触发重连风暴）；</li>
 *   <li>Subscribe 消息：startup 时不知道 tokenId 集合，所以连接成功后只发 hello/ping；
 *       动态订阅由 {@link #subscribeMarkets(List)} / {@link #subscribeUsers(List)} 暴露
 *       给上游（如 watchlist 变更时调用）；当前 round 先打通连接，后续 round 补 subscribe 调用方。</li>
 * </ol>
 */
@Service
@Slf4j
public class PolymarketWebSocketClient {

    private static final String LOG_PREFIX = "[POLY_WS_CLIENT]";

    private final SmartWalletProperties properties;
    private final ObjectMapper objectMapper;
    private final PolymarketMarketDataStreamService marketDataStreamService;
    private final PolymarketSmartWalletMonitorService monitorService;

    private OkHttpClient httpClient;
    private ScheduledExecutorService scheduler;
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final AtomicBoolean started = new AtomicBoolean(false);

    private Channel marketChannel;
    private Channel userChannel;

    // Counters exposed via getStats() for /audit endpoint / Prometheus
    private final AtomicLong totalMessagesReceived = new AtomicLong();
    private final AtomicLong tradeMessagesRouted = new AtomicLong();
    private final AtomicLong bookMessagesRouted = new AtomicLong();
    private final AtomicLong unknownMessagesSkipped = new AtomicLong();
    private final AtomicLong sendErrors = new AtomicLong();

    @Autowired
    public PolymarketWebSocketClient(SmartWalletProperties properties,
                                     ObjectMapper objectMapper,
                                     PolymarketMarketDataStreamService marketDataStreamService,
                                     PolymarketSmartWalletMonitorService monitorService) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.marketDataStreamService = marketDataStreamService;
        this.monitorService = monitorService;
    }

    // ── lifecycle ─────────────────────────────────────────────────────

    @PostConstruct
    public void start() {
        if (!isEnabled()) {
            log.info("{} action=启动 result=跳过 reason=enable-ws-client=false", LOG_PREFIX);
            return;
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }
        SmartWalletProperties.PolymarketMonitor pm = polymarketConfig();
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(pm.getWsClientConnectTimeoutMs(), TimeUnit.MILLISECONDS)
                .readTimeout(pm.getWsClientReadTimeoutMs(), TimeUnit.MILLISECONDS)
                .pingInterval(Math.max(pm.getWsClientPingIntervalSeconds(), 0), TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "poly-ws-scheduler");
            t.setDaemon(true);
            return t;
        });
        marketChannel = new Channel("market", pm.getWsClientMarketChannelUrl(), true);
        userChannel = new Channel("user", pm.getWsClientUserChannelUrl(), false);
        log.info("{} action=启动 result=开始 marketUrl={} userUrl={} pingS={}", LOG_PREFIX,
                pm.getWsClientMarketChannelUrl(), pm.getWsClientUserChannelUrl(),
                pm.getWsClientPingIntervalSeconds());
        marketChannel.connect();
        userChannel.connect();
    }

    @PreDestroy
    public void stop() {
        if (!started.get()) {
            return;
        }
        shuttingDown.set(true);
        try {
            if (marketChannel != null) marketChannel.close();
        } catch (Exception ignore) { /* swallow on shutdown */ }
        try {
            if (userChannel != null) userChannel.close();
        } catch (Exception ignore) { /* swallow on shutdown */ }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        marketDataStreamService.markConnected(false);
        log.info("{} action=关闭 result=完成 totalMessagesReceived={}",
                LOG_PREFIX, totalMessagesReceived.get());
    }

    // ── public API ────────────────────────────────────────────────────

    /** 动态订阅指定 tokenId 列表到 market channel。可被上游 watchlist 变更逻辑调用。 */
    public boolean subscribeMarkets(List<String> tokenIds) {
        if (marketChannel == null || tokenIds == null || tokenIds.isEmpty()) {
            return false;
        }
        return marketChannel.sendSubscribe(buildMarketSubscribePayload(dedupAndClean(tokenIds)));
    }

    /** 动态订阅指定 wallet 列表到 user channel。可被 watchlist HOT/WARM 升级时调用。 */
    public boolean subscribeUsers(List<String> walletAddresses) {
        if (userChannel == null || walletAddresses == null || walletAddresses.isEmpty()) {
            return false;
        }
        return userChannel.sendSubscribe(buildUserSubscribePayload(dedupAndClean(walletAddresses)));
    }

    /** 当前已订阅的 tokenIds（merge of all batches sent so far），便于运维诊断。 */
    public Set<String> getSubscribedMarketTokens() {
        return marketChannel == null ? Collections.emptySet() : marketChannel.subscribedTokens();
    }

    public Set<String> getSubscribedUserWallets() {
        return userChannel == null ? Collections.emptySet() : userChannel.subscribedTokens();
    }

    public ClientStats getStats() {
        return ClientStats.builder()
                .enabled(isEnabled())
                .marketConnected(marketChannel != null && marketChannel.isConnected())
                .userConnected(userChannel != null && userChannel.isConnected())
                .marketReconnectAttempts(marketChannel == null ? 0 : marketChannel.reconnectCount.get())
                .userReconnectAttempts(userChannel == null ? 0 : userChannel.reconnectCount.get())
                .totalMessagesReceived(totalMessagesReceived.get())
                .bookMessagesRouted(bookMessagesRouted.get())
                .tradeMessagesRouted(tradeMessagesRouted.get())
                .unknownMessagesSkipped(unknownMessagesSkipped.get())
                .sendErrors(sendErrors.get())
                .build();
    }

    // ── internal helpers ─────────────────────────────────────────────

    private boolean isEnabled() {
        SmartWalletProperties.PolymarketMonitor pm = polymarketConfig();
        return pm != null && pm.isEnableWsClient() && StringUtils.hasText(pm.getWsClientMarketChannelUrl());
    }

    private SmartWalletProperties.PolymarketMonitor polymarketConfig() {
        return properties == null ? null : properties.getPolymarket();
    }

    private List<String> dedupAndClean(List<String> raw) {
        List<String> out = new ArrayList<>();
        for (String s : raw) {
            if (s == null) continue;
            String trimmed = s.trim();
            if (trimmed.isEmpty()) continue;
            if (!out.contains(trimmed)) out.add(trimmed);
        }
        return out;
    }

    /**
     * Polymarket 订阅消息格式（按官方文档示例）：
     * <pre>{"type":"market","markets":["token-1","token-2"]}</pre>
     * 实际格式以服务器端为准；若返回 error 则在 onMessage 中能看到，可调整为正确格式。
     */
    private String buildMarketSubscribePayload(List<String> tokenIds) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "market");
        body.put("markets", tokenIds);
        return writeJson(body);
    }

    /**
     * user channel 订阅消息：
     * <pre>{"type":"user","auth":{"apiKey":"...","secret":"...","passphrase":"..."},"markets":["..."]}</pre>
     */
    private String buildUserSubscribePayload(List<String> walletAddresses) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "user");
        body.put("markets", walletAddresses);
        String token = polymarketConfig() == null ? null : polymarketConfig().getWsClientUserChannelAuthToken();
        if (StringUtils.hasText(token)) {
            body.put("auth_token", token);
        }
        return writeJson(body);
    }

    private String writeJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            log.warn("{} action=序列化订阅消息 result=失败 reason={}", LOG_PREFIX, e.getMessage());
            return "{}";
        }
    }

    /**
     * 按 event_type 路由消息到 dataStream / monitor。
     * 失败 swallow，绝不抛回 OkHttp 线程（避免触发重连风暴）。
     */
    private void routeMessage(String channelName, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        totalMessagesReceived.incrementAndGet();
        try {
            // 快速判定（无须完整 parse）：trade 类事件特征字段 → user channel；book/price → market
            // 完整 parse 在下游服务内已经做了，这里只做粗粒度路由
            boolean looksLikeTrade = text.contains("\"event_type\":\"trade\"")
                    || text.contains("\"event_type\":\"order\"")
                    || text.contains("\"tradeId\"")
                    || text.contains("\"externalTradeId\"")
                    || text.contains("\"trade_id\"");
            boolean looksLikeBook = text.contains("\"event_type\":\"book\"")
                    || text.contains("\"event_type\":\"price_change\"")
                    || text.contains("\"event_type\":\"last_trade_price\"")
                    || text.contains("\"bids\"")
                    || text.contains("\"asks\"")
                    || text.contains("\"midpoint\"");

            if (looksLikeTrade) {
                tradeMessagesRouted.incrementAndGet();
                monitorService.ingestWsTradeEvent(text);
            } else if (looksLikeBook) {
                bookMessagesRouted.incrementAndGet();
                marketDataStreamService.handleMessage(text);
            } else {
                unknownMessagesSkipped.incrementAndGet();
                if (log.isDebugEnabled()) {
                    log.debug("{} channel={} action=路由 result=未识别event_type len={} sample={}",
                            LOG_PREFIX, channelName, text.length(),
                            text.length() < 256 ? text : text.substring(0, 256));
                }
            }
        } catch (Exception e) {
            log.warn("{} channel={} action=路由消息 result=异常 reason={}",
                    LOG_PREFIX, channelName, e.getMessage());
        }
    }

    // ── Channel: per-channel lifecycle / reconnect state ──────────────

    private class Channel {
        final String name;
        final String url;
        /** market channel updates dataStream.markConnected; user channel does not (different signal) */
        final boolean updatesDataStreamConnected;
        final AtomicInteger reconnectCount = new AtomicInteger();
        final AtomicBoolean connected = new AtomicBoolean(false);
        final Set<String> subscribed = new CopyOnWriteArraySet<>();
        // Pending subscribe payloads buffered while disconnected; replayed on next onOpen
        final List<String> pendingOnOpenSends = new ArrayList<>();
        volatile WebSocket socket;

        Channel(String name, String url, boolean updatesDataStreamConnected) {
            this.name = name;
            this.url = url;
            this.updatesDataStreamConnected = updatesDataStreamConnected;
        }

        boolean isConnected() {
            return connected.get();
        }

        Set<String> subscribedTokens() {
            return Collections.unmodifiableSet(subscribed);
        }

        synchronized void connect() {
            if (shuttingDown.get()) return;
            if (httpClient == null) return;
            if (!StringUtils.hasText(url)) {
                log.warn("{} channel={} action=连接 result=跳过 reason=空URL", LOG_PREFIX, name);
                return;
            }
            try {
                Request req = new Request.Builder().url(url).build();
                socket = httpClient.newWebSocket(req, new ChannelListener(this));
            } catch (Exception e) {
                log.warn("{} channel={} action=连接 result=异常 reason={}", LOG_PREFIX, name, e.getMessage());
                scheduleReconnect();
            }
        }

        synchronized void close() {
            connected.set(false);
            if (socket != null) {
                try {
                    socket.close(1000, "shutdown");
                } catch (Exception ignore) {
                    /* swallow */
                }
                socket = null;
            }
            if (updatesDataStreamConnected) {
                marketDataStreamService.markConnected(false);
            }
        }

        boolean sendSubscribe(String payload) {
            if (payload == null || payload.isEmpty()) return false;
            // Always buffer for replay on reconnect
            synchronized (pendingOnOpenSends) {
                pendingOnOpenSends.add(payload);
            }
            if (!connected.get() || socket == null) {
                if (log.isDebugEnabled()) {
                    log.debug("{} channel={} action=订阅 result=未连接将延迟发送 payloadLen={}",
                            LOG_PREFIX, name, payload.length());
                }
                return false;
            }
            try {
                boolean queued = socket.send(payload);
                if (!queued) {
                    sendErrors.incrementAndGet();
                }
                return queued;
            } catch (Exception e) {
                sendErrors.incrementAndGet();
                log.warn("{} channel={} action=订阅 result=异常 reason={}", LOG_PREFIX, name, e.getMessage());
                return false;
            }
        }

        void replayPendingSubscribes() {
            List<String> toSend;
            synchronized (pendingOnOpenSends) {
                if (pendingOnOpenSends.isEmpty()) return;
                toSend = new ArrayList<>(pendingOnOpenSends);
            }
            long delayMs = polymarketConfig() == null ? 200L : polymarketConfig().getWsClientSubscribeBatchDelayMs();
            int idx = 0;
            for (String payload : toSend) {
                final int seq = idx;
                if (scheduler != null && !scheduler.isShutdown()) {
                    scheduler.schedule(() -> {
                        if (socket == null) return;
                        try {
                            socket.send(payload);
                        } catch (Exception e) {
                            sendErrors.incrementAndGet();
                            log.warn("{} channel={} action=订阅replay result=异常 seq={} reason={}",
                                    LOG_PREFIX, name, seq, e.getMessage());
                        }
                    }, Math.max(delayMs * seq, 0), TimeUnit.MILLISECONDS);
                }
                idx++;
            }
        }

        synchronized void scheduleReconnect() {
            if (shuttingDown.get()) return;
            SmartWalletProperties.PolymarketMonitor pm = polymarketConfig();
            int attempts = reconnectCount.incrementAndGet();
            marketDataStreamService.incrementReconnectAttempts();
            long initial = pm == null ? 2_000L : pm.getWsClientReconnectInitialDelayMs();
            long max = pm == null ? 60_000L : pm.getWsClientReconnectMaxDelayMs();
            int hardCap = pm == null ? 30 : pm.getWsClientMaxReconnectAttempts();
            // exponential backoff: initial * 2^(attempts-1), capped at max
            int shift = Math.min(attempts - 1, 30);
            long delay = Math.min(max, initial * (1L << shift));
            // After hardCap fast retries, keep retrying at max delay (slow recovery, no give up)
            if (attempts > hardCap) {
                delay = max;
            }
            log.info("{} channel={} action=安排重连 attempt={} delayMs={}", LOG_PREFIX, name, attempts, delay);
            if (scheduler != null && !scheduler.isShutdown()) {
                scheduler.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
            }
        }
    }

    private class ChannelListener extends WebSocketListener {
        private final Channel channel;

        ChannelListener(Channel channel) {
            this.channel = channel;
        }

        @Override
        public void onOpen(WebSocket ws, okhttp3.Response response) {
            channel.connected.set(true);
            channel.reconnectCount.set(0);
            if (channel.updatesDataStreamConnected) {
                marketDataStreamService.markConnected(true);
            }
            log.info("{} channel={} action=onOpen result=已连接", LOG_PREFIX, channel.name);
            // replay any pending subscribes (即使为空也是无害)
            channel.replayPendingSubscribes();
        }

        @Override
        public void onMessage(WebSocket ws, String text) {
            routeMessage(channel.name, text);
        }

        @Override
        public void onClosing(WebSocket ws, int code, String reason) {
            log.info("{} channel={} action=onClosing code={} reason={}", LOG_PREFIX, channel.name, code, reason);
            channel.connected.set(false);
            if (channel.updatesDataStreamConnected) {
                marketDataStreamService.markConnected(false);
            }
        }

        @Override
        public void onClosed(WebSocket ws, int code, String reason) {
            log.info("{} channel={} action=onClosed code={} reason={}", LOG_PREFIX, channel.name, code, reason);
            channel.connected.set(false);
            if (channel.updatesDataStreamConnected) {
                marketDataStreamService.markConnected(false);
            }
            channel.scheduleReconnect();
        }

        @Override
        public void onFailure(WebSocket ws, Throwable t, okhttp3.Response response) {
            channel.connected.set(false);
            if (channel.updatesDataStreamConnected) {
                marketDataStreamService.markConnected(false);
            }
            log.warn("{} channel={} action=onFailure reason={}", LOG_PREFIX, channel.name,
                    t == null ? "null" : t.getMessage());
            channel.scheduleReconnect();
        }
    }

    // ── DTOs ─────────────────────────────────────────────────────────

    @lombok.Data
    @lombok.Builder
    public static class ClientStats {
        private boolean enabled;
        private boolean marketConnected;
        private boolean userConnected;
        private int marketReconnectAttempts;
        private int userReconnectAttempts;
        private long totalMessagesReceived;
        private long bookMessagesRouted;
        private long tradeMessagesRouted;
        private long unknownMessagesSkipped;
        private long sendErrors;
    }
}
