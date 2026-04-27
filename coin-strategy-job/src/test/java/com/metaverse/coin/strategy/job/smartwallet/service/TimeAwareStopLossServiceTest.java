package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketInfoEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TimeAwareStopLossServiceTest {

    private SmartWalletProperties properties;
    private TimeAwareStopLossService service;

    @BeforeEach
    void setUp() {
        properties = new SmartWalletProperties();
        SmartWalletProperties.SignalRisk risk = new SmartWalletProperties.SignalRisk();
        risk.setTimeAwareStopLossEnabled(true);
        risk.setTrendStopLossEnabled(false);
        risk.setAutoStopLossThresholdRate(new BigDecimal("0.35")); // fallback

        SmartWalletProperties.StopLossTier tier7d = new SmartWalletProperties.StopLossTier();
        tier7d.setRemainingHours(168);
        tier7d.setThresholdRate(new BigDecimal("0.35"));

        SmartWalletProperties.StopLossTier tier24h = new SmartWalletProperties.StopLossTier();
        tier24h.setRemainingHours(24);
        tier24h.setThresholdRate(new BigDecimal("0.25"));

        SmartWalletProperties.StopLossTier tier6h = new SmartWalletProperties.StopLossTier();
        tier6h.setRemainingHours(6);
        tier6h.setThresholdRate(new BigDecimal("0.15"));

        SmartWalletProperties.StopLossTier tier0h = new SmartWalletProperties.StopLossTier();
        tier0h.setRemainingHours(0);
        tier0h.setThresholdRate(new BigDecimal("0.05"));

        risk.setStopLossTiers(List.of(tier7d, tier24h, tier6h, tier0h));
        properties.setSignalRisk(risk);
        service = new TimeAwareStopLossService(properties);
    }

    @Test
    void resolveThreshold_remaining8days_returns35pct() {
        SmartWalletPositionEntity position = positionWithRemainingHours(200); // >168h
        BigDecimal threshold = service.resolveStopLossThreshold(position);
        assertEquals(new BigDecimal("0.35"), threshold);
    }

    @Test
    void resolveThreshold_remaining48h_returns25pct() {
        SmartWalletPositionEntity position = positionWithRemainingHours(48); // 24-168h
        BigDecimal threshold = service.resolveStopLossThreshold(position);
        assertEquals(new BigDecimal("0.25"), threshold);
    }

    @Test
    void resolveThreshold_remaining10h_returns15pct() {
        SmartWalletPositionEntity position = positionWithRemainingHours(10); // 6-24h
        BigDecimal threshold = service.resolveStopLossThreshold(position);
        assertEquals(new BigDecimal("0.15"), threshold);
    }

    @Test
    void resolveThreshold_remaining2h_returns5pct() {
        SmartWalletPositionEntity position = positionWithRemainingHours(2); // <6h
        BigDecimal threshold = service.resolveStopLossThreshold(position);
        assertEquals(new BigDecimal("0.05"), threshold);
    }

    @Test
    void resolveThreshold_noMarketEndAt_returnsFallback() {
        SmartWalletPositionEntity position = new SmartWalletPositionEntity();
        // marketInfo is null → no marketEndAt → fallback to autoStopLossThresholdRate
        BigDecimal threshold = service.resolveStopLossThreshold(position);
        assertEquals(new BigDecimal("0.35"), threshold);
    }

    @Test
    void resolveThreshold_disabledFeature_returnsFallback() {
        properties.getSignalRisk().setTimeAwareStopLossEnabled(false);
        SmartWalletPositionEntity position = positionWithRemainingHours(2);
        BigDecimal threshold = service.resolveStopLossThreshold(position);
        assertEquals(new BigDecimal("0.35"), threshold); // falls back to autoStopLossThresholdRate
    }

    // ── Trend stop-loss tests ─────────────────────────────────────────────────

    @Test
    void recordPrice_consecutiveDecline_triggersTrendStopLoss() {
        properties.getSignalRisk().setTrendStopLossEnabled(true);
        properties.getSignalRisk().setTrendStopLossConsecutivePeriods(3);
        service = new TimeAwareStopLossService(properties);

        Long positionId = 42L;
        // ROI is negative throughout (markPrice < avgEntryPrice)
        service.recordMarkPrice(positionId, new BigDecimal("0.50"), new BigDecimal("0.60"));
        service.recordMarkPrice(positionId, new BigDecimal("0.45"), new BigDecimal("0.60"));
        service.recordMarkPrice(positionId, new BigDecimal("0.40"), new BigDecimal("0.60"));

        assertTrue(service.isTrendStopLossTriggered(positionId, new BigDecimal("0.40"), new BigDecimal("0.60")));
    }

    @Test
    void recordPrice_notConsecutiveDecline_doesNotTrigger() {
        properties.getSignalRisk().setTrendStopLossEnabled(true);
        properties.getSignalRisk().setTrendStopLossConsecutivePeriods(3);
        service = new TimeAwareStopLossService(properties);

        Long positionId = 43L;
        service.recordMarkPrice(positionId, new BigDecimal("0.50"), new BigDecimal("0.60"));
        service.recordMarkPrice(positionId, new BigDecimal("0.55"), new BigDecimal("0.60")); // price bounced
        service.recordMarkPrice(positionId, new BigDecimal("0.40"), new BigDecimal("0.60"));

        assertFalse(service.isTrendStopLossTriggered(positionId, new BigDecimal("0.40"), new BigDecimal("0.60")));
    }

    // helper
    private SmartWalletPositionEntity positionWithRemainingHours(int hoursRemaining) {
        SmartWalletPositionEntity position = new SmartWalletPositionEntity();
        SmartWalletMarketInfoEntity marketInfo = new SmartWalletMarketInfoEntity();
        long endAt = System.currentTimeMillis() + (long) hoursRemaining * 3600_000L;
        marketInfo.setMarketEndAt(endAt);
        position.setMarketInfo(marketInfo);
        return position;
    }
}
