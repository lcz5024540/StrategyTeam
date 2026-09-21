package com.metaverse.coin.strategy.core.arbitrage.spotperp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-instrument dry-run state machine: FLAT -> OPENED -> CLOSED.
 *
 * <p>PnL accounted as funding received on the short perp. Basis is a risk gate,
 * not a PnL source. Live order placement does not exist in this class.</p>
 */
public final class SpotPerpDryRunEngine {
    public enum ActionType { OPENED, CLOSED, FUNDING_SETTLED, ROLLED_BACK, SKIPPED }

    public record Action(
            SpotPerpInstrument instrument,
            ActionType type,
            String reason,
            String positionId,
            BigDecimal fundingDelta
    ) {
    }

    public record CycleResult(Instant now, List<Action> actions, List<SpotPerpPosition> openPositions) {
        public long count(ActionType type) {
            return actions.stream().filter(action -> action.type() == type).count();
        }
    }

    private final SpotPerpRules rules;
    private final SpotPerpGates gates;
    private final SpotPerpPaperExecutor executor;
    private final Map<String, SpotPerpPosition> open = new ConcurrentHashMap<>();
    private final List<SpotPerpPosition> closed = new ArrayList<>();

    public SpotPerpDryRunEngine(SpotPerpRules rules) {
        this.rules = Objects.requireNonNull(rules, "rules");
        this.gates = new SpotPerpGates(rules);
        this.executor = new SpotPerpPaperExecutor(rules);
    }

    public CycleResult tick(Instant now, List<SpotPerpSnapshot> snapshots) {
        Objects.requireNonNull(now, "now");
        List<Action> actions = new ArrayList<>();
        if (snapshots == null || snapshots.isEmpty()) {
            return new CycleResult(now, List.of(), openPositions());
        }
        for (SpotPerpSnapshot snapshot : snapshots) {
            actions.addAll(tickOne(now, snapshot));
        }
        return new CycleResult(now, List.copyOf(actions), openPositions());
    }

    public Optional<SpotPerpPosition> position(SpotPerpInstrument instrument) {
        return Optional.ofNullable(open.get(instrument.key()));
    }

    public List<SpotPerpPosition> openPositions() {
        return List.copyOf(open.values());
    }

    public List<SpotPerpPosition> closedPositions() {
        synchronized (closed) {
            return List.copyOf(closed);
        }
    }

    public SpotPerpRules rules() {
        return rules;
    }

    public Map<String, BigDecimal> fundingPnlByInstrument() {
        Map<String, BigDecimal> pnl = new LinkedHashMap<>();
        for (SpotPerpPosition position : open.values()) {
            pnl.merge(position.instrument().key(), position.fundingFeesEarned(), BigDecimal::add);
        }
        synchronized (closed) {
            for (SpotPerpPosition position : closed) {
                pnl.merge(position.instrument().key(), position.fundingFeesEarned(), BigDecimal::add);
            }
        }
        return pnl;
    }

    private List<Action> tickOne(Instant now, SpotPerpSnapshot snapshot) {
        List<Action> actions = new ArrayList<>();
        SpotPerpPosition current = open.get(snapshot.instrument().key());
        if (current == null) {
            Optional<String> blocked = gates.openBlockReason(snapshot, now);
            if (blocked.isPresent()) {
                actions.add(new Action(snapshot.instrument(), ActionType.SKIPPED, blocked.get(), null, BigDecimal.ZERO));
                return actions;
            }
            SpotPerpPaperExecutor.Result opened = executor.open(snapshot, now);
            if (!opened.success()) {
                ActionType type = opened.position() != null
                        && opened.position().status() == SpotPerpPosition.Status.ROLLED_BACK
                        ? ActionType.ROLLED_BACK
                        : ActionType.SKIPPED;
                actions.add(new Action(snapshot.instrument(), type, opened.message(),
                        opened.position() == null ? null : opened.position().positionId(), BigDecimal.ZERO));
                return actions;
            }
            open.put(snapshot.instrument().key(), opened.position());
            actions.add(new Action(snapshot.instrument(), ActionType.OPENED, "OPENED",
                    opened.position().positionId(), BigDecimal.ZERO));
            return actions;
        }

        BigDecimal settled = settleIfDue(current, snapshot, now);
        if (settled != null) {
            actions.add(new Action(snapshot.instrument(), ActionType.FUNDING_SETTLED, "FUNDING_SETTLED",
                    current.positionId(), settled));
        }
        Optional<SpotPerpGates.CloseSignal> close = gates.closeSignal(snapshot, current, now);
        if (close.isEmpty()) {
            return actions;
        }
        SpotPerpPaperExecutor.Result closedResult = executor.close(current, snapshot, now, close.get().reason());
        if (!closedResult.success()) {
            actions.add(new Action(snapshot.instrument(), ActionType.SKIPPED, closedResult.message(),
                    current.positionId(), BigDecimal.ZERO));
            return actions;
        }
        open.remove(snapshot.instrument().key());
        synchronized (this.closed) {
            this.closed.add(current);
        }
        actions.add(new Action(snapshot.instrument(), ActionType.CLOSED, close.get().reason(),
                current.positionId(), BigDecimal.ZERO));
        return actions;
    }

    private BigDecimal settleIfDue(SpotPerpPosition position, SpotPerpSnapshot snapshot, Instant now) {
        Instant due = position.nextFundingTime();
        Instant next = snapshot.nextFundingTime();
        if (due == null || next == null || !next.isAfter(due) || now.isBefore(due)) {
            return null;
        }
        BigDecimal amount = position.accrueFunding(due, snapshot.fundingRate(), snapshot.markPrice());
        position.advanceFundingClock(next);
        return amount;
    }
}
