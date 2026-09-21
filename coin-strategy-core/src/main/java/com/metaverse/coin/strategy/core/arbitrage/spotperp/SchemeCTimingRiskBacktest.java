package com.metaverse.coin.strategy.core.arbitrage.spotperp;

import com.metaverse.coin.strategy.core.arbitrage.spotperp.SpotPerpInstrument.SpotPerpAsset;
import com.metaverse.coin.strategy.core.arbitrage.spotperp.SpotPerpRules.SchemeCPolicy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Compares Scheme C timing / leverage / risk overlays on the production dry-run engine.
 *
 * <p>Locked: Binance+HTX, BTC+ETH, 10,000 USDT notional, funding-fee PnL only, paper fills.
 * Fees are percent-of-notional (28 bps Binance / 48 bps HTX round-trip).</p>
 *
 * <p>Near-settlement variants inject a synthetic tick {@code entryWindow} before each public
 * funding print. The predicted rate is either the print itself (late-window oracle, typical
 * of Binance predicted funding in the last 30 minutes) or the previous print (lagged, no
 * look-ahead). There is still no historical L2.</p>
 */
public final class SchemeCTimingRiskBacktest {
    public static final BigDecimal TEN_THOUSAND = new BigDecimal("10000");
    public static final Duration ENTRY_WINDOW = Duration.ofMinutes(30);
    private static final Duration INTERVAL = Duration.ofHours(8);
    private static final BigDecimal BPS = new BigDecimal("10000");
    private static final BigDecimal BASIS_20 = new BigDecimal("0.0020");
    private static final BigDecimal BASIS_10 = new BigDecimal("0.0010");
    private static final BigDecimal LIQ_15 = new BigDecimal("0.15");
    private static final BigDecimal LIQ_10 = new BigDecimal("0.10");
    private static final BigDecimal MMR = new BigDecimal("0.004");
    private static final BigDecimal CLOSE_UNATTRACTIVE = new BigDecimal("0.00002");

    public record Variant(
            String id,
            String label,
            SpotPerpRules rules,
            boolean lagPredictor
    ) {
        public boolean nearSettlement() {
            return rules.schemeC().nearSettlementEntry();
        }
    }

    public record InstrumentRow(
            SpotPerpInstrument instrument,
            int prints,
            int opens,
            int closes,
            int settlements,
            Map<String, Integer> skipReasons,
            Map<String, Integer> closeReasons,
            BigDecimal fundingPnl,
            BigDecimal estimatedFees,
            BigDecimal netAfterFees,
            BigDecimal maxAdverseBasis,
            BigDecimal minLiquidationDistance
    ) {
        public BigDecimal netBpsOfNotional() {
            return toBps(netAfterFees, TEN_THOUSAND);
        }
    }

    public record VariantResult(
            Variant variant,
            List<InstrumentRow> rows,
            Map<String, Integer> skipReasons,
            Map<String, Integer> closeReasons,
            BigDecimal fundingPnl,
            BigDecimal estimatedFees,
            BigDecimal netAfterFees,
            BigDecimal maxAdverseBasis,
            BigDecimal minLiquidationDistance,
            int opens,
            int closes,
            int settlements
    ) {
        public BigDecimal netBpsOfStackedNotional() {
            return toBps(netAfterFees, TEN_THOUSAND.multiply(BigDecimal.valueOf(4)));
        }

        public BigDecimal netBpsOfStackedCapital() {
            return toBps(netAfterFees, variant.rules().capitalUsdt().multiply(BigDecimal.valueOf(4)));
        }

        public BigDecimal netBpsOfOneSlotCapital() {
            return toBps(netAfterFees, variant.rules().capitalUsdt());
        }
    }

    public record Comparison(
            Instant from,
            Instant to,
            List<String> fetchErrors,
            int prints,
            Map<String, Integer> rateBuckets,
            List<VariantResult> results
    ) {
        public VariantResult bestNetVsRisk() {
            return results.stream()
                    .max(Comparator
                            .comparing((VariantResult row) -> row.netAfterFees())
                            .thenComparing(VariantResult::minLiquidationDistance)
                            .thenComparing(Comparator.comparing(VariantResult::maxAdverseBasis).reversed()))
                    .orElseThrow();
        }

        public String asText() {
            StringBuilder out = new StringBuilder();
            out.append("Scheme C timing + leverage/risk backtest\n");
            out.append("Locked: Binance+HTX BTC+ETH, 10000 USDT notional/symbol, funding-fee only, paper.\n");
            out.append("Period: ").append(from).append(" -> ").append(to).append('\n');
            out.append("Prints: ").append(prints).append('\n');
            out.append("Rate buckets (all venues/assets): ").append(rateBuckets).append('\n');
            if (!fetchErrors.isEmpty()) {
                out.append("Fetch errors:\n");
                fetchErrors.forEach(error -> out.append("  - ").append(error).append('\n'));
            }
            out.append('\n');
            out.append(String.format(
                    "%-8s %5s %5s %6s %12s %12s %12s %8s %8s %8s %8s%n",
                    "Variant", "Open", "Close", "FundN", "Funding", "Fees", "NetUSDT",
                    "bpsN40k", "bpsCap", "advBps", "minLiq"));
            for (VariantResult row : results) {
                out.append(String.format(
                        "%-8s %5d %5d %6d %12s %12s %12s %8s %8s %8s %8s%n",
                        row.variant().id(),
                        row.opens(),
                        row.closes(),
                        row.settlements(),
                        money(row.fundingPnl()),
                        money(row.estimatedFees()),
                        money(row.netAfterFees()),
                        money(row.netBpsOfStackedNotional()),
                        money(row.netBpsOfStackedCapital()),
                        bps(row.maxAdverseBasis()),
                        pct(row.minLiquidationDistance())));
            }
            VariantResult best = bestNetVsRisk();
            out.append("\nBest net-vs-risk: ").append(best.variant().id())
                    .append(" — ").append(best.variant().label()).append('\n');
            out.append("bpsN40k = net / (4*10000) * 10000. bpsCap = net / (4*(spot+perp/lev)) * 10000.\n");
            out.append("advBps = max (basis - entryBasis) while open. minLiq = min remaining short-perp distance.\n\n");
            for (VariantResult row : results) {
                out.append(row.variant().id()).append(" ").append(row.variant().label()).append('\n');
                out.append("  lev=").append(row.variant().rules().leverage())
                        .append("x capital/symbol=").append(money(row.variant().rules().capitalUsdt()))
                        .append(" USDT near=").append(row.variant().nearSettlement())
                        .append(" lag=").append(row.variant().lagPredictor())
                        .append(" holdN=").append(row.variant().rules().schemeC().maxSettlementsHeld())
                        .append(" basisCap=").append(bps(row.variant().rules().maxBasisRate()))
                        .append("bp liqStop=").append(pct(row.variant().rules().schemeC().minLiquidationDistance()))
                        .append('\n');
                out.append("  skip=").append(row.skipReasons()).append('\n');
                out.append("  close=").append(row.closeReasons()).append('\n');
                for (InstrumentRow inst : row.rows()) {
                    out.append(String.format("    %-18s open=%d close=%d fundN=%d net=%s bps10k=%s advBps=%s minLiq=%s skip=%s close=%s%n",
                            inst.instrument().key(), inst.opens(), inst.closes(), inst.settlements(),
                            money(inst.netAfterFees()), money(inst.netBpsOfNotional()),
                            bps(inst.maxAdverseBasis()), pct(inst.minLiquidationDistance()),
                            inst.skipReasons(), inst.closeReasons()));
                }
            }
            return out.toString();
        }
    }

    public static List<Variant> catalog() {
        List<Variant> variants = new ArrayList<>();
        variants.add(new Variant(
                "C0",
                "control: anytime entry (10m settlement block), 21d TTL, 2x, basis 20bp, no liq stop",
                SpotPerpRules.schemeCTenThousand(2, 30, CLOSE_UNATTRACTIVE, BASIS_20,
                        Duration.ofMinutes(10), Duration.ofHours(8), SchemeCPolicy.disabled()),
                false));
        variants.add(near("C1", "near-30m, hold 1 settlement, 2x, basis 20bp, liq 15%",
                2, 1, 1, BASIS_20, LIQ_15, false));
        variants.add(near("C2", "near-30m, hold 3 settlements, 2x, basis 20bp, liq 15%",
                2, 3, 3, BASIS_20, LIQ_15, false));
        variants.add(near("C3", "near-30m, hold 9 settlements, 2x, basis 20bp, liq 15%",
                2, 9, 9, BASIS_20, LIQ_15, false));
        variants.add(nearTtl("C4", "near-30m, 21d TTL, 2x, basis 20bp, liq 15%",
                2, BASIS_20, LIQ_15));
        variants.add(near("C5", "near-30m, hold 3 settlements, 1x, basis 20bp, liq 15%",
                1, 3, 3, BASIS_20, LIQ_15, false));
        variants.add(near("C6", "near-30m, hold 3 settlements, 3x, basis 20bp, liq 15%",
                3, 3, 3, BASIS_20, LIQ_15, false));
        variants.add(near("C7", "near-30m, hold 3 settlements, 2x, basis 10bp, liq 15%",
                2, 3, 3, BASIS_10, LIQ_15, false));
        variants.add(nearTtl("C8", "near-30m, 21d TTL, 3x, basis 20bp, liq 10%",
                3, BASIS_20, LIQ_10));
        variants.add(near("C2L", "C2 lagged predictor (previous print, no look-ahead)",
                2, 3, 3, BASIS_20, LIQ_15, true));
        return List.copyOf(variants);
    }

    public Comparison compare(List<SpotPerpHistoryClient.FundingPrint> prints, Instant from, Instant to,
            List<String> fetchErrors) {
        Objects.requireNonNull(prints, "prints");
        Map<String, Integer> buckets = rateBuckets(prints);
        List<VariantResult> results = new ArrayList<>();
        for (Variant variant : catalog()) {
            results.add(replay(variant, prints));
        }
        return new Comparison(from, to, fetchErrors == null ? List.of() : List.copyOf(fetchErrors),
                prints.size(), buckets, results);
    }

    VariantResult replay(Variant variant, List<SpotPerpHistoryClient.FundingPrint> prints) {
        SpotPerpDryRunEngine engine = new SpotPerpDryRunEngine(variant.rules());
        Map<SpotPerpInstrument, Integer> printCounts = new LinkedHashMap<>();
        Map<SpotPerpInstrument, Integer> opens = new LinkedHashMap<>();
        Map<SpotPerpInstrument, Integer> closes = new LinkedHashMap<>();
        Map<SpotPerpInstrument, Integer> settlements = new LinkedHashMap<>();
        Map<SpotPerpInstrument, Map<String, Integer>> skipReasons = new LinkedHashMap<>();
        Map<SpotPerpInstrument, Map<String, Integer>> closeReasons = new LinkedHashMap<>();
        Map<SpotPerpInstrument, BigDecimal> maxAdverse = new LinkedHashMap<>();
        Map<SpotPerpInstrument, BigDecimal> minLiq = new LinkedHashMap<>();
        Map<SpotPerpInstrument, BigDecimal> previousRate = new LinkedHashMap<>();
        Map<SpotPerpAsset, BigDecimal> binanceMark = new EnumMap<>(SpotPerpAsset.class);

        for (SpotPerpHistoryClient.FundingPrint raw : prints) {
            if (raw.instrument().venue() == SpotPerpInstrument.SpotPerpVenue.BINANCE
                    && SpotPerpGates.usableMark(raw.markPrice())) {
                binanceMark.put(raw.instrument().asset(), raw.markPrice());
            }
            SpotPerpHistoryClient.FundingPrint print = proxyHtxMark(raw, binanceMark);
            printCounts.merge(print.instrument(), 1, Integer::sum);

            if (variant.nearSettlement()) {
                if (engine.position(print.instrument()).isEmpty()) {
                    BigDecimal predicted = variant.lagPredictor()
                            ? previousRate.get(print.instrument())
                            : print.fundingRate();
                    if (predicted == null) {
                        bump(skipReasons, print.instrument(), "NO_PREDICTOR");
                    } else {
                        Instant near = print.fundingTime().minus(variant.rules().schemeC().entryWindow());
                        SpotPerpSnapshot pre = withTiming(
                                SpotPerpBacktest.snapshotFromPrint(withRate(print, predicted), INTERVAL),
                                predicted, print.fundingTime(), near);
                        recordCycle(engine.tick(near, List.of(pre)), opens, closes, settlements,
                                skipReasons, closeReasons);
                    }
                }
                if (engine.position(print.instrument()).isPresent()) {
                    SpotPerpSnapshot atPrint = withTiming(
                            SpotPerpBacktest.snapshotFromPrint(print, INTERVAL),
                            print.fundingRate(), print.nextFundingTime(INTERVAL), print.fundingTime());
                    recordCycle(engine.tick(print.fundingTime(), List.of(atPrint)),
                            opens, closes, settlements, skipReasons, closeReasons);
                    trackRisk(engine, atPrint, maxAdverse, minLiq, variant.rules());
                }
            } else {
                SpotPerpSnapshot snapshot = SpotPerpBacktest.snapshotFromPrint(print, INTERVAL);
                recordCycle(engine.tick(print.fundingTime(), List.of(snapshot)),
                        opens, closes, settlements, skipReasons, closeReasons);
                trackRisk(engine, snapshot, maxAdverse, minLiq, variant.rules());
            }
            previousRate.put(print.instrument(), print.fundingRate());
        }

        Map<String, BigDecimal> fees = new LinkedHashMap<>();
        for (SpotPerpPosition position : engine.closedPositions()) {
            fees.merge(position.instrument().key(), position.estimatedFees(), BigDecimal::add);
        }
        for (SpotPerpPosition position : engine.openPositions()) {
            fees.merge(position.instrument().key(), position.estimatedFees(), BigDecimal::add);
        }
        Map<String, BigDecimal> funding = engine.fundingPnlByInstrument();

        List<InstrumentRow> rows = new ArrayList<>();
        Map<String, Integer> skipAll = new LinkedHashMap<>();
        Map<String, Integer> closeAll = new LinkedHashMap<>();
        BigDecimal fundingSum = BigDecimal.ZERO;
        BigDecimal feeSum = BigDecimal.ZERO;
        BigDecimal netSum = BigDecimal.ZERO;
        BigDecimal adverseMax = new BigDecimal("-1");
        BigDecimal liqMin = new BigDecimal("9");
        int openSum = 0;
        int closeSum = 0;
        int settleSum = 0;
        for (SpotPerpInstrument instrument : SpotPerpInstrument.universe()) {
            BigDecimal pnl = funding.getOrDefault(instrument.key(), BigDecimal.ZERO);
            BigDecimal fee = fees.getOrDefault(instrument.key(), BigDecimal.ZERO);
            BigDecimal net = pnl.subtract(fee);
            BigDecimal adverse = maxAdverse.getOrDefault(instrument, BigDecimal.ZERO);
            BigDecimal liq = minLiq.getOrDefault(instrument, BigDecimal.ONE);
            Map<String, Integer> skips = skipReasons.getOrDefault(instrument, Map.of());
            Map<String, Integer> closed = closeReasons.getOrDefault(instrument, Map.of());
            skips.forEach((key, count) -> skipAll.merge(key, count, Integer::sum));
            closed.forEach((key, count) -> closeAll.merge(key, count, Integer::sum));
            rows.add(new InstrumentRow(
                    instrument,
                    printCounts.getOrDefault(instrument, 0),
                    opens.getOrDefault(instrument, 0),
                    closes.getOrDefault(instrument, 0),
                    settlements.getOrDefault(instrument, 0),
                    skips,
                    closed,
                    pnl,
                    fee,
                    net,
                    adverse,
                    liq
            ));
            fundingSum = fundingSum.add(pnl);
            feeSum = feeSum.add(fee);
            netSum = netSum.add(net);
            openSum += opens.getOrDefault(instrument, 0);
            closeSum += closes.getOrDefault(instrument, 0);
            settleSum += settlements.getOrDefault(instrument, 0);
            if (adverse.compareTo(adverseMax) > 0) {
                adverseMax = adverse;
            }
            if (liq.compareTo(liqMin) < 0) {
                liqMin = liq;
            }
        }
        return new VariantResult(variant, rows, skipAll, closeAll, fundingSum, feeSum, netSum,
                adverseMax, liqMin, openSum, closeSum, settleSum);
    }

    private static Variant near(String id, String label, int leverage, int minPeriods, int holdN,
            BigDecimal basisCap, BigDecimal liqStop, boolean lag) {
        SchemeCPolicy policy = new SchemeCPolicy(ENTRY_WINDOW, holdN, liqStop, MMR);
        SpotPerpRules rules = SpotPerpRules.schemeCTenThousand(
                leverage, minPeriods, BigDecimal.ZERO, basisCap,
                Duration.ZERO, Duration.ZERO, policy);
        return new Variant(id, label, rules, lag);
    }

    private static Variant nearTtl(String id, String label, int leverage, BigDecimal basisCap,
            BigDecimal liqStop) {
        SchemeCPolicy policy = new SchemeCPolicy(ENTRY_WINDOW, 0, liqStop, MMR);
        SpotPerpRules rules = SpotPerpRules.schemeCTenThousand(
                leverage, 30, CLOSE_UNATTRACTIVE, basisCap,
                Duration.ZERO, Duration.ofHours(8), policy);
        return new Variant(id, label, rules, false);
    }

    private static void recordCycle(
            SpotPerpDryRunEngine.CycleResult cycle,
            Map<SpotPerpInstrument, Integer> opens,
            Map<SpotPerpInstrument, Integer> closes,
            Map<SpotPerpInstrument, Integer> settlements,
            Map<SpotPerpInstrument, Map<String, Integer>> skipReasons,
            Map<SpotPerpInstrument, Map<String, Integer>> closeReasons
    ) {
        for (SpotPerpDryRunEngine.Action action : cycle.actions()) {
            switch (action.type()) {
                case OPENED -> opens.merge(action.instrument(), 1, Integer::sum);
                case CLOSED, ROLLED_BACK -> {
                    closes.merge(action.instrument(), 1, Integer::sum);
                    bump(closeReasons, action.instrument(), action.reason());
                }
                case FUNDING_SETTLED -> settlements.merge(action.instrument(), 1, Integer::sum);
                case SKIPPED -> bump(skipReasons, action.instrument(), action.reason());
            }
        }
    }

    private static void trackRisk(
            SpotPerpDryRunEngine engine,
            SpotPerpSnapshot snapshot,
            Map<SpotPerpInstrument, BigDecimal> maxAdverse,
            Map<SpotPerpInstrument, BigDecimal> minLiq,
            SpotPerpRules rules
    ) {
        engine.position(snapshot.instrument()).ifPresent(position -> {
            BigDecimal adverse = snapshot.executableOpenBasis().subtract(position.entryBasis());
            maxAdverse.merge(position.instrument(), adverse, BigDecimal::max);
            if (SpotPerpGates.usableMark(snapshot.markPrice())) {
                BigDecimal distance = position.liquidationDistance(snapshot.markPrice(),
                        rules.schemeC().maintenanceMarginRate());
                minLiq.merge(position.instrument(), distance, BigDecimal::min);
            }
        });
    }

    private static void bump(Map<SpotPerpInstrument, Map<String, Integer>> reasons,
            SpotPerpInstrument instrument, String reason) {
        reasons.computeIfAbsent(instrument, key -> new LinkedHashMap<>())
                .merge(reason, 1, Integer::sum);
    }

    private static SpotPerpHistoryClient.FundingPrint proxyHtxMark(
            SpotPerpHistoryClient.FundingPrint print,
            Map<SpotPerpAsset, BigDecimal> binanceMark
    ) {
        if (SpotPerpGates.usableMark(print.markPrice())) {
            return print;
        }
        BigDecimal proxy = binanceMark.get(print.instrument().asset());
        if (proxy == null) {
            return print;
        }
        return new SpotPerpHistoryClient.FundingPrint(
                print.instrument(),
                print.fundingTime(),
                print.fundingRate(),
                proxy,
                print.indexOrPremium(),
                print.source() + "+binance-mark-proxy");
    }

    private static SpotPerpHistoryClient.FundingPrint withRate(
            SpotPerpHistoryClient.FundingPrint print, BigDecimal rate) {
        return new SpotPerpHistoryClient.FundingPrint(
                print.instrument(), print.fundingTime(), rate, print.markPrice(),
                print.indexOrPremium(), print.source());
    }

    private static SpotPerpSnapshot withTiming(SpotPerpSnapshot base, BigDecimal rate,
            Instant nextFunding, Instant receivedAt) {
        return new SpotPerpSnapshot(
                base.instrument(),
                rate,
                base.fundingIntervalHours(),
                nextFunding,
                base.markPrice(),
                base.indexPrice(),
                base.spotBid(),
                base.spotAsk(),
                base.perpBid(),
                base.perpAsk(),
                base.spotBids(),
                base.spotAsks(),
                base.perpBids(),
                base.perpAsks(),
                base.quantityStep(),
                base.minimumNotional(),
                receivedAt);
    }

    private static Map<String, Integer> rateBuckets(List<SpotPerpHistoryClient.FundingPrint> prints) {
        Map<String, Integer> buckets = new LinkedHashMap<>();
        buckets.put("total", prints.size());
        int positive = 0;
        int ge8bps = 0;
        int ge28bps = 0;
        int ge48bps = 0;
        int negative = 0;
        for (SpotPerpHistoryClient.FundingPrint print : prints) {
            BigDecimal rate = print.fundingRate();
            if (rate.signum() < 0) {
                negative++;
            } else if (rate.signum() > 0) {
                positive++;
            }
            if (rate.compareTo(new BigDecimal("0.00008")) >= 0) {
                ge8bps++;
            }
            if (rate.compareTo(new BigDecimal("0.0028")) >= 0) {
                ge28bps++;
            }
            if (rate.compareTo(new BigDecimal("0.0048")) >= 0) {
                ge48bps++;
            }
        }
        buckets.put("positive", positive);
        buckets.put("negative", negative);
        buckets.put("ge_8bps", ge8bps);
        buckets.put("ge_28bps_binance_1print_fee", ge28bps);
        buckets.put("ge_48bps_htx_1print_fee", ge48bps);
        return buckets;
    }

    static BigDecimal toBps(BigDecimal amount, BigDecimal notional) {
        if (notional == null || notional.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return amount.multiply(BPS).divide(notional, 4, RoundingMode.HALF_UP);
    }

    private static String money(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP).toPlainString();
    }

    private static String bps(BigDecimal rate) {
        return rate.multiply(BPS).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String pct(BigDecimal rate) {
        return rate.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
    }
}
