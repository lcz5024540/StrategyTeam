package com.metaverse.coin.strategy.htx.replay;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Offline-only analysis command. It reads a frozen projection and never calls HTX, a database or live policy code. */
public final class HtxTrendAssessmentReplayCommand {
    private HtxTrendAssessmentReplayCommand() {
    }

    public static void main(String[] args) throws IOException {
        Arguments arguments = Arguments.parse(args);
        JSONObject input = JSON.parseObject(Files.readString(arguments.input()));
        if (input == null) {
            throw new IllegalArgumentException("input must be a JSON object");
        }
        String integrity = input.getString("dataIntegrity");
        JSONArray rawObservations = input.getJSONArray("observations");
        if (rawObservations == null) {
            throw new IllegalArgumentException("input observations must be a JSON array");
        }
        List<HtxTrendAssessmentReplayAnalyzer.PositionObservation> observations = new ArrayList<>();
        for (Object raw : rawObservations) {
            JSONObject value = (JSONObject) raw;
            observations.add(new HtxTrendAssessmentReplayAnalyzer.PositionObservation(
                    value.getString("primaryModel"), value.getString("direction"), value.getString("marketRegime"),
                    value.getString("timeframeProfile"), value.getBigDecimal("maxFavorableR"), value.getBigDecimal("mae"),
                    value.getBigDecimal("givebackRate"), value.getLongValue("holdingBars"),
                    value.getList("transitions", String.class), value.getBigDecimal("netPnl"),
                    value.getString("terminalState"), "PARTIAL".equals(value.getString("dataIntegrity")),
                    value.getBoolean("structureTargetReached"), value.getBoolean("invalidationReached")));
        }
        HtxTrendAssessmentReplayAnalyzer.Result result = new HtxTrendAssessmentReplayAnalyzer().analyze(integrity, observations);
        if (arguments.output().getParent() != null) Files.createDirectories(arguments.output().getParent());
        Files.writeString(arguments.output(), JSON.toJSONString(Map.of(
                "schema", "htx-trend-three-stage-assessment-replay-v1",
                "input", arguments.input().toAbsolutePath().normalize().toString(),
                "evidenceState", result.evidenceState(), "reason", result.reason(), "summaries", result.summaries(),
                "transitionMatrix", result.transitionMatrix(), "transitionOutcomes", result.transitionOutcomes(),
                "policyDecision", "FORBIDDEN: replay output cannot change live entry, sizing or exit switches"
        )));
    }

    record Arguments(Path input, Path output) {
        static Arguments parse(String[] args) {
            String input = null, output = null;
            for (String arg : args) {
                if (arg == null || !arg.startsWith("--") || !arg.contains("=")) throw new IllegalArgumentException(usage());
                String[] pair = arg.substring(2).split("=", 2);
                if ("input".equals(pair[0])) input = pair[1];
                else if ("output".equals(pair[0])) output = pair[1];
                else throw new IllegalArgumentException(usage());
            }
            if (input == null || input.isBlank() || output == null || output.isBlank()) throw new IllegalArgumentException(usage());
            return new Arguments(Path.of(input), Path.of(output));
        }
        private static String usage() { return "usage: --input=FROZEN_PROJECTION.json --output=REPORT.json"; }
    }
}
