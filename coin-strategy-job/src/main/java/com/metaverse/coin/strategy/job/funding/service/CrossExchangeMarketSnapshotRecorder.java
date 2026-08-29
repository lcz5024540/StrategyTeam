package com.metaverse.coin.strategy.job.funding.service;

import com.metaverse.coin.strategy.core.arbitrage.config.MultiExchangeFundingRateProperties;
import com.metaverse.coin.strategy.core.arbitrage.multi.CrossExchangeMarketDataProvider;
import com.metaverse.coin.strategy.core.arbitrage.multi.NormalizedFundingRateSnapshot;
import com.metaverse.coin.strategy.core.arbitrage.multi.NormalizedOrderBookSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/** Appends public market snapshots to a source-attributed CSV dataset for offline replay. */
@Component
public class CrossExchangeMarketSnapshotRecorder {
    private static final String HEADER = "schemaVersion,capturedAt,source,exchange,symbol,"
            + "rawFundingRate,fundingIntervalHours,nextFundingTime,markPrice,indexPrice,"
            + "bestBid,bestAsk,bidNotional,askNotional,bidLevels,askLevels,"
            + "fundingReceivedAt,bookReceivedAt,status,error";
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd")
            .withZone(ZoneOffset.UTC);

    private final List<CrossExchangeMarketDataProvider> providers;
    private final Path outputDirectory;
    private final List<String> symbols;
    private final int depth;

    @Autowired
    public CrossExchangeMarketSnapshotRecorder(List<CrossExchangeMarketDataProvider> providers,
            MultiExchangeFundingRateProperties properties, Environment environment) {
        this(providers.stream()
                        .filter(provider -> properties.getExchanges().contains(provider.exchange()))
                        .toList(),
                Path.of(environment.getProperty("arbitrage.multi-exchange.capture-output-directory",
                        "./data/funding-replay")),
                symbols(environment.getProperty("arbitrage.multi-exchange.capture-symbols",
                        "BTCUSDT")),
                Integer.parseInt(environment.getProperty(
                        "arbitrage.multi-exchange.capture-order-book-depth", "20")));
    }

    CrossExchangeMarketSnapshotRecorder(List<CrossExchangeMarketDataProvider> providers,
            Path outputDirectory, List<String> symbols, int depth) {
        if (outputDirectory == null || depth <= 0) {
            throw new IllegalArgumentException("output directory and positive depth are required");
        }
        this.providers = List.copyOf(providers);
        this.outputDirectory = outputDirectory;
        this.symbols = List.copyOf(symbols);
        this.depth = depth;
    }

    public synchronized CaptureResult capture(Instant capturedAt) {
        // A separate filename prevents appending v2 rows below an existing v1 header.
        Path output = outputDirectory.resolve("cross-exchange-funding-v2-"
                + FILE_DATE.format(capturedAt) + ".csv");
        int succeeded = 0;
        int failed = 0;
        try {
            Files.createDirectories(outputDirectory);
            boolean writeHeader = Files.notExists(output) || Files.size(output) == 0;
            try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                if (writeHeader) {
                    writer.write(HEADER);
                    writer.newLine();
                }
                for (CrossExchangeMarketDataProvider provider : providers) {
                    for (String symbol : symbols) {
                        try {
                            NormalizedFundingRateSnapshot funding = provider.fundingRate(symbol);
                            NormalizedOrderBookSnapshot book = provider.orderBook(symbol, depth);
                            writer.write(successRow(capturedAt, provider, funding, book));
                            succeeded++;
                        } catch (RuntimeException exception) {
                            writer.write(failureRow(capturedAt, provider, symbol, exception));
                            failed++;
                        }
                        writer.newLine();
                    }
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("cannot write funding replay snapshot " + output, exception);
        }
        return new CaptureResult(output, succeeded, failed);
    }

    private String successRow(Instant capturedAt, CrossExchangeMarketDataProvider provider,
            NormalizedFundingRateSnapshot funding, NormalizedOrderBookSnapshot book) {
        return String.join(",", "2", capturedAt.toString(), "PUBLIC_REST",
                provider.exchange().name(), csv(funding.canonicalSymbol()),
                funding.rawFundingRate().toPlainString(),
                String.valueOf(funding.fundingIntervalHours()), funding.nextFundingTime().toString(),
                funding.markPrice().toPlainString(), funding.indexPrice().toPlainString(),
                book.bestBid().toPlainString(), book.bestAsk().toPlainString(),
                book.bidNotional().toPlainString(), book.askNotional().toPlainString(),
                csv(levels(book.bidLevels())), csv(levels(book.askLevels())),
                funding.receivedAt().toString(), book.receivedAt().toString(), "OK", "");
    }

    private String failureRow(Instant capturedAt, CrossExchangeMarketDataProvider provider,
            String symbol, RuntimeException exception) {
        return String.join(",", "2", capturedAt.toString(), "PUBLIC_REST",
                provider.exchange().name(), csv(symbol), "", "", "", "", "", "", "", "", "",
                "", "", "", "", "ERROR", csv(exception.getClass().getSimpleName() + ": "
                        + String.valueOf(exception.getMessage())));
    }

    private String levels(List<NormalizedOrderBookSnapshot.Level> levels) {
        return levels.stream().map(level -> level.price().toPlainString() + ":"
                + level.notional().toPlainString()).collect(Collectors.joining(";"));
    }

    private String csv(String value) {
        String safe = value == null ? "" : value;
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    private static List<String> symbols(String configured) {
        return Arrays.stream(configured.split(",")).map(String::trim)
                .filter(symbol -> !symbol.isBlank()).distinct().toList();
    }

    public record CaptureResult(Path output, int succeeded, int failed) { }
}
