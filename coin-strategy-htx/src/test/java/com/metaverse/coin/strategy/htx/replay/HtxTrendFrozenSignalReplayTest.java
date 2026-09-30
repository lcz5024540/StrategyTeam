package com.metaverse.coin.strategy.htx.replay;

import com.metaverse.coin.strategy.htx.config.HtxTrendEnsembleShadowProperties;
import com.metaverse.coin.strategy.htx.service.HtxTrendEnsembleSignalCalculator;
import com.metaverse.coin.strategy.htx.service.HtxTrendEnsembleSignalCalculator.Direction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class HtxTrendFrozenSignalReplayTest {

    @TempDir
    Path directory;

    @Test
    void evaluatesOnlyTheSelectedCompletedBarWithProductionCalculators() throws Exception {
        Path input = directory.resolve("BTC-USDT_15min.json");
        Files.writeString(input, candles());
        Path manifest = manifest(input);
        HtxTrendFrozenDataset dataset = HtxTrendFrozenDataset.open(directory, manifest);

        HtxTrendFrozenSignalReplay.Observation observation = new HtxTrendFrozenSignalReplay().evaluate(
                dataset, "BTC-USDT", "15min", 139L * 900L, properties(), 32);

        assertEquals(Direction.LONG, observation.ensemble().direction());
        assertFalse(observation.combinations().isEmpty());
        assertEquals(HtxTrendFrozenSignalReplay.EXTERNAL_GATE_UNAVAILABLE, observation.executionGateEvidence());
    }

    @Test
    void streamsBatchObservationsAndSeparatesUnavailableExternalGates() throws Exception {
        Path input = directory.resolve("BTC-USDT_15min.json");
        Files.writeString(input, candles());
        HtxTrendFrozenDataset dataset = HtxTrendFrozenDataset.open(directory, manifest(input));
        List<HtxTrendFrozenSignalReplay.Observation> observations = new ArrayList<>();

        HtxTrendFrozenSignalReplay.BatchSummary result = new HtxTrendFrozenSignalReplay().replay(
                dataset, "15min", 138L * 900L, 139L * 900L, properties(), 32, observations::add);

        assertEquals(2L, result.evaluatedBars());
        assertEquals(2L, observations.size());
        assertEquals(2L, result.directionalBars());
        assertEquals(result.combinationCount(), result.externalGateUnavailableCount());
        assertEquals(2L, result.contracts().get("BTC-USDT").evaluatedBars());
    }

    @Test
    void usesTheSameTrailingKlineWindowAsTheProductionService() throws Exception {
        Path input = directory.resolve("BTC-USDT_15min.json");
        Files.writeString(input, candles());
        HtxTrendFrozenDataset dataset = HtxTrendFrozenDataset.open(directory, manifest(input));
        HtxTrendEnsembleShadowProperties properties = properties();
        properties.setKlineSize(100);

        HtxTrendFrozenSignalReplay.Observation replayed = new HtxTrendFrozenSignalReplay().evaluate(
                dataset, "BTC-USDT", "15min", 139L * 900L, properties, 32);
        List<com.metaverse.coin.strategy.common.client.htx.model.HtxSwapKline> candles = dataset.candles("BTC-USDT", "15min");
        HtxTrendEnsembleSignalCalculator.TrendCandidateSignal expected = new HtxTrendEnsembleSignalCalculator().evaluate(
                candles.subList(40, 140), properties, "15min", 140L * 900L + properties.getCandleCloseGraceSeconds());

        assertEquals(expected, replayed.ensemble());
    }

    private String candles() {
        StringBuilder rows = new StringBuilder("{\"status\":\"ok\",\"data\":[");
        for (int index = 0; index <= 140; index++) {
            if (index > 0) rows.append(',');
            BigDecimal close = index == 140 ? BigDecimal.ONE : BigDecimal.valueOf(101L + index);
            BigDecimal open = index == 140 ? BigDecimal.valueOf(2) : BigDecimal.valueOf(100L + index);
            rows.append("{\"id\":").append(index * 900L)
                    .append(",\"open\":").append(open)
                    .append(",\"high\":").append(open.max(close).add(BigDecimal.ONE))
                    .append(",\"low\":").append(open.min(close).subtract(new BigDecimal("0.5")))
                    .append(",\"close\":").append(close)
                    .append(",\"amount\":10,\"vol\":10,\"trade_turnover\":1000,\"count\":1}");
        }
        return rows.append("]}").toString();
    }

    private Path manifest(Path input) throws IOException {
        Path manifest = directory.resolve("dataset-manifest.json");
        Files.writeString(manifest, """
                {"schema":"htx-trend-research-dataset-manifest-v1","sourceKind":"FROZEN_PUBLIC_KLINE","datasetKey":"research:test",
                "inputIntegrity":"COMPLETE","inputs":[{"contract":"BTC-USDT","period":"15min",
                "file":"BTC-USDT_15min.json","sha256":"%s","count":141,"firstBarTime":0,
                "lastBarTime":126000,"sourceKind":"RAW_PUBLIC","coverage":"COMPLETE"}]}
                """.formatted(sha256(input)));
        return manifest;
    }

    private HtxTrendEnsembleShadowProperties properties() {
        HtxTrendEnsembleShadowProperties value = new HtxTrendEnsembleShadowProperties();
        value.setFastEmaPeriod(8); value.setMediumEmaPeriod(21); value.setSlowEmaPeriod(55);
        value.setBreakoutLookback(20); value.setMomentumLookback(8); value.setAtrPeriod(5); value.setAdxPeriod(5);
        value.setVolumeLookback(20); value.setMinAtrPct(new BigDecimal("0.0001")); value.setMinAdx(BigDecimal.ONE);
        value.setMinMomentumPct(new BigDecimal("0.001")); value.setMinVolumeRatio(BigDecimal.ONE);
        value.setEntryScoreThreshold(new BigDecimal("60")); value.setMinScoreGap(new BigDecimal("10"));
        return value;
    }

    private static String sha256(Path path) throws IOException {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
