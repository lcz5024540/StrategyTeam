package com.metaverse.coin.strategy.htx.replay;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable execution facts required by BT-01 and the outcome layer of BT-02A.
 *
 * <p>Public K-lines can reproduce a signal but cannot tell us what the historical account could have
 * traded. This reader makes those missing facts an explicit, versioned input. It is deliberately not
 * a database importer and has no HTX/network dependency. An incomplete document remains readable so
 * callers can write an auditable {@code INSUFFICIENT_EVIDENCE} result, but cannot produce trade metrics.</p>
 */
public final class HtxTrendFrozenExecutionEvidence {

    public static final String SCHEMA = "htx-trend-frozen-execution-evidence-v1";
    public static final String SOURCE_KIND = "FROZEN_EXECUTION_FACTS";

    private HtxTrendFrozenExecutionEvidence() {
    }

    public static Document open(Path path, String expectedDatasetKey) throws IOException {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("BT-01 requires an immutable execution-evidence document");
        }
        JSONObject root = JSON.parseObject(Files.readString(path));
        if (root == null || !SCHEMA.equals(root.getString("schema")) || !SOURCE_KIND.equals(root.getString("sourceKind"))) {
            throw new IllegalArgumentException("unsupported frozen execution-evidence schema");
        }
        String datasetKey = required(root, "datasetKey");
        if (expectedDatasetKey != null && !expectedDatasetKey.equals(datasetKey)) {
            throw new IllegalArgumentException("execution evidence datasetKey does not match frozen K-line dataset");
        }
        boolean complete = "COMPLETE".equals(root.getString("inputIntegrity")) && root.getBooleanValue("universeComplete");
        JSONArray rows = root.getJSONArray("trades");
        List<Trade> trades = new ArrayList<>();
        if (rows != null) {
            for (int index = 0; index < rows.size(); index++) {
                trades.add(Trade.from(rows.getJSONObject(index), complete));
            }
        }
        String reason = complete && !trades.isEmpty() ? null : root.getString("incompleteReason");
        if (!complete && (reason == null || reason.isBlank())) {
            reason = "historical candidate universe, final gate, preflight, fills or v4 exit inputs are incomplete";
        }
        return new Document(datasetKey, complete, required(root, "evidenceKey"), List.copyOf(trades), reason);
    }

    private static String required(JSONObject row, String field) {
        String value = row == null ? null : row.getString(field);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("execution evidence requires " + field);
        return value;
    }

    private static BigDecimal positive(JSONObject row, String field) {
        BigDecimal value = row == null ? null : row.getBigDecimal(field);
        if (value == null || value.signum() <= 0) throw new IllegalArgumentException("execution evidence requires positive " + field);
        return value;
    }

    private static long positiveLong(JSONObject row, String field) {
        Long value = row == null ? null : row.getLong(field);
        if (value == null || value <= 0L) throw new IllegalArgumentException("execution evidence requires positive " + field);
        return value;
    }

    public record Document(String datasetKey, boolean complete, String evidenceKey, List<Trade> trades,
                           String incompleteReason) {
    }

    /** One candidate with its historical final gate, preflight, fills and exit evidence. */
    public record Trade(String runId, String contractCode, String period, long entryBarTime, String primaryModel,
                        String direction, String finalGate, String finalGateEvidenceRef, String preflightEvidenceRef,
                        Map<String, String> gateOutcomes, Fill entryFill, List<Fill> exitFills, List<JSONObject> exitInputs) {
        private static Trade from(JSONObject row, boolean strict) {
            if (row == null) throw new IllegalArgumentException("execution evidence trade must be an object");
            String runId = required(row, "runId");
            String contract = required(row, "contractCode");
            String period = required(row, "period");
            long barTime = positiveLong(row, "entryBarTime");
            String model = required(row, "primaryModel");
            String direction = required(row, "direction");
            JSONObject finalGate = row.getJSONObject("finalGate");
            JSONObject preflight = row.getJSONObject("preflight");
            JSONObject entry = row.getJSONObject("entryFill");
            JSONArray exits = row.getJSONArray("exitFills");
            JSONArray decisions = row.getJSONArray("exitInputs");
            if (!strict) {
                return new Trade(runId, contract, period, barTime, model, direction, null, null, null, Map.of(), null, List.of(), List.of());
            }
            if (finalGate == null || !"PASSED".equals(finalGate.getString("result"))) {
                throw new IllegalArgumentException("complete execution evidence requires a PASSED finalGate");
            }
            if (preflight == null || blank(preflight.getString("feeProfileRef")) || blank(preflight.getString("orderBookRef"))
                    || blank(preflight.getString("marginRef")) || blank(preflight.getString("leverageRef"))
                    || blank(preflight.getString("positionStateRef"))) {
                throw new IllegalArgumentException("complete execution evidence requires frozen fee, book, margin, leverage and position facts");
            }
            if (exits == null || exits.isEmpty() || decisions == null || decisions.isEmpty()) {
                throw new IllegalArgumentException("complete execution evidence requires exit fills and v4 exit inputs");
            }
            JSONObject gates = row.getJSONObject("gateOutcomes");
            Map<String, String> gateOutcomes = new LinkedHashMap<>();
            for (String profile : List.of("COMBINATION_ONLY", "CURRENT_PRODUCTION_COMBINATION_GATE",
                    "CURRENT_PLUS_MULTITIMEFRAME_DIRECTION", "COUNTER_MARKET_RISK_ONLY")) {
                String outcome = gates == null ? null : gates.getString(profile);
                if (!"PASSED".equals(outcome) && !"REJECTED".equals(outcome)) {
                    throw new IllegalArgumentException("complete execution evidence requires a reproducible " + profile + " outcome");
                }
                gateOutcomes.put(profile, outcome);
            }
            List<Fill> exitFills = new ArrayList<>();
            for (int index = 0; index < exits.size(); index++) exitFills.add(Fill.from(exits.getJSONObject(index)));
            List<JSONObject> exitInputs = new ArrayList<>();
            for (int index = 0; index < decisions.size(); index++) {
                JSONObject input = decisions.getJSONObject(index);
                if (input == null) throw new IllegalArgumentException("complete execution evidence contains a null v4 exit input");
                exitInputs.add(input);
            }
            return new Trade(runId, contract, period, barTime, model, direction, "PASSED",
                    required(finalGate, "evidenceRef"), preflight.getString("orderBookRef"),
                    Map.copyOf(gateOutcomes), Fill.from(entry), List.copyOf(exitFills), List.copyOf(exitInputs));
        }
    }

    /** A real or paper fill with an exchange/order identity, so no candle close can be silently substituted. */
    public record Fill(String orderId, BigDecimal price, long contracts, BigDecimal fee) {
        private static Fill from(JSONObject row) {
            return new Fill(required(row, "orderId"), positive(row, "price"), positiveLong(row, "contracts"),
                    nonNegative(row, "fee"));
        }
    }

    private static BigDecimal nonNegative(JSONObject row, String field) {
        BigDecimal value = row == null ? null : row.getBigDecimal(field);
        if (value == null || value.signum() < 0) throw new IllegalArgumentException("execution evidence requires non-negative " + field);
        return value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
