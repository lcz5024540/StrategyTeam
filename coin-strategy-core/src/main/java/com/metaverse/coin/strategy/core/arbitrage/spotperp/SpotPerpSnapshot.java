package com.metaverse.coin.strategy.core.arbitrage.spotperp;

import com.metaverse.coin.strategy.common.client.perpetual.PerpetualMarketDataClient;
import com.metaverse.coin.strategy.core.arbitrage.multi.NormalizedOrderBookSnapshot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Public-only market snapshot used by gates, sizing and paper fills. */
public record SpotPerpSnapshot(
        SpotPerpInstrument instrument,
        BigDecimal fundingRate,
        int fundingIntervalHours,
        Instant nextFundingTime,
        BigDecimal markPrice,
        BigDecimal indexPrice,
        BigDecimal spotBid,
        BigDecimal spotAsk,
        BigDecimal perpBid,
        BigDecimal perpAsk,
        List<NormalizedOrderBookSnapshot.Level> spotBids,
        List<NormalizedOrderBookSnapshot.Level> spotAsks,
        List<NormalizedOrderBookSnapshot.Level> perpBids,
        List<NormalizedOrderBookSnapshot.Level> perpAsks,
        BigDecimal quantityStep,
        BigDecimal minimumNotional,
        Instant receivedAt
) {
    public SpotPerpSnapshot {
        Objects.requireNonNull(instrument, "instrument");
        Objects.requireNonNull(fundingRate, "fundingRate");
        Objects.requireNonNull(nextFundingTime, "nextFundingTime");
        Objects.requireNonNull(markPrice, "markPrice");
        Objects.requireNonNull(indexPrice, "indexPrice");
        Objects.requireNonNull(spotBid, "spotBid");
        Objects.requireNonNull(spotAsk, "spotAsk");
        Objects.requireNonNull(perpBid, "perpBid");
        Objects.requireNonNull(perpAsk, "perpAsk");
        Objects.requireNonNull(quantityStep, "quantityStep");
        Objects.requireNonNull(minimumNotional, "minimumNotional");
        Objects.requireNonNull(receivedAt, "receivedAt");
        if (fundingIntervalHours < 1) {
            throw new IllegalArgumentException("fundingIntervalHours must be >= 1");
        }
        if (spotAsk.compareTo(spotBid) < 0 || perpAsk.compareTo(perpBid) < 0) {
            throw new IllegalArgumentException("crossed book is not executable");
        }
        if (quantityStep.signum() <= 0 || minimumNotional.signum() <= 0) {
            throw new IllegalArgumentException("lot constraints must be positive");
        }
        spotBids = List.copyOf(Objects.requireNonNull(spotBids, "spotBids"));
        spotAsks = List.copyOf(Objects.requireNonNull(spotAsks, "spotAsks"));
        perpBids = List.copyOf(Objects.requireNonNull(perpBids, "perpBids"));
        perpAsks = List.copyOf(Objects.requireNonNull(perpAsks, "perpAsks"));
    }

    /**
     * Executable open basis: buy spot at ask, sell perp at bid.
     * Negative means the hedge locks an immediate cash loss versus spot.
     */
    public BigDecimal executableOpenBasis() {
        return perpBid.subtract(spotAsk).divide(spotAsk, 18, RoundingMode.HALF_UP);
    }

    /** Mid-mark basis used when only settlement prints exist (backtest). */
    public BigDecimal markBasis() {
        return markPrice.subtract(indexPrice).divide(indexPrice, 18, RoundingMode.HALF_UP);
    }

    public static List<NormalizedOrderBookSnapshot.Level> levelsFrom(
            List<PerpetualMarketDataClient.BookLevel> levels) {
        List<NormalizedOrderBookSnapshot.Level> converted = new ArrayList<>();
        if (levels == null) {
            return List.of();
        }
        for (PerpetualMarketDataClient.BookLevel level : levels) {
            converted.add(new NormalizedOrderBookSnapshot.Level(level.price(), level.notional()));
        }
        return List.copyOf(converted);
    }

    public static List<NormalizedOrderBookSnapshot.Level> syntheticBook(
            BigDecimal price, BigDecimal notional, int levels, BigDecimal tick, boolean bids) {
        if (price == null || notional == null || price.signum() <= 0 || notional.signum() <= 0) {
            throw new IllegalArgumentException("synthetic book needs positive price and notional");
        }
        BigDecimal step = tick == null || tick.signum() <= 0 ? price.movePointLeft(4) : tick;
        List<NormalizedOrderBookSnapshot.Level> result = new ArrayList<>();
        BigDecimal remaining = notional;
        BigDecimal perLevel = notional.divide(BigDecimal.valueOf(Math.max(1, levels)), 8, RoundingMode.DOWN);
        if (perLevel.signum() <= 0) {
            perLevel = notional;
        }
        for (int index = 0; index < Math.max(1, levels) && remaining.signum() > 0; index++) {
            BigDecimal levelPrice = bids
                    ? price.subtract(step.multiply(BigDecimal.valueOf(index)))
                    : price.add(step.multiply(BigDecimal.valueOf(index)));
            if (levelPrice.signum() <= 0) {
                break;
            }
            BigDecimal levelNotional = remaining.min(perLevel.max(new BigDecimal("0.01")));
            result.add(new NormalizedOrderBookSnapshot.Level(levelPrice, levelNotional));
            remaining = remaining.subtract(levelNotional);
        }
        return List.copyOf(result);
    }
}
