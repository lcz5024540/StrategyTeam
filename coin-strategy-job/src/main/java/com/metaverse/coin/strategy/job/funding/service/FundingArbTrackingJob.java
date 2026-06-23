package com.metaverse.coin.strategy.job.funding.service;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SingleExchangeArbitragePosition;
import com.metaverse.coin.strategy.core.arbitrage.config.SingleExchangeSpotFuturesProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnExpression("'${binance.api.api-key:}' != '' && '${binance.api.secret-key:}' != ''")
public class FundingArbTrackingJob {

    private final FundingArbLifecycleService lifecycleService;
    private final SingleExchangeSpotFuturesProperties properties;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(fixedDelayString = "${arbitrage.spot-futures.tracking-fixed-delay-ms:60000}")
    public void scheduledTrackPositions() {
        if (!Boolean.TRUE.equals(properties.getTrackingEnabled())) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.info("Skip FundingArb tracking because previous run is still in progress.");
            return;
        }
        try {
            int limit = properties.getTrackingPositionLimit() == null ? 50 : properties.getTrackingPositionLimit();
            List<SingleExchangeArbitragePosition> positions = lifecycleService.loadTrackablePositions(limit);
            if (positions == null || positions.isEmpty()) {
                return;
            }
            int processed = 0;
            int failed = 0;
            for (SingleExchangeArbitragePosition position : positions) {
                try {
                    lifecycleService.trackPosition(position);
                    processed++;
                } catch (Exception e) {
                    failed++;
                    log.error("FundingArb tracking failed for positionId={}, symbol={}, message={}",
                            position.getId(), position.getSymbol(), e.getMessage(), e);
                }
            }
            log.info("FundingArb tracking finished: scanned={}, processed={}, failed={}",
                    positions.size(), processed, failed);
        } finally {
            running.set(false);
        }
    }
}
