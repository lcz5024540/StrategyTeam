package com.metaverse.coin.strategy.job.funding.service;

import com.metaverse.coin.strategy.core.arbitrage.config.MultiExchangeFundingRateProperties;
import com.metaverse.coin.strategy.core.arbitrage.multi.CrossExchangeOpportunity;
import com.metaverse.coin.strategy.core.arbitrage.multi.CrossExchangeOpportunitySelector;
import com.metaverse.coin.strategy.core.arbitrage.multi.CrossExchangeOpportunityStabilityGate;
import com.metaverse.coin.strategy.core.arbitrage.multi.Exchange;
import com.metaverse.coin.strategy.core.arbitrage.multi.NormalizedOrderBookSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Replays captured public snapshots through the production opportunity selection rules. */
@Service
public class CrossExchangeFundingReplayService {
    private final CrossExchangeMarketSnapshotReplayReader reader;
    private final MultiExchangeFundingRateProperties properties;
    private final Path captureDirectory;

    @Autowired
    public CrossExchangeFundingReplayService(CrossExchangeMarketSnapshotReplayReader reader,
            MultiExchangeFundingRateProperties properties, Environment environment) {
        this(reader, properties, Path.of(environment.getProperty(
                "arbitrage.multi-exchange.capture-output-directory", "./data/funding-replay")));
    }

    CrossExchangeFundingReplayService(CrossExchangeMarketSnapshotReplayReader reader,
            MultiExchangeFundingRateProperties properties, Path captureDirectory) {
        this.reader = reader;
        this.properties = properties;
        this.captureDirectory = captureDirectory.toAbsolutePath().normalize();
    }

    public ReplayReport replay(String fileName) {
        Path input = resolveInput(fileName);
        CrossExchangeMarketSnapshotReplayReader.ReplayDataset dataset = reader.read(input);
        CrossExchangeOpportunitySelector selector = new CrossExchangeOpportunitySelector();
        CrossExchangeOpportunityStabilityGate stability =
                new CrossExchangeOpportunityStabilityGate(properties.getStabilityWindow());
        CrossExchangeOpportunitySelector.Limits limits = new CrossExchangeOpportunitySelector.Limits(
                properties.getMaximumQuoteAge(), properties.getMaximumSettlementSkew(),
                properties.getMaxMarkPriceDeviation(), properties.getMinimumNotional(),
                properties.getMinNetYieldRate(), properties.getBasisRiskBuffer(),
                properties.isAllowMixedFundingSigns(), properties.getMinimumCostCoverageRatio(),
                properties.getMaxExecutableBasis());

        int evaluatedPairs = 0;
        int qualifiedPairs = 0;
        int approvedPairs = 0;
        Map<String, Integer> rejectedByReason = new LinkedHashMap<>();
        List<ReplayFrameResult> frames = new ArrayList<>();
        List<CrossExchangeMarketSnapshotReplayReader.ReplayFrame> orderedFrames = dataset.frames().stream()
                .sorted(Comparator.comparing(CrossExchangeMarketSnapshotReplayReader.ReplayFrame::capturedAt)
                        .thenComparing(CrossExchangeMarketSnapshotReplayReader.ReplayFrame::symbol))
                .toList();
        for (CrossExchangeMarketSnapshotReplayReader.ReplayFrame frame : orderedFrames) {
            var rates = frame.funding().values().stream()
                    .filter(snapshot -> properties.getExchanges().contains(snapshot.exchange()))
                    .toList();
            Map<Exchange, NormalizedOrderBookSnapshot> books = new java.util.EnumMap<>(Exchange.class);
            frame.books().forEach((exchange, book) -> {
                if (properties.getExchanges().contains(exchange)) {
                    books.put(exchange, book);
                }
            });
            List<CrossExchangeOpportunity> evaluated = selector.evaluate(rates, books,
                    properties.getCosts(), properties.getTargetNotional(), limits, frame.capturedAt());
            int frameQualified = 0;
            int frameApproved = 0;
            for (CrossExchangeOpportunity opportunity : evaluated) {
                evaluatedPairs++;
                if (!opportunity.executable()) {
                    rejectedByReason.merge(opportunity.rejectionReason(), 1, Integer::sum);
                    continue;
                }
                qualifiedPairs++;
                frameQualified++;
                if (stability.stable(opportunity, frame.capturedAt())) {
                    approvedPairs++;
                    frameApproved++;
                }
            }
            frames.add(new ReplayFrameResult(frame.capturedAt(), frame.symbol(), evaluated.size(),
                    frameQualified, frameApproved));
        }
        return new ReplayReport(input.getFileName().toString(), dataset.frames().size(),
                dataset.failures().size(), evaluatedPairs, qualifiedPairs, approvedPairs,
                Map.copyOf(rejectedByReason), List.copyOf(frames));
    }

    private Path resolveInput(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("replay fileName is required");
        }
        Path requested = Path.of(fileName);
        if (requested.isAbsolute() || requested.getNameCount() != 1
                || !fileName.endsWith(".csv")) {
            throw new IllegalArgumentException("replay fileName must be a CSV file in the capture directory");
        }
        try {
            Path realDirectory = captureDirectory.toRealPath();
            Path input = realDirectory.resolve(requested).normalize();
            if (!Files.isRegularFile(input)) {
                throw new IllegalArgumentException("replay file does not exist in the capture directory");
            }
            Path realInput = input.toRealPath();
            if (!realInput.startsWith(realDirectory)) {
                throw new IllegalArgumentException("replay file must remain inside the capture directory");
            }
            return realInput;
        } catch (IOException exception) {
            throw new IllegalArgumentException("replay capture directory is unavailable", exception);
        }
    }

    public record ReplayFrameResult(java.time.Instant capturedAt, String symbol,
            int evaluatedPairs, int qualifiedPairs, int approvedPairs) { }

    public record ReplayReport(String fileName, int frames, int captureFailures,
            int evaluatedPairs, int qualifiedPairs, int approvedPairs,
            Map<String, Integer> rejectedByReason, List<ReplayFrameResult> frameResults) { }
}
