package com.metaverse.coin.strategy.htx.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HtxTrendOrderStateMachineTest {
    @Test
    void acceptsPartialFillThenCancelAndRejectsFillAfterTerminalCancellation() {
        HtxTrendOrderStateMachine machine = new HtxTrendOrderStateMachine();
        var submitted = machine.apply(HtxTrendOrderStateMachine.State.INTENT, HtxTrendOrderStateMachine.Event.SUBMIT, 10, 0);
        var accepted = machine.apply(submitted.to(), HtxTrendOrderStateMachine.Event.ACCEPT, 10, 0);
        var partial = machine.apply(accepted.to(), HtxTrendOrderStateMachine.Event.FILL_UPDATE, 10, 4);
        var pending = machine.apply(partial.to(), HtxTrendOrderStateMachine.Event.REQUEST_CANCEL, 10, 4);
        var cancelled = machine.apply(pending.to(), HtxTrendOrderStateMachine.Event.CANCEL_CONFIRMED, 10, 4);
        assertEquals(HtxTrendOrderStateMachine.State.CANCELLED, cancelled.to());
        assertFalse(machine.apply(cancelled.to(), HtxTrendOrderStateMachine.Event.FILL_UPDATE, 10, 10).accepted());
    }
    @Test void preventsACompletedOrderFromBeingExpired() {
        HtxTrendOrderStateMachine machine = new HtxTrendOrderStateMachine();
        assertFalse(machine.apply(HtxTrendOrderStateMachine.State.FILLED, HtxTrendOrderStateMachine.Event.EXPIRE, 1, 1).accepted());
    }
}
