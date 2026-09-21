package com.metaverse.coin.strategy.core.arbitrage.spotperp;

import com.metaverse.coin.strategy.core.arbitrage.multi.NormalizedOrderBookSnapshot;
import com.metaverse.coin.strategy.core.arbitrage.multi.OrderBookSweep;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Sequential paper hedge. Default is spot taker IOC then perp taker IOC.
 * Scheme B can rest one or both legs at the passive quote. No live orders.
 *
 * <p>Second-leg failure immediately compensates the first leg as a taker
 * unwind — emergency exits do not wait for a maker fill.</p>
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
    private final SpotPerpExecutionPolicy policy;

    public SpotPerpPaperExecutor(SpotPerpRules rules) {
        this(rules, SpotPerpExecutionPolicy.productionTaker());
    }

    public SpotPerpPaperExecutor(SpotPerpRules rules, SpotPerpExecutionPolicy policy) {
        this.rules = Objects.requireNonNull(rules, "rules");
        this.policy = Objects.requireNonNull(policy, "policy");
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
        OrderBookSweep.Result spotBuy = fillSpot(snapshot, sized.quantity(), true, liquidity);
        OrderBookSweep.Result perpSell;
        try {
            perpSell = fillPerp(snapshot, sized.quantity(), true, liquidity);
        } catch (RuntimeException exception) {
            OrderBookSweep.Result unwind = takerSpotSell(snapshot, sized.quantity());
            BigDecimal unwindNotional = sized.quantity().multiply(
                    spotBuy.vwap().add(unwind.vwap()).divide(new BigDecimal("2"), 18, RoundingMode.HALF_UP));
            SpotPerpPosition rolled = SpotPerpPosition.opened(
                    snapshot.instrument(), rules.leverage(), sized.quantity(),
                    spotBuy.vwap(), unwind.vwap(), snapshot.executableOpenBasis(),
                    snapshot.fundingRate(), now, snapshot.nextFundingTime(),
                    productionOrLegFee(snapshot, unwindNotional, true, false)
                            .add(productionOrLegFee(snapshot, unwindNotional, true, false)),
                    List.of(fill("SPOT", "BUY", policy.style().spotOrderType(), spotBuy, now, "-OPEN-SPOT"),
                            fill("SPOT", "SELL", "MARKET_IOC", unwind, now, "-COMPENSATE-SPOT")));
            rolled.markRolledBack(now, "PERP_OPEN_FAILED_SPOT_UNWOUND", List.of());
            return new Result(false, "PERP_OPEN_FAILED_SPOT_UNWOUND", rolled, rolled.fills());
        }
        BigDecimal openFee = productionOrLegFee(snapshot, sized.quantity().multiply(spotBuy.vwap()), true,
                policy.style().spotMaker())
                .add(productionOrLegFee(snapshot, sized.quantity().multiply(perpSell.vwap()), false,
                        policy.style().perpMaker()));
        SpotPerpPosition position = SpotPerpPosition.opened(
                snapshot.instrument(), rules.leverage(), sized.quantity(),
                spotBuy.vwap(), perpSell.vwap(), snapshot.executableOpenBasis(),
                snapshot.fundingRate(), now, snapshot.nextFundingTime(), openFee,
                List.of(fill("SPOT", "BUY", policy.style().spotOrderType(), spotBuy, now, "-OPEN-SPOT"),
                        fill("PERP", "SELL", policy.style().perpOrderType(), perpSell, now, "-OPEN-PERP")));
        return new Result(true, "OPENED", position, position.fills());
    }

    public Result close(SpotPerpPosition position, SpotPerpSnapshot snapshot, Instant now, String reason) {
        SpotPerpGates.LiquidityAssessment liquidity = gates.assessCloseLiquidity(snapshot, position.quantity());
        OrderBookSweep.Result perpBuy;
        OrderBookSweep.Result spotSell;
        try {
            perpBuy = fillPerp(snapshot, position.quantity(), false, liquidity);
            spotSell = fillSpot(snapshot, position.quantity(), false, liquidity);
        } catch (RuntimeException exception) {
            return new Result(false, "CLOSE_DEPTH_INSUFFICIENT", position, List.of());
        }
        BigDecimal closeFee = productionOrLegFee(snapshot, position.quantity().multiply(perpBuy.vwap()), false,
                policy.style().perpMaker())
                .add(productionOrLegFee(snapshot, position.quantity().multiply(spotSell.vwap()), true,
                        policy.style().spotMaker()));
        position.close(now, reason, spotSell.vwap(), perpBuy.vwap(), closeFee,
                List.of(fill("PERP", "BUY", policy.style().perpOrderType(), perpBuy, now, "-CLOSE-PERP"),
                        fill("SPOT", "SELL", policy.style().spotOrderType(), spotSell, now, "-CLOSE-SPOT")));
        return new Result(true, reason, position, position.fills());
    }

    public SpotPerpExecutionPolicy policy() {
        return policy;
    }

    private OrderBookSweep.Result fillSpot(SpotPerpSnapshot snapshot, BigDecimal quantity, boolean opening,
            SpotPerpGates.LiquidityAssessment liquidity) {
        if (policy.style().spotMaker()) {
            BigDecimal price = opening ? snapshot.spotBid() : snapshot.spotAsk();
            return limitFill(price, quantity);
        }
        if (opening) {
            return liquidity != null && liquidity.spotSweep() != null
                    ? liquidity.spotSweep()
                    : OrderBookSweep.forQuantity(snapshot.spotAsks(), quantity, snapshot.spotAsk());
        }
        return liquidity != null && liquidity.spotSweep() != null
                ? liquidity.spotSweep()
                : takerSpotSell(snapshot, quantity);
    }

    private OrderBookSweep.Result fillPerp(SpotPerpSnapshot snapshot, BigDecimal quantity, boolean opening,
            SpotPerpGates.LiquidityAssessment liquidity) {
        if (policy.style().perpMaker()) {
            BigDecimal price = opening ? snapshot.perpAsk() : snapshot.perpBid();
            return limitFill(price, quantity);
        }
        if (opening) {
            return OrderBookSweep.forQuantity(snapshot.perpBids(), quantity, snapshot.perpBid());
        }
        return liquidity != null && liquidity.passed() && liquidity.perpSweep() != null
                ? liquidity.perpSweep()
                : OrderBookSweep.forQuantity(snapshot.perpAsks(), quantity, snapshot.perpAsk());
    }

    private static OrderBookSweep.Result takerSpotSell(SpotPerpSnapshot snapshot, BigDecimal quantity) {
        return OrderBookSweep.forQuantity(snapshot.spotBids(), quantity, snapshot.spotBid());
    }

    private static OrderBookSweep.Result limitFill(BigDecimal price, BigDecimal quantity) {
        BigDecimal cushion = quantity.multiply(price).multiply(new BigDecimal("4"));
        List<NormalizedOrderBookSnapshot.Level> book = List.of(
                new NormalizedOrderBookSnapshot.Level(price, cushion));
        return OrderBookSweep.forQuantity(book, quantity, price);
    }

    private BigDecimal productionOrLegFee(SpotPerpSnapshot snapshot, BigDecimal notional, boolean spot,
            boolean maker) {
        if (!policy.publishedLegFees()) {
            BigDecimal halfRoundTrip = rules.roundTripFee(snapshot.instrument().venue())
                    .divide(new BigDecimal("2"), 18, RoundingMode.HALF_UP);
            return notional.multiply(halfRoundTrip).setScale(8, RoundingMode.HALF_UP);
        }
        SpotPerpFeeSchedule schedule = SpotPerpFeeSchedule.forVenue(snapshot.instrument().venue());
        BigDecimal rate = spot ? schedule.spot(maker) : schedule.perp(maker);
        return notional.multiply(rate).setScale(8, RoundingMode.HALF_UP);
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
