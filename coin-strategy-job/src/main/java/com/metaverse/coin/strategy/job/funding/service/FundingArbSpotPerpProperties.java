package com.metaverse.coin.strategy.job.funding.service;

import com.metaverse.coin.strategy.core.arbitrage.spotperp.SpotPerpRules;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Funding-profile settings for the BTC/ETH Binance+HTX spot-perp dry-run. */
@Component
public class FundingArbSpotPerpProperties {
    private final boolean enabled;
    private final long pollIntervalMs;
    private final SpotPerpRules rules;

    @Autowired
    public FundingArbSpotPerpProperties(Environment environment) {
        this.enabled = Boolean.parseBoolean(environment.getProperty(
                "arbitrage.spot-perp-funding.enabled", "true"));
        this.pollIntervalMs = Long.parseLong(environment.getProperty(
                "arbitrage.spot-perp-funding.poll-interval-ms", "30000"));
        this.rules = SpotPerpRules.fromEnvironment(environment);
    }

    public boolean enabled() {
        return enabled;
    }

    public long pollIntervalMs() {
        return pollIntervalMs;
    }

    public SpotPerpRules rules() {
        return rules;
    }
}
