package com.metaverse.coin.strategy.core.arbitrage.multi;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CrossExchangeFundingReplayTest {
    private static final Instant BASE_TIME = Instant.parse("2026-08-20T00:00:00Z");
    private static final BigDecimal TARGET_NOTIONAL = new BigDecimal("100");
    private static final CrossExchangeOpportunitySelector.Limits LIMITS =
            new CrossExchangeOpportunitySelector.Limits(Duration.ofMinutes(1),
                    Duration.ofMinutes(2), new BigDecimal("0.01"), BigDecimal.TEN,
                    new BigDecimal("0.00001"), BigDecimal.ZERO, false);

    @Test
    void replaysFundingAndOrderBookScenariosDeterministically() throws IOException {
        List<ReplayFrame> frames = loadFrames();
        CrossExchangeOpportunitySelector selector = new CrossExchangeOpportunitySelector();
        Map<String, CrossExchangeOpportunityStabilityGate> gates = new HashMap<>();
        Set<String> coveredScenarios = new HashSet<>();
        Set<Exchange> coveredExchanges = new HashSet<>();

        for (ReplayFrame frame : frames) {
            Instant now = BASE_TIME.plusSeconds(frame.elapsedSeconds());
            CrossExchangeOpportunity opportunity = selector.evaluate(frame.rates(now),
                    frame.books(now), zeroCosts(), TARGET_NOTIONAL, LIMITS, now).get(0);
            boolean stable = gates.computeIfAbsent(frame.scenario(), ignored ->
                    new CrossExchangeOpportunityStabilityGate(Duration.ofMinutes(3)))
                    .stable(opportunity, now);

            assertEquals(frame.expectedReason(), opportunity.rejectionReason(), frame.scenario());
            assertEquals(frame.expectedLong(), opportunity.longQuote().exchange(), frame.scenario());
            assertEquals(frame.expectedShort(), opportunity.shortQuote().exchange(), frame.scenario());
            assertEquals(frame.expectedStable(), stable, frame.scenario());
            coveredScenarios.add(frame.scenario());
            coveredExchanges.add(frame.exchangeA());
            coveredExchanges.add(frame.exchangeB());
        }

        assertEquals(Set.of("same_positive", "same_negative", "mixed_signs", "settlement_skew",
                "funding_spike", "stale_funding", "stale_order_book", "price_anomaly"),
                coveredScenarios);
        assertEquals(Set.of(Exchange.values()), coveredExchanges);
    }

    private List<ReplayFrame> loadFrames() throws IOException {
        InputStream resource = getClass().getClassLoader().getResourceAsStream(
                "funding-replay/cross-exchange-funding-v1.csv");
        assertNotNull(resource);
        List<ReplayFrame> frames = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                resource, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                frames.add(ReplayFrame.parse(line));
            }
        }
        return frames;
    }

    private Map<Exchange, ExchangeCost> zeroCosts() {
        Map<Exchange, ExchangeCost> costs = new EnumMap<>(Exchange.class);
        for (Exchange exchange : Exchange.values()) {
            costs.put(exchange, new ExchangeCost(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        }
        return costs;
    }

    private record ReplayFrame(String scenario, long elapsedSeconds,
            Exchange exchangeA, BigDecimal rateA, int intervalA, long settlementOffsetA,
            BigDecimal markA, long fundingAgeA, long bookAgeA,
            BigDecimal bidNotionalA, BigDecimal askNotionalA,
            Exchange exchangeB, BigDecimal rateB, int intervalB, long settlementOffsetB,
            BigDecimal markB, long fundingAgeB, long bookAgeB,
            BigDecimal bidNotionalB, BigDecimal askNotionalB,
            String expectedReason, Exchange expectedLong, Exchange expectedShort,
            boolean expectedStable) {

        private static ReplayFrame parse(String line) {
            String[] value = line.split("\\|", -1);
            if (value.length != 24) {
                throw new IllegalArgumentException("invalid replay row: " + line);
            }
            return new ReplayFrame(value[0], Long.parseLong(value[1]), Exchange.parse(value[2]),
                    decimal(value[3]), Integer.parseInt(value[4]), Long.parseLong(value[5]),
                    decimal(value[6]), Long.parseLong(value[7]), Long.parseLong(value[8]),
                    decimal(value[9]), decimal(value[10]), Exchange.parse(value[11]),
                    decimal(value[12]), Integer.parseInt(value[13]), Long.parseLong(value[14]),
                    decimal(value[15]), Long.parseLong(value[16]), Long.parseLong(value[17]),
                    decimal(value[18]), decimal(value[19]), emptyToNull(value[20]),
                    Exchange.parse(value[21]), Exchange.parse(value[22]),
                    Boolean.parseBoolean(value[23]));
        }

        private List<NormalizedFundingRateSnapshot> rates(Instant now) {
            return List.of(rate(exchangeA, rateA, intervalA, settlementOffsetA,
                            markA, fundingAgeA, now),
                    rate(exchangeB, rateB, intervalB, settlementOffsetB,
                            markB, fundingAgeB, now));
        }

        private Map<Exchange, NormalizedOrderBookSnapshot> books(Instant now) {
            Map<Exchange, NormalizedOrderBookSnapshot> books = new EnumMap<>(Exchange.class);
            books.put(exchangeA, book(exchangeA, markA, bidNotionalA, askNotionalA, bookAgeA, now));
            books.put(exchangeB, book(exchangeB, markB, bidNotionalB, askNotionalB, bookAgeB, now));
            return books;
        }

        private NormalizedFundingRateSnapshot rate(Exchange exchange, BigDecimal rate,
                int interval, long settlementOffset, BigDecimal mark, long age, Instant now) {
            return new NormalizedFundingRateSnapshot(exchange, "BTCUSDT", rate, interval,
                    now.plusSeconds(settlementOffset), mark, mark, now.minusSeconds(age));
        }

        private NormalizedOrderBookSnapshot book(Exchange exchange, BigDecimal mark,
                BigDecimal bidNotional, BigDecimal askNotional, long age, Instant now) {
            return new NormalizedOrderBookSnapshot(exchange, "BTCUSDT",
                    mark.subtract(BigDecimal.ONE), mark.add(BigDecimal.ONE),
                    bidNotional, askNotional, now.minusSeconds(age));
        }

        private static BigDecimal decimal(String value) {
            return new BigDecimal(value);
        }

        private static String emptyToNull(String value) {
            return value.isEmpty() ? null : value;
        }
    }
}
