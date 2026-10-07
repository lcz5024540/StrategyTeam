package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendStructureLifecycleEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendStructureLifecycleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Serializes one entry decision for one completed K-line cycle across scheduler runs and service restarts.
 * A database unique key, rather than an in-memory cache, is the source of truth.
 * A new completed K-line gets a new key and must independently pass all trend and execution gates.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HtxTrendStructureLifecycleService {
    public static final String FIB_PULLBACK_CONTINUATION = "FIB_PULLBACK_CONTINUATION";
    public static final String STRUCTURE_ALREADY_CONSUMED = "STRUCTURE_ALREADY_CONSUMED";
    public static final String STRUCTURE_LIFECYCLE_UNAVAILABLE = "STRUCTURE_LIFECYCLE_UNAVAILABLE";
    private final HtxTrendStructureLifecycleMapper mapper;

    /**
     * The breakout bar remains evidence for a Fibonacci candidate, but it is never the identity of a trade.
     * The completed entry candle (runId) defines the entry cycle, so a later candle starts a fresh assessment.
     */
    public static String entryCycleKey(String contractCode, String direction, String primaryModel, String runId) {
        if (contractCode == null || contractCode.isBlank() || direction == null || direction.isBlank()
                || primaryModel == null || primaryModel.isBlank() || runId == null || runId.isBlank()) {
            return null;
        }
        return contractCode + ":" + direction + ":" + primaryModel + ":ENTRY_CYCLE:" + runId;
    }

    /** A read-only early gate, used before expensive account and leverage operations. */
    public Decision availability(String structureKey) {
        if (structureKey == null || structureKey.isBlank()) {
            return Decision.notApplicable();
        }
        try {
            HtxTrendStructureLifecycleEntity existing = mapper.findByStructureKey(structureKey);
            if (existing == null || "ABANDONED".equals(existing.getState())) {
                return Decision.available();
            }
            return Decision.blocked(STRUCTURE_ALREADY_CONSUMED + " state=" + existing.getState()
                    + " entryPositionId=" + existing.getEntryPositionId());
        } catch (RuntimeException ex) {
            log.error("trend structure lifecycle availability query failed structureKey={}", structureKey, ex);
            return Decision.unavailable(STRUCTURE_LIFECYCLE_UNAVAILABLE);
        }
    }

    /** Atomically claims the structure just before a durable position intent is created. */
    public Decision claim(Claim claim) {
        if (claim == null || claim.structureKey() == null || claim.structureKey().isBlank()) {
            return Decision.notApplicable();
        }
        try {
            HtxTrendStructureLifecycleEntity entity = new HtxTrendStructureLifecycleEntity();
            entity.setStructureKey(claim.structureKey());
            entity.setContractCode(claim.contractCode());
            entity.setTimeframe(claim.timeframe());
            entity.setDirection(claim.direction());
            entity.setPrimaryModel(claim.primaryModel());
            entity.setBreakoutBarTime(claim.breakoutBarTime());
            entity.setSourceRunId(claim.sourceRunId());
            if (mapper.insertDiscovered(entity) == 1) {
                return Decision.claimedDecision();
            }
            HtxTrendStructureLifecycleEntity existing = mapper.findByStructureKey(claim.structureKey());
            if (existing != null && "ABANDONED".equals(existing.getState())
                    && mapper.reclaimAbandoned(claim.structureKey(), claim.sourceRunId()) == 1) {
                return Decision.claimedDecision();
            }
            return Decision.blocked(STRUCTURE_ALREADY_CONSUMED + " state="
                    + (existing == null ? "UNKNOWN" : existing.getState()) + " entryPositionId="
                    + (existing == null ? null : existing.getEntryPositionId()));
        } catch (RuntimeException ex) {
            log.error("trend structure lifecycle claim failed structureKey={}", claim.structureKey(), ex);
            return Decision.unavailable(STRUCTURE_LIFECYCLE_UNAVAILABLE);
        }
    }

    public void attachIntent(String structureKey, String sourceRunId, long positionId) {
        if (structureKey == null || sourceRunId == null || positionId <= 0) return;
        try {
            mapper.attachIntent(structureKey, sourceRunId, positionId);
        } catch (RuntimeException ex) {
            log.error("trend structure lifecycle intent attachment failed positionId={} structureKey={}", positionId, structureKey, ex);
        }
    }

    public void markEntryActive(long positionId) {
        try {
            mapper.markEntryActive(positionId);
        } catch (RuntimeException ex) {
            log.error("trend structure lifecycle activation failed positionId={}", positionId, ex);
        }
    }

    public void markEntryAbandoned(long positionId, String reason) {
        try {
            mapper.markEntryAbandoned(positionId, clip(reason));
        } catch (RuntimeException ex) {
            log.error("trend structure lifecycle abandon failed positionId={}", positionId, ex);
        }
    }

    /** A terminal trade closes this entry cycle; only a later completed K-line can create a new cycle. */
    public void markTerminal(long positionId, String exitReason) {
        try {
            mapper.markTerminal(positionId, terminalState(exitReason), clip(exitReason));
        } catch (RuntimeException ex) {
            log.error("trend structure lifecycle terminal update failed positionId={}", positionId, ex);
        }
    }

    private static String terminalState(String exitReason) {
        String value = exitReason == null ? "" : exitReason.toUpperCase(Locale.ROOT);
        if (value.contains("MODEL_INVALIDATION") || value.contains("INITIAL_STOP")) return "INVALIDATED";
        if (value.contains("PROFIT") || value.contains("TRAILING") || value.contains("TARGET")) return "PROFIT_PROTECTED";
        return "EXHAUSTED";
    }

    private static String clip(String value) {
        return value == null ? null : value.substring(0, Math.min(value.length(), 512));
    }

    public record Claim(String structureKey, String contractCode, String timeframe, String direction,
                        String primaryModel, Long breakoutBarTime, String sourceRunId) { }

    public record Decision(boolean applicable, boolean allowed, boolean claimed, String reason) {
        public static Decision notApplicable() { return new Decision(false, true, false, null); }
        public static Decision available() { return new Decision(true, true, false, null); }
        public static Decision claimedDecision() { return new Decision(true, true, true, null); }
        public static Decision blocked(String reason) { return new Decision(true, false, false, reason); }
        public static Decision unavailable(String reason) { return new Decision(true, false, false, reason); }
    }
}
