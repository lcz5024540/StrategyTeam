package com.metaverse.coin.strategy.core.arbitrage.spotperp;

import com.metaverse.coin.strategy.core.arbitrage.multi.OrderBookSweep;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Sequential paper hedge: spot taker IOC first, then perp taker IOC.
 * Second-leg failure immediately compensates the first leg. No live orders.
 */
public final class SpotPerpPaperExecutor {
    public record Result(
            boolean success,
            String message,
            SpotPerpPosition position,
            List<SpotPerpPosition.Fill> fills
    ) {
    }

    private final SpotPerpRules rules;
    private final SpotPerpGates gates;

    public SpotPerpPaperExecutor(SpotPerpRules rules) {
        this.rules = Objects.requireNonNull(rules, "rules");
        this.gates = new SpotPerpGates(rules);
    }

    public Result open(SpotPerpSnapshot snapshot, Instant now) {
        SpotPerpSizer.Result sized = gates.sizer().size(snapshot);
        if (!sized.accepted()) {
            return new Result(false, sized.rejectionReason(), null, List.of());
        }
        SpotPerpGates.LiquidityAssessment liquidity = gates.assessOpenLiquidity(snapshot, sized.quantity());
        if (!liquidity.passed()) {
            return new Result(false, liquidity.rejectionReason(), null, List.of());
        }
        OrderBookSweep.Result spotBuy = liquidity.spotSweep();
        OrderBookSweep.Result perpSell;
        try {
            perpSell = OrderBookSweep.forQuantity(snapshot.perpBids(), sized.quantity(), snapshot.perpBid());
        } catch (RuntimeException exception) {
            OrderBookSweep.Result unwind = OrderBookSweep.forQuantity(
                    snapshot.spotBids(), sized.quantity(), snapshot.spotBid());
            SpotPerpPosition rolled = SpotPerpPosition.opened(
                    snapshot.instrument(), rules.leverage(), sized.quantity(),
                    spotBuy.vwap(), unwind.vwap(), snapshot.executableOpenBasis(),
                    snapshot.fundingRate(), now, snapshot.nextFundingTime(),
                    twoLegFee(snapshot, sized.quantity(), spotBuy.vwap(), unwind.vwap()),
                    List.of(fill("SPOT", "BUY", "MARKET_IOC", spotBuy, now, "-OPEN-SPOT"),
                            fill("SPOT", "SELL", "MARKET_IOC", unwind, now, "-COMPENSATE-SPOT")));
            rolled.markRolledBack(now, "PERP_OPEN_FAILED_SPOT_UNWOUND", List.of());
            return new Result(false, "PERP_OPEN_FAILED_SPOT_UNWOUND", rolled,
                    rolled.fills());
        }
        BigDecimal openFee = twoLegFee(snapshot, sized.quantity(), spotBuy.vwap(), perpSell.vwap());
        SpotPerpPosition position = SpotPerpPosition.opened(
                snapshot.instrument(), rules.leverage(), sized.quantity(),
                spotBuy.vwap(), perpSell.vwap(), snapshot.executableOpenBasis(),
                snapshot.fundingRate(), now, snapshot.nextFundingTime(), openFee,
                List.of(fill("SPOT", "BUY", "MARKET_IOC", spotBuy, now, "-OPEN-SPOT"),
                        fill("PERP", "SELL", "MARKET_IOC", perpSell, now, "-OPEN-PERP")));
        return new Result(true, "OPENED", position, position.fills());
    }

    public Result close(SpotPerpPosition position, SpotPerpSnapshot snapshot, Instant now, String reason) {
        SpotPerpGates.LiquidityAssessment liquidity = gates.assessCloseLiquidity(snapshot, position.quantity());
        // Close liquidity warning does not block an emergency or TTL exit; still require a fill.
        OrderBookSweep.Result perpBuy;
        OrderBookSweep.Result spotSell;
        try {
            perpBuy = liquidity.passed() && liquidity.perpSweep() != null
                    ? liquidity.perpSweep()
                    : OrderBookSweep.forQuantity(snapshot.perpAsks(), position.quantity(), snapshot.perpAsk());
            spotSell = liquidity.passed() && liquidity.spotSweep() != null
                    ? liquidity.spotSweep()
                    : OrderBookSweep.forQuantity(snapshot.spotBids(), position.quantity(), snapshot.spotBid());
        } catch (RuntimeException exception) {
            return new Result(false, "CLOSE_DEPTH_INSUFFICIENT", position, List.of());
        }
        BigDecimal closeFee = twoLegFee(snapshot, position.quantity(), perpBuy.vwap(), spotSell.vwap());
        position.close(now, reason, spotSell.vwap(), perpBuy.vwap(), closeFee,
                List.of(fill("PERP", "BUY", "MARKET_IOC", perpBuy, now, "-CLOSE-PERP"),
                        fill("SPOT", "SELL", "MARKET_IOC", spotSell, now, "-CLOSE-SPOT")));
        return new Result(true, reason, position, position.fills());
    }

    /**
     * {@code roundTripFee} is the full open+close lifecycle (four taker fills:
     * spot in, perp in, perp out, spot out). Each two-leg event therefore books
     * half of that rate so a completed cycle matches the gate, which subtracts
     * the configured round-trip once.
     */
    private BigDecimal twoLegFee(SpotPerpSnapshot snapshot, BigDecimal quantity,
            BigDecimal firstPrice, BigDecimal secondPrice) {
        BigDecimal perFill = rules.roundTripFee(snapshot.instrument().venue())
                .divide(new BigDecimal("4"), 18, RoundingMode.HALF_UP);
        return fillFee(quantity, firstPrice, perFill).add(fillFee(quantity, secondPrice, perFill));
    }

    private static BigDecimal fillFee(BigDecimal quantity, BigDecimal price, BigDecimal perFill) {
        return quantity.multiply(price).multiply(perFill).setScale(8, RoundingMode.HALF_UP);
    }

    private static SpotPerpPosition.Fill fill(String leg, String side, String orderType,
            OrderBookSweep.Result sweep, Instant now, String suffix) {
        return new SpotPerpPosition.Fill(
                "PAPER" + suffix,
                leg,
                side,
                orderType,
                sweep.filledQuantity(),
                sweep.vwap(),
                sweep.filledNotional(),
                sweep.impactBps(),
                now
        );
    }
}
