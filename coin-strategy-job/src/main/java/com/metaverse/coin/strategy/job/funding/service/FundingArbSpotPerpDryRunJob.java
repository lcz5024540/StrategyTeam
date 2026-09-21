package com.metaverse.coin.strategy.job.funding.service;

import com.metaverse.coin.strategy.core.arbitrage.spotperp.PublicSpotPerpMarketSource;
import com.metaverse.coin.strategy.core.arbitrage.spotperp.SpotPerpDryRunEngine;
import com.metaverse.coin.strategy.core.arbitrage.spotperp.SpotPerpPosition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Public-data dry-run scanner. Places no live orders and does not require API keys
 * or the RSA decryptor. Paper state lives in process memory for the JVM lifetime.
 */
@Component
@Slf4j
@ConditionalOnProperty(prefix = "arbitrage.spot-perp-funding", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class FundingArbSpotPerpDryRunJob {
    private final FundingArbSpotPerpProperties properties;
    private final PublicSpotPerpMarketSource marketSource;
    private final SpotPerpDryRunEngine engine;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public FundingArbSpotPerpDryRunJob(FundingArbSpotPerpProperties properties) {
        this.properties = properties;
        this.marketSource = new PublicSpotPerpMarketSource();
        this.engine = new SpotPerpDryRunEngine(properties.rules());
        log.info("Spot-perp funding dry-run armed: dryRun={}, notional={}, leverage={}x, venues=BINANCE+HTX, assets=BTC+ETH",
                properties.rules().dryRun(), properties.rules().notionalUsdt(), properties.rules().leverage());
    }

    @Scheduled(fixedDelayString = "${arbitrage.spot-perp-funding.poll-interval-ms:30000}")
    public void pollPublicMarkets() {
        if (!properties.enabled()) {
            return;
        }
        if (!properties.rules().dryRun()) {
            log.error("Refusing to run: live spot-perp trading is not implemented");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.info("Skip spot-perp dry-run cycle; previous cycle still running");
            return;
        }
        try {
            PublicSpotPerpMarketSource.FetchResult fetched = marketSource.fetchUniverse();
            SpotPerpDryRunEngine.CycleResult cycle = engine.tick(
                    fetched.at() == null ? Instant.now() : fetched.at(), fetched.snapshots());
            for (PublicSpotPerpMarketSource.FetchError error : fetched.errors()) {
                log.warn("Public market fetch failed for {}: {}", error.instrument(), error.message());
            }
            for (String note : fetched.notes()) {
                log.info("spot-perp note: {}", note);
            }
            for (SpotPerpDryRunEngine.Action action : cycle.actions()) {
                if (action.type() == SpotPerpDryRunEngine.ActionType.SKIPPED) {
                    log.debug("spot-perp {} {}", action.instrument(), action.reason());
                } else {
                    log.info("spot-perp {} {} {} pnlDelta={}",
                            action.instrument(), action.type(), action.reason(), action.fundingDelta());
                }
            }
            for (SpotPerpPosition position : cycle.openPositions()) {
                log.info("spot-perp open {} qty={} fundingPnL={} fees={}",
                        position.instrument().key(), position.quantity(),
                        position.fundingFeesEarned(), position.estimatedFees());
            }
        } catch (RuntimeException exception) {
            log.error("spot-perp dry-run cycle failed: {}", exception.getMessage(), exception);
        } finally {
            running.set(false);
        }
    }

    SpotPerpDryRunEngine engine() {
        return engine;
    }
}
