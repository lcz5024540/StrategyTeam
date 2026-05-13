package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketTradeEventRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketWalletMetricsRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

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
