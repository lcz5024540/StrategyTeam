package com.metaverse.coin.strategy.job.funding.service;

import com.metaverse.coin.strategy.core.arbitrage.spotperp.SpotPerpRules;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FundingArbSpotPerpPropertiesTest {
    @Test
    void defaultsStayDryRunWithTenThousandUsdtNotional() {
        FundingArbSpotPerpProperties properties = new FundingArbSpotPerpProperties(new MockEnvironment());
        assertTrue(properties.enabled());
        assertTrue(properties.rules().dryRun());
        assertEquals(0, new BigDecimal("10000").compareTo(properties.rules().notionalUsdt()));
        assertEquals(0, new BigDecimal("10000").compareTo(SpotPerpRules.defaults().notionalUsdt()));
        assertEquals(2, properties.rules().leverage());
    }

    @Test
    void liveSwitchIsRejected() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("arbitrage.spot-perp-funding.dry-run", "false");
        assertThrows(IllegalArgumentException.class, () -> new FundingArbSpotPerpProperties(environment));
        assertThrows(IllegalArgumentException.class, () -> SpotPerpRules.fromEnvironment(environment));
    }
}
