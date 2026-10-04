package com.metaverse.coin.strategy.htx.replay;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Standalone BT-02A command. It reads only frozen K-lines and writes diagnostic JSONL plus a summary. */
public final class HtxTrendFrozenGateAblationCommand {

    private static final String HEADER_SCHEMA = "htx-trend-frozen-gate-ablation-v1";
    private static final String SUMMARY_SCHEMA = "htx-trend-frozen-gate-ablation-summary-v1";

    private HtxTrendFrozenGateAblationCommand() {
    }

    public static void main(String[] args) throws Exception {
        run(Arguments.parse(args));
    }

    static Result run(Arguments arguments) throws IOException {
        HtxTrendFrozenDataset dataset = HtxTrendFrozenDataset.open(arguments.dataDirectory(), arguments.manifest());
        HtxTrendFrozenReplayConfiguration.Configuration configuration = HtxTrendFrozenReplayConfiguration.open(
                arguments.configuration());
        HtxTrendFrozenGateAblation.Configuration gateConfiguration = gateConfiguration(arguments.configuration());
        HtxTrendFrozenGateAblation.Result result = new HtxTrendFrozenGateAblation().replay(dataset,
                arguments.contractCodes(), arguments.period(), arguments.startBarTime(), arguments.endBarTime(),
                configuration.properties(), configuration.boxWindowBars(), gateConfiguration);
        Files.createDirectories(arguments.output().toAbsolutePath().normalize().getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(arguments.output(), StandardCharsets.UTF_8)) {
            writer.write(JSON.toJSONString(header(dataset, arguments, configuration, gateConfiguration)));
            writer.newLine();
            for (HtxTrendFrozenGateAblation.Decision decision : result.decisions()) {
                writer.write(JSON.toJSONString(decision));
                writer.newLine();
            }
        }
        Path summary = Path.of(arguments.output().toString() + ".summary.json");
        Files.writeString(summary, JSON.toJSONString(summary(result, configuration, gateConfiguration)));
        return new Result(arguments.output(), summary, result);
    }

    private static HtxTrendFrozenGateAblation.Configuration gateConfiguration(Path path) throws IOException {
        JSONObject root = JSON.parseObject(Files.readString(path));
        JSONObject gate = root == null ? null : root.getJSONObject("gateAblation");
        if (gate == null || !"htx-trend-frozen-gate-ablation-v1".equals(gate.getString("schema"))) {
            throw new IllegalArgumentException("frozen replay configuration must include an explicit gateAblation section");
        }
        if (!"COMPLETE".equals(gate.getString("inputIntegrity"))) {
            throw new IllegalArgumentException("gateAblation configuration must declare COMPLETE inputIntegrity");
        }
        List<String> periods = gate.getList("directionConfirmationPeriods", String.class);
        return new HtxTrendFrozenGateAblation.Configuration(requiredPositive(gate, "minimumBreadthSymbols"),
                requiredDecimal(gate, "breadthThreshold"), requiredDecimal(gate, "oppositeBreadthMaximum"), periods);
    }

    private static Map<String, Object> header(HtxTrendFrozenDataset dataset, Arguments arguments,
                                              HtxTrendFrozenReplayConfiguration.Configuration configuration,
                                              HtxTrendFrozenGateAblation.Configuration gateConfiguration) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schema", HEADER_SCHEMA);
        value.put("recordType", "HEADER");
        value.put("datasetKey", dataset.datasetKey());
        value.put("contracts", arguments.contractCodes());
        value.put("period", arguments.period());
        value.put("startBarTime", arguments.startBarTime());
        value.put("endBarTime", arguments.endBarTime());
        value.put("configurationKey", configuration.configurationKey());
        value.put("configurationSha256", configuration.sha256());
        value.put("configurationEvidence", configuration.configurationEvidence());
        value.put("gateAblation", gateConfiguration);
        value.put("executionScope", "KLINE_ONLY_GATE_ABLATION");
        value.put("evidenceBoundary", HtxTrendFrozenGateAblation.KLINE_ONLY_EVIDENCE);
        return value;
    }

    private static Map<String, Object> summary(HtxTrendFrozenGateAblation.Result result,
                                               HtxTrendFrozenReplayConfiguration.Configuration configuration,
                                               HtxTrendFrozenGateAblation.Configuration gateConfiguration) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schema", SUMMARY_SCHEMA);
        value.put("datasetKey", result.datasetKey());
        value.put("entryPeriod", result.entryPeriod());
        value.put("startBarTime", result.startBarTime());
        value.put("endBarTime", result.endBarTime());
        value.put("evaluatedBars", result.evaluatedBars());
        value.put("profiles", result.summaries());
        value.put("configurationKey", configuration.configurationKey());
        value.put("configurationSha256", configuration.sha256());
        value.put("gateAblation", gateConfiguration);
        value.put("executionScope", "KLINE_ONLY_GATE_ABLATION");
        value.put("dataIntegrity", "INSUFFICIENT_EVIDENCE");
        value.put("evidenceBoundary", result.evidenceBoundary());
        value.put("policyDecision", "FORBIDDEN: no historical execution inputs or comparable outcomes");
        return value;
    }

    private static int requiredPositive(JSONObject input, String field) {
        Integer value = input.getInteger(field);
        if (value == null || value < 1) {
            throw new IllegalArgumentException("gateAblation requires positive " + field);
        }
        return value;
    }

    private static java.math.BigDecimal requiredDecimal(JSONObject input, String field) {
        java.math.BigDecimal value = input.getBigDecimal(field);
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException("gateAblation requires non-negative " + field);
        }
        return value;
    }

    record Arguments(Path dataDirectory, Path manifest, List<String> contractCodes, String period,
                     long startBarTime, long endBarTime, Path configuration, Path output) {
        Arguments {
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
            List<String> contracts = List.of(required(values, "contracts").split(",")).stream()
                    .map(String::trim).filter(value -> !value.isEmpty()).toList();
            return new Arguments(Path.of(required(values, "data-dir")), Path.of(required(values, "manifest")), contracts,
                    required(values, "period"), parseBarTime(required(values, "start")), parseBarTime(required(values, "end")),
                    Path.of(required(values, "configuration")), Path.of(required(values, "output")));
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

    record Result(Path output, Path summary, HtxTrendFrozenGateAblation.Result result) {
    }
}
