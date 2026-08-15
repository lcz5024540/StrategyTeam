package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.common.client.htx.model.HtxDepth;
import com.metaverse.coin.strategy.common.client.htx.model.HtxPriceLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Validates that an order book has enough depth to support both legs of a locked-pair trade.
 * <p>
 * Rule (from {@code HTX_SPOT_EXECUTION_SDK_V1.md} §5):
 * <pre>
 *   buy_ok  = SUM asks[0..N-1].amount  >= qty * safetyRatio
 *   sell_ok = SUM bids[0..N-1].amount  >= qty * safetyRatio
 *   depth_ok = buy_ok && sell_ok
 * </pre>
 */
@Slf4j
@Component
public class HtxDepthGuard {

    /**
     * Verdict of a depth check. Fields describe the two sides independently so the caller can
     * report *which* side of the book was thin (buy vs sell).
     */
    @Data
    @AllArgsConstructor
    public static class Verdict {
        private boolean buyOk;
        private boolean sellOk;
        private BigDecimal cumulativeAskAmount;
        private BigDecimal cumulativeBidAmount;
        private BigDecimal requiredAmount;

        public boolean allOk() {
            return buyOk && sellOk;
        }
    }

    public Verdict check(HtxDepth depth, BigDecimal qty, BigDecimal safetyRatio, int topN) {
        return checkSides(depth, qty, qty, safetyRatio, topN);
    }

    /** Buy quantity consumes asks; sell quantity consumes bids. Useful when two close legs differ. */
    public Verdict checkSides(HtxDepth depth, BigDecimal buyQty, BigDecimal sellQty,
                              BigDecimal safetyRatio, int topN) {
        if (depth == null || buyQty == null || sellQty == null
                || buyQty.signum() < 0 || sellQty.signum() < 0
                || (buyQty.signum() == 0 && sellQty.signum() == 0)) {
            return new Verdict(false, false, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        // v1.0.2 hotfix #10: verify book ordering. HTX's step0 book must be asks ascending /
        // bids descending; if HTX changes API semantics or an aggregator returns unsorted rows,
        // our sumTopN would silently pass while operating on the wrong levels.
        if (!isMonotonic(depth.getAsks(), true) || !isMonotonic(depth.getBids(), false)) {
            log.warn("HTX depth ordering violation for {}; refusing to trade", depth.getSymbol());
            return new Verdict(false, false, BigDecimal.ZERO, BigDecimal.ZERO,
                    buyQty.max(sellQty).multiply(safetyRatio));
        }
        BigDecimal requiredBuy = buyQty.multiply(safetyRatio);
        BigDecimal requiredSell = sellQty.multiply(safetyRatio);
        BigDecimal askSum = sumTopN(depth.getAsks(), topN);
        BigDecimal bidSum = sumTopN(depth.getBids(), topN);
        boolean buyOk = buyQty.signum() == 0 || askSum.compareTo(requiredBuy) >= 0;
        boolean sellOk = sellQty.signum() == 0 || bidSum.compareTo(requiredSell) >= 0;
        Verdict v = new Verdict(buyOk, sellOk, askSum, bidSum, requiredBuy.max(requiredSell));
        if (!v.allOk()) {
            log.warn("HTX depth insufficient: symbol={} buyQty={} sellQty={} requiredBuy={} requiredSell={} "
                            + "asksTop{}={} bidsTop{}={}",
                    depth.getSymbol(), buyQty, sellQty, requiredBuy, requiredSell,
                    topN, askSum, topN, bidSum);
        }
        return v;
    }

    /**
     * Ordered book invariant: asks strictly ascending, bids strictly descending. Equal-price rows
     * count as violations because HTX aggregates same-price liquidity into one level upstream.
     */
    private static boolean isMonotonic(List<HtxPriceLevel> levels, boolean ascending) {
        if (levels == null || levels.size() < 2) {
            return true;
        }
        for (int i = 1; i < levels.size(); i++) {
            BigDecimal prev = levels.get(i - 1).getPrice();
            BigDecimal cur = levels.get(i).getPrice();
            if (prev == null || cur == null) {
                return false;
            }
            int cmp = cur.compareTo(prev);
            if (ascending ? cmp <= 0 : cmp >= 0) {
                return false;
            }
        }
        return true;
    }

    private static BigDecimal sumTopN(List<HtxPriceLevel> levels, int topN) {
        if (levels == null || levels.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = BigDecimal.ZERO;
        int limit = Math.min(topN, levels.size());
        for (int i = 0; i < limit; i++) {
            BigDecimal amount = levels.get(i).getAmount();
            if (amount != null) {
                sum = sum.add(amount);
            }
        }
        return sum;
    }
}
