package com.metaverse.coin.strategy.htx.replay;

import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapKline;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HtxTrendFrozenDatasetTest {

    @TempDir
    Path directory;

    @Test
    void mapsValidatedFrozenKlinesToTheProductionCalculatorType() throws Exception {
        Path data = directory.resolve("BTC-USDT_15min.json");
        Files.writeString(data, """
                {"status":"ok","data":[
                  {"id":200,"open":102,"high":104,"low":101,"close":103,"amount":12,"vol":12,"trade_turnover":1236,"count":4},
                  {"id":100,"open":100,"high":103,"low":99,"close":102,"amount":10,"vol":10,"trade_turnover":1020,"count":3}
                ]}
                """);
        Path manifest = manifest(data, "COMPLETE", sha256(data));

        HtxTrendFrozenDataset dataset = HtxTrendFrozenDataset.open(directory, manifest);
        List<HtxSwapKline> candles = dataset.candles("BTC-USDT", "15min");

        assertEquals("research:test", dataset.datasetKey());
        assertEquals(List.of("BTC-USDT"), dataset.contracts());
        assertEquals(List.of("15min"), dataset.periods("BTC-USDT"));
        assertEquals(List.of(100L, 200L), candles.stream().map(HtxSwapKline::getId).toList());
        assertEquals("102", candles.get(0).getClose().toPlainString());
        assertEquals("1236", candles.get(1).getTradeTurnover().toPlainString());
    }

    @Test
    void rejectsPartialDatasetBeforeAnyCandleIsRead() throws Exception {
        Path data = directory.resolve("BTC-USDT_15min.json");
        Files.writeString(data, "{\"status\":\"ok\",\"data\":[]}");

        assertThrows(IllegalArgumentException.class,
                () -> HtxTrendFrozenDataset.open(directory, manifest(data, "PARTIAL", sha256(data))));
    }

    @Test
    void rejectsTamperedInputInsteadOfUsingItForReplay() throws Exception {
        Path data = directory.resolve("BTC-USDT_15min.json");
        Files.writeString(data, """
                {"status":"ok","data":[
                  {"id":100,"open":100,"high":103,"low":99,"close":102,"amount":10,"vol":10,"trade_turnover":1020,"count":3},
                  {"id":200,"open":102,"high":104,"low":101,"close":103,"amount":12,"vol":12,"trade_turnover":1236,"count":4}
                ]}
                """);
        Path manifest = manifest(data, "COMPLETE", sha256(data));
        Files.writeString(data, Files.readString(data).replace("103", "109"));

        HtxTrendFrozenDataset dataset = HtxTrendFrozenDataset.open(directory, manifest);
        assertThrows(IllegalStateException.class, () -> dataset.candles("BTC-USDT", "15min"));
    }

    private Path manifest(Path input, String integrity, String checksum) throws IOException {
        Path manifest = directory.resolve("dataset-manifest.json");
        Files.writeString(manifest, """
                {"schema":"htx-trend-research-dataset-manifest-v1","sourceKind":"FROZEN_PUBLIC_KLINE","datasetKey":"research:test",
                "inputIntegrity":"%s","inputs":[{"contract":"BTC-USDT","period":"15min",
                "file":"BTC-USDT_15min.json","sha256":"%s","count":2,"firstBarTime":100,
                "lastBarTime":200,"sourceKind":"RAW_PUBLIC","coverage":"COMPLETE"}]}
                """.formatted(integrity, checksum));
        return manifest;
    }

    private static String sha256(Path path) throws IOException {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
