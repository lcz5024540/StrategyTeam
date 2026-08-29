package com.metaverse.coin.strategy.job.funding.service;

import com.metaverse.coin.strategy.core.arbitrage.multi.CrossExchangeMarketDataProvider;
import com.metaverse.coin.strategy.core.arbitrage.multi.Exchange;
import com.metaverse.coin.strategy.core.arbitrage.multi.NormalizedFundingRateSnapshot;
import com.metaverse.coin.strategy.core.arbitrage.multi.NormalizedOrderBookSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossExchangeMarketSnapshotRecorderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void appendsReplayableSnapshotsWithProvenanceAndIsolatesProviderFailure() throws Exception {
        Instant capturedAt = Instant.parse("2026-08-21T00:00:00Z");
        CrossExchangeMarketSnapshotRecorder recorder = new CrossExchangeMarketSnapshotRecorder(
                List.of(provider(Exchange.BINANCE, capturedAt), failingProvider()),
                temporaryDirectory, List.of("BTCUSDT"), 20);

        CrossExchangeMarketSnapshotRecorder.CaptureResult first = recorder.capture(capturedAt);
        CrossExchangeMarketSnapshotRecorder.CaptureResult second =
                recorder.capture(capturedAt.plusSeconds(60));

        assertEquals(1, first.succeeded());
        assertEquals(1, first.failed());
        assertEquals(first.output(), second.output());
        List<String> lines = Files.readAllLines(first.output());
        assertEquals(5, lines.size());
        assertEquals(1, lines.stream().filter(line -> line.startsWith("schemaVersion,")).count());
        assertTrue(lines.get(1).contains("PUBLIC_REST,BINANCE,\"BTCUSDT\""));
        assertTrue(lines.get(1).endsWith("OK,"));
        assertTrue(lines.get(1).contains("\"99:495;98:490\",\"101:303;102:204\""));
        assertTrue(lines.get(2).contains("PUBLIC_REST,OKX,\"BTCUSDT\""));
        assertTrue(lines.get(2).contains("ERROR,\"IllegalStateException: unavailable\""));

        CrossExchangeMarketSnapshotReplayReader.ReplayDataset dataset =
                new CrossExchangeMarketSnapshotReplayReader().read(first.output());
        assertEquals(2, dataset.frames().size());
        assertEquals(2, dataset.failures().size());
        assertEquals(new BigDecimal("0.0001"), dataset.frames().get(0).funding()
                .get(Exchange.BINANCE).rawFundingRate());
        assertEquals(new BigDecimal("900"), dataset.frames().get(0).books()
                .get(Exchange.BINANCE).askNotional());
        assertEquals(new BigDecimal("102"), dataset.frames().get(0).books()
                .get(Exchange.BINANCE).askLevels().get(1).price());
    }

    @Test
    void scheduledJobDelegatesToRecorder() throws Exception {
        Instant snapshotTime = Instant.parse("2026-08-21T00:00:00Z");
        CrossExchangeMarketSnapshotRecorder recorder = new CrossExchangeMarketSnapshotRecorder(
                List.of(provider(Exchange.BINANCE, snapshotTime)), temporaryDirectory,
                List.of("BTCUSDT"), 20);

        new CrossExchangeMarketSnapshotCaptureJob(recorder).capture();

        try (var files = Files.list(temporaryDirectory)) {
            assertEquals(1, files.filter(path -> path.getFileName().toString()
                    .startsWith("cross-exchange-funding-")).count());
        }
    }

    private CrossExchangeMarketDataProvider provider(Exchange exchange, Instant receivedAt) {
        return new CrossExchangeMarketDataProvider() {
            @Override
            public Exchange exchange() {
                return exchange;
            }

            @Override
            public List<String> supportedUsdtPerpetualSymbols() {
                return List.of("BTCUSDT");
            }

            @Override
            public NormalizedFundingRateSnapshot fundingRate(String symbol) {
                return new NormalizedFundingRateSnapshot(exchange, symbol,
                        new BigDecimal("0.0001"), 8, receivedAt.plusSeconds(3600),
                        new BigDecimal("100"), new BigDecimal("99.9"), receivedAt);
            }

            @Override
            public NormalizedOrderBookSnapshot orderBook(String symbol, int depth) {
                return new NormalizedOrderBookSnapshot(exchange, symbol,
                        new BigDecimal("99"), new BigDecimal("101"),
                        new BigDecimal("1000"), new BigDecimal("900"),
                        List.of(new NormalizedOrderBookSnapshot.Level(new BigDecimal("99"),
                                        new BigDecimal("495")),
                                new NormalizedOrderBookSnapshot.Level(new BigDecimal("98"),
                                        new BigDecimal("490"))),
                        List.of(new NormalizedOrderBookSnapshot.Level(new BigDecimal("101"),
                                        new BigDecimal("303")),
                                new NormalizedOrderBookSnapshot.Level(new BigDecimal("102"),
                                        new BigDecimal("204"))),
                        receivedAt);
            }
        };
    }

    private CrossExchangeMarketDataProvider failingProvider() {
        return new CrossExchangeMarketDataProvider() {
            @Override
            public Exchange exchange() {
                return Exchange.OKX;
            }

            @Override
            public List<String> supportedUsdtPerpetualSymbols() {
                return List.of("BTCUSDT");
            }

            @Override
            public NormalizedFundingRateSnapshot fundingRate(String symbol) {
                throw new IllegalStateException("unavailable");
            }

            @Override
            public NormalizedOrderBookSnapshot orderBook(String symbol, int depth) {
                throw new IllegalStateException("unavailable");
            }
        };
    }
}
