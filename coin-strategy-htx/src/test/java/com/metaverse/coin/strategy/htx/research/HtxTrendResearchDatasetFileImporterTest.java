package com.metaverse.coin.strategy.htx.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.htx.persistence.HtxTrendResearchDatasetFreezeRequest;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HtxTrendResearchDatasetFileImporterTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void reads_only_a_complete_checksum_verified_manifest() throws Exception {
        Path directory = Files.createTempDirectory("htx-research-import");
        Path candles = directory.resolve("BTC-USDT_15min.json");
        Files.writeString(candles, "{\"status\":\"ok\",\"data\":[{\"id\":1788192000,\"open\":\"100\",\"high\":\"101\",\"low\":\"99\",\"close\":\"100.5\",\"vol\":\"2\",\"trade_turnover\":\"200\",\"count\":2}]}" );
        Path manifest = directory.resolve("dataset-manifest.json");
        JSON.writeValue(manifest.toFile(), Map.of(
                "schema", "htx-trend-research-dataset-manifest-v1", "datasetKey", "research:test",
                "inputChecksum", "dataset-checksum", "inputIntegrity", "COMPLETE",
                "inputStartAt", "2026-09-01T00:00:00", "inputEndAt", "2026-09-01T00:15:00",
                "contracts", new String[]{"BTC-USDT"}, "periods", new String[]{"15min"},
                "inputs", new Object[]{Map.of("contract", "BTC-USDT", "period", "15min", "file", candles.getFileName().toString(),
                        "sourceKind", "RAW_PUBLIC", "sha256", sha256(candles), "count", 1,
                        "firstBarTime", 1788192000L, "lastBarTime", 1788192000L)}));

        HtxTrendResearchDatasetFreezeRequest request = HtxTrendResearchDatasetFileImporter.readCompleteRequest(directory, manifest);

        assertEquals("research:test", request.datasetKey());
        assertEquals("COMPLETE", request.inputIntegrity());
        assertEquals(1, request.candles().size());
        assertEquals("BTC-USDT", request.candles().get(0).getContractCode());
    }

    @Test
    void rejects_a_partial_manifest_before_any_ledger_write() throws Exception {
        Path directory = Files.createTempDirectory("htx-research-import");
        Path manifest = directory.resolve("dataset-manifest.json");
        Files.writeString(manifest, "{\"schema\":\"htx-trend-research-dataset-manifest-v1\",\"datasetKey\":\"research:test\",\"inputChecksum\":\"x\",\"inputIntegrity\":\"PARTIAL\",\"contracts\":[\"BTC-USDT\"],\"periods\":[\"15min\"],\"inputs\":[]}");

        assertThrows(IllegalArgumentException.class,
                () -> HtxTrendResearchDatasetFileImporter.readCompleteRequest(directory, manifest));
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
