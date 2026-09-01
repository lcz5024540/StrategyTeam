package com.metaverse.coin.strategy.htx.strategy;

import com.metaverse.coin.strategy.htx.persistence.entity.HtxGridWindowEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class MovingGridEngineTest {
    private final MovingGridEngine engine = new MovingGridEngine();
    private final BigDecimal step = new BigDecimal("0.01");
    private final BigDecimal hysteresis = new BigDecimal("0.001");

    @Test void initializesStableDownwardLattice() {
        HtxGridWindowEntity window = engine.initial("xautusdt", new BigDecimal("100"), step, 3,
                LocalDateTime.of(2026, 8, 31, 10, 0));

        assertEquals(0, new BigDecimal("100.00").compareTo(window.getUpperPrice()));
        assertEquals(0, new BigDecimal("98.01").compareTo(window.getLowerPrice()));
        assertEquals(1L, window.getEpoch());
        assertEquals(MovingGridEngine.RUNNING, window.getState());
    }

    @Test void lowerBreachRequiresConfirmationThenMovesDownByIntegerSteps() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 31, 10, 0);
        HtxGridWindowEntity window = engine.initial("xautusdt", new BigDecimal("100"), step, 3, now);

        MovingGridEngine.Decision first = engine.observe(window, new BigDecimal("96.90"), hysteresis,
                2, 0, 20, now.plusSeconds(1));
        assertFalse(first.migrated());
        assertEquals(1, window.getBreachCount());

        MovingGridEngine.Decision second = engine.observe(window, new BigDecimal("96.90"), hysteresis,
                2, 0, 20, now.plusSeconds(2));
        assertTrue(second.migrated());
        assertTrue(second.signedSteps() < 0);
        assertEquals(MovingGridEngine.MIGRATING_DOWN, window.getState());
        assertEquals(2L, window.getEpoch());
        assertTrue(window.getLowerPrice().compareTo(new BigDecimal("96.90")) <= 0);
    }

    @Test void upperBreachMovesUpAndCooldownBlocksImmediateReverse() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 31, 10, 0);
        HtxGridWindowEntity window = engine.initial("xautusdt", new BigDecimal("100"), step, 3, now);

        MovingGridEngine.Decision up = engine.observe(window, new BigDecimal("102"), hysteresis,
                1, 60_000, 20, now.plusSeconds(1));
        assertTrue(up.migrated());
        assertTrue(up.signedSteps() > 0);
        long epochAfterUp = window.getEpoch();

        MovingGridEngine.Decision reverse = engine.observe(window, new BigDecimal("90"), hysteresis,
                1, 60_000, 20, now.plusSeconds(2));
        assertFalse(reverse.migrated());
        assertEquals(epochAfterUp, window.getEpoch());
    }

    @Test void inRangePriceClearsBreachWithoutMoving() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 31, 10, 0);
        HtxGridWindowEntity window = engine.initial("xautusdt", new BigDecimal("100"), step, 3, now);
        engine.observe(window, new BigDecimal("96"), hysteresis, 2, 0, 20, now.plusSeconds(1));

        MovingGridEngine.Decision decision = engine.observe(window, new BigDecimal("99"), hysteresis,
                2, 0, 20, now.plusSeconds(2));

        assertFalse(decision.migrated());
        assertEquals(0, window.getBreachCount());
        assertNull(window.getBreachDirection());
    }
}
