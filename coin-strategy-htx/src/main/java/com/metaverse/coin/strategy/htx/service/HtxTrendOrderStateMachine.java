package com.metaverse.coin.strategy.htx.service;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

/** Pure order-life-cycle validator. Exchange reports, not strategy intent, are the only state transitions. */
public final class HtxTrendOrderStateMachine {
    private static final Map<State, EnumSet<State>> TRANSITIONS = transitions();

    public Transition apply(State current, Event event, long requestedContracts, long cumulativeFilledContracts) {
        if (current == null || event == null || requestedContracts <= 0 || cumulativeFilledContracts < 0
                || cumulativeFilledContracts > requestedContracts) return Transition.rejected(current, "invalid order transition input");
        State next = next(event, requestedContracts, cumulativeFilledContracts);
        if (!TRANSITIONS.getOrDefault(current, EnumSet.noneOf(State.class)).contains(next)) {
            return Transition.rejected(current, "illegal transition " + current + " -> " + next);
        }
        return new Transition(current, next, true, null);
    }

    private static State next(Event event, long requested, long filled) {
        return switch (event) {
            case SUBMIT -> State.SUBMITTED;
            case ACCEPT -> State.ACCEPTED;
            case FILL_UPDATE -> filled == requested ? State.FILLED : filled > 0 ? State.PARTIALLY_FILLED : State.ACCEPTED;
            case REJECT -> State.REJECTED;
            case EXPIRE -> State.EXPIRED;
            case REQUEST_CANCEL -> State.CANCEL_PENDING;
            case CANCEL_CONFIRMED -> State.CANCELLED;
        };
    }

    private static Map<State, EnumSet<State>> transitions() {
        Map<State, EnumSet<State>> value = new EnumMap<>(State.class);
        value.put(State.INTENT, EnumSet.of(State.SUBMITTED, State.REJECTED));
        value.put(State.SUBMITTED, EnumSet.of(State.ACCEPTED, State.REJECTED, State.EXPIRED, State.CANCEL_PENDING));
        value.put(State.ACCEPTED, EnumSet.of(State.ACCEPTED, State.PARTIALLY_FILLED, State.FILLED, State.REJECTED, State.EXPIRED, State.CANCEL_PENDING));
        value.put(State.PARTIALLY_FILLED, EnumSet.of(State.PARTIALLY_FILLED, State.FILLED, State.CANCEL_PENDING, State.EXPIRED));
        value.put(State.CANCEL_PENDING, EnumSet.of(State.PARTIALLY_FILLED, State.FILLED, State.CANCELLED));
        value.put(State.FILLED, EnumSet.noneOf(State.class)); value.put(State.REJECTED, EnumSet.noneOf(State.class));
        value.put(State.EXPIRED, EnumSet.noneOf(State.class)); value.put(State.CANCELLED, EnumSet.noneOf(State.class));
        return Map.copyOf(value);
    }

    public enum State { INTENT, SUBMITTED, ACCEPTED, PARTIALLY_FILLED, FILLED, REJECTED, EXPIRED, CANCEL_PENDING, CANCELLED }
    public enum Event { SUBMIT, ACCEPT, FILL_UPDATE, REJECT, EXPIRE, REQUEST_CANCEL, CANCEL_CONFIRMED }
    public record Transition(State from, State to, boolean accepted, String reason) {
        static Transition rejected(State current, String reason) { return new Transition(current, current, false, reason); }
    }
}
