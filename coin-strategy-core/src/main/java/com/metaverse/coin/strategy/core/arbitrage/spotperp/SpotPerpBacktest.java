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
 * pass for BTC/ETH Scheme A 10,000 USDT against the 1,000,000 USDT synthetic
 * book. That limitation is reported on the result.</p>
 *
 * <p>Funding, estimated fees, and net are linear in sized quantity. Fee/funding
 * ratios and bps-of-notional are therefore scale-invariant except lot rounding.
 * A negative net at 100 USDT is the same rate economics as at 10,000 USDT.</p>
 */
public final class SpotPerpBacktest {
    public static final String METHOD = "public funding-settlement replay; synthetic deep book at mark; "
            + "no historical L2; PnL = short-perp funding only";
    static final BigDecimal BPS = new BigDecimal("10000");
    static final BigDecimal SMALL_NOTIONAL = new BigDecimal("100");
    static final BigDecimal SCHEME_A_NOTIONAL = new BigDecimal("10000");

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
        public BigDecimal fundingBps(BigDecimal notional) {
            return bpsOfNotional(fundingPnl, notional);
        }

        public BigDecimal feesBps(BigDecimal notional) {
            return bpsOfNotional(estimatedFees, notional);
        }

        public BigDecimal netBps(BigDecimal notional) {
            return bpsOfNotional(netAfterFees, notional);
        }

        public BigDecimal feeToFundingRatio() {
            return ratio(estimatedFees, fundingPnl);
        }
    }

    public record ScaleRow(
            SpotPerpInstrument instrument,
            int opensSmall,
            int opensLarge,
            BigDecimal fundingSmall,
            BigDecimal fundingLarge,
            BigDecimal feesSmall,
            BigDecimal feesLarge,
            BigDecimal netSmall,
            BigDecimal netLarge,
            BigDecimal notionalSmall,
            BigDecimal notionalLarge
    ) {
        public BigDecimal expectedScale() {
            return ratio(notionalLarge, notionalSmall);
        }

        public BigDecimal fundingScale() {
            return ratio(fundingLarge, fundingSmall);
        }

        public BigDecimal feesScale() {
            return ratio(feesLarge, feesSmall);
        }

        public BigDecimal netScale() {
            return ratio(netLarge, netSmall);
        }

        public BigDecimal feeToFundingSmall() {
            return ratio(feesSmall, fundingSmall);
        }

        public BigDecimal feeToFundingLarge() {
            return ratio(feesLarge, fundingLarge);
        }

        public BigDecimal fundingBpsSmall() {
            return bpsOfNotional(fundingSmall, notionalSmall);
        }

        public BigDecimal fundingBpsLarge() {
            return bpsOfNotional(fundingLarge, notionalLarge);
        }

        public BigDecimal feesBpsSmall() {
            return bpsOfNotional(feesSmall, notionalSmall);
        }

        public BigDecimal feesBpsLarge() {
            return bpsOfNotional(feesLarge, notionalLarge);
        }

        public BigDecimal netBpsSmall() {
            return bpsOfNotional(netSmall, notionalSmall);
        }

        public BigDecimal netBpsLarge() {
            return bpsOfNotional(netLarge, notionalLarge);
        }
    }

    public record ScaleComparison(
            BigDecimal notionalSmall,
            BigDecimal notionalLarge,
            List<ScaleRow> rows
    ) {
        public String asText() {
            StringBuilder builder = new StringBuilder();
            builder.append("Scale invariance (").append(notionalSmall.toPlainString())
                    .append(" vs ").append(notionalLarge.toPlainString())
                    .append(" USDT, same prints)\n");
            builder.append("Funding, fees, and net USDT scale ~linearly with notional. ")
                    .append("Fee/funding ratios and bps-of-notional are scale-invariant except lot rounding. ")
                    .append("Do not read a small-notional negative net as \"fees ate funding because size was small.\"\n");
            builder.append(String.format(
                    "%-18s %7s %7s %10s %10s %12s %12s %11s %11s %10s %10s%n",
                    "Instrument", "OpenS", "OpenL", "FundScale", "FeeScale",
                    "Fee/FundS", "Fee/FundL", "FundBpsS", "FundBpsL", "FeeBpsS", "FeeBpsL"));
            for (ScaleRow row : rows) {
                builder.append(String.format(
                        "%-18s %7d %7d %10s %10s %12s %12s %11s %11s %10s %10s%n",
                        row.instrument().key(),
                        row.opensSmall(),
                        row.opensLarge(),
                        fmt(row.fundingScale()),
                        fmt(row.feesScale()),
                        fmt(row.feeToFundingSmall()),
                        fmt(row.feeToFundingLarge()),
                        fmt(row.fundingBpsSmall()),
                        fmt(row.fundingBpsLarge()),
                        fmt(row.feesBpsSmall()),
                        fmt(row.feesBpsLarge())));
            }
            return builder.toString();
        }
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
            BigDecimal notional = rules.notionalUsdt();
            StringBuilder builder = new StringBuilder();
            builder.append("Spot-perp funding dry-run backtest\n");
            builder.append("Method: ").append(method).append('\n');
            builder.append("Period: ").append(from).append(" -> ").append(to).append('\n');
            builder.append("Notional: ").append(notional.toPlainString()).append(" USDT / symbol\n");
            builder.append("Leverage: ").append(rules.leverage()).append("x perp short\n");
            builder.append("Units: USDT cash and bps of configured per-symbol notional (")
                    .append(notional.toPlainString()).append(")\n");
            builder.append("Data notes: ").append(dataNotes).append('\n');
            if (!fetchErrors.isEmpty()) {
                builder.append("Fetch errors:\n");
                for (String error : fetchErrors) {
                    builder.append("  - ").append(error).append('\n');
                }
            }
            builder.append(String.format("%-18s %6s %5s %5s %6s %14s %10s %14s %10s %14s %10s %10s%n",
                    "Instrument", "Prints", "Open", "Close", "FundN",
                    "FundingUSDT", "FundBps", "FeesUSDT", "FeeBps", "NetUSDT", "NetBps", "Fee/Fund"));
            BigDecimal fundingTotal = BigDecimal.ZERO;
            BigDecimal feeTotal = BigDecimal.ZERO;
            BigDecimal netTotal = BigDecimal.ZERO;
            int openTotal = 0;
            for (InstrumentStats row : stats) {
                fundingTotal = fundingTotal.add(row.fundingPnl());
                feeTotal = feeTotal.add(row.estimatedFees());
                netTotal = netTotal.add(row.netAfterFees());
                openTotal += row.opens();
                builder.append(String.format("%-18s %6d %5d %5d %6d %14s %10s %14s %10s %14s %10s %10s%n",
                        row.instrument().key(), row.prints(), row.opens(), row.closes(),
                        row.settlements(),
                        row.fundingPnl().toPlainString(),
                        fmt(row.fundingBps(notional)),
                        row.estimatedFees().toPlainString(),
                        fmt(row.feesBps(notional)),
                        row.netAfterFees().toPlainString(),
                        fmt(row.netBps(notional)),
                        fmt(row.feeToFundingRatio())));
                if (row.skipReasons() != null && !row.skipReasons().isEmpty()) {
                    builder.append("    skip reasons: ").append(row.skipReasons()).append('\n');
                }
            }
            builder.append(String.format("%-18s %6s %5d %5s %6s %14s %10s %14s %10s %14s %10s %10s%n",
                    "TOTAL", "", openTotal, "", "",
                    fundingTotal.toPlainString(),
                    fmt(bpsOfNotional(fundingTotal, notional)),
                    feeTotal.toPlainString(),
                    fmt(bpsOfNotional(feeTotal, notional)),
                    netTotal.toPlainString(),
                    fmt(bpsOfNotional(netTotal, notional)),
                    fmt(ratio(feeTotal, fundingTotal))));
            builder.append("TOTAL bps are vs one symbol's notional (")
                    .append(notional.toPlainString())
                    .append("), not vs 4× notional. Per-row bps are the comparable unit.\n");
            return builder.toString();
        }
    }

    public Report replay(List<SpotPerpHistoryClient.FundingPrint> prints, SpotPerpRules rules,
            Instant from, Instant to, List<String> fetchErrors) {
        Objects.requireNonNull(prints, "prints");
        SpotPerpDryRunEngine engine = new SpotPerpDryRunEngine(rules);
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
                + "No historical spot/perp L2; synthetic 1,000,000 USDT book so Scheme A 10,000 USDT "
                + "BTC/ETH (coverage 2.0 → 20,000 USDT required depth) liquidity gates are assumed to pass. "
                + "If a venue fetch failed, that series is empty.";
        return new Report(METHOD, from, to, rules, stats,
                fetchErrors == null ? List.of() : List.copyOf(fetchErrors), notes);
    }

    public ScaleComparison compareScale(Report small, Report large) {
        Objects.requireNonNull(small, "small");
        Objects.requireNonNull(large, "large");
        Map<String, InstrumentStats> smallByKey = small.stats().stream()
                .collect(Collectors.toMap(row -> row.instrument().key(), row -> row, (a, b) -> a, LinkedHashMap::new));
        List<ScaleRow> rows = new ArrayList<>();
        for (InstrumentStats largeRow : large.stats()) {
            InstrumentStats smallRow = smallByKey.get(largeRow.instrument().key());
            if (smallRow == null) {
                continue;
            }
            rows.add(new ScaleRow(
                    largeRow.instrument(),
                    smallRow.opens(),
                    largeRow.opens(),
                    smallRow.fundingPnl(),
                    largeRow.fundingPnl(),
                    smallRow.estimatedFees(),
                    largeRow.estimatedFees(),
                    smallRow.netAfterFees(),
                    largeRow.netAfterFees(),
                    small.rules().notionalUsdt(),
                    large.rules().notionalUsdt()));
        }
        return new ScaleComparison(small.rules().notionalUsdt(), large.rules().notionalUsdt(), List.copyOf(rows));
    }

    static SpotPerpSnapshot snapshotFromPrint(SpotPerpHistoryClient.FundingPrint print, Duration interval) {
        BigDecimal mark = print.markPrice() == null || print.markPrice().signum() <= 0
                ? new BigDecimal("100000")
                : print.markPrice();
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

    static BigDecimal bpsOfNotional(BigDecimal amount, BigDecimal notional) {
        if (amount == null || notional == null || notional.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return amount.divide(notional, 10, RoundingMode.HALF_UP).multiply(BPS).stripTrailingZeros();
    }

    static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() == 0) {
            return null;
        }
        return numerator.divide(denominator, 8, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    static String fmt(BigDecimal value) {
        return value == null ? "n/a" : value.toPlainString();
    }
}
