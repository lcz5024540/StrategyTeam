package com.metaverse.coin.strategy.htx.replay;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HtxTrendFrozenSignalReplayCommandTest {

    @TempDir
    Path directory;

    @Test
    void writesStreamingObservationsAndASeparateSummary() throws Exception {
        Path input = directory.resolve("BTC-USDT_15min.json");
        Files.writeString(input, candles());
        Path manifest = manifest(input);
        Path output = directory.resolve("out/observations.jsonl");

        HtxTrendFrozenSignalReplayCommand.Result result = HtxTrendFrozenSignalReplayCommand.run(
                HtxTrendFrozenSignalReplayCommand.Arguments.parse(new String[]{
                        "--data-dir=" + directory,
                        "--manifest=" + manifest,
                        "--contracts=BTC-USDT",
                        "--period=15min",
                        "--start=" + (138L * 900L),
                        "--end=" + (139L * 900L),
                        "--output=" + output}));

        assertEquals(2L, result.batchSummary().evaluatedBars());
        assertEquals(3, Files.readAllLines(output).size());
        JSONObject header = JSON.parseObject(Files.readAllLines(output).get(0));
        assertEquals("HEADER", header.getString("recordType"));
        assertEquals("SIGNAL_AND_COMBINATION_ONLY", header.getString("executionScope"));
        assertEquals("INSUFFICIENT_EVIDENCE: historical ensemble configuration was not frozen; code defaults were used",
                header.getString("calculatorConfigurationEvidence"));
        JSONObject summary = JSON.parseObject(Files.readString(result.summary()));
        assertEquals(2L, summary.getLongValue("evaluatedBars"));
        assertEquals(HtxTrendFrozenSignalReplay.EXTERNAL_GATE_UNAVAILABLE,
                summary.getString("executionGateEvidence"));
    }

    @Test
    void refusesAnImplicitUniverseWideReplay() {
        assertThrows(IllegalArgumentException.class, () -> HtxTrendFrozenSignalReplayCommand.Arguments.parse(new String[]{
                "--data-dir=/tmp/data", "--manifest=/tmp/manifest", "--period=15min", "--start=1", "--end=2", "--output=/tmp/out"}));
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
}
