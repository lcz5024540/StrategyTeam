package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketSnapshotEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShadowFillSimulationServiceTest {

    private ShadowFillSimulationService service;

    @BeforeEach
    void setUp() {
        service = new ShadowFillSimulationService();
    }

    private SmartWalletMarketSnapshotEntity snapshotWithDepth(BigDecimal bestAsk,
                                                              BigDecimal askDepth1c,
                                                              BigDecimal askDepth3c,
                                                              BigDecimal askDepth5c) {
        return new SmartWalletMarketSnapshotEntity()
                .setBestAsk(bestAsk)
                .setBestBid(bestAsk.subtract(new BigDecimal("0.01")))
                .setAskDepth1c(askDepth1c)
                .setAskDepth3c(askDepth3c)
                .setAskDepth5c(askDepth5c)
                .setBidDepth1c(askDepth1c)
                .setBidDepth3c(askDepth3c)
                .setBidDepth5c(askDepth5c);
    }

    @Test
    void invalidInput_isHandled() {
        ShadowFillSimulationService.ShadowFill r = service.simulate(null, BigDecimal.ONE, null);
        assertEquals("INVALID_INPUT", r.getStatus());
        r = service.simulate("BUY", null, null);
        assertEquals("INVALID_INPUT", r.getStatus());
        r = service.simulate("BUY", BigDecimal.ZERO, null);
        assertEquals("INVALID_INPUT", r.getStatus());
    }

    @Test
    void missingSnapshot_returnsStatus() {
        ShadowFillSimulationService.ShadowFill r = service.simulate("BUY", new BigDecimal("100"), null);
        assertEquals("MISSING_SNAPSHOT", r.getStatus());
    }

    @Test
    void buyWithinOneCentDepth_zeroSlippage() {
        // stake $100 / $0.50 = 200 shares, ask_depth_1c = 500 → 全部 1c 内成交
        SmartWalletMarketSnapshotEntity snap = snapshotWithDepth(
                new BigDecimal("0.50"),
                new BigDecimal("500"),
                new BigDecimal("800"),
                new BigDecimal("1200"));
        ShadowFillSimulationService.ShadowFill r = service.simulate("BUY", new BigDecimal("100"), snap);
        assertEquals("OK", r.getStatus());
        assertEquals(new BigDecimal("0.500000"), r.getEntryPrice());
        assertEquals(0, r.getSlippageBps().intValue());
        // 入场成本 ≈ stake (允许 1 cent 误差)
        assertTrue(r.getEntryCostUsdc().subtract(new BigDecimal("100")).abs()
                .compareTo(new BigDecimal("0.01")) <= 0);
    }

    @Test
    void buyExceedsOneCentDepth_picksVwap() {
        // size = 100/0.5 = 200, ask_depth_1c = 100 → 100 在 1c, 100 在 1c-3c → vwap > best_ask
        SmartWalletMarketSnapshotEntity snap = snapshotWithDepth(
                new BigDecimal("0.50"),
                new BigDecimal("100"),
                new BigDecimal("300"),
                new BigDecimal("500"));
        ShadowFillSimulationService.ShadowFill r = service.simulate("BUY", new BigDecimal("100"), snap);
        assertEquals("OK", r.getStatus());
        assertTrue(r.getEntryPrice().compareTo(new BigDecimal("0.50")) > 0,
                "VWAP should be above best_ask when extending beyond 1c depth");
        assertTrue(r.getSlippageBps() > 0, "slippage should be positive");
        // VWAP 不应超过 best_ask + 2c
        assertTrue(r.getEntryPrice().compareTo(new BigDecimal("0.52")) <= 0);
    }

    @Test
    void buyExceedsFiveCentDepth_isNoDepth() {
        // size 巨大 → 5c 也吃不下
        SmartWalletMarketSnapshotEntity snap = snapshotWithDepth(
                new BigDecimal("0.50"),
                new BigDecimal("10"),
                new BigDecimal("20"),
                new BigDecimal("30"));
        ShadowFillSimulationService.ShadowFill r = service.simulate("BUY", new BigDecimal("100"), snap);
        assertEquals("NO_DEPTH", r.getStatus());
        assertFalse(r.isOk());
        assertNull(r.getEntryPrice());
    }

    @Test
    void sellSide_symmetricVwap() {
        // SELL 用 best_bid; 同样 size > depth_1c
        SmartWalletMarketSnapshotEntity snap = snapshotWithDepth(
                new BigDecimal("0.50"),   // best_ask 0.50 → best_bid 0.49
                new BigDecimal("100"),
                new BigDecimal("300"),
                new BigDecimal("500"));
        ShadowFillSimulationService.ShadowFill r = service.simulate("SELL", new BigDecimal("49"), snap);
        // size = 49 / 0.49 ≈ 100, 全部 1c 内 → 价格 = 0.49 best_bid
        assertEquals("OK", r.getStatus());
        assertEquals(new BigDecimal("0.490000"), r.getEntryPrice());
        assertEquals(0, r.getSlippageBps().intValue());
    }

    @Test
    void missingDepthFields_returnsDryFallback() {
        SmartWalletMarketSnapshotEntity snap = new SmartWalletMarketSnapshotEntity()
                .setBestAsk(new BigDecimal("0.50"))
                .setBestBid(new BigDecimal("0.49"));
        // 深度都没填; size = 200 → 1c 比较时 depth1c==null 跳过
        ShadowFillSimulationService.ShadowFill r = service.simulate("BUY", new BigDecimal("100"), snap);
        assertEquals("DRY_FALLBACK", r.getStatus());
        assertNotNull(r.getEntryPrice());
        assertTrue(r.getEntryPrice().compareTo(new BigDecimal("0.50")) > 0);
        assertTrue(r.getSlippageBps() > 0);
    }
}
