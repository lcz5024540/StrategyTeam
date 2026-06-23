package com.metaverse.coin.strategy.job.smartwallet.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketTradeEventRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketWalletMetricsRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
@RequiredArgsConstructor
public class PolymarketSmartWalletMonitorService {

    private static final String SOURCE = "POLYMARKET";
    private static final String LOG_PREFIX = "[POLY_FOLLOW]";

    private final SmartWalletProperties properties;
    private final SmartWalletProfileStoreService profileStoreService;
    private final SmartWalletFollowTaskService followTaskService;
    private final SmartWalletTimeFormatter timeFormatter;
    private final JdbcTemplate jdbcTemplate;

    // F-P1-8: WS trade-event 推流路径相关
    // - objectMapper: 注入可空，便于纯 unit test 直接 new 实例不需 Spring 装配
    // - dedupe map: synchronized LinkedHashMap LRU；命中即跳过 ingestTradeEvent，让 DB 唯一性兜底
    // - counters: 暴露给 /audit/poly-monitor 端点 / Prometheus 用，便于灰度阶段观察 WS hit 率
    @Autowired(required = false)
    private ObjectMapper objectMapper;

    private final Map<String, Long> wsTradeDedupeLru = new LinkedHashMap<>(256, 0.75f, true);
    private final AtomicLong wsTradeEventsReceived = new AtomicLong();
    private final AtomicLong wsTradeEventsForwarded = new AtomicLong();
    private final AtomicLong wsTradeEventsDeduped = new AtomicLong();
    private final AtomicLong wsTradeEventsParseFailures = new AtomicLong();
    private final AtomicLong wsTradeEventsDisabledSkips = new AtomicLong();

    @PostConstruct
    public void ensureTradeEventSchema() {
        try {
            jdbcTemplate.execute("ALTER TABLE `smart_wallet_trade_event` ADD COLUMN `size_shares` DECIMAL(20,8) DEFAULT NULL AFTER `price`");
        } catch (Exception ignored) {
            // Column already exists on most runs; ignore duplicate-column errors.
        }
    }

    public SmartWalletProfileEntity ingestWalletMetrics(PolymarketWalletMetricsRequest request) {
        log.info("{} action=导入Polymarket钱包指标 result=开始 wallet={} chain={} winRate={} pnlUsdc={} totalTrades={}",
                LOG_PREFIX, request.getWalletAddress(), request.getChain(), request.getWinRate(),
                request.getRealizedPnlUsdc(), request.getTotalTrades());
        normalizeMetricsRequest(request);
        SmartWalletProfileEntity profile = profileStoreService.upsertMetrics(request);
        log.info("{} action=导入Polymarket钱包指标 result=完成 wallet={} profileId={} qualified={} watchEnabled={} walletScore={} monitorTier={}",
                LOG_PREFIX, profile.getWalletAddress(), profile.getId(), profile.getQualified(),
                profile.getWatchEnabled(), profile.getWalletScore(), profile.getMonitorTier());
        return profile;
    }

    /**
     * F-P1-8: WS trade-event 推流入口。
     *
     * <p>由真实 Polymarket WebSocket 客户端在收到 user-trades / market-trades channel 事件后调用，
     * 把 raw JSON payload 推到风控链路（事件级触发 {@code recordTradeEvent}），不再等 30s polling 窗口。
     *
     * <p>设计要点：
     * <ol>
     *   <li>开关 {@code polymarket.enable-ws-trade-event-push=false}（默认 OFF）：未实装 WS 客户端时
     *       即使误调本方法也只是计数 {@code wsTradeEventsDisabledSkips}，不影响主路径；</li>
     *   <li>本地 LRU 去重 ({@code wsTradeDedupeLru})：WS 风暴 / WS+REST overlap 时避免重复进 DB；
     *       窗口外的条目惰性清理，超过 {@code wsTradeEventDedupeMaxEntries} 走 LinkedHashMap LRU 淘汰；</li>
     *   <li>JSON 解析失败 / 必需字段缺失：仅计数 {@code wsTradeEventsParseFailures} + 警告日志，
     *       不抛异常（避免 WS 帧处理线程因单条脏数据中断订阅）；</li>
     *   <li>解析成功后透传到既有 {@link #ingestTradeEvent(PolymarketTradeEventRequest)}：
     *       走和 REST polling 同一条路径，正常进入 {@code SmartWalletFollowTaskService.recordTradeEvent}
     *       的去重 + 风控 + 跟单任务派发；</li>
     *   <li>{@code recordTradeEvent} 本身已对 {@code externalTradeId} 做 DB 唯一性检查（line 124-145），
     *       即便 WS 推流与 REST polling 同时命中同一条 trade 也不会双写。</li>
     * </ol>
     *
     * <p>支持的 JSON 字段（兼容多种命名约定）：
     * <pre>
     * {
     *   "walletAddress"   | "proxyWallet" | "user"     : String,
     *   "marketId"        | "market" | "conditionId"   : String,
     *   "externalTradeId" | "tradeId" | "id"           : String,   // 必填，用作去重 key
     *   "outcome"                                       : String,
     *   "side"            | "action"                    : String,   // BUY / SELL
     *   "price"                                         : Number,
     *   "sizeShares"      | "size" | "shares"           : Number,
     *   "sizeUsdc"        | "notional" | "usdcSize"     : Number,
     *   "openedAt"        | "tradedAt" | "ts" | "time"  : Number(epoch ms)
     * }
     * </pre>
     *
     * @param payload WS 客户端收到的完整 trade payload (单条 trade 或包裹层) JSON 字符串
     * @return 真正 ingest 的 entity（dedupe / 解析失败 / 开关 OFF 时返回 null）
     */
    public SmartWalletTradeEventEntity ingestWsTradeEvent(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        wsTradeEventsReceived.incrementAndGet();

        // 1. 开关检查
        if (!isWsTradeEventPushEnabled()) {
            wsTradeEventsDisabledSkips.incrementAndGet();
            log.debug("{} action=WS推流入口 result=跳过 reason=enable-ws-trade-event-push=false", LOG_PREFIX);
            return null;
        }

        // 2. JSON 解析
        PolymarketTradeEventRequest request;
        try {
            request = parseWsTradePayload(payload);
        } catch (Exception e) {
            wsTradeEventsParseFailures.incrementAndGet();
            log.warn("{} action=WS推流入口 result=解析失败 reason={} payloadLen={}",
                    LOG_PREFIX, e.getMessage(), payload.length());
            return null;
        }
        if (request == null) {
            wsTradeEventsParseFailures.incrementAndGet();
            log.warn("{} action=WS推流入口 result=解析失败 reason=必需字段缺失(walletAddress/marketId/externalTradeId)", LOG_PREFIX);
            return null;
        }

        // 3. 本地 LRU 去重
        String tradeId = request.getExternalTradeId();
        if (tradeId != null && !tradeId.isBlank() && isWithinDedupeWindow(tradeId)) {
            wsTradeEventsDeduped.incrementAndGet();
            log.debug("{} action=WS推流入口 result=本地LRU去重命中 externalTradeId={}", LOG_PREFIX, tradeId);
            return null;
        }
        rememberWsTradeId(tradeId);

        // 4. 透传到既有 ingestTradeEvent 路径（包含 recordTradeEvent DB 唯一性兜底 + 风控派发）
        try {
            SmartWalletTradeEventEntity saved = ingestTradeEvent(request);
            wsTradeEventsForwarded.incrementAndGet();
            return saved;
        } catch (Exception e) {
            log.warn("{} action=WS推流入口 result=透传失败 externalTradeId={} reason={}",
                    LOG_PREFIX, tradeId, rootCauseMessage(e));
            return null;
        }
    }

    /**
     * F-P1-8 stats 暴露。供 /audit 端点 / 健康检查读取，便于灰度阶段观察 WS hit 率。
     */
    public WsTradeEventStats getWsTradeEventStats() {
        return new WsTradeEventStats(
                wsTradeEventsReceived.get(),
                wsTradeEventsForwarded.get(),
                wsTradeEventsDeduped.get(),
                wsTradeEventsParseFailures.get(),
                wsTradeEventsDisabledSkips.get());
    }

    private boolean isWsTradeEventPushEnabled() {
        SmartWalletProperties.PolymarketMonitor pm = properties == null ? null : properties.getPolymarket();
        return pm != null && pm.isEnableWsTradeEventPush();
    }

    private long getDedupeWindowMs() {
        SmartWalletProperties.PolymarketMonitor pm = properties == null ? null : properties.getPolymarket();
        if (pm == null) {
            return 60_000L;
        }
        return pm.getWsTradeEventDedupeWindowMs();
    }

    private int getDedupeMaxEntries() {
        SmartWalletProperties.PolymarketMonitor pm = properties == null ? null : properties.getPolymarket();
        if (pm == null) {
            return 5_000;
        }
        return Math.max(pm.getWsTradeEventDedupeMaxEntries(), 1);
    }

    private boolean isWithinDedupeWindow(String tradeId) {
        long windowMs = getDedupeWindowMs();
        if (windowMs <= 0L) {
            return false;
        }
        synchronized (wsTradeDedupeLru) {
            Long seenAt = wsTradeDedupeLru.get(tradeId);
            if (seenAt == null) {
                return false;
            }
            if (System.currentTimeMillis() - seenAt > windowMs) {
                wsTradeDedupeLru.remove(tradeId);
                return false;
            }
            return true;
        }
    }

    private void rememberWsTradeId(String tradeId) {
        if (tradeId == null || tradeId.isBlank()) {
            return;
        }
        long windowMs = getDedupeWindowMs();
        if (windowMs <= 0L) {
            return;
        }
        int maxEntries = getDedupeMaxEntries();
        synchronized (wsTradeDedupeLru) {
            wsTradeDedupeLru.put(tradeId, System.currentTimeMillis());
            // LRU 容量上限：超出按访问顺序淘汰最旧
            if (wsTradeDedupeLru.size() > maxEntries) {
                Iterator<Map.Entry<String, Long>> it = wsTradeDedupeLru.entrySet().iterator();
                int removeCount = wsTradeDedupeLru.size() - maxEntries;
                for (int i = 0; i < removeCount && it.hasNext(); i++) {
                    it.next();
                    it.remove();
                }
            }
        }
    }

    private PolymarketTradeEventRequest parseWsTradePayload(String payload) throws Exception {
        ObjectMapper mapper = resolveObjectMapper();
        JsonNode root = mapper.readTree(payload);
        JsonNode trade = root.has("event") ? root.get("event")
                : root.has("data") ? root.get("data")
                : root.has("trade") ? root.get("trade")
                : root;
        if (trade == null || trade.isNull()) {
            return null;
        }

        String walletAddress = text(trade, "walletAddress", "wallet_address", "proxyWallet", "proxy_wallet", "user", "userAddress", "owner");
        String marketId = text(trade, "marketId", "market_id", "market", "conditionId", "condition_id");
        String externalTradeId = text(trade, "externalTradeId", "external_trade_id", "tradeId", "trade_id", "id", "txHash", "transactionHash");
        if (walletAddress == null || marketId == null || externalTradeId == null) {
            return null;
        }

        PolymarketTradeEventRequest req = new PolymarketTradeEventRequest();
        req.setWalletAddress(walletAddress);
        String chain = text(trade, "chain");
        req.setChain(chain == null || chain.isBlank() ? "polygon" : chain);
        req.setExternalTradeId(externalTradeId);
        req.setMarketId(marketId);
        req.setQuestion(text(trade, "question", "title", "marketName"));
        req.setOutcome(text(trade, "outcome", "outcomeText", "outcomeName"));
        req.setSide(text(trade, "side", "action"));
        BigDecimal price = decimal(trade, "price");
        if (price != null) {
            req.setPrice(price);
        }
        BigDecimal sizeShares = decimal(trade, "sizeShares", "size_shares", "size", "shares", "quantity");
        if (sizeShares != null) {
            req.setSizeShares(sizeShares);
        }
        BigDecimal sizeUsdc = decimal(trade, "sizeUsdc", "size_usdc", "notional", "notionalUsdc", "usdcSize");
        if (sizeUsdc != null) {
            req.setSizeUsdc(sizeUsdc);
        }
        Long openedAtMs = epochMillis(trade, "openedAt", "opened_at", "tradedAt", "traded_at", "ts", "time", "timestamp");
        if (openedAtMs != null) {
            req.setOpenedAt(new Date(openedAtMs));
        }
        return req;
    }

    private ObjectMapper resolveObjectMapper() {
        if (objectMapper != null) {
            return objectMapper;
        }
        // 兜底：测试场景未注入 Spring bean 时本地实例化（线程安全，复用即可）
        objectMapper = new ObjectMapper();
        return objectMapper;
    }

    private static String text(JsonNode node, String... keys) {
        if (node == null) {
            return null;
        }
        for (String key : keys) {
            JsonNode v = node.get(key);
            if (v != null && !v.isNull()) {
                String s = v.asText(null);
                if (s != null && !s.isBlank()) {
                    return s.trim();
                }
            }
        }
        return null;
    }

    private static BigDecimal decimal(JsonNode node, String... keys) {
        if (node == null) {
            return null;
        }
        for (String key : keys) {
            JsonNode v = node.get(key);
            if (v != null && !v.isNull()) {
                try {
                    return new BigDecimal(v.asText());
                } catch (NumberFormatException ignored) {
                    // try next key
                }
            }
        }
        return null;
    }

    private static Long epochMillis(JsonNode node, String... keys) {
        if (node == null) {
            return null;
        }
        for (String key : keys) {
            JsonNode v = node.get(key);
            if (v == null || v.isNull()) {
                continue;
            }
            try {
                long raw;
                if (v.isNumber()) {
                    raw = v.asLong();
                } else {
                    raw = Long.parseLong(v.asText().trim());
                }
                // 兼容秒 / 毫秒：< 10^12 当作秒（即 ~2001 年之前），其他当作毫秒
                if (raw > 0 && raw < 1_000_000_000_000L) {
                    return raw * 1000L;
                }
                if (raw > 0) {
                    return raw;
                }
            } catch (NumberFormatException ignored) {
                // try next key
            }
        }
        return null;
    }

    private static String rootCauseMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage();
    }

    /** F-P1-8 stats DTO. */
    public static class WsTradeEventStats {
        private final long received;
        private final long forwarded;
        private final long deduped;
        private final long parseFailures;
        private final long disabledSkips;

        public WsTradeEventStats(long received, long forwarded, long deduped, long parseFailures, long disabledSkips) {
            this.received = received;
            this.forwarded = forwarded;
            this.deduped = deduped;
            this.parseFailures = parseFailures;
            this.disabledSkips = disabledSkips;
        }

        public long getReceived() { return received; }
        public long getForwarded() { return forwarded; }
        public long getDeduped() { return deduped; }
        public long getParseFailures() { return parseFailures; }
        public long getDisabledSkips() { return disabledSkips; }
    }

    public SmartWalletTradeEventEntity ingestTradeEvent(PolymarketTradeEventRequest request) {
        BigDecimal normalizedPrice = safe(request.getPrice());
        BigDecimal normalizedShares = resolveShares(request, normalizedPrice);
        BigDecimal normalizedNotionalUsdc = resolveNotionalUsdc(request, normalizedPrice, normalizedShares);
        log.info("{} action=导入Polymarket钱包交易 result=开始 wallet={} marketId={} question={} outcome={} side={} externalTradeId={} price={} sizeShares={} sizeUsdc={} openedAtBjt={} openedAtMs={} polymarketSearchUrl={}",
                LOG_PREFIX, request.getWalletAddress(), request.getMarketId(), request.getQuestion(), request.getOutcome(),
                request.getSide(), request.getExternalTradeId(), normalizedPrice, normalizedShares, normalizedNotionalUsdc,
                openedAtBjt(request.getOpenedAt()), openedAtMillis(request.getOpenedAt()), polymarketSearchUrl(request.getQuestion()));
        SmartWalletTradeEventEntity event = new SmartWalletTradeEventEntity()
                .setWalletAddress(request.getWalletAddress())
                .setChain(request.getChain())
                .setSource(SOURCE)
                .setExternalTradeId(request.getExternalTradeId())
                .setMarketId(request.getMarketId())
                .setQuestion(request.getQuestion())
                .setOutcome(normalizeUpper(request.getOutcome(), "YES"))
                .setSide(normalizeUpper(request.getSide(), "BUY"))
                .setPrice(normalizedPrice)
                .setSizeShares(normalizedShares)
                .setSizeUsdc(normalizedNotionalUsdc)
                .setOpenedAt(request.getOpenedAt() == null ? new Date() : request.getOpenedAt());
        SmartWalletTradeEventEntity saved = followTaskService.recordTradeEvent(event);
        log.info("{} action=导入Polymarket钱包交易 result=完成 eventId={} wallet={} marketId={} question={} outcome={} side={} price={} sizeShares={} sizeUsdc={} processed={} openedAtBjt={} openedAtMs={} polymarketSearchUrl={}",
                LOG_PREFIX, saved.getId(), saved.getWalletAddress(), saved.getMarketId(), saved.getQuestion(),
                saved.getOutcome(), saved.getSide(), saved.getPrice(), saved.getSizeShares(), saved.getSizeUsdc(),
                saved.getProcessed(), openedAtBjt(saved.getOpenedAt()),
                openedAtMillis(saved.getOpenedAt()), polymarketSearchUrl(saved.getQuestion()));
        return saved;
    }

    private String openedAtBjt(Date openedAt) {
        Long millis = openedAtMillis(openedAt);
        return millis == null ? null : timeFormatter.formatEpochMillisToBjt(millis);
    }

    private Long openedAtMillis(Date openedAt) {
        return openedAt == null ? null : openedAt.getTime();
    }

    private String polymarketSearchUrl(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }
        return "https://polymarket.com/search?query=" + URLEncoder.encode(question.trim(), StandardCharsets.UTF_8);
    }

    public List<SmartWalletProfileEntity> listProfiles() {
        return profileStoreService.listAll();
    }

    public List<SmartWalletProfileEntity> listQualifiedCandidates() {
        return profileStoreService.listQualified();
    }

    public List<SmartWalletProfileEntity> listExecutableWatchlist() {
        return profileStoreService.listWatchEnabled();
    }

    public List<SmartWalletProfileEntity> listWarmWatchlist() {
        return profileStoreService.listWarmWatchlist();
    }

    public List<SmartWalletProfileEntity> listColdProfiles() {
        return profileStoreService.listColdProfiles();
    }

    public List<SmartWalletProfileEntity> listWatchlist() {
        return listExecutableWatchlist();
    }

    public int getAnalyzeTopN() {
        return properties.getPolymarket().getAnalyzeTopN();
    }

    public boolean isDiscoveryEnabled() {
        return properties.isEnabled()
                && properties.getPolymarket().isEnabled()
                && properties.getPolymarket().isAutoDiscoverEnabled();
    }

    public boolean isEventSyncEnabled() {
        return properties.isEnabled()
                && properties.getPolymarket().isEnabled()
                && properties.getPolymarket().isAutoSyncEventsEnabled();
    }

    public int getWatchlistTradeSyncBatchSize() {
        Integer value = properties.getPolymarket().getWatchlistTradeSyncBatchSize();
        return value == null ? 3 : Math.max(value, 1);
    }

    public Long getWalletTradeSyncMinIntervalMs() {
        return properties.getPolymarket().getWalletTradeSyncMinIntervalMs();
    }

    public Long getWalletTradeErrorCooldownMs() {
        return properties.getPolymarket().getWalletTradeErrorCooldownMs();
    }

    private void normalizeMetricsRequest(PolymarketWalletMetricsRequest request) {
        if (request.getChain() == null || request.getChain().trim().isEmpty()) {
            request.setChain("polygon");
        }
        request.setWinRate(safe(request.getWinRate()));
        request.setRealizedPnlUsdc(safe(request.getRealizedPnlUsdc()));
        request.setAverageSizeUsdc(safe(request.getAverageSizeUsdc()));
        request.setAverageRoiRate(safe(request.getAverageRoiRate()));
        request.setMaxDrawdownRate(safe(request.getMaxDrawdownRate()));
        request.setRecentWinRate7d(request.getRecentWinRate7d() == null ? null : safe(request.getRecentWinRate7d()));
        request.setRecentWinRate30d(request.getRecentWinRate30d() == null ? null : safe(request.getRecentWinRate30d()));
        request.setRecentTradeCount7d(nonNegative(request.getRecentTradeCount7d()));
        request.setRecentTradeCount30d(nonNegative(request.getRecentTradeCount30d()));
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal resolveShares(PolymarketTradeEventRequest request, BigDecimal price) {
        BigDecimal shares = safe(request.getSizeShares());
        if (shares.compareTo(BigDecimal.ZERO) > 0) {
            return shares;
        }
        BigDecimal notionalUsdc = safe(request.getSizeUsdc());
        if (notionalUsdc.compareTo(BigDecimal.ZERO) > 0 && price.compareTo(BigDecimal.ZERO) > 0) {
            return notionalUsdc.divide(price, 8, RoundingMode.HALF_UP);
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal resolveNotionalUsdc(PolymarketTradeEventRequest request, BigDecimal price, BigDecimal shares) {
        BigDecimal notionalUsdc = safe(request.getSizeUsdc());
        if (notionalUsdc.compareTo(BigDecimal.ZERO) > 0) {
            return notionalUsdc;
        }
        if (shares.compareTo(BigDecimal.ZERO) > 0 && price.compareTo(BigDecimal.ZERO) > 0) {
            return shares.multiply(price).setScale(8, RoundingMode.HALF_UP);
        }
        return BigDecimal.ZERO;
    }

    private Integer nonNegative(Integer value) {
        if (value == null) {
            return null;
        }
        return Math.max(value, 0);
    }

    private String normalizeUpper(String value, String defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return value.trim().toUpperCase();
    }
}
