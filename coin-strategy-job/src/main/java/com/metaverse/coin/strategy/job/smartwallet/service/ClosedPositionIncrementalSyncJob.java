package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Polymarket closed-positions 增量同步定时 Job。
 *
 * 策略：
 * - HOT 地址：每 {@code smart-wallet.polymarket.closed-position-sync.hot-incremental-fixed-delay-ms}（默认 5 分钟）触发一次
 * - WARM 地址：每 {@code smart-wallet.polymarket.closed-position-sync.warm-incremental-fixed-delay-ms}（默认 15 分钟）触发一次
 * - 两个 tier 各自独立互斥（AtomicBoolean），允许 HOT/WARM 并发执行（但每 tier 内部不重叠）
 * - 单批上限 batchSize（默认 100）防止一轮塞满线程池队列
 *
 * 核心工作由 {@link ClosedPositionSyncDispatcher#dispatchIncremental(List, int)} 负责。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ClosedPositionIncrementalSyncJob {

    private final ClosedPositionSyncDispatcher dispatcher;
    private final SmartWalletProperties properties;
    private final AtomicBoolean hotRunning = new AtomicBoolean(false);
    private final AtomicBoolean warmRunning = new AtomicBoolean(false);

    /** HOT 地址增量同步：默认 5 分钟一次。 */
    @Scheduled(fixedDelayString = "${smart-wallet.polymarket.closed-position-sync.hot-incremental-fixed-delay-ms:300000}")
    public void incrementalHot() {
        if (!isJobEnabled()) {
            return;
        }
        if (!hotRunning.compareAndSet(false, true)) {
            log.debug("[ClosedPositionIncrementalSyncJob][HOT] skip overlap");
            return;
        }
        try {
            int batchSize = resolveBatchSize();
            ClosedPositionSyncDispatcher.DispatchResult r =
                    dispatcher.dispatchIncremental(List.of("HOT"), batchSize);
            log.info("[ClosedPositionIncrementalSyncJob][HOT] dispatched={} ok={} failed={} inserted={} elapsedMs={}",
                    r.total(), r.ok(), r.failed(), r.inserted(), r.elapsedMs());
        } catch (Exception ex) {
            log.warn("[ClosedPositionIncrementalSyncJob][HOT] error: {}", ex.getMessage(), ex);
        } finally {
            hotRunning.set(false);
        }
    }

    /** WARM 地址增量同步：默认 15 分钟一次。 */
    @Scheduled(fixedDelayString = "${smart-wallet.polymarket.closed-position-sync.warm-incremental-fixed-delay-ms:900000}")
    public void incrementalWarm() {
        if (!isJobEnabled()) {
            return;
        }
        if (!warmRunning.compareAndSet(false, true)) {
            log.debug("[ClosedPositionIncrementalSyncJob][WARM] skip overlap");
            return;
        }
        try {
            int batchSize = resolveBatchSize();
            ClosedPositionSyncDispatcher.DispatchResult r =
                    dispatcher.dispatchIncremental(List.of("WARM"), batchSize);
            log.info("[ClosedPositionIncrementalSyncJob][WARM] dispatched={} ok={} failed={} inserted={} elapsedMs={}",
                    r.total(), r.ok(), r.failed(), r.inserted(), r.elapsedMs());
        } catch (Exception ex) {
            log.warn("[ClosedPositionIncrementalSyncJob][WARM] error: {}", ex.getMessage(), ex);
        } finally {
            warmRunning.set(false);
        }
    }

    private boolean isJobEnabled() {
        if (!properties.isEnabled() || !properties.getPolymarket().isEnabled()) {
            return false;
        }
        SmartWalletProperties.PolymarketMonitor.ClosedPositionSync cfg =
                properties.getPolymarket().getClosedPositionSync();
        return cfg != null && cfg.isEnabled();
    }

    private int resolveBatchSize() {
        Integer configured = properties.getPolymarket().getClosedPositionSync() == null
                ? null
                : properties.getPolymarket().getClosedPositionSync().getDispatchBatchSize();
        return configured == null || configured <= 0 ? 100 : Math.min(configured, 2000);
    }
}