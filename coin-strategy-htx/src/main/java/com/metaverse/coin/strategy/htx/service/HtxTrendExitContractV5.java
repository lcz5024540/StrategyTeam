package com.metaverse.coin.strategy.htx.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Versioned DTOs and canonical hashing for the side-effect-free trend exit v5 kernel. */
public final class HtxTrendExitContractV5 {
    public static final String VERSION = "trend-exit-v5";
    private static final ObjectMapper JSON = new ObjectMapper();
    private HtxTrendExitContractV5() { }

    public enum Side { LONG, SHORT }
    public enum EventKind { TICK, CLOSED_BAR }
    public enum ModelState { VALID, DETERIORATING, INVALID, UNKNOWN }
    public enum ProfitPhase { BUILDING, ESTABLISHED, HARVEST, RUNNER, CLOSED }
    public enum ExecutionState { IDLE, INTENT, SENT, PARTIAL, CONFIRMED, UNKNOWN }
    public enum Action { HOLD, UPDATE_SOFT_BOUNDARY, PARTIAL_CLOSE, FULL_CLOSE, RECONCILE_REQUIRED }

    public record EntryThesis(long account_id, String strategy_instance, long position_epoch, String model_id,
                              String structure_id, Side side, long timeframe_seconds, long first_fill_time_ms,
                              BigDecimal entry_price, long filled_contracts, BigDecimal contract_size,
                              BigDecimal immutable_initial_stop, BigDecimal failure_zone, BigDecimal target_price,
                              BigDecimal atr_at_entry, BigDecimal entry_fee, String engine_version,
                              String contract_schema, String model_adapter_version, String policy_hash) {
        public String contractHash() { return canonicalHash(this); }
    }
    public record Policy(int maturity_bars, int structure_failure_confirm_bars, int soft_breach_confirm_bars,
                         BigDecimal arm_atr_multiplier, BigDecimal arm_cost_distance_multiple,
                         BigDecimal arm_net_cost_multiple, BigDecimal noise_atr_multiplier,
                         BigDecimal noise_min_atr, BigDecimal partial_fraction,
                         BigDecimal partial_net_cost_multiple, int max_partial_stages,
                         int no_extension_horizon_bars, long close_grace_ms) { }
    public record State(long revision, ProfitPhase phase, ExecutionState execution_state, Long last_bar_end_ms,
                        int eligible_bars, int structure_failure_bars, BigDecimal best_close,
                        BigDecimal active_soft_boundary, String active_boundary_id,
                        BigDecimal pending_soft_boundary, String pending_boundary_id, int soft_breach_bars,
                        Long armed_bar_end_ms, int partial_stage, boolean extension_achieved) {
        public static State initial() { return new State(0, ProfitPhase.BUILDING, ExecutionState.IDLE, null, 0, 0,
                null, null, null, null, null, 0, null, 0, false); }
    }
    public record Event(String event_key, EventKind kind, long event_time_ms, BigDecimal reference_price,
                        long confirmed_remaining_contracts, String data_quality, boolean ownership_confirmed,
                        Long bar_open_ms, Long bar_end_ms, BigDecimal close_price, BigDecimal atr_at_close,
                        BigDecimal current_net_pnl, BigDecimal round_trip_cost, ModelState model_state,
                        List<String> evidence_groups, boolean target_reached, BigDecimal partial_net_pnl) { }
    public record Decision(Action action, ProfitPhase phase, long target_remaining_contracts,
                           List<String> reason_codes, List<String> evidence_groups, String input_hash,
                           BigDecimal active_soft_boundary, BigDecimal pending_soft_boundary,
                           int eligible_bars, int soft_breach_bars) { }
    public record Transition(Decision decision, State state) { }

    public static Policy defaultPolicy() {
        return new Policy(2, 2, 2, new BigDecimal("1.0"), new BigDecimal("2.0"), new BigDecimal("2.0"),
                new BigDecimal("1.0"), new BigDecimal("0.5"), new BigDecimal("0.333333333333333333"),
                new BigDecimal("2.0"), 1, 16, 0);
    }
    public static String canonicalHash(Object value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonicalJson(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder(64);
            for (byte item : digest) output.append(String.format("%02x", item & 0xff));
            return output.toString();
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    public static String canonicalJson(Object value) {
        try { return JSON.writeValueAsString(normalize(value)); }
        catch (JsonProcessingException error) { throw new IllegalStateException("cannot canonicalize v5 contract", error); }
    }
    private static Object normalize(Object value) {
        if (value == null || value instanceof String || value instanceof Number && !(value instanceof BigDecimal)
                || value instanceof Boolean) return value;
        if (value instanceof BigDecimal decimal) return decimal.toPlainString();
        if (value instanceof Enum<?> item) return item.name();
        if (value instanceof Map<?, ?> map) {
            Map<String,Object> sorted=new TreeMap<>(); map.forEach((key,item)->sorted.put(String.valueOf(key),normalize(item))); return sorted;
        }
        if (value instanceof Collection<?> collection) { List<Object> out=new ArrayList<>(); collection.forEach(item->out.add(normalize(item))); return out; }
        if (value.getClass().isRecord()) {
            Map<String,Object> out=new LinkedHashMap<>();
            List<RecordComponent> parts=new ArrayList<>(List.of(value.getClass().getRecordComponents()));
            parts.sort(Comparator.comparing(RecordComponent::getName));
            for (RecordComponent part:parts) try { out.put(part.getName(),normalize(part.getAccessor().invoke(value))); }
            catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
            return out;
        }
        throw new IllegalArgumentException("unsupported canonical value "+value.getClass().getName());
    }
}
