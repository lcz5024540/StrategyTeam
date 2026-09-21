package com.metaverse.coin.strategy.core.arbitrage.spotperp;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Runnable dry-run entrypoint that never starts Spring, never reads API keys,
 * and never places orders. Uses public REST only.
 *
 * <pre>
 *   mvn -pl coin-strategy-core -am exec:java -Dexec.args=once
 *   mvn -pl coin-strategy-core -am exec:java -Dexec.args=backtest
 *   mvn -pl coin-strategy-core -am exec:java -Dexec.args=scheme-c
 * </pre>
 */
public final class SpotPerpDryRunApp {
    private SpotPerpDryRunApp() {
    }

    public static void main(String[] args) {
        String command = args == null || args.length == 0 ? "once" : args[0].trim().toLowerCase();
        SpotPerpRules rules = SpotPerpRules.defaults();
        switch (command) {
            case "once" -> runOnce(rules);
            case "backtest" -> runBacktest(rules);
            case "scheme-c" -> runSchemeC();
            default -> {
                System.err.println("Unknown command '" + command + "'. Use once | backtest | scheme-c");
                System.exit(2);
            }
        }
    }

    static SpotPerpDryRunEngine.CycleResult runOnce(SpotPerpRules rules) {
        PublicSpotPerpMarketSource source = new PublicSpotPerpMarketSource();
        PublicSpotPerpMarketSource.FetchResult fetched = source.fetchUniverse();
        SpotPerpDryRunEngine engine = new SpotPerpDryRunEngine(rules);
        SpotPerpDryRunEngine.CycleResult cycle = engine.tick(fetched.at(), fetched.snapshots());
        System.out.println("Spot-perp funding dry-run (one public cycle)");
        System.out.println("dry-run=" + rules.dryRun() + " notional=" + rules.notionalUsdt()
                + " USDT leverage=" + rules.leverage() + "x");
        System.out.println("snapshots=" + fetched.snapshots().size() + " errors=" + fetched.errors().size());
        for (String note : fetched.notes()) {
            System.out.println("  note: " + note);
        }
        for (PublicSpotPerpMarketSource.FetchError error : fetched.errors()) {
            System.out.println("  fetch-error " + error.instrument() + " " + error.message());
        }
        for (SpotPerpSnapshot snapshot : fetched.snapshots()) {
            System.out.printf("  %s funding=%s basis=%s next=%s%n",
                    snapshot.instrument().key(),
                    snapshot.fundingRate().toPlainString(),
                    snapshot.executableOpenBasis().stripTrailingZeros().toPlainString(),
                    snapshot.nextFundingTime());
        }
        for (SpotPerpDryRunEngine.Action action : cycle.actions()) {
            System.out.printf("  action %s %s %s%n", action.instrument().key(), action.type(), action.reason());
        }
        if (cycle.openPositions().isEmpty()) {
            System.out.println("open positions: none");
        }
        for (SpotPerpPosition position : cycle.openPositions()) {
            System.out.printf("  open %s qty=%s fundingPnL=%s%n",
                    position.instrument().key(), position.quantity().toPlainString(),
                    position.fundingFeesEarned().toPlainString());
        }
        return cycle;
    }

    static SpotPerpBacktest.Report runBacktest(SpotPerpRules rules) {
        Instant to = Instant.now().truncatedTo(ChronoUnit.HOURS);
        Instant from = to.minus(Duration.ofDays(90));
        List<String> errors = new ArrayList<>();
        SpotPerpHistoryClient client = new SpotPerpHistoryClient();
        List<SpotPerpHistoryClient.FundingPrint> prints = client.fetchUniverse(from, to, errors);
        SpotPerpBacktest.Report report = new SpotPerpBacktest().replay(prints, rules, from, to, errors);
        System.out.print(report.asText());
        System.out.println("prints fetched: " + prints.size());
        return report;
    }

    static SchemeCTimingRiskBacktest.Comparison runSchemeC() {
        Instant to = Instant.now().truncatedTo(ChronoUnit.HOURS);
        Instant from = to.minus(Duration.ofDays(90));
        List<String> errors = new ArrayList<>();
        SpotPerpHistoryClient client = new SpotPerpHistoryClient();
        List<SpotPerpHistoryClient.FundingPrint> prints = client.fetchUniverse(from, to, errors);
        SchemeCTimingRiskBacktest.Comparison comparison =
                new SchemeCTimingRiskBacktest().compare(prints, from, to, errors);
        System.out.print(comparison.asText());
        System.out.println("prints fetched: " + prints.size());
        return comparison;
    }
}
