package com.metaverse.coin.strategy.job.btcminute.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.job.btcminute.config.BtcMinuteStrategyProperties;
import com.metaverse.coin.strategy.job.btcminute.model.BtcPriceSnapshot;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * BTC 实时价格服务 —— 通过 Binance WebSocket 获取毫秒级价格推送。
 *
 * <p>连接 Binance {@code wss://stream.binance.com:9443/ws/btcusdt@kline_1m}，
 * 接收 1 分钟 K 线推送，维护最新价格和历史 K 线环形缓冲区。</p>
 *
 * <p>断线自动重连，超过最大重连次数后回退到 REST API 轮询。</p>
 */
@Service
@Slf4j
public class BtcPriceFeedService {

    private static final String LOG_PREFIX = "[BTC_PRICE_FEED]";

    private final BtcMinuteStrategyProperties properties;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;

    /** 最新价格快照 */
    private final AtomicReference<BtcPriceSnapshot> latestSnapshot = new AtomicReference<>();

    /** 历史 K 线缓冲区（最多保留 N 根，用于动量/波动率计算） */
    private final CopyOnWriteArrayList<BtcPriceSnapshot> klineHistory = new CopyOnWriteArrayList<>();

    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicInteger reconnectCount = new AtomicInteger(0);
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    private volatile WebSocket webSocket;
    private ScheduledExecutorService scheduler;

    public BtcPriceFeedService(BtcMinuteStrategyProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    @PostConstruct
    public void init() {
        if (!properties.isEnabled()) {
            log.info("{} BTC 策略未启用，跳过价格 Feed 初始化", LOG_PREFIX);
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "btc-price-feed");
            t.setDaemon(true);
            return t;
        });

        // 先用 REST 加载历史 K 线
        loadHistoryKlines();

        // 启动 WebSocket 连接
        connectWebSocket();

        // 定时健康检查（如果 WS 断开且重连失败，回退到 REST 轮询）
        scheduler.scheduleWithFixedDelay(this::healthCheck, 30, 15, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void shutdown() {
        shuttingDown.set(true);
        if (webSocket != null) {
            webSocket.close(1000, "shutdown");
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    // ── 公共 API ──────────────────────────────────────────────────────

    /** 获取最新 BTC 价格快照 */
    public BtcPriceSnapshot getLatestSnapshot() {
        return latestSnapshot.get();
    }

    /** 获取最新 BTC 价格，如果没有数据返回 null */
    public BigDecimal getLatestPrice() {
        BtcPriceSnapshot snap = latestSnapshot.get();
        return snap != null ? snap.getPrice() : null;
    }

    /** 获取最近 N 根 K 线快照（时间升序） */
    public List<BtcPriceSnapshot> getRecentKlines(int count) {
        List<BtcPriceSnapshot> all = new ArrayList<>(klineHistory);
        int size = all.size();
        if (size <= count) return all;
        return all.subList(size - count, size);
    }

    /** WebSocket 是否已连接 */
    public boolean isConnected() {
        return connected.get();
    }

    // ── WebSocket 连接 ──────────────────────────────────────────────

    private void connectWebSocket() {
        BtcMinuteStrategyProperties.BinanceWs wsConfig = properties.getBinanceWs();
        String url = wsConfig.getBaseUrl() + "/" + wsConfig.getSymbol() + "@kline_" + wsConfig.getKlineInterval();

        log.info("{} 连接 Binance WebSocket: {}", LOG_PREFIX, url);

        Request request = new Request.Builder().url(url).build();
        webSocket = httpClient.newWebSocket(request, new WebSocketListener() {

            @Override
            public void onOpen(WebSocket ws, okhttp3.Response response) {
                connected.set(true);
                reconnectCount.set(0);
                log.info("{} WebSocket 连接成功", LOG_PREFIX);
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                try {
                    parseKlineMessage(text);
                } catch (Exception e) {
                    log.warn("{} 解析 K 线消息失败: {}", LOG_PREFIX, e.getMessage());
                }
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                connected.set(false);
                log.info("{} WebSocket 关闭中: code={} reason={}", LOG_PREFIX, code, reason);
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                connected.set(false);
                log.info("{} WebSocket 已关闭: code={} reason={}", LOG_PREFIX, code, reason);
                scheduleReconnect();
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, okhttp3.Response response) {
                connected.set(false);
                log.warn("{} WebSocket 连接失败: {}", LOG_PREFIX, t.getMessage());
                scheduleReconnect();
            }
        });
    }

    private void scheduleReconnect() {
        if (shuttingDown.get()) return;

        BtcMinuteStrategyProperties.BinanceWs wsConfig = properties.getBinanceWs();
        int attempts = reconnectCount.incrementAndGet();

        if (attempts > wsConfig.getMaxReconnectAttempts()) {
            log.error("{} 重连次数超限 ({}/{}), 回退到 REST 轮询", LOG_PREFIX, attempts, wsConfig.getMaxReconnectAttempts());
            startRestFallback();
            return;
        }

        int delay = wsConfig.getReconnectIntervalSeconds() * Math.min(attempts, 10);
        log.info("{} 第 {} 次重连将在 {}s 后执行", LOG_PREFIX, attempts, delay);

        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.schedule(this::connectWebSocket, delay, TimeUnit.SECONDS);
        }
    }

    // ── K 线消息解析 ──────────────────────────────────────────────────

    private void parseKlineMessage(String text) throws Exception {
        JsonNode root = objectMapper.readTree(text);
        JsonNode k = root.get("k");
        if (k == null) return;

        BigDecimal close = new BigDecimal(k.get("c").asText());
        BigDecimal open = new BigDecimal(k.get("o").asText());
        BigDecimal high = new BigDecimal(k.get("h").asText());
        BigDecimal low = new BigDecimal(k.get("l").asText());
        BigDecimal volume = new BigDecimal(k.get("v").asText());
        boolean isClosed = k.get("x").asBoolean();

        BtcPriceSnapshot snapshot = BtcPriceSnapshot.builder()
                .price(close)
                .klineOpen(open)
                .klineHigh(high)
                .klineLow(low)
                .klineClose(close)
                .klineVolume(volume)
                .timestamp(Instant.now())
                .source("WEBSOCKET")
                .build();

        latestSnapshot.set(snapshot);

        // K 线收盘时记入历史
        if (isClosed) {
            addToHistory(snapshot);
            log.debug("{} K线收盘: price={} volume={}", LOG_PREFIX, close, volume);
        }
    }

    // ── REST 回退 ──────────────────────────────────────────────────────

    private void loadHistoryKlines() {
        try {
            BtcMinuteStrategyProperties.BinanceWs wsConfig = properties.getBinanceWs();
            int count = properties.getMomentum().getHistoryKlineCount();
            String url = String.format("%s/api/v3/klines?symbol=%s&interval=%s&limit=%d",
                    wsConfig.getRestBaseUrl(), wsConfig.getSymbol().toUpperCase(),
                    wsConfig.getKlineInterval(), count);

            Request request = new Request.Builder().url(url).build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    log.warn("{} REST 加载历史 K 线失败: code={}", LOG_PREFIX, response.code());
                    return;
                }
                JsonNode array = objectMapper.readTree(response.body().string());
                for (JsonNode kline : array) {
                    BtcPriceSnapshot snap = BtcPriceSnapshot.builder()
                            .klineOpen(new BigDecimal(kline.get(1).asText()))
                            .klineHigh(new BigDecimal(kline.get(2).asText()))
                            .klineLow(new BigDecimal(kline.get(3).asText()))
                            .klineClose(new BigDecimal(kline.get(4).asText()))
                            .klineVolume(new BigDecimal(kline.get(5).asText()))
                            .price(new BigDecimal(kline.get(4).asText()))
                            .timestamp(Instant.ofEpochMilli(kline.get(0).asLong()))
                            .source("REST")
                            .build();
                    addToHistory(snap);
                }
                log.info("{} REST 加载 {} 根历史 K 线完成", LOG_PREFIX, array.size());

                // 用最后一根 K 线更新最新价格
                if (!klineHistory.isEmpty()) {
                    latestSnapshot.set(klineHistory.get(klineHistory.size() - 1));
                }
            }
        } catch (Exception e) {
            log.error("{} REST 加载历史 K 线异常: {}", LOG_PREFIX, e.getMessage(), e);
        }
    }

    private void startRestFallback() {
        log.info("{} 启动 REST 轮询回退模式（每 5 秒）", LOG_PREFIX);
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.scheduleWithFixedDelay(this::fetchLatestPriceViaRest, 0, 5, TimeUnit.SECONDS);
        }
    }

    private void fetchLatestPriceViaRest() {
        try {
            BtcMinuteStrategyProperties.BinanceWs wsConfig = properties.getBinanceWs();
            String url = String.format("%s/api/v3/ticker/price?symbol=%s",
                    wsConfig.getRestBaseUrl(), wsConfig.getSymbol().toUpperCase());

            Request request = new Request.Builder().url(url).build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) return;
                JsonNode node = objectMapper.readTree(response.body().string());
                BigDecimal price = new BigDecimal(node.get("price").asText());
                latestSnapshot.set(BtcPriceSnapshot.builder()
                        .price(price)
                        .timestamp(Instant.now())
                        .source("REST")
                        .build());
            }
        } catch (Exception e) {
            log.warn("{} REST 轮询获取价格失败: {}", LOG_PREFIX, e.getMessage());
        }
    }

    private void healthCheck() {
        if (!connected.get() && reconnectCount.get() <= properties.getBinanceWs().getMaxReconnectAttempts()) {
            log.info("{} 健康检查: WebSocket 未连接，触发重连", LOG_PREFIX);
            connectWebSocket();
        }
        BtcPriceSnapshot snap = latestSnapshot.get();
        if (snap != null) {
            long ageSeconds = Instant.now().getEpochSecond() - snap.getTimestamp().getEpochSecond();
            if (ageSeconds > 60) {
                log.warn("{} 价格数据过期: age={}s, 触发 REST 刷新", LOG_PREFIX, ageSeconds);
                fetchLatestPriceViaRest();
            }
        }
    }

    // ── 内部工具 ──────────────────────────────────────────────────────

    private void addToHistory(BtcPriceSnapshot snapshot) {
        klineHistory.add(snapshot);
        int maxSize = properties.getMomentum().getHistoryKlineCount() + 10;
        while (klineHistory.size() > maxSize) {
            klineHistory.remove(0);
        }
    }
}
