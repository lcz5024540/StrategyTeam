package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Polymarket closed-positions 全量复扫定时 Job。
 *
 * 策略：
 * - 默认 1 小时检查一次（fixedDelay=fullResyncFixedDelayMs，默认 24h），实际触发受
 *   {@code closed_sync_last_full_at} 与 {@code fullResyncMinIntervalMs}（默认 24h）共同约束，
 *   保证每个地址至少 24h 才会再次全量复扫。
 * - 一轮最多处理 {@code dispatchBatchSize} 个地址（默认 100），按 last_full_at 升序优先选最久未刷的。
 * - 单实例互斥（AtomicBoolean）。
 *
 * 用途：
 * - 兜底处理增量遗漏（极端边界 case）
 * - 数据质量从 PARTIAL/STUCK 恢复到 TRUTH 的最终路径
 * - 配合 incrementalIdlePageThreshold 一起保证最终一致性
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ClosedPositionFullResyncJob {

    private final ClosedPositionSyncDispatcher dispatcher;
    private final SmartWalletProperties properties;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(fixedDelayString = "${smart-wallet.polymarket.closed-position-sync.full-resync-fixed-delay-ms:86400000}")
    public void runFullResync() {
        if (!isJobEnabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.debug("[ClosedPositionFullResyncJob] skip overlap");
            return;
        }
        try {
            int batchSize = resolveBatchSize();
            // HOT 优先：HOT 全量复扫节奏与 WARM 一致都是 24h，但每轮处理 batchSize 个最久未刷新的
            ClosedPositionSyncDispatcher.DispatchResult r =
                    dispatcher.dispatchFullResync(List.of("HOT", "WARM"), batchSize);
            log.info("[ClosedPositionFullResyncJob] dispatched={} ok={} failed={} visited={} inserted={} elapsedMs={}",
                    r.total(), r.ok(), r.failed(), r.visited(), r.inserted(), r.elapsedMs());
        } catch (Exception ex) {
            log.warn("[ClosedPositionFullResyncJob] error: {}", ex.getMessage(), ex);
        } finally {
            running.set(false);
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