package com.metaverse.coin.strategy.core.arbitrage.spotperp;

import com.metaverse.coin.strategy.core.arbitrage.multi.NormalizedOrderBookSnapshot;
import com.metaverse.coin.strategy.core.arbitrage.spotperp.SpotPerpInstrument.SpotPerpAsset;
import com.metaverse.coin.strategy.core.arbitrage.spotperp.SpotPerpInstrument.SpotPerpVenue;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

final class SpotPerpTestSupport {
    static final Instant T0 = Instant.parse("2026-06-01T00:00:00Z");

    private SpotPerpTestSupport() {
    }

    static SpotPerpRules testRules() {
        EnumMap<SpotPerpVenue, BigDecimal> fees = new EnumMap<>(SpotPerpVenue.class);
        fees.put(SpotPerpVenue.BINANCE, new BigDecimal("0.0004"));
        fees.put(SpotPerpVenue.HTX, new BigDecimal("0.0008"));
        return new SpotPerpRules(
                true,
                true,
                new BigDecimal("100"),
                2,
                new BigDecimal("0.0001"),
                new BigDecimal("0.00002"),
                new BigDecimal("-0.0005"),
                new BigDecimal("0.0020"),
                new BigDecimal("0.0015"),
                BigDecimal.ZERO,
                4,
                Duration.ofMinutes(10),
                Duration.ofHours(8),
                Duration.ofDays(21),
                Duration.ofSeconds(15),
                new BigDecimal("50"),
                new BigDecimal("1.5"),
                new BigDecimal("0.0005"),
                new BigDecimal("1.25"),
                Map.copyOf(fees)
        );
    }

    static SpotPerpInstrument btcBinance() {
        return new SpotPerpInstrument(SpotPerpVenue.BINANCE, SpotPerpAsset.BTC);
    }

    static SpotPerpInstrument ethHtx() {
        return new SpotPerpInstrument(SpotPerpVenue.HTX, SpotPerpAsset.ETH);
    }

    static SpotPerpSnapshot liquid(SpotPerpInstrument instrument, String fundingRate, Instant now) {
        return snapshot(instrument, fundingRate, "100000", "100000", "100000", "100010",
                "99990", "100000", new BigDecimal("200000"), now, now.plus(Duration.ofHours(8)));
    }

    static SpotPerpSnapshot snapshot(
            SpotPerpInstrument instrument,
            String fundingRate,
            String spotBid,
            String spotAsk,
            String perpBid,
            String perpAsk,
            String mark,
            String index,
            BigDecimal bookNotional,
            Instant receivedAt,
            Instant nextFunding
    ) {
        BigDecimal spotBidPx = new BigDecimal(spotBid);
        BigDecimal spotAskPx = new BigDecimal(spotAsk);
        BigDecimal perpBidPx = new BigDecimal(perpBid);
        BigDecimal perpAskPx = new BigDecimal(perpAsk);
        BigDecimal tick = new BigDecimal("10");
        List<NormalizedOrderBookSnapshot.Level> spotBids =
                SpotPerpSnapshot.syntheticBook(spotBidPx, bookNotional, 4, tick, true);
        List<NormalizedOrderBookSnapshot.Level> spotAsks =
                SpotPerpSnapshot.syntheticBook(spotAskPx, bookNotional, 4, tick, false);
        List<NormalizedOrderBookSnapshot.Level> perpBids =
                SpotPerpSnapshot.syntheticBook(perpBidPx, bookNotional, 4, tick, true);
        List<NormalizedOrderBookSnapshot.Level> perpAsks =
                SpotPerpSnapshot.syntheticBook(perpAskPx, bookNotional, 4, tick, false);
        BigDecimal step = instrument.asset() == SpotPerpAsset.BTC
                ? new BigDecimal("0.00001")
                : new BigDecimal("0.0001");
        return new SpotPerpSnapshot(
                instrument,
                new BigDecimal(fundingRate),
                8,
                nextFunding,
                new BigDecimal(mark),
                new BigDecimal(index),
                spotBidPx,
                spotAskPx,
                perpBidPx,
                perpAskPx,
                spotBids,
                spotAsks,
                perpBids,
                perpAsks,
                step,
                new BigDecimal("5"),
                receivedAt
        );
    }
}
