package com.metaverse.coin.strategy.htx.replay;

import com.metaverse.coin.strategy.htx.config.HtxTrendEnsembleShadowProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class HtxTrendFrozenGateAblationTest {

    @TempDir
    Path directory;

    @Test
    void keepsKlineOnlyGateEvidenceSeparateFromTradeOutcomeEvidence() throws Exception {
        Path input = directory.resolve("BTC-USDT_15min.json");
        Files.writeString(input, candles());
        HtxTrendFrozenDataset dataset = HtxTrendFrozenDataset.open(directory, manifest(input));
        HtxTrendFrozenGateAblation.Result result = new HtxTrendFrozenGateAblation().replay(dataset,
                List.of("BTC-USDT"), "15min", 138L * 900L, 139L * 900L, properties(), 32,
                new HtxTrendFrozenGateAblation.Configuration(1, new BigDecimal("0.70"), new BigDecimal("0.15"),
                        List.of("30min")));

        HtxTrendFrozenGateAblation.Summary current = result.summaries().get(
                HtxTrendFrozenGateAblation.Profile.CURRENT_PRODUCTION_COMBINATION_GATE);
        HtxTrendFrozenGateAblation.Summary riskOnly = result.summaries().get(
                HtxTrendFrozenGateAblation.Profile.COUNTER_MARKET_RISK_ONLY);
        assertEquals(current, riskOnly);
        assertFalse(result.evidenceBoundary().isBlank());
        assertFalse(result.decisions().isEmpty());
        assertEquals("UNAVAILABLE", result.decisions().stream()
                .filter(value -> value.profile() == HtxTrendFrozenGateAblation.Profile.CURRENT_PLUS_MULTITIMEFRAME_DIRECTION)
                .findFirst().orElseThrow().outcome().state());
    }

    private String candles() {
        StringBuilder rows = new StringBuilder("{\"status\":\"ok\",\"data\":[");
        for (int index = 0; index <= 140; index++) {
            if (index > 0) {
                rows.append(',');
            }
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

    private Path manifest(Path input) throws Exception {
        Path manifest = directory.resolve("dataset-manifest.json");
        Files.writeString(manifest, """
                {"schema":"htx-trend-research-dataset-manifest-v1","sourceKind":"FROZEN_PUBLIC_KLINE","datasetKey":"research:test",
                "inputIntegrity":"COMPLETE","inputs":[{"contract":"BTC-USDT","period":"15min",
                "file":"BTC-USDT_15min.json","sha256":"%s","count":141,"firstBarTime":0,
                "lastBarTime":126000,"sourceKind":"RAW_PUBLIC","coverage":"COMPLETE"}]}
                """.formatted(java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(input)))));
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
}
