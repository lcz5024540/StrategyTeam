package com.metaverse.coin.strategy.htx.replay;

import com.alibaba.fastjson2.JSON;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline command for replaying production signal and combination calculations against frozen public K-lines.
 *
 * <p>The command has no Spring, database, account-key or HTX network dependency. It deliberately requires an
 * explicit contract list so research runs remain bounded and their inputs are explicit. Each evaluation uses the
 * same trailing K-line window size as the production service.</p>
 */
public final class HtxTrendFrozenSignalReplayCommand {

    private static final String OBSERVATION_SCHEMA = "htx-trend-frozen-signal-observation-v1";
    private static final String SUMMARY_SCHEMA = "htx-trend-frozen-signal-summary-v1";
    private static final ZoneOffset SHANGHAI_OFFSET = ZoneOffset.ofHours(8);
    private static final int DEFAULT_BOX_WINDOW_BARS = 32;

    private HtxTrendFrozenSignalReplayCommand() {
    }

    public static void main(String[] args) throws Exception {
        Arguments arguments = Arguments.parse(args);
        Result result = run(arguments);
        System.out.println(JSON.toJSONString(Map.of(
                "output", result.output().toAbsolutePath().normalize().toString(),
                "summary", result.summary().toAbsolutePath().normalize().toString(),
                "evaluatedBars", result.batchSummary().evaluatedBars(),
                "combinationCount", result.batchSummary().combinationCount(),
                "externalGateUnavailableCount", result.batchSummary().externalGateUnavailableCount())));
    }

    static Result run(Arguments arguments) throws IOException {
        HtxTrendFrozenDataset dataset = HtxTrendFrozenDataset.open(arguments.dataDirectory(), arguments.manifest());
        HtxTrendFrozenReplayConfiguration.Configuration configuration = HtxTrendFrozenReplayConfiguration.open(
                arguments.configuration());
        HtxTrendFrozenSignalReplay replay = new HtxTrendFrozenSignalReplay();
        Path output = arguments.output().toAbsolutePath().normalize();
        Path summary = output.resolveSibling(output.getFileName() + ".summary.json");
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            write(writer, header(dataset, arguments, configuration));
            HtxTrendFrozenSignalReplay.BatchSummary batchSummary = replay.replay(dataset, arguments.contractCodes(),
                    arguments.period(), arguments.startBarTime(), arguments.endBarTime(), configuration.properties(),
                    configuration.boxWindowBars(), observation -> writeUnchecked(writer, observationRecord(observation, configuration)));
            Files.writeString(summary, JSON.toJSONString(summaryRecord(arguments, batchSummary, configuration)), StandardCharsets.UTF_8);
            return new Result(output, summary, batchSummary);
        }
    }

    private static void writeUnchecked(BufferedWriter writer, Map<String, Object> record) {
        try {
            write(writer, record);
        } catch (IOException exception) {
            throw new ReplayOutputException(exception);
        }
    }

    private static void write(BufferedWriter writer, Map<String, Object> record) throws IOException {
        writer.write(JSON.toJSONString(record));
        writer.newLine();
        // A stopped or deliberately bounded research run must still leave independently usable observations.
        writer.flush();
    }

    private static Map<String, Object> header(HtxTrendFrozenDataset dataset, Arguments arguments,
                                               HtxTrendFrozenReplayConfiguration.Configuration configuration) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("schema", OBSERVATION_SCHEMA);
        header.put("recordType", "HEADER");
        header.put("datasetKey", dataset.datasetKey());
        header.put("sourceKind", "FROZEN_PUBLIC_KLINE");
        header.put("contracts", arguments.contractCodes());
        header.put("period", arguments.period());
        header.put("startBarTime", arguments.startBarTime());
        header.put("endBarTime", arguments.endBarTime());
        header.put("configurationKey", configuration.configurationKey());
        header.put("configurationSourceKind", configuration.sourceKind());
        header.put("configurationSourceReference", configuration.sourceReference());
        header.put("configurationSha256", configuration.sha256());
        header.put("boxWindowBars", configuration.boxWindowBars());
        header.put("executionScope", "SIGNAL_AND_COMBINATION_ONLY");
        header.put("calculatorConfigurationEvidence", configuration.configurationEvidence());
        header.put("executionGateEvidence", HtxTrendFrozenSignalReplay.EXTERNAL_GATE_UNAVAILABLE);
        return header;
    }

    private static Map<String, Object> observationRecord(HtxTrendFrozenSignalReplay.Observation observation,
                                                           HtxTrendFrozenReplayConfiguration.Configuration configuration) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("schema", OBSERVATION_SCHEMA);
        record.put("recordType", "OBSERVATION");
        record.put("datasetKey", observation.datasetKey());
        record.put("contract", observation.contractCode());
        record.put("period", observation.period());
        record.put("barTime", observation.barTime());
        record.put("barClosedAt", Instant.ofEpochSecond(observation.barTime()).atOffset(SHANGHAI_OFFSET).toString());
        record.put("ensemble", observation.ensemble());
        record.put("combinations", observation.combinations());
        record.put("configurationKey", configuration.configurationKey());
        record.put("configurationSourceReference", configuration.sourceReference());
        record.put("configurationSha256", configuration.sha256());
        record.put("calculatorConfigurationEvidence", configuration.configurationEvidence());
        record.put("executionGateEvidence", observation.executionGateEvidence());
        return record;
    }

    private static Map<String, Object> summaryRecord(Arguments arguments,
                                                       HtxTrendFrozenSignalReplay.BatchSummary batchSummary,
                                                       HtxTrendFrozenReplayConfiguration.Configuration configuration) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("schema", SUMMARY_SCHEMA);
        record.put("datasetKey", batchSummary.datasetKey());
        record.put("contracts", arguments.contractCodes());
        record.put("period", batchSummary.period());
        record.put("startBarTime", batchSummary.startBarTime());
        record.put("endBarTime", batchSummary.endBarTime());
        record.put("evaluatedBars", batchSummary.evaluatedBars());
        record.put("directionalBars", batchSummary.directionalBars());
        record.put("combinationCount", batchSummary.combinationCount());
        record.put("externalGateUnavailableCount", batchSummary.externalGateUnavailableCount());
        record.put("contractsSummary", batchSummary.contracts());
        record.put("executionScope", "SIGNAL_AND_COMBINATION_ONLY");
        record.put("configurationKey", configuration.configurationKey());
        record.put("configurationSourceKind", configuration.sourceKind());
        record.put("configurationSourceReference", configuration.sourceReference());
        record.put("configurationSha256", configuration.sha256());
        record.put("calculatorConfigurationEvidence", configuration.configurationEvidence());
        record.put("executionGateEvidence", HtxTrendFrozenSignalReplay.EXTERNAL_GATE_UNAVAILABLE);
        return record;
    }

    public record Arguments(Path dataDirectory, Path manifest, List<String> contractCodes, String period,
                            long startBarTime, long endBarTime, Path configuration, Path output) {
        public Arguments {
            if (dataDirectory == null || manifest == null || contractCodes == null || contractCodes.isEmpty()
                    || period == null || period.isBlank() || startBarTime > endBarTime || configuration == null || output == null) {
                throw new IllegalArgumentException("data directory, manifest, contracts, period, ordered window, configuration and output are required");
            }
            contractCodes = List.copyOf(contractCodes);
        }

        static Arguments parse(String[] args) {
            Map<String, String> values = new LinkedHashMap<>();
            for (String arg : args) {
                if (arg == null || !arg.startsWith("--") || !arg.contains("=")) {
                    throw new IllegalArgumentException(usage());
                }
                String[] pair = arg.substring(2).split("=", 2);
                if (values.putIfAbsent(pair[0], pair[1]) != null) {
                    throw new IllegalArgumentException("duplicate argument: --" + pair[0]);
                }
            }
            String contracts = required(values, "contracts");
            List<String> contractCodes = List.of(contracts.split(",")).stream()
                    .map(String::trim).filter(value -> !value.isEmpty()).toList();
            return new Arguments(Path.of(required(values, "data-dir")), Path.of(required(values, "manifest")),
                    contractCodes, required(values, "period"), parseBarTime(required(values, "start")),
                    parseBarTime(required(values, "end")), Path.of(required(values, "configuration")),
                    Path.of(required(values, "output")));
        }

        private static long parseBarTime(String value) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException ignored) {
                return OffsetDateTime.parse(value).toEpochSecond();
            }
        }

        private static String required(Map<String, String> values, String key) {
            String value = values.get(key);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("missing --" + key + "; " + usage());
            }
            return value;
        }

        private static String usage() {
            return "usage: --data-dir=PATH --manifest=PATH --configuration=PATH --contracts=BTC-USDT,ETH-USDT "
                    + "--period=15min --start=2026-09-18T00:00:00+08:00 --end=2026-09-18T23:45:00+08:00 --output=PATH";
        }
    }

    public record Result(Path output, Path summary, HtxTrendFrozenSignalReplay.BatchSummary batchSummary) {
    }

    private static final class ReplayOutputException extends RuntimeException {
        private ReplayOutputException(IOException cause) {
            super(cause);
        }
    }
}
