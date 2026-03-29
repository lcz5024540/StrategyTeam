package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketTradeEventRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketWalletMetricsRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PolymarketSmartWalletMonitorService {

    private static final String SOURCE = "POLYMARKET";

    private final SmartWalletProperties properties;
    private final SmartWalletProfileStoreService profileStoreService;
    private final SmartWalletFollowTaskService followTaskService;

    public SmartWalletProfileEntity ingestWalletMetrics(PolymarketWalletMetricsRequest request) {
        normalizeMetricsRequest(request);
        return profileStoreService.upsertMetrics(request);
    }

    public SmartWalletTradeEventEntity ingestTradeEvent(PolymarketTradeEventRequest request) {
        SmartWalletTradeEventEntity event = new SmartWalletTradeEventEntity()
                .setWalletAddress(request.getWalletAddress())
                .setChain(request.getChain())
                .setSource(SOURCE)
                .setExternalTradeId(request.getExternalTradeId())
                .setMarketId(request.getMarketId())
                .setQuestion(request.getQuestion())
                .setOutcome(normalizeUpper(request.getOutcome(), "YES"))
                .setSide(normalizeUpper(request.getSide(), "BUY"))
                .setPrice(safe(request.getPrice()))
                .setSizeUsdc(safe(request.getSizeUsdc()))
                .setOpenedAt(request.getOpenedAt() == null ? new Date() : request.getOpenedAt());
        return followTaskService.recordTradeEvent(event);
    }

    public List<SmartWalletProfileEntity> listProfiles() {
        return profileStoreService.listAll();
    }

    public List<SmartWalletProfileEntity> listWatchlist() {
        return profileStoreService.listWatchEnabled();
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

    private void normalizeMetricsRequest(PolymarketWalletMetricsRequest request) {
        if (request.getChain() == null || request.getChain().trim().isEmpty()) {
            request.setChain("polygon");
        }
        request.setWinRate(safe(request.getWinRate()));
        request.setRealizedPnlUsdc(safe(request.getRealizedPnlUsdc()));
        request.setAverageSizeUsdc(safe(request.getAverageSizeUsdc()));
        request.setAverageRoiRate(safe(request.getAverageRoiRate()));
        request.setMaxDrawdownRate(safe(request.getMaxDrawdownRate()));
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String normalizeUpper(String value, String defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return value.trim().toUpperCase();
    }
}
