package com.metaverse.coin.strategy.core.arbitrage.spotperp;

import com.metaverse.coin.strategy.core.arbitrage.multi.NormalizedOrderBookSnapshot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Replays funding-settlement prints through the production dry-run engine.
 *
 * <p>Historical L2 is not published on the public funding endpoints, so fills are
 * assumed at mark with a synthetic deep book. Liquidity gates therefore always
 * pass for BTC/ETH 100 USDT. That limitation is reported on the result.</p>
 */
public final class SpotPerpBacktest {
    public static final String METHOD = "public funding-settlement replay; synthetic deep book at mark; "
            + "no historical L2; PnL = short-perp funding only";

    public record InstrumentStats(
            SpotPerpInstrument instrument,
            int prints,
            int opens,
            int closes,
            int settlements,
            int skips,
            Map<String, Integer> skipReasons,
            BigDecimal fundingPnl,
            BigDecimal estimatedFees,
            BigDecimal netAfterFees,
            Instant firstPrint,
            Instant lastPrint
    ) {
    }

    public record Report(
            String method,
            Instant from,
            Instant to,
            SpotPerpRules rules,
            List<InstrumentStats> stats,
            List<String> fetchErrors,
            String dataNotes
    ) {
        public String asText() {
            StringBuilder builder = new StringBuilder();
            builder.append("Spot-perp funding dry-run backtest\n");
            builder.append("Method: ").append(method).append('\n');
            builder.append("Period: ").append(from).append(" -> ").append(to).append('\n');
            builder.append("Notional: ").append(rules.notionalUsdt()).append(" USDT / symbol\n");
            builder.append("Leverage: ").append(rules.leverage()).append("x perp short\n");
            builder.append("Data notes: ").append(dataNotes).append('\n');
            if (!fetchErrors.isEmpty()) {
                builder.append("Fetch errors:\n");
                for (String error : fetchErrors) {
                    builder.append("  - ").append(error).append('\n');
                }
            }
            builder.append(String.format("%-18s %6s %5s %5s %6s %12s %12s %12s%n",
                    "Instrument", "Prints", "Open", "Close", "FundN", "FundingPnL", "Fees", "Net"));
            for (InstrumentStats row : stats) {
                builder.append(String.format("%-18s %6d %5d %5d %6d %12s %12s %12s%n",
                        row.instrument().key(), row.prints(), row.opens(), row.closes(),
                        row.settlements(),
                        row.fundingPnl().toPlainString(),
                        row.estimatedFees().toPlainString(),
                        row.netAfterFees().toPlainString()));
                if (row.skipReasons() != null && !row.skipReasons().isEmpty()) {
                    builder.append("    skip reasons: ").append(row.skipReasons()).append('\n');
                }
            }
            return builder.toString();
        }
    }

    public Report replay(List<SpotPerpHistoryClient.FundingPrint> prints, SpotPerpRules rules,
            Instant from, Instant to, List<String> fetchErrors) {
        return replay(prints, rules, SpotPerpExecutionPolicy.productionTaker(), from, to, fetchErrors);
    }

    public Report replay(List<SpotPerpHistoryClient.FundingPrint> prints, SpotPerpRules rules,
            SpotPerpExecutionPolicy policy, Instant from, Instant to, List<String> fetchErrors) {
        Objects.requireNonNull(prints, "prints");
        SpotPerpDryRunEngine engine = new SpotPerpDryRunEngine(rules, policy);
        Map<SpotPerpInstrument, Integer> printCounts = new LinkedHashMap<>();
        Map<SpotPerpInstrument, Instant> first = new LinkedHashMap<>();
        Map<SpotPerpInstrument, Instant> last = new LinkedHashMap<>();
        Map<SpotPerpInstrument, Integer> opens = new LinkedHashMap<>();
        Map<SpotPerpInstrument, Integer> closes = new LinkedHashMap<>();
        Map<SpotPerpInstrument, Integer> settlements = new LinkedHashMap<>();
        Map<SpotPerpInstrument, Integer> skips = new LinkedHashMap<>();
        Map<SpotPerpInstrument, Map<String, Integer>> skipReasons = new LinkedHashMap<>();
        Duration interval = Duration.ofHours(8);
        for (SpotPerpHistoryClient.FundingPrint print : prints) {
            printCounts.merge(print.instrument(), 1, Integer::sum);
            first.putIfAbsent(print.instrument(), print.fundingTime());
            last.put(print.instrument(), print.fundingTime());
            SpotPerpSnapshot snapshot = snapshotFromPrint(print, interval);
            SpotPerpDryRunEngine.CycleResult cycle = engine.tick(print.fundingTime(), List.of(snapshot));
            for (SpotPerpDryRunEngine.Action action : cycle.actions()) {
                switch (action.type()) {
                    case OPENED -> opens.merge(action.instrument(), 1, Integer::sum);
                    case CLOSED, ROLLED_BACK -> closes.merge(action.instrument(), 1, Integer::sum);
                    case FUNDING_SETTLED -> settlements.merge(action.instrument(), 1, Integer::sum);
                    case SKIPPED -> {
                        skips.merge(action.instrument(), 1, Integer::sum);
                        skipReasons.computeIfAbsent(action.instrument(), key -> new LinkedHashMap<>())
                                .merge(action.reason(), 1, Integer::sum);
                    }
                }
            }
        }
        Map<String, BigDecimal> fees = engine.closedPositions().stream().collect(Collectors.toMap(
                position -> position.instrument().key(),
                SpotPerpPosition::estimatedFees,
                BigDecimal::add,
                LinkedHashMap::new));
        for (SpotPerpPosition open : engine.openPositions()) {
            fees.merge(open.instrument().key(), open.estimatedFees(), BigDecimal::add);
        }
        Map<String, BigDecimal> funding = engine.fundingPnlByInstrument();
        List<InstrumentStats> stats = new ArrayList<>();
        for (SpotPerpInstrument instrument : SpotPerpInstrument.universe()) {
            BigDecimal pnl = funding.getOrDefault(instrument.key(), BigDecimal.ZERO);
            BigDecimal fee = fees.getOrDefault(instrument.key(), BigDecimal.ZERO);
            stats.add(new InstrumentStats(
                    instrument,
                    printCounts.getOrDefault(instrument, 0),
                    opens.getOrDefault(instrument, 0),
                    closes.getOrDefault(instrument, 0),
                    settlements.getOrDefault(instrument, 0),
                    skips.getOrDefault(instrument, 0),
                    skipReasons.getOrDefault(instrument, Map.of()),
                    pnl,
                    fee,
                    pnl.subtract(fee),
                    first.get(instrument),
                    last.get(instrument)
            ));
        }
        String notes = "Binance: GET /fapi/v1/fundingRate (includes markPrice). "
                + "HTX: GET /linear-swap-api/v1/swap_historical_funding_rate (mark often absent; "
                + "avg_premium_index used as basis proxy when present). "
                + "No historical spot/perp L2; synthetic 1,000,000 USDT book so "
                + rules.notionalUsdt().toPlainString()
                + " USDT BTC/ETH liquidity gates are assumed to pass. If a venue fetch failed, that series is empty.";
        return new Report(METHOD, from, to, rules, stats,
                fetchErrors == null ? List.of() : List.copyOf(fetchErrors), notes);
    }

    static SpotPerpSnapshot snapshotFromPrint(SpotPerpHistoryClient.FundingPrint print, Duration interval) {
        BigDecimal mark = placeholderMark(print);
        BigDecimal index = inferIndex(print, mark);
        BigDecimal tick = mark.movePointLeft(5).max(new BigDecimal("0.01"));
        BigDecimal spotAsk = index;
        BigDecimal spotBid = index.subtract(tick).max(tick);
        BigDecimal perpBid = mark;
        BigDecimal perpAsk = mark.add(tick);
        BigDecimal depth = new BigDecimal("1000000");
        List<NormalizedOrderBookSnapshot.Level> spotBids = SpotPerpSnapshot.syntheticBook(spotBid, depth, 5, tick, true);
        List<NormalizedOrderBookSnapshot.Level> spotAsks = SpotPerpSnapshot.syntheticBook(spotAsk, depth, 5, tick, false);
        List<NormalizedOrderBookSnapshot.Level> perpBids = SpotPerpSnapshot.syntheticBook(perpBid, depth, 5, tick, true);
        List<NormalizedOrderBookSnapshot.Level> perpAsks = SpotPerpSnapshot.syntheticBook(perpAsk, depth, 5, tick, false);
        BigDecimal step = mark.compareTo(new BigDecimal("1000")) > 0
                ? new BigDecimal("0.00001")
                : new BigDecimal("0.0001");
        return new SpotPerpSnapshot(
                print.instrument(),
                print.fundingRate(),
                8,
                print.nextFundingTime(interval),
                mark,
                index,
                spotBid,
                spotAsk,
                perpBid,
                perpAsk,
                spotBids,
                spotAsks,
                perpBids,
                perpAsks,
                step,
                new BigDecimal("5"),
                print.fundingTime()
        );
    }

    /**
     * HTX historical funding often omits mark (the client stores 1). Using that
     * would size a 10,000 USDT BTC hedge as 10,000 coins. Substitute a BTC/ETH
     * placeholder so fees stay percent-of-notional; still not a real mark.
     */
    private static BigDecimal placeholderMark(SpotPerpHistoryClient.FundingPrint print) {
        BigDecimal mark = print.markPrice();
        if (mark == null || mark.signum() <= 0 || mark.compareTo(new BigDecimal("10")) < 0) {
            return print.instrument().asset() == SpotPerpInstrument.SpotPerpAsset.BTC
                    ? new BigDecimal("100000")
                    : new BigDecimal("4000");
        }
        return mark;
    }

    private static BigDecimal inferIndex(SpotPerpHistoryClient.FundingPrint print, BigDecimal mark) {
        BigDecimal premium = print.indexOrPremium();
        if (premium == null) {
            return mark;
        }
        // HTX avg_premium_index is a rate; Binance history repeats markPrice here.
        if (premium.abs().compareTo(new BigDecimal("0.05")) <= 0
                && premium.compareTo(mark) != 0) {
            return mark.divide(BigDecimal.ONE.add(premium), 8, RoundingMode.HALF_UP);
        }
        if (premium.signum() > 0) {
            return premium;
        }
        return mark;
    }
}
