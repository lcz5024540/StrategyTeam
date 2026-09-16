package com.metaverse.coin.strategy.job.funding.service;

import com.metaverse.coin.strategy.core.arbitrage.config.MultiExchangeFundingRateProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CrossExchangeFundingReplayServiceTest {
    private static final String HEADER = "schemaVersion,capturedAt,source,exchange,symbol,"
            + "rawFundingRate,fundingIntervalHours,nextFundingTime,markPrice,indexPrice,"
            + "bestBid,bestAsk,bidNotional,askNotional,fundingReceivedAt,bookReceivedAt,status,error\n";
    private static final String L2_HEADER = "schemaVersion,capturedAt,source,exchange,symbol,"
            + "rawFundingRate,fundingIntervalHours,nextFundingTime,markPrice,indexPrice,"
            + "bestBid,bestAsk,bidNotional,askNotional,bidLevels,askLevels,"
            + "fundingReceivedAt,bookReceivedAt,status,error\n";

    @TempDir
    Path captureDirectory;

    @Test
    void replaysLegacyCapturesUsingTopOfBookNotional() throws Exception {
        Instant first = Instant.parse("2026-08-21T00:00:00Z");
        Instant stable = first.plusSeconds(180);
        Path input = captureDirectory.resolve("sample.csv");
        Files.writeString(input, HEADER
                + row(stable, "BINANCE", "0.008")
                + row(stable, "OKX", "0.080")
                + row(first, "BINANCE", "0.008")
                + row(first, "OKX", "0.080"), StandardCharsets.UTF_8);
        CrossExchangeFundingReplayService service = new CrossExchangeFundingReplayService(
                new CrossExchangeMarketSnapshotReplayReader(),
                new MultiExchangeFundingRateProperties(), captureDirectory);

        CrossExchangeFundingReplayService.ReplayReport report = service.replay("sample.csv");

        assertEquals(2, report.frames());
        assertEquals(2, report.evaluatedPairs());
        assertEquals(2, report.qualifiedPairs());
        assertEquals(1, report.approvedPairs());
        assertEquals(0, report.rejectedByReason().size());
    }

    @Test
    void replaysL2CapturesThroughCostsAndStabilityWindow() throws Exception {
        Instant first = Instant.parse("2026-08-21T00:00:00Z");
        Instant stable = first.plusSeconds(180);
        Path input = captureDirectory.resolve("l2-sample.csv");
        Files.writeString(input, L2_HEADER
                + l2Row(stable, "BINANCE", "0.008")
                + l2Row(stable, "OKX", "0.080")
                + l2Row(first, "BINANCE", "0.008")
                + l2Row(first, "OKX", "0.080"), StandardCharsets.UTF_8);
        CrossExchangeFundingReplayService service = new CrossExchangeFundingReplayService(
                new CrossExchangeMarketSnapshotReplayReader(),
                new MultiExchangeFundingRateProperties(), captureDirectory);

        CrossExchangeFundingReplayService.ReplayReport report = service.replay("l2-sample.csv");

        assertEquals(2, report.frames());
        assertEquals(2, report.evaluatedPairs());
        assertEquals(2, report.qualifiedPairs());
        assertEquals(1, report.approvedPairs());
        assertEquals(1, report.frameResults().get(1).approvedPairs());
    }

    @Test
    void aggregatesCaptureFailuresAndRejectedReasons() throws Exception {
        Instant capturedAt = Instant.parse("2026-08-21T00:00:00Z");
        Path input = captureDirectory.resolve("rejected.csv");
        Files.writeString(input, HEADER
                + row(capturedAt, "BINANCE", "-0.008")
                + row(capturedAt, "OKX", "0.080")
                + failureRow(capturedAt, "HTX", "timeout"),
                StandardCharsets.UTF_8);
        CrossExchangeFundingReplayService service = new CrossExchangeFundingReplayService(
                new CrossExchangeMarketSnapshotReplayReader(),
                new MultiExchangeFundingRateProperties(), captureDirectory);

        CrossExchangeFundingReplayService.ReplayReport report = service.replay("rejected.csv");

        assertEquals(1, report.captureFailures());
        assertEquals(1, report.evaluatedPairs());
        assertEquals(1, report.qualifiedPairs());
        assertEquals(0, report.rejectedByReason().size());
    }

    @Test
    void confinesReplayInputToConfiguredCaptureDirectory() {
        CrossExchangeFundingReplayService service = new CrossExchangeFundingReplayService(
                new CrossExchangeMarketSnapshotReplayReader(),
                new MultiExchangeFundingRateProperties(), captureDirectory);

        assertThrows(IllegalArgumentException.class, () -> service.replay("../outside.csv"));
        assertThrows(IllegalArgumentException.class, () -> service.replay("missing.csv"));
        assertThrows(IllegalArgumentException.class, () -> service.replay("sample.txt"));
    }

    @Test
    void rejectsSymlinkThatEscapesCaptureDirectory() throws Exception {
        Path allowed = Files.createDirectory(captureDirectory.resolve("allowed"));
        Path outside = captureDirectory.resolve("outside.csv");
        Files.writeString(outside, HEADER, StandardCharsets.UTF_8);
        Files.createSymbolicLink(allowed.resolve("linked.csv"), outside);
        CrossExchangeFundingReplayService service = new CrossExchangeFundingReplayService(
                new CrossExchangeMarketSnapshotReplayReader(),
                new MultiExchangeFundingRateProperties(), allowed);

        assertThrows(IllegalArgumentException.class, () -> service.replay("linked.csv"));
    }

    private String row(Instant capturedAt, String exchange, String fundingRate) {
        return String.join(",", "1", capturedAt.toString(), "PUBLIC_REST", exchange,
                "\"BTCUSDT\"", fundingRate, "8", capturedAt.plusSeconds(3600).toString(),
                "100", "100", "99.9", "100.1", "1000", "1000",
                capturedAt.toString(), capturedAt.toString(), "OK", "") + "\n";
    }

    private String l2Row(Instant capturedAt, String exchange, String fundingRate) {
        return String.join(",", "2", capturedAt.toString(), "PUBLIC_REST", exchange,
                "\"BTCUSDT\"", fundingRate, "8", capturedAt.plusSeconds(3600).toString(),
                "100", "100", "99.9", "100.1", "1000", "1000", "\"99.9:1000\"",
                "\"100.1:1000\"", capturedAt.toString(), capturedAt.toString(), "OK", "") + "\n";
    }

    private String failureRow(Instant capturedAt, String exchange, String error) {
        return String.join(",", "1", capturedAt.toString(), "PUBLIC_REST", exchange,
                "\"BTCUSDT\"", "", "", "", "", "", "", "", "", "", "", "",
                "ERROR", "\"" + error + "\"") + "\n";
    }
}
