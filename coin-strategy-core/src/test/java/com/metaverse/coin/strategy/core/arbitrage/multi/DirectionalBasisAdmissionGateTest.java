package com.metaverse.coin.strategy.core.arbitrage.multi;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectionalBasisAdmissionGateTest {
    @Test
    void warmsUpWithoutBlockingThenRejectsBasisBelowHistoricalPercentile() {
        DirectionalBasisAdmissionGate gate = new DirectionalBasisAdmissionGate(true, 3, 4, 70);

        assertTrue(assess(gate, "100", "99.0").admitted());
        assertTrue(assess(gate, "100", "99.5").admitted());
        assertTrue(assess(gate, "100", "100.0").admitted());

        DirectionalBasisAdmissionGate.Assessment weak = assess(gate, "100", "99.2");
        assertFalse(weak.admitted());
        assertTrue(weak.threshold().compareTo(new BigDecimal("-0.005")) > 0);
    }

    @Test
    void keepsHistoryIndependentBySymbolAndDirection() {
        DirectionalBasisAdmissionGate gate = new DirectionalBasisAdmissionGate(true, 1, 2, 50);
        assess(gate, "100", "101");

        DirectionalBasisAdmissionGate.Assessment other = gate.assess("ETHUSDT", Exchange.BITGET,
                Exchange.OKX, new BigDecimal("100"), new BigDecimal("99"));

        assertTrue(other.admitted());
    }

    @Test
    void exposesMedianAndAdmissionThresholdForSharedExitModel() {
        DirectionalBasisAdmissionGate gate = new DirectionalBasisAdmissionGate(true, 2, 4, 70);
        assess(gate, "100", "99");
        assess(gate, "100", "101");

        DirectionalBasisAdmissionGate.Profile profile = gate.profile(
                "BTCUSDT", Exchange.BITGET, Exchange.OKX);

        assertTrue(profile.median().compareTo(BigDecimal.ZERO) == 0);
        assertTrue(profile.admissionThreshold().compareTo(BigDecimal.ZERO) > 0);
    }

    private DirectionalBasisAdmissionGate.Assessment assess(DirectionalBasisAdmissionGate gate,
            String longPrice, String shortPrice) {
        return gate.assess("BTCUSDT", Exchange.BITGET, Exchange.OKX,
                new BigDecimal(longPrice), new BigDecimal(shortPrice));
    }
}
