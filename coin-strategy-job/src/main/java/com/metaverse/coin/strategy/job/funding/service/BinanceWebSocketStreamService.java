package com.metaverse.coin.strategy.job.funding.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.common.client.binance.BinanceApiService;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesAccount;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesOrder;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesOrderBook;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesPositionRisk;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesPremiumIndex;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceOrderBook;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinancePriceTicker;
import com.metaverse.coin.strategy.common.model.binance.entity.spot.BinanceOrder;
import com.metaverse.coin.strategy.core.arbitrage.config.SingleExchangeSpotFuturesProperties;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Credentials;
import okhttp3.Authenticator;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnBean(BinanceApiService.class)
public class BinanceWebSocketStreamService implements DisposableBean {

    private static final String SPOT_WEBSOCKET_API_URL = "wss://ws-api.binance.com:443/ws-api/v3";
    private static final String FUTURES_WEBSOCKET_API_URL = "wss://ws-fapi.binance.com/ws-fapi/v1";
    private static final String FUTURES_USER_DATA_PING_DELAY_MS = "1800000";

    private final BinanceWebSocketProperties properties;
    private final SingleExchangeSpotFuturesProperties spotFuturesProperties;
    private final BinanceApiService binanceApiService;
    private final ObjectMapper objectMapper;
    private volatile OkHttpClient okHttpClient;
    private final ScheduledExecutorService reconnectExecutor = Executors.newSingleThreadScheduledExecutor();

    private final Map<String, Snapshot<BinancePriceTicker>> spotPrices = new ConcurrentHashMap<>();
    private final Map<String, Snapshot<BinancePriceTicker>> futuresPrices = new ConcurrentHashMap<>();
    private final Map<String, Snapshot<BinanceFuturesPremiumIndex>> premiumIndices = new ConcurrentHashMap<>();
    private final Map<String, Snapshot<BinanceOrderBook>> spotDepth = new ConcurrentHashMap<>();
    private final Map<String, Snapshot<BinanceFuturesOrderBook>> futuresDepth = new ConcurrentHashMap<>();
    private final Map<String, Snapshot<BinanceOrder>> spotOrders = new ConcurrentHashMap<>();
    private final Map<String, Snapshot<BinanceFuturesOrder>> futuresOrders = new ConcurrentHashMap<>();
    private final Map<String, Snapshot<List<BinanceFuturesPositionRisk>>> positionRisks = new ConcurrentHashMap<>();
    private volatile Snapshot<BinanceFuturesAccount> futuresAccount;

    private final Map<String, WebSocket> spotMarketSockets = new ConcurrentHashMap<>();
    private final Map<String, WebSocket> futuresMarketSockets = new ConcurrentHashMap<>();
    private volatile WebSocket spotUserApiSocket;
    private volatile WebSocket futuresUserApiSocket;
    private volatile WebSocket futuresUserSocket;
    private volatile Long spotUserSubscriptionId;
    private volatile String futuresListenKey;

    private final AtomicLong publicMessagesReceived = new AtomicLong();
    private final AtomicLong userMessagesReceived = new AtomicLong();
    private final AtomicLong webSocketApiRequestId = new AtomicLong();
    private volatile Date lastPublicEventAt;
    private volatile Date lastUserEventAt;

    @PostConstruct
    public void start() {
        if (!properties.isEnabled()) {
            return;
        }
        okHttpClient = buildOkHttpClient();
        Set<String> configuredSymbols = properties.getSymbols();
        configuredSymbols.forEach(this::subscribeSymbol);
        if (configuredSymbols.isEmpty() && StringUtils.hasText(spotFuturesProperties.getSymbol())) {
            subscribeSymbol(spotFuturesProperties.getSymbol());
        }
        connectUserStreams();
    }

    public void subscribeSymbol(String symbol) {
        if (!properties.isEnabled() || !properties.isPublicMarketEnabled() || !StringUtils.hasText(symbol)) {
            return;
        }
        String normalized = symbol.trim().toUpperCase(Locale.ROOT);
        spotMarketSockets.computeIfAbsent(normalized, this::connectSpotMarketStream);
        futuresMarketSockets.computeIfAbsent(normalized, this::connectFuturesMarketStream);
    }

    public BinancePriceTicker getCachedSpotPrice(String symbol) {
        subscribeSymbol(symbol);
        return fresh(spotPrices.get(normalize(symbol)));
    }

    public BinancePriceTicker getCachedFuturesPrice(String symbol) {
        subscribeSymbol(symbol);
        return fresh(futuresPrices.get(normalize(symbol)));
    }

    public BinanceFuturesPremiumIndex getCachedPremiumIndex(String symbol) {
        subscribeSymbol(symbol);
        return fresh(premiumIndices.get(normalize(symbol)));
    }

    public List<BinanceFuturesPremiumIndex> getCachedPremiumIndices() {
        long now = System.currentTimeMillis();
        return premiumIndices.values().stream()
                .filter(entry -> isFresh(entry, now))
                .map(Snapshot::getValue)
                .toList();
    }

    public BinanceOrderBook getCachedSpotOrderBook(String symbol) {
        subscribeSymbol(symbol);
        return fresh(spotDepth.get(normalize(symbol)));
    }

    public BinanceFuturesOrderBook getCachedFuturesOrderBook(String symbol) {
        subscribeSymbol(symbol);
        return fresh(futuresDepth.get(normalize(symbol)));
    }

    public BinanceOrder getCachedSpotOrder(String symbol, Long orderId, String clientOrderId) {
        return fresh(spotOrders.get(orderKey(symbol, orderId, clientOrderId)));
    }

    public BinanceFuturesOrder getCachedFuturesOrder(String symbol, Long orderId, String clientOrderId) {
        return fresh(futuresOrders.get(orderKey(symbol, orderId, clientOrderId)));
    }

    public BinanceFuturesAccount getCachedFuturesAccount() {
        return fresh(futuresAccount);
    }

    public List<BinanceFuturesPositionRisk> getCachedPositionRisk(String symbol) {
        return fresh(positionRisks.get(normalize(symbol)));
    }

    public void cacheSpotPrice(BinancePriceTicker value) {
        if (value != null && StringUtils.hasText(value.getSymbol())) {
            spotPrices.put(normalize(value.getSymbol()), snapshot(value));
        }
    }

    public void cacheFuturesPrice(BinancePriceTicker value) {
        if (value != null && StringUtils.hasText(value.getSymbol())) {
            futuresPrices.put(normalize(value.getSymbol()), snapshot(value));
        }
    }

    public void cachePremiumIndex(BinanceFuturesPremiumIndex value) {
        if (value != null && StringUtils.hasText(value.getSymbol())) {
            premiumIndices.put(normalize(value.getSymbol()), snapshot(value));
        }
    }

    public void cacheSpotOrderBook(String symbol, BinanceOrderBook value) {
        if (value != null && StringUtils.hasText(symbol)) {
            spotDepth.put(normalize(symbol), snapshot(value));
        }
    }

    public void cacheFuturesOrderBook(String symbol, BinanceFuturesOrderBook value) {
        if (value != null && StringUtils.hasText(symbol)) {
            futuresDepth.put(normalize(symbol), snapshot(value));
        }
    }

    public void cacheSpotOrder(BinanceOrder value) {
        if (value != null) {
            spotOrders.put(orderKey(value.getSymbol(), value.getOrderId(), value.getClientOrderId()), snapshot(value));
        }
    }

    public void cacheFuturesOrder(BinanceFuturesOrder value) {
        if (value != null) {
            futuresOrders.put(orderKey(value.getSymbol(), value.getOrderId(), value.getClientOrderId()), snapshot(value));
        }
    }

    public void cacheFuturesAccount(BinanceFuturesAccount value) {
        if (value != null) {
            futuresAccount = snapshot(value);
        }
    }

    public void cachePositionRisk(String symbol, List<BinanceFuturesPositionRisk> value) {
        if (value != null && StringUtils.hasText(symbol)) {
            positionRisks.put(normalize(symbol), snapshot(value));
        }
    }

    public void handlePublicMessage(String payload) {
        handlePublicMessage(payload, false);
    }

    public void handlePublicMessage(String payload, boolean futures) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode data = root.has("data") ? root.get("data") : root;
            String eventType = text(data, "e");
            if ("markPriceUpdate".equals(eventType)) {
                ingestMarkPrice(data);
            } else if (data.has("b") && data.has("a") && data.has("B") && data.has("A")) {
                ingestBookTicker(data, futures);
            } else if (data.has("bids") || data.has("asks") || data.has("b") || data.has("a")) {
                ingestDepth(data, streamName(root), futures);
            }
            publicMessagesReceived.incrementAndGet();
            lastPublicEventAt = new Date();
        } catch (Exception e) {
            log.debug("Failed to handle Binance public WS message: {}", e.getMessage());
        }
    }

    public void handleUserMessage(String payload, boolean futures) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            if (root.has("event")) {
                root = root.get("event");
            }
            String eventType = text(root, "e");
            if (futures && "ORDER_TRADE_UPDATE".equals(eventType)) {
                ingestFuturesOrder(root.get("o"));
            } else if (futures && "ACCOUNT_UPDATE".equals(eventType)) {
                ingestFuturesAccountUpdate(root.get("a"), number(root, "E"));
            } else if (!futures && "executionReport".equals(eventType)) {
                ingestSpotOrder(root);
            }
            userMessagesReceived.incrementAndGet();
            lastUserEventAt = new Date();
        } catch (Exception e) {
            log.debug("Failed to handle Binance user WS message: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = FUTURES_USER_DATA_PING_DELAY_MS)
    public void keepAliveUserStreams() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            if (StringUtils.hasText(futuresListenKey) && futuresUserApiSocket != null) {
                futuresUserApiSocket.send(buildFuturesUserDataApiRequest("userDataStream.ping"));
            }
        } catch (Exception e) {
            log.warn("Failed to ping Binance Futures user data stream through WebSocket API: {}", e.getMessage());
        }
    }

    public BinanceWebSocketStats getStats() {
        return BinanceWebSocketStats.builder()
                .enabled(properties.isEnabled())
                .publicMarketEnabled(properties.isPublicMarketEnabled())
                .subscribedSymbols(spotMarketSockets.size())
                .cachedPremiumIndices(premiumIndices.size())
                .cachedSpotDepth(spotDepth.size())
                .cachedFuturesDepth(futuresDepth.size())
                .cachedSpotOrders(spotOrders.size())
                .cachedFuturesOrders(futuresOrders.size())
                .publicMessagesReceived(publicMessagesReceived.get())
                .userMessagesReceived(userMessagesReceived.get())
                .lastPublicEventAt(lastPublicEventAt)
                .lastUserEventAt(lastUserEventAt)
                .build();
    }

    private WebSocket connectSpotMarketStream(String symbol) {
        String lower = symbol.toLowerCase(Locale.ROOT);
        String url = properties.getSpotStreamUrl() + "/stream?streams="
                + lower + "@bookTicker/" + lower + "@depth20@100ms";
        return newWebSocket(url, new PublicListener(false, () -> reconnectSpot(symbol)));
    }

    private WebSocket connectFuturesMarketStream(String symbol) {
        String lower = symbol.toLowerCase(Locale.ROOT);
        String url = properties.getFuturesStreamUrl() + "/stream?streams="
                + lower + "@bookTicker/" + lower + "@depth20@100ms/" + lower + "@markPrice@1s";
        return newWebSocket(url, new PublicListener(true, () -> reconnectFutures(symbol)));
    }

    private void connectUserStreams() {
        connectSpotUserDataApi();
        connectFuturesUserDataApi();
    }

    private void connectSpotUserDataApi() {
        try {
            spotUserApiSocket = newWebSocket(SPOT_WEBSOCKET_API_URL, new SpotUserApiListener());
        } catch (Exception e) {
            log.warn("Failed to connect Binance Spot WebSocket API user data stream: {}", e.getMessage());
        }
    }

    private void connectFuturesUserDataApi() {
        try {
            futuresUserApiSocket = newWebSocket(FUTURES_WEBSOCKET_API_URL, new FuturesUserApiListener());
        } catch (Exception e) {
            log.warn("Failed to connect Binance Futures WebSocket API user data stream: {}", e.getMessage());
        }
    }

    private void connectFuturesPrivateUserStream() {
        if (!StringUtils.hasText(futuresListenKey)) {
            return;
        }
        futuresUserSocket = newWebSocket(properties.getFuturesStreamUrl() + "/private/ws/" + futuresListenKey,
                new UserListener(true, this::connectFuturesUserDataApi));
    }

    String buildSpotUserDataSubscribeRequest() {
        String apiKey = binanceApiService.getApiKey();
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException("Binance API key and secret key are required for Spot WebSocket API user data stream");
        }
        long timestamp = System.currentTimeMillis();
        String payload = "apiKey=" + apiKey + "&timestamp=" + timestamp;
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("apiKey", apiKey);
        params.put("timestamp", timestamp);
        params.put("signature", binanceApiService.signHmacSha256Hex(payload));
        return buildWebSocketApiRequest(nextWebSocketApiRequestId("sus"),
                "userDataStream.subscribe.signature", params);
    }

    String buildSpotUserDataUnsubscribeRequest() {
        Map<String, Object> params = new LinkedHashMap<>();
        if (spotUserSubscriptionId != null) {
            params.put("subscriptionId", spotUserSubscriptionId);
        }
        return buildWebSocketApiRequest(nextWebSocketApiRequestId("suu"),
                "userDataStream.unsubscribe", params);
    }

    String buildFuturesUserDataApiRequest(String method) {
        String apiKey = binanceApiService.getApiKey();
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException("Binance API key is required for Futures WebSocket API user data stream");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("apiKey", apiKey);
        return buildWebSocketApiRequest(nextWebSocketApiRequestId("fus"),
                method, params);
    }

    private String nextWebSocketApiRequestId(String prefix) {
        return prefix + Long.toString(webSocketApiRequestId.incrementAndGet(), 36);
    }

    private String buildWebSocketApiRequest(String id, String method, Map<String, Object> params) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("id", id);
        request.put("method", method);
        request.put("params", params);
        try {
            return objectMapper.writeValueAsString(request);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build Binance WebSocket API request", e);
        }
    }

    private WebSocket newWebSocket(String url, WebSocketListener listener) {
        Request request = new Request.Builder().url(url).build();
        return getOkHttpClient().newWebSocket(request, listener);
    }

    private OkHttpClient getOkHttpClient() {
        if (okHttpClient == null) {
            okHttpClient = buildOkHttpClient();
        }
        return okHttpClient;
    }

    private OkHttpClient buildOkHttpClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(properties.getConnectTimeoutMs(), TimeUnit.MILLISECONDS)
                .readTimeout(properties.getReadTimeoutMs(), TimeUnit.MILLISECONDS)
                .writeTimeout(properties.getWriteTimeoutMs(), TimeUnit.MILLISECONDS)
                // Binance sends server ping frames; OkHttp automatically replies with pong.
                // Keep client-initiated ping disabled by default to avoid proxy-induced false disconnects.
                .pingInterval(properties.getPingIntervalMs(), TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true);
        ProxySettings proxySettings = normalizeProxySettings();
        if (proxySettings != null) {
            builder.proxy(new Proxy(proxySettings.type(), new InetSocketAddress(proxySettings.host(), proxySettings.port())));
            Authenticator authenticator = buildProxyAuthenticator();
            if (authenticator != null) {
                builder.proxyAuthenticator(authenticator);
            }
            log.info("Configured Binance websocket client to use {} proxy {}:{}",
                    proxySettings.type(), proxySettings.host(), proxySettings.port());
        }
        return builder.build();
    }

    private ProxySettings normalizeProxySettings() {
        String proxyHost = properties.getProxyHost();
        if (!StringUtils.hasText(proxyHost)) {
            return null;
        }
        String trimmedProxyHost = proxyHost.trim();
        if (!trimmedProxyHost.contains("://")) {
            if (properties.getProxyPort() <= 0) {
                throw new IllegalArgumentException("Binance websocket proxy-port must be greater than 0 when proxy-host does not include a port");
            }
            return new ProxySettings(trimmedProxyHost, properties.getProxyPort(), resolveProxyType(properties.getProxyType()));
        }
        try {
            URI proxyUri = new URI(trimmedProxyHost);
            String host = proxyUri.getHost();
            int port = proxyUri.getPort() > 0 ? proxyUri.getPort() : properties.getProxyPort();
            if (!StringUtils.hasText(host) || port <= 0) {
                throw new IllegalArgumentException("Invalid Binance websocket proxy: " + proxyHost);
            }
            String scheme = StringUtils.hasText(proxyUri.getScheme()) ? proxyUri.getScheme() : properties.getProxyType();
            return new ProxySettings(host, port, resolveProxyType(scheme));
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid Binance websocket proxy: " + proxyHost, e);
        }
    }

    private Proxy.Type resolveProxyType(String proxyType) {
        if ("SOCKS".equalsIgnoreCase(proxyType) || "SOCKS5".equalsIgnoreCase(proxyType)) {
            return Proxy.Type.SOCKS;
        }
        return Proxy.Type.HTTP;
    }

    private Authenticator buildProxyAuthenticator() {
        if (!StringUtils.hasText(properties.getProxyUsername())) {
            return null;
        }
        String password = properties.getProxyPassword() == null ? "" : properties.getProxyPassword();
        return (route, response) -> response.request().newBuilder()
                .header("Proxy-Authorization", Credentials.basic(properties.getProxyUsername(), password))
                .build();
    }

    private void reconnectSpot(String symbol) {
        spotMarketSockets.remove(symbol);
        reconnectExecutor.schedule(() -> subscribeSymbol(symbol), properties.getReconnectDelayMs(), TimeUnit.MILLISECONDS);
    }

    private void reconnectFutures(String symbol) {
        futuresMarketSockets.remove(symbol);
        reconnectExecutor.schedule(() -> subscribeSymbol(symbol), properties.getReconnectDelayMs(), TimeUnit.MILLISECONDS);
    }

    private void ingestMarkPrice(JsonNode data) {
        String symbol = text(data, "s");
        if (!StringUtils.hasText(symbol)) {
            return;
        }
        BigDecimal markPrice = decimal(data, "p");
        premiumIndices.put(normalize(symbol), snapshot(BinanceFuturesPremiumIndex.builder()
                .symbol(normalize(symbol))
                .markPrice(markPrice)
                .indexPrice(decimal(data, "i"))
                .lastFundingRate(decimal(data, "r"))
                .nextFundingTime(number(data, "T"))
                .time(number(data, "E"))
                .build()));
        if (markPrice != null) {
            futuresPrices.put(normalize(symbol), snapshot(BinancePriceTicker.builder()
                    .symbol(normalize(symbol))
                    .price(markPrice)
                    .build()));
        }
    }

    private void ingestBookTicker(JsonNode data, boolean futures) {
        String symbol = normalize(text(data, "s"));
        BigDecimal bid = decimal(data, "b");
        BigDecimal ask = decimal(data, "a");
        if (!StringUtils.hasText(symbol) || bid == null || ask == null) {
            return;
        }
        BigDecimal mid = bid.add(ask).divide(new BigDecimal("2"), 8, RoundingMode.HALF_UP);
        BinancePriceTicker ticker = BinancePriceTicker.builder().symbol(symbol).price(mid).build();
        if (futures) {
            futuresPrices.put(symbol, snapshot(ticker));
        } else {
            spotPrices.put(symbol, snapshot(ticker));
        }
    }

    private void ingestDepth(JsonNode data, String streamName, boolean futures) {
        String symbol = normalize(text(data, "s"));
        if (!StringUtils.hasText(symbol) && StringUtils.hasText(streamName)) {
            symbol = streamName.split("@")[0].toUpperCase(Locale.ROOT);
        }
        if (!StringUtils.hasText(symbol)) {
            return;
        }
        List<List<String>> bids = levels(firstPresent(data, "bids", "b"));
        List<List<String>> asks = levels(firstPresent(data, "asks", "a"));
        if (streamName != null && streamName.contains("depth")) {
            if (futures) {
                BinanceFuturesOrderBook book = new BinanceFuturesOrderBook();
                book.setLastUpdateId(number(data, "lastUpdateId", "u"));
                book.setBids(bids);
                book.setAsks(asks);
                futuresDepth.put(symbol, snapshot(book));
            } else {
                BinanceOrderBook book = new BinanceOrderBook();
                book.setLastUpdateId(number(data, "lastUpdateId", "u"));
                book.setBids(bids);
                book.setAsks(asks);
                spotDepth.put(symbol, snapshot(book));
            }
        }
    }

    private void ingestSpotOrder(JsonNode data) {
        BinanceOrder order = BinanceOrder.builder()
                .symbol(normalize(text(data, "s")))
                .orderId(number(data, "i"))
                .clientOrderId(text(data, "c"))
                .side(text(data, "S"))
                .type(text(data, "o"))
                .timeInForce(text(data, "f"))
                .price(decimal(data, "p", "L"))
                .origQty(decimal(data, "q"))
                .executedQty(decimal(data, "z"))
                .cummulativeQuoteQty(decimal(data, "Z"))
                .status(text(data, "X"))
                .time(number(data, "O"))
                .updateTime(number(data, "E"))
                .build();
        cacheSpotOrder(order);
    }

    private void ingestFuturesOrder(JsonNode data) {
        if (data == null || data.isNull()) {
            return;
        }
        BinanceFuturesOrder order = BinanceFuturesOrder.builder()
                .symbol(normalize(text(data, "s")))
                .orderId(number(data, "i"))
                .clientOrderId(text(data, "c"))
                .side(text(data, "S"))
                .positionSide(text(data, "ps"))
                .type(text(data, "o"))
                .timeInForce(text(data, "f"))
                .price(decimal(data, "p"))
                .avgPrice(decimal(data, "ap"))
                .origQty(decimal(data, "q"))
                .executedQty(decimal(data, "z"))
                .cumQuote(decimal(data, "Z"))
                .status(text(data, "X"))
                .updateTime(number(data, "T"))
                .build();
        cacheFuturesOrder(order);
    }

    private void ingestFuturesAccountUpdate(JsonNode data, Long updateTime) {
        if (data == null || data.isNull()) {
            return;
        }
        BinanceFuturesAccount account = getCachedFuturesAccount();
        if (account == null) {
            account = BinanceFuturesAccount.builder().build();
        }
        account.setUpdateTime(updateTime);
        futuresAccount = snapshot(account);
        JsonNode positions = data.get("P");
        if (positions != null && positions.isArray()) {
            for (JsonNode position : positions) {
                String symbol = normalize(text(position, "s"));
                if (!StringUtils.hasText(symbol)) {
                    continue;
                }
                BinanceFuturesPositionRisk risk = BinanceFuturesPositionRisk.builder()
                        .symbol(symbol)
                        .positionAmt(decimal(position, "pa"))
                        .entryPrice(decimal(position, "ep"))
                        .unRealizedProfit(decimal(position, "up"))
                        .positionSide(text(position, "ps"))
                        .updateTime(updateTime)
                        .build();
                positionRisks.put(symbol, snapshot(List.of(risk)));
            }
        }
    }

    private List<List<String>> levels(JsonNode node) {
        List<List<String>> levels = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return levels;
        }
        for (JsonNode level : node) {
            if (level.isArray() && level.size() >= 2) {
                levels.add(List.of(level.get(0).asText(), level.get(1).asText()));
            }
        }
        return levels;
    }

    private JsonNode firstPresent(JsonNode node, String... keys) {
        if (node == null) {
            return null;
        }
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && !value.isNull()) {
                return value;
            }
        }
        return null;
    }

    private BigDecimal decimal(JsonNode node, String... keys) {
        JsonNode value = keys.length == 0 ? node : firstPresent(node, keys);
        if (value == null || value.isNull()) {
            return null;
        }
        try {
            return new BigDecimal(value.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long number(JsonNode node, String... keys) {
        JsonNode value = firstPresent(node, keys);
        if (value == null || value.isNull()) {
            return null;
        }
        try {
            return Long.valueOf(value.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String text(JsonNode node, String key) {
        JsonNode value = node == null ? null : node.get(key);
        return value == null || value.isNull() ? null : value.asText(null);
    }

    private String streamName(JsonNode root) {
        return text(root, "stream");
    }

    private String normalize(String symbol) {
        return symbol == null ? null : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private String orderKey(String symbol, Long orderId, String clientOrderId) {
        return normalize(symbol) + ":" + (orderId == null ? "" : orderId) + ":" + (clientOrderId == null ? "" : clientOrderId);
    }

    private <T> Snapshot<T> snapshot(T value) {
        return new Snapshot<>(value, System.currentTimeMillis());
    }

    private <T> T fresh(Snapshot<T> snapshot) {
        return isFresh(snapshot, System.currentTimeMillis()) ? snapshot.getValue() : null;
    }

    private boolean isFresh(Snapshot<?> snapshot, long now) {
        return snapshot != null && now - snapshot.getUpdatedAt() <= properties.getCacheTtlMs();
    }

    @Override
    public void destroy() {
        spotMarketSockets.values().forEach(socket -> socket.close(1000, "shutdown"));
        futuresMarketSockets.values().forEach(socket -> socket.close(1000, "shutdown"));
        if (spotUserApiSocket != null) {
            try {
                spotUserApiSocket.send(buildSpotUserDataUnsubscribeRequest());
            } catch (Exception e) {
                log.debug("Failed to unsubscribe Binance Spot WebSocket API user data stream: {}", e.getMessage());
            }
            spotUserApiSocket.close(1000, "shutdown");
        }
        if (futuresUserApiSocket != null) {
            try {
                futuresUserApiSocket.send(buildFuturesUserDataApiRequest("userDataStream.stop"));
            } catch (Exception e) {
                log.debug("Failed to stop Binance Futures user data stream through WebSocket API: {}", e.getMessage());
            }
            futuresUserApiSocket.close(1000, "shutdown");
        }
        if (futuresUserSocket != null) {
            futuresUserSocket.close(1000, "shutdown");
        }
        reconnectExecutor.shutdownNow();
    }

    private class PublicListener extends WebSocketListener {
        private final boolean futures;
        private final Runnable reconnect;

        private PublicListener(boolean futures, Runnable reconnect) {
            this.futures = futures;
            this.reconnect = reconnect;
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            handlePublicMessage(text, futures);
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, okhttp3.Response response) {
            log.warn("Binance public WS disconnected: {}, response={}", t.getMessage(), describeResponse(response));
            reconnect.run();
        }
    }

    private class UserListener extends WebSocketListener {
        private final boolean futures;
        private final Runnable reconnect;

        private UserListener(boolean futures, Runnable reconnect) {
            this.futures = futures;
            this.reconnect = reconnect;
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            handleUserMessage(text, futures);
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, okhttp3.Response response) {
            log.warn("Binance user WS disconnected: {}, response={}", t.getMessage(), describeResponse(response));
            if (futures) {
                futuresUserSocket = null;
            }
            reconnectExecutor.schedule(reconnect, properties.getReconnectDelayMs(), TimeUnit.MILLISECONDS);
        }
    }

    private class SpotUserApiListener extends WebSocketListener {
        @Override
        public void onOpen(WebSocket webSocket, okhttp3.Response response) {
            webSocket.send(buildSpotUserDataSubscribeRequest());
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            try {
                JsonNode root = objectMapper.readTree(text);
                if (root.has("event")) {
                    handleUserMessage(text, false);
                    return;
                }
                int status = root.path("status").asInt(0);
                if (status == 200) {
                    JsonNode subscriptionId = root.path("result").path("subscriptionId");
                    if (!subscriptionId.isMissingNode() && !subscriptionId.isNull()) {
                        spotUserSubscriptionId = subscriptionId.asLong();
                        log.info("Subscribed Binance Spot WebSocket API user data stream: subscriptionId={}", spotUserSubscriptionId);
                    }
                    return;
                }
                if (root.has("error")) {
                    log.warn("Binance Spot WebSocket API user data stream request failed: {}", root.get("error"));
                }
            } catch (Exception e) {
                log.debug("Failed to handle Binance Spot WebSocket API message: {}", e.getMessage());
            }
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, okhttp3.Response response) {
            log.warn("Binance Spot WebSocket API user data stream disconnected: {}, response={}",
                    t.getMessage(), describeResponse(response));
            spotUserApiSocket = null;
            reconnectExecutor.schedule(BinanceWebSocketStreamService.this::connectSpotUserDataApi,
                    properties.getReconnectDelayMs(), TimeUnit.MILLISECONDS);
        }
    }

    private class FuturesUserApiListener extends WebSocketListener {
        @Override
        public void onOpen(WebSocket webSocket, okhttp3.Response response) {
            webSocket.send(buildFuturesUserDataApiRequest("userDataStream.start"));
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            try {
                JsonNode root = objectMapper.readTree(text);
                int status = root.path("status").asInt(0);
                if (status == 200) {
                    String listenKey = root.path("result").path("listenKey").asText(null);
                    if (StringUtils.hasText(listenKey)) {
                        boolean firstStart = !listenKey.equals(futuresListenKey);
                        futuresListenKey = listenKey;
                        if (firstStart || futuresUserSocket == null) {
                            connectFuturesPrivateUserStream();
                        }
                    }
                    return;
                }
                if (root.has("error")) {
                    log.warn("Binance Futures WebSocket API user data stream request failed: {}", root.get("error"));
                }
            } catch (Exception e) {
                log.debug("Failed to handle Binance Futures WebSocket API message: {}", e.getMessage());
            }
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, okhttp3.Response response) {
            log.warn("Binance Futures WebSocket API user data stream disconnected: {}, response={}",
                    t.getMessage(), describeResponse(response));
            futuresUserApiSocket = null;
            reconnectExecutor.schedule(BinanceWebSocketStreamService.this::connectFuturesUserDataApi,
                    properties.getReconnectDelayMs(), TimeUnit.MILLISECONDS);
        }
    }

    private String describeResponse(okhttp3.Response response) {
        if (response == null) {
            return "none";
        }
        return "code=" + response.code() + ", message=" + response.message();
    }

    private record ProxySettings(String host, int port, Proxy.Type type) {
    }

    @Data
    private static class Snapshot<T> {
        private final T value;
        private final long updatedAt;
    }

    @Data
    @Builder
    public static class BinanceWebSocketStats {
        private boolean enabled;
        private boolean publicMarketEnabled;
        private int subscribedSymbols;
        private int cachedPremiumIndices;
        private int cachedSpotDepth;
        private int cachedFuturesDepth;
        private int cachedSpotOrders;
        private int cachedFuturesOrders;
        private long publicMessagesReceived;
        private long userMessagesReceived;
        private Date lastPublicEventAt;
        private Date lastUserEventAt;
    }
}
