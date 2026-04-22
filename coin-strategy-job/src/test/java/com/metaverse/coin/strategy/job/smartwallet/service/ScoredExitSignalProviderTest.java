package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketInfoEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScoredExitSignalProviderTest {

    private SmartWalletProperties properties;
    private ScoredExitSignalProvider provider;

    @BeforeEach
    void setUp() {
        properties = new SmartWalletProperties();
        SmartWalletProperties.SignalRisk risk = new SmartWalletProperties.SignalRisk();
        risk.setTimeAwareStopLossEnabled(true);
        risk.setAutoStopLossThresholdRate(new BigDecimal("0.35")); // fallback
        risk.setExitScoringRoiMomentumWeight(new BigDecimal("0.10"));
        risk.setExitScoringMinThreshold(new BigDecimal("0.03"));
        risk.setExitScoringMaxThreshold(new BigDecimal("0.50"));

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
        provider = new ScoredExitSignalProvider(properties);
    }

    // remaining=48h → base=0.25; position breakeven (ROI=0) → no penalty → 0.25
    @Test
    void threshold_breakevenPosition_returnsBaseTimeThreshold() {
        SmartWalletPositionEntity pos = positionWithRemainingHours(48);
        pos.setLastMarkPrice(new BigDecimal("0.50"));
        pos.setAverageEntryPrice(new BigDecimal("0.50")); // ROI = 0
        BigDecimal t = provider.resolveStopLossThreshold(pos);
        // base=0.25, penalty=0 → 0.25
        assertEquals(new BigDecimal("0.25"), t);
    }

    // remaining=48h → base=0.25; ROI=-20%, weight=0.10 → penalty=0.02 → threshold=0.23
    @Test
    void threshold_losingPosition_tightensThreshold() {
        SmartWalletPositionEntity pos = positionWithRemainingHours(48);
        pos.setLastMarkPrice(new BigDecimal("0.40"));
        pos.setAverageEntryPrice(new BigDecimal("0.50")); // ROI = -20%
        BigDecimal t = provider.resolveStopLossThreshold(pos);
        // base=0.25, penalty=0.20*0.10=0.02 → 0.23
        assertEquals(new BigDecimal("0.23"), t);
    }

    // remaining=2h → base=0.05; ROI=-60%, weight=0.10 → penalty=0.06 → 0.05-0.06=-0.01 → floor at minThreshold=0.03
    @Test
    void threshold_deeplyLosing_clampedToMin() {
        SmartWalletPositionEntity pos = positionWithRemainingHours(2);
        pos.setLastMarkPrice(new BigDecimal("0.20"));
        pos.setAverageEntryPrice(new BigDecimal("0.50")); // ROI = -60%
        BigDecimal t = provider.resolveStopLossThreshold(pos);
        assertEquals(new BigDecimal("0.03"), t);
    }

    // winning position: ROI > 0 → no penalty → base threshold unchanged
    @Test
    void threshold_winningPosition_noReduction() {
        SmartWalletPositionEntity pos = positionWithRemainingHours(10);
        pos.setLastMarkPrice(new BigDecimal("0.70"));
        pos.setAverageEntryPrice(new BigDecimal("0.50")); // ROI = +40%
        BigDecimal t = provider.resolveStopLossThreshold(pos);
        // base=0.15, penalty=0 (positive ROI → no penalty) → 0.15
        assertEquals(new BigDecimal("0.15"), t);
    }

    // null markPrice → fall back to base time threshold without penalty
    @Test
    void threshold_nullMarkPrice_returnsBaseTier() {
        SmartWalletPositionEntity pos = positionWithRemainingHours(48);
        pos.setLastMarkPrice(null);
        pos.setAverageEntryPrice(new BigDecimal("0.50"));
        BigDecimal t = provider.resolveStopLossThreshold(pos);
        assertEquals(new BigDecimal("0.25"), t);
    }

    // trend triggered: 3 consecutive declines while ROI < 0
    @Test
    void trendStopLoss_consecutiveDeclines_triggers() {
        properties.getSignalRisk().setTrendStopLossEnabled(true);
        properties.getSignalRisk().setTrendStopLossConsecutivePeriods(3);
        provider = new ScoredExitSignalProvider(properties);

        Long pid = 99L;
        provider.recordMarkPrice(pid, new BigDecimal("0.50"), new BigDecimal("0.60"));
        provider.recordMarkPrice(pid, new BigDecimal("0.45"), new BigDecimal("0.60"));
        provider.recordMarkPrice(pid, new BigDecimal("0.40"), new BigDecimal("0.60"));

        assertTrue(provider.isTrendStopLossTriggered(pid, new BigDecimal("0.40"), new BigDecimal("0.60")));
    }

    // clearPositionHistory removes cached price data
    @Test
    void clearPositionHistory_removesPriceData() {
        properties.getSignalRisk().setTrendStopLossEnabled(true);
        properties.getSignalRisk().setTrendStopLossConsecutivePeriods(3);
        provider = new ScoredExitSignalProvider(properties);

        Long pid = 100L;
        provider.recordMarkPrice(pid, new BigDecimal("0.50"), new BigDecimal("0.60"));
        provider.recordMarkPrice(pid, new BigDecimal("0.45"), new BigDecimal("0.60"));
        provider.recordMarkPrice(pid, new BigDecimal("0.40"), new BigDecimal("0.60"));
        assertTrue(provider.isTrendStopLossTriggered(pid, new BigDecimal("0.40"), new BigDecimal("0.60")));

        provider.clearPositionHistory(pid);
        assertFalse(provider.isTrendStopLossTriggered(pid, new BigDecimal("0.40"), new BigDecimal("0.60")));
    }

    private SmartWalletPositionEntity positionWithRemainingHours(int hoursRemaining) {
        SmartWalletPositionEntity pos = new SmartWalletPositionEntity();
        SmartWalletMarketInfoEntity marketInfo = new SmartWalletMarketInfoEntity();
        marketInfo.setMarketEndAt(System.currentTimeMillis() + (long) hoursRemaining * 3600_000L);
        pos.setMarketInfo(marketInfo);
        return pos;
    }
}
