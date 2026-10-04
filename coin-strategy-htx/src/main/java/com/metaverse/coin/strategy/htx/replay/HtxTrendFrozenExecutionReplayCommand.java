package com.metaverse.coin.strategy.htx.replay;

import com.alibaba.fastjson2.JSON;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Offline entry point for BT-01. It cannot call HTX, a database or a live policy service. */
public final class HtxTrendFrozenExecutionReplayCommand {
    private HtxTrendFrozenExecutionReplayCommand() {
    }

    public static void main(String[] args) throws Exception {
        Result result = run(Arguments.parse(args));
        System.out.println(JSON.toJSONString(Map.of("output", result.output().toAbsolutePath().normalize().toString(),
                "evidenceState", result.result().evidenceState(), "tradeCount", result.result().outcomes().size())));
    }

    static Result run(Arguments arguments) throws IOException {
        HtxTrendFrozenDataset dataset = HtxTrendFrozenDataset.open(arguments.dataDirectory(), arguments.manifest());
        HtxTrendFrozenReplayConfiguration.Configuration configuration = HtxTrendFrozenReplayConfiguration.open(arguments.configuration());
        HtxTrendFrozenExecutionEvidence.Document evidence = HtxTrendFrozenExecutionEvidence.open(arguments.evidence(), dataset.datasetKey());
        HtxTrendFrozenExecutionReplay.Result replay = new HtxTrendFrozenExecutionReplay().replay(dataset, evidence,
                configuration.properties(), configuration.boxWindowBars());
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema", "htx-trend-same-source-execution-replay-v1");
        report.put("datasetKey", replay.datasetKey()); report.put("evidenceKey", replay.evidenceKey());
        report.put("configurationKey", configuration.configurationKey()); report.put("configurationSha256", configuration.sha256());
        report.put("executionScope", "PRODUCTION_SIGNAL_AND_V4_EXIT_WITH_FROZEN_EXECUTION_FACTS");
        report.put("evidenceState", replay.evidenceState()); report.put("reason", replay.reason());
        report.put("outcomes", replay.outcomes()); report.put("summaries", replay.summaries());
        report.put("gateAblations", replay.gateAblations());
        report.put("policyDecision", "FORBIDDEN: this replay cannot mutate a Dashboard policy or enable live execution");
        Path output = arguments.output().toAbsolutePath().normalize();
        if (output.getParent() != null) Files.createDirectories(output.getParent());
        Files.writeString(output, JSON.toJSONString(report));
        return new Result(output, replay);
    }

    record Arguments(Path dataDirectory, Path manifest, Path configuration, Path evidence, Path output) {
        static Arguments parse(String[] args) {
            Map<String, String> values = new LinkedHashMap<>();
            for (String arg : args) {
                if (arg == null || !arg.startsWith("--") || !arg.contains("=")) throw new IllegalArgumentException(usage());
                String[] pair = arg.substring(2).split("=", 2);
                if (values.putIfAbsent(pair[0], pair[1]) != null) throw new IllegalArgumentException("duplicate argument: --" + pair[0]);
            }
            return new Arguments(Path.of(required(values, "data-dir")), Path.of(required(values, "manifest")),
                    Path.of(required(values, "configuration")), Path.of(required(values, "evidence")), Path.of(required(values, "output")));
        }
        private static String required(Map<String, String> values, String key) {
            String value = values.get(key); if (value == null || value.isBlank()) throw new IllegalArgumentException("missing --" + key + "; " + usage()); return value;
        }
        private static String usage() { return "usage: --data-dir=PATH --manifest=PATH --configuration=PATH --evidence=PATH --output=PATH"; }
    }

    record Result(Path output, HtxTrendFrozenExecutionReplay.Result result) {
    }
}
