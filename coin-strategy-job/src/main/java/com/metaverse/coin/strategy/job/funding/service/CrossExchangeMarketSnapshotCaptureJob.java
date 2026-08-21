package com.metaverse.coin.strategy.job.funding.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@ConditionalOnProperty(prefix = "arbitrage.multi-exchange", name = "capture-enabled",
        havingValue = "true")
public class CrossExchangeMarketSnapshotCaptureJob {
    private final CrossExchangeMarketSnapshotRecorder recorder;

    public CrossExchangeMarketSnapshotCaptureJob(CrossExchangeMarketSnapshotRecorder recorder) {
        this.recorder = recorder;
    }

    @Scheduled(fixedDelayString = "${arbitrage.multi-exchange.capture-delay-ms:60000}")
    public void capture() {
        recorder.capture(Instant.now());
    }
}
