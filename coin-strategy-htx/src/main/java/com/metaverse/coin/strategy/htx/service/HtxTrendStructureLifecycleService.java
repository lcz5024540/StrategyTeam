package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendStructureLifecycleEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendStructureLifecycleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Set;

/**
 * Serializes ownership of an entry structure while an entry intent or position is still active.
 * A database unique key, rather than an in-memory cache, is the source of truth.
 * A terminal trade releases the key: every later candidate must pass its current trend and execution gates again.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HtxTrendStructureLifecycleService {
    public static final String FIB_PULLBACK_CONTINUATION = "FIB_PULLBACK_CONTINUATION";
    public static final String STRUCTURE_ALREADY_CONSUMED = "STRUCTURE_ALREADY_CONSUMED";
    public static final String STRUCTURE_LIFECYCLE_UNAVAILABLE = "STRUCTURE_LIFECYCLE_UNAVAILABLE";
    private static final Set<String> RECLAIMABLE_STATES = Set.of(
            "ABANDONED", "EXHAUSTED", "INVALIDATED", "PROFIT_PROTECTED", "SUPERSEDED");

    private final HtxTrendStructureLifecycleMapper mapper;

    /** A read-only early gate, used before expensive account and leverage operations. */
    public Decision availability(String structureKey) {
        if (structureKey == null || structureKey.isBlank()) {
            return Decision.notApplicable();
        }
        try {
            HtxTrendStructureLifecycleEntity existing = mapper.findByStructureKey(structureKey);
            if (existing == null || isReclaimable(existing.getState())) {
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
            if (existing != null && isReclaimable(existing.getState())
                    && mapper.reclaimReusable(claim.structureKey(), claim.sourceRunId()) == 1) {
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

    /** A terminal trade releases its structure key so a later candidate can be assessed from fresh market evidence. */
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

    private static boolean isReclaimable(String state) {
        return state != null && RECLAIMABLE_STATES.contains(state);
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
