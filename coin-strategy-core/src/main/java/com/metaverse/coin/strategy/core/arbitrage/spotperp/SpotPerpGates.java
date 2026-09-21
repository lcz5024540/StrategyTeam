package com.metaverse.coin.strategy.core.arbitrage.spotperp;

import com.metaverse.coin.strategy.core.arbitrage.multi.NormalizedOrderBookSnapshot;
import com.metaverse.coin.strategy.core.arbitrage.multi.OrderBookSweep;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Open/close and liquidity gates for the funding-fee harvest.
 *
 * <p>Open: positive funding above threshold, basis inside band, projected funding
 * over {@code minFundingPeriods} covers round-trip fees + impact, book is deep enough.
 * Close: funding turns unattractive or non-positive, basis too wide, or holding TTL.</p>
 */
public final class SpotPerpGates {
    public record CloseSignal(String reason, boolean emergency) {
    }

    public record LiquidityAssessment(
            boolean passed,
            String rejectionReason,
            OrderBookSweep.Result spotSweep,
            OrderBookSweep.Result perpSweep,
            BigDecimal estimatedSlippageRate
    ) {
        static LiquidityAssessment reject(String reason) {
            return new LiquidityAssessment(false, reason, null, null, BigDecimal.ZERO);
        }
    }

    private final SpotPerpRules rules;
    private final SpotPerpSizer sizer;

    public SpotPerpGates(SpotPerpRules rules) {
        this.rules = Objects.requireNonNull(rules, "rules");
        this.sizer = new SpotPerpSizer(rules);
    }

    public Optional<String> openBlockReason(SpotPerpSnapshot snapshot, Instant now) {
        if (!rules.autoOpenEnabled()) {
            return Optional.of("AUTO_OPEN_DISABLED");
        }
        if (snapshot.receivedAt() != null
                && Duration.between(snapshot.receivedAt(), now).compareTo(rules.maxQuoteAge()) > 0) {
            return Optional.of("QUOTE_STALE");
        }
        if (snapshot.fundingRate().compareTo(BigDecimal.ZERO) <= 0) {
            return Optional.of("FUNDING_NON_POSITIVE");
        }
        if (snapshot.fundingRate().compareTo(rules.openThreshold()) < 0) {
            return Optional.of("FUNDING_BELOW_OPEN_THRESHOLD");
        }
        if (withinSettlementProtection(snapshot.nextFundingTime(), now)) {
            return Optional.of("WITHIN_SETTLEMENT_PROTECTION");
        }
        BigDecimal basis = snapshot.executableOpenBasis();
        if (basis.compareTo(rules.minBasisRate()) < 0) {
            return Optional.of("BASIS_BELOW_MIN");
        }
        if (basis.compareTo(rules.maxBasisRate()) > 0) {
            return Optional.of("BASIS_ABOVE_MAX");
        }
        SpotPerpSizer.Result sized = sizer.size(snapshot);
        if (!sized.accepted()) {
            return Optional.of(sized.rejectionReason());
        }
        LiquidityAssessment liquidity = assessOpenLiquidity(snapshot, sized.quantity());
        if (!liquidity.passed()) {
            return Optional.of(liquidity.rejectionReason());
        }
        BigDecimal expectedNet = expectedNetYield(snapshot.fundingRate(),
                liquidity.estimatedSlippageRate(), snapshot.instrument().venue());
        if (expectedNet.compareTo(rules.minNetYieldRate()) < 0) {
            return Optional.of("EXPECTED_NET_YIELD_TOO_LOW");
        }
        return Optional.empty();
    }

    public Optional<CloseSignal> closeSignal(SpotPerpSnapshot snapshot, SpotPerpPosition position,
            Instant now) {
        Objects.requireNonNull(position, "position");
        BigDecimal funding = snapshot.fundingRate();
        if (funding.compareTo(BigDecimal.ZERO) <= 0) {
            return Optional.of(new CloseSignal("FUNDING_NON_POSITIVE", true));
        }
        BigDecimal basis = snapshot.executableOpenBasis();
        if (basis.abs().compareTo(rules.maxBasisRate()) > 0) {
            return Optional.of(new CloseSignal("BASIS_TOO_WIDE", true));
        }
        BigDecimal deterioration = basis.subtract(position.entryBasis());
        if (deterioration.compareTo(rules.maxBasisDeteriorationRate()) > 0) {
            return Optional.of(new CloseSignal("BASIS_DETERIORATED", true));
        }
        Duration held = Duration.between(position.openedAt(), now);
        if (held.compareTo(rules.maxHolding()) >= 0) {
            return Optional.of(new CloseSignal("MAX_HOLDING_TTL", false));
        }
        if (funding.compareTo(rules.closeThreshold()) < 0 && held.compareTo(rules.minHolding()) >= 0) {
            return Optional.of(new CloseSignal("FUNDING_UNATTRACTIVE", false));
        }
        return Optional.empty();
    }

    public LiquidityAssessment assessOpenLiquidity(SpotPerpSnapshot snapshot, BigDecimal quantity) {
        return assess(snapshot, quantity, true);
    }

    public LiquidityAssessment assessCloseLiquidity(SpotPerpSnapshot snapshot, BigDecimal quantity) {
        return assess(snapshot, quantity, false);
    }

    public SpotPerpSizer sizer() {
        return sizer;
    }

    public BigDecimal expectedNetYield(BigDecimal fundingRate, BigDecimal slippageRate,
            SpotPerpInstrument.SpotPerpVenue venue) {
        return fundingRate.multiply(BigDecimal.valueOf(rules.minFundingPeriods()))
                .subtract(rules.roundTripFee(venue))
                .subtract(zero(slippageRate))
                .setScale(8, RoundingMode.HALF_UP);
    }

    private LiquidityAssessment assess(SpotPerpSnapshot snapshot, BigDecimal quantity, boolean opening) {
        List<NormalizedOrderBookSnapshot.Level> spotLevels = opening ? snapshot.spotAsks() : snapshot.spotBids();
        List<NormalizedOrderBookSnapshot.Level> perpLevels = opening ? snapshot.perpBids() : snapshot.perpAsks();
        BigDecimal spotBest = opening ? snapshot.spotAsk() : snapshot.spotBid();
        BigDecimal perpBest = opening ? snapshot.perpBid() : snapshot.perpAsk();
        try {
            OrderBookSweep.Result spot = OrderBookSweep.forQuantity(spotLevels, quantity, spotBest);
            OrderBookSweep.Result perp = OrderBookSweep.forQuantity(perpLevels, quantity, perpBest);
            if (spot.impactBps().compareTo(rules.maxVwapImpactBps()) > 0) {
                return LiquidityAssessment.reject("SPOT_IMPACT_TOO_HIGH");
            }
            if (perp.impactBps().compareTo(rules.maxVwapImpactBps()) > 0) {
                return LiquidityAssessment.reject("PERP_IMPACT_TOO_HIGH");
            }
            BigDecimal spotDepth = notionalWithinImpact(spotLevels, spotBest, opening);
            BigDecimal perpDepth = notionalWithinImpact(perpLevels, perpBest, !opening);
            if (spotDepth.compareTo(rules.minBookNotional()) < 0) {
                return LiquidityAssessment.reject("INSUFFICIENT_SPOT_DEPTH_NOTIONAL");
            }
            if (perpDepth.compareTo(rules.minBookNotional()) < 0) {
                return LiquidityAssessment.reject("INSUFFICIENT_PERP_DEPTH_NOTIONAL");
            }
            BigDecimal required = rules.requiredCoverageNotional();
            if (spotDepth.compareTo(required) < 0) {
                return LiquidityAssessment.reject("INSUFFICIENT_SPOT_DEPTH_COVERAGE");
            }
            if (perpDepth.compareTo(required) < 0) {
                return LiquidityAssessment.reject("INSUFFICIENT_PERP_DEPTH_COVERAGE");
            }
            BigDecimal slippage = spot.impactBps().add(perp.impactBps())
                    .divide(new BigDecimal("10000"), 18, RoundingMode.HALF_UP);
            return new LiquidityAssessment(true, null, spot, perp, slippage);
        } catch (RuntimeException exception) {
            return LiquidityAssessment.reject("INSUFFICIENT_DEPTH");
        }
    }

    private BigDecimal notionalWithinImpact(List<NormalizedOrderBookSnapshot.Level> levels,
            BigDecimal best, boolean buy) {
        BigDecimal cap = buy
                ? best.multiply(BigDecimal.ONE.add(rules.maxVwapImpact()))
                : best.multiply(BigDecimal.ONE.subtract(rules.maxVwapImpact()));
        BigDecimal filled = BigDecimal.ZERO;
        for (NormalizedOrderBookSnapshot.Level level : levels) {
            boolean inside = buy ? level.price().compareTo(cap) <= 0 : level.price().compareTo(cap) >= 0;
            if (!inside) {
                break;
            }
            filled = filled.add(level.notional());
        }
        return filled;
    }

    private boolean withinSettlementProtection(Instant nextFundingTime, Instant now) {
        if (nextFundingTime == null || rules.settlementProtection().isZero()
                || rules.settlementProtection().isNegative()) {
            return false;
        }
        Duration until = Duration.between(now, nextFundingTime);
        return !until.isNegative() && until.compareTo(rules.settlementProtection()) <= 0;
    }

    private static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
