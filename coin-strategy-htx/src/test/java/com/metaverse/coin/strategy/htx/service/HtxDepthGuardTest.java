package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.common.client.htx.model.HtxDepth;
import com.metaverse.coin.strategy.common.client.htx.model.HtxPriceLevel;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtxDepthGuardTest {

    private final HtxDepthGuard guard = new HtxDepthGuard();

    @Test
    void bothSidesSufficient() {
        HtxDepth d = mkDepth(
                levels("100.0", "1.0", "99.9", "2.0", "99.8", "3.0"),
                levels("100.1", "1.0", "100.2", "2.0", "100.3", "3.0"));
        HtxDepthGuard.Verdict v = guard.check(d, new BigDecimal("1.0"), new BigDecimal("3.0"), 5);
        assertTrue(v.allOk());
        assertTrue(v.isBuyOk());
        assertTrue(v.isSellOk());
    }

    @Test
    void askSideThinRejectsBuy() {
        HtxDepth d = mkDepth(
                levels("100.0", "10.0"),
                levels("100.1", "0.5"));
        HtxDepthGuard.Verdict v = guard.check(d, new BigDecimal("1.0"), new BigDecimal("3.0"), 5);
        assertFalse(v.isBuyOk());
        assertTrue(v.isSellOk());
    }

    @Test
    void bidSideThinRejectsSell() {
        HtxDepth d = mkDepth(
                levels("100.0", "0.5"),
                levels("100.1", "10.0"));
        HtxDepthGuard.Verdict v = guard.check(d, new BigDecimal("1.0"), new BigDecimal("3.0"), 5);
        assertTrue(v.isBuyOk());
        assertFalse(v.isSellOk());
    }

    @Test
    void zeroQuantityRejectsBoth() {
        HtxDepth d = mkDepth(
                levels("100.0", "10.0"),
                levels("100.1", "10.0"));
        HtxDepthGuard.Verdict v = guard.check(d, BigDecimal.ZERO, new BigDecimal("3.0"), 5);
        assertFalse(v.allOk());
    }

    @Test
    void nullDepthIsSafeReject() {
        HtxDepthGuard.Verdict v = guard.check(null, new BigDecimal("1.0"), new BigDecimal("3.0"), 5);
        assertFalse(v.allOk());
    }

    // ================= HOTFIX v1.0.2 · #10 =================

    @Test
    void hotfix10_asksOutOfOrderRejects() {
        // asks descending is WRONG; sizes look sufficient but the invariant is violated
        HtxDepth d = mkDepth(
                levels("100.0", "10.0", "99.9", "10.0"),
                levels("100.5", "10.0", "100.1", "10.0"));
        HtxDepthGuard.Verdict v = guard.check(d, new BigDecimal("1.0"), new BigDecimal("3.0"), 5);
        assertFalse(v.allOk());
        assertFalse(v.isBuyOk());
        assertFalse(v.isSellOk());
    }

    @Test
    void hotfix10_bidsOutOfOrderRejects() {
        HtxDepth d = mkDepth(
                levels("99.9", "10.0", "100.0", "10.0"),   // bids ascending — wrong
                levels("100.1", "10.0", "100.2", "10.0"));
        HtxDepthGuard.Verdict v = guard.check(d, new BigDecimal("1.0"), new BigDecimal("3.0"), 5);
        assertFalse(v.allOk());
    }

    @Test
    void hotfix10_equalPriceLevelsRejects() {
        // HTX aggregates same-price liquidity into one level — duplicates indicate broken feed
        HtxDepth d = mkDepth(
                levels("100.0", "10.0", "100.0", "10.0"),
                levels("100.1", "10.0", "100.2", "10.0"));
        HtxDepthGuard.Verdict v = guard.check(d, new BigDecimal("1.0"), new BigDecimal("3.0"), 5);
        assertFalse(v.allOk());
    }

    private static HtxDepth mkDepth(List<HtxPriceLevel> bids, List<HtxPriceLevel> asks) {
        HtxDepth d = new HtxDepth();
        d.setSymbol("btcusdt");
        d.setBids(bids == null ? Collections.emptyList() : bids);
        d.setAsks(asks == null ? Collections.emptyList() : asks);
        return d;
    }

    private static List<HtxPriceLevel> levels(String... pairs) {
        HtxPriceLevel[] arr = new HtxPriceLevel[pairs.length / 2];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = new HtxPriceLevel(new BigDecimal(pairs[2 * i]), new BigDecimal(pairs[2 * i + 1]));
        }
        return Arrays.asList(arr);
    }
}
