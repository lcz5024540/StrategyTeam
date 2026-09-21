package com.metaverse.coin.strategy.core.arbitrage.spotperp;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Side-by-side public-data replay of the production taker engine vs Scheme B.
 *
 * <p>Does not place orders. Historical L2 is not available from the funding
 * endpoints; maker fills are assumed at the passive quote.</p>
 */
public final class SpotPerpSchemeCompare {
    public static final BigDecimal SLOT = new BigDecimal("10000");
    public static final BigDecimal BUFFER = new BigDecimal("0.0005");
    public static final int SCHEME_B_HORIZON = 12;

    public record Scheme(
            String id,
            String description,
            SpotPerpRules rules,
            SpotPerpExecutionPolicy policy
    ) {
    }

    public record Row(
            String schemeId,
            String schemeDescription,
            SpotPerpBacktest.InstrumentStats stats,
            BigDecimal fundingBps,
            BigDecimal feeBps,
            BigDecimal netBps
    ) {
    }

    public record Report(
            Instant from,
            Instant to,
            int prints,
            List<String> fetchErrors,
            List<Scheme> schemes,
            List<Row> rows,
            String honesty
    ) {
        public String asText() {
            StringBuilder builder = new StringBuilder();
            builder.append("Spot-perp Scheme B vs taker (paper, funding-fee only)\n");
            builder.append("Period: ").append(from).append(" -> ").append(to).append('\n');
            builder.append("Notional: ").append(SLOT.toPlainString()).append(" USDT / symbol, 2x perp short\n");
            builder.append("Prints: ").append(prints).append('\n');
            builder.append("Honesty: ").append(honesty).append('\n');
            if (!fetchErrors.isEmpty()) {
                builder.append("Fetch errors:\n");
                for (String error : fetchErrors) {
                    builder.append("  - ").append(error).append('\n');
                }
            }
            String header = String.format("%-22s %-18s %5s %5s %6s %12s %12s %12s %8s %8s %8s%n",
                    "Scheme", "Instrument", "Open", "Close", "FundN",
                    "FundUSDT", "FeeUSDT", "NetUSDT", "FundBps", "FeeBps", "NetBps");
            builder.append(header);
            String current = "";
            for (Row row : rows) {
                if (!row.schemeId().equals(current)) {
                    current = row.schemeId();
                    builder.append("-- ").append(row.schemeId()).append(": ")
                            .append(row.schemeDescription()).append('\n');
                }
                SpotPerpBacktest.InstrumentStats stats = row.stats();
                builder.append(String.format("%-22s %-18s %5d %5d %6d %12s %12s %12s %8s %8s %8s%n",
                        row.schemeId(),
                        stats.instrument().key(),
                        stats.opens(),
                        stats.closes(),
                        stats.settlements(),
                        money(stats.fundingPnl()),
                        money(stats.estimatedFees()),
                        money(stats.netAfterFees()),
                        bpsText(row.fundingBps()),
                        bpsText(row.feeBps()),
                        bpsText(row.netBps())));
            }
            builder.append('\n');
            builder.append(totalsTable());
            return builder.toString();
        }

        private String totalsTable() {
            StringBuilder builder = new StringBuilder();
            builder.append(String.format("%-22s %5s %12s %12s %12s %8s %8s %8s%n",
                    "Scheme total", "Open", "FundUSDT", "FeeUSDT", "NetUSDT",
                    "FundBps", "FeeBps", "NetBps"));
            String last = null;
            BigDecimal fund = BigDecimal.ZERO;
            BigDecimal fee = BigDecimal.ZERO;
            BigDecimal net = BigDecimal.ZERO;
            int opens = 0;
            for (int index = 0; index <= rows.size(); index++) {
                Row row = index < rows.size() ? rows.get(index) : null;
                if (row == null || (last != null && !row.schemeId().equals(last))) {
                    builder.append(String.format("%-22s %5d %12s %12s %12s %8s %8s %8s%n",
                            last, opens, money(fund), money(fee), money(net),
                            bpsText(toBps(fund)), bpsText(toBps(fee)), bpsText(toBps(net))));
                    fund = BigDecimal.ZERO;
                    fee = BigDecimal.ZERO;
                    net = BigDecimal.ZERO;
                    opens = 0;
                }
                if (row == null) {
                    break;
                }
                last = row.schemeId();
                opens += row.stats().opens();
                fund = fund.add(row.stats().fundingPnl());
                fee = fee.add(row.stats().estimatedFees());
                net = net.add(row.stats().netAfterFees());
            }
            return builder.toString();
        }
    }

    public static List<Scheme> defaultSchemes() {
        return List.of(
                new Scheme("A_TAKER_PROD",
                        "production sequential MARKET IOC; lump 28/48 bps charged twice (open+close)",
                        SpotPerpRules.tenThousandTaker(),
                        SpotPerpExecutionPolicy.productionTaker()),
                new Scheme("A_TAKER_BPS",
                        "sequential MARKET IOC; published VIP0/Prime0 taker 30/52 bps, 4-leg fees",
                        SpotPerpRules.publishedTaker(30, BigDecimal.ZERO),
                        SpotPerpExecutionPolicy.publishedTaker()),
                new Scheme("B_MAKER",
                        "both LIMIT; remaining=12*rate; fees+5bps buffer; optimistic same-bar fill",
                        SpotPerpRules.schemeB(SpotPerpExecutionStyle.MAKER_LIMIT, SCHEME_B_HORIZON, BUFFER),
                        SpotPerpExecutionPolicy.schemeB(SpotPerpExecutionStyle.MAKER_LIMIT, 0)),
                new Scheme("B_MIXED_PERP_MAKER",
                        "spot MARKET then perp LIMIT; remaining=12*rate; fees+5bps buffer",
                        SpotPerpRules.schemeB(SpotPerpExecutionStyle.MIXED_SPOT_TAKER_PERP_MAKER,
                                SCHEME_B_HORIZON, BUFFER),
                        SpotPerpExecutionPolicy.schemeB(SpotPerpExecutionStyle.MIXED_SPOT_TAKER_PERP_MAKER, 0)),
                new Scheme("B_MAKER_DELAY1",
                        "both LIMIT; rest one 8h bar before fill (conservative, still no L2)",
                        SpotPerpRules.schemeB(SpotPerpExecutionStyle.MAKER_LIMIT, SCHEME_B_HORIZON, BUFFER),
                        SpotPerpExecutionPolicy.schemeB(SpotPerpExecutionStyle.MAKER_LIMIT, 1)),
                new Scheme("B_MAKER_HORIZON30",
                        "both LIMIT; same 30-period persistence as A plus 5bps buffer (isolates maker fees)",
                        SpotPerpRules.schemeB(SpotPerpExecutionStyle.MAKER_LIMIT, 30, BUFFER),
                        SpotPerpExecutionPolicy.schemeB(SpotPerpExecutionStyle.MAKER_LIMIT, 0))
        );
    }

    public Report run(List<SpotPerpHistoryClient.FundingPrint> prints, Instant from, Instant to,
            List<String> fetchErrors) {
        return run(prints, from, to, fetchErrors, defaultSchemes());
    }

    public Report run(List<SpotPerpHistoryClient.FundingPrint> prints, Instant from, Instant to,
            List<String> fetchErrors, List<Scheme> schemes) {
        Objects.requireNonNull(prints, "prints");
        List<Row> rows = new ArrayList<>();
        SpotPerpBacktest backtest = new SpotPerpBacktest();
        for (Scheme scheme : schemes) {
            SpotPerpBacktest.Report replay = backtest.replay(
                    prints, scheme.rules(), scheme.policy(), from, to, fetchErrors);
            for (SpotPerpBacktest.InstrumentStats stats : replay.stats()) {
                rows.add(new Row(
                        scheme.id(),
                        scheme.description(),
                        stats,
                        toBps(stats.fundingPnl()),
                        toBps(stats.estimatedFees()),
                        toBps(stats.netAfterFees())));
            }
        }
        String honesty = "Public funding prints only; no historical L2. Maker/limit fills are assumed "
                + "at the passive quote on a synthetic 1,000,000 USDT book. Fill probability, queue "
                + "position, and adverse selection are NOT observed. HTX mark is often missing and "
                + "is replaced by a BTC/ETH placeholder so notional stays 10,000 USDT. PnL is short-perp "
                + "funding minus estimated trading fees; basis is a risk gate, not a PnL source. "
                + "A_TAKER_PROD keeps the production executor (lump round-trip/2 per fill × 4 fills). "
                + "Scheme B gates on remaining = current_rate × horizon vs per-leg round-trip bps + "
                + BUFFER.movePointRight(4).toPlainString() + " bps buffer.";
        return new Report(from, to, prints.size(),
                fetchErrors == null ? List.of() : List.copyOf(fetchErrors),
                List.copyOf(schemes), List.copyOf(rows), honesty);
    }

    static BigDecimal toBps(BigDecimal usdt) {
        if (usdt == null) {
            return BigDecimal.ZERO;
        }
        return usdt.divide(SLOT, 10, RoundingMode.HALF_UP).movePointRight(4)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private static String money(BigDecimal value) {
        return value == null ? "0" : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String bpsText(BigDecimal value) {
        return value == null ? "0" : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
