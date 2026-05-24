package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletProfileMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletClosedSyncAsyncConfig;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 调度入口：根据 monitor_tier 与 closed_sync_last_full_at 选择待同步钱包，
 * 提交到 smartWalletClosedSyncExecutor 并发执行 ClosedPositionIncrementalSyncService。
 *
 * 设计：Dispatcher 只负责"挑地址 + 提交 + 汇总日志"，单地址逻辑由 ClosedPositionIncrementalSyncService 自己保障。
*      并发由线程池上限决定，HTTP 总并发由 Service 内 Semaphore 兜底。
 */
@Service
@Slf4j
public class ClosedPositionSyncDispatcher {

    private static final String SOURCE = ClosedPositionIncrementalSyncService.SOURCE;

    private final SmartWalletProfileMapper smartWalletProfileMapper;
    private final ClosedPositionIncrementalSyncService incrementalSyncService;
    private final WalletBehaviorProfileService walletBehaviorProfileService;
    private final SmartWalletProperties smartWalletProperties;
    private final ThreadPoolTaskExecutor closedSyncExecutor;

    public ClosedPositionSyncDispatcher(
            SmartWalletProfileMapper smartWalletProfileMapper,
            ClosedPositionIncrementalSyncService incrementalSyncService,
            WalletBehaviorProfileService walletBehaviorProfileService,
            SmartWalletProperties smartWalletProperties,
            @Qualifier(SmartWalletClosedSyncAsyncConfig.EXECUTOR_BEAN_NAME) ThreadPoolTaskExecutor closedSyncExecutor) {
        this.smartWalletProfileMapper = smartWalletProfileMapper;
        this.incrementalSyncService = incrementalSyncService;
        this.walletBehaviorProfileService = walletBehaviorProfileService;
        this.smartWalletProperties = smartWalletProperties;
        this.closedSyncExecutor = closedSyncExecutor;
    }

    /**
     * 执行增量同步：选择 HOT/WARM 地址（也可指定 tier 子集），提交并发执行 syncIncremental。
     *
     * @param tiers 监控 tier 过滤列表，例如 List.of("HOT","WARM")
     * @param batchSize 单批次处理上限（避免一轮全部塞满线程池队列）
     * @return DispatchResult 含成功/失败/跳过统计
     */
    public DispatchResult dispatchIncremental(List<String> tiers, int batchSize) {
        if (!isEnabled()) {
            return DispatchResult.disabled();
        }
        List<String> wallets = loadDispatchTargets(tiers, batchSize);
        if (wallets.isEmpty()) {
            return DispatchResult.empty(tiers);
        }
        return runConcurrent(wallets, "incremental", incrementalSyncService::syncIncremental);
    }

    /**
     * 执行全量复扫：按"closed_sync_last_full_at 为空 或 距今 ≥ fullResyncMinIntervalMs"过滤地址。
     * 默认每地址 24h 最多一次。
     */
    public DispatchResult dispatchFullResync(List<String> tiers, int batchSize) {
        if (!isEnabled()) {
            return DispatchResult.disabled();
        }
        SmartWalletProperties.PolymarketMonitor.ClosedPositionSync cfg = config();
        long minIntervalMs = cfg == null || cfg.getFullResyncMinIntervalMs() == null
                ? 24 * 3600_000L : cfg.getFullResyncMinIntervalMs();
        Date threshold = new Date(System.currentTimeMillis() - minIntervalMs);

        LambdaQueryWrapper<SmartWalletProfileEntity> wrapper = new LambdaQueryWrapper<SmartWalletProfileEntity>()
                .eq(SmartWalletProfileEntity::getSource, SOURCE)
                .in(tiers != null && !tiers.isEmpty(),
                        SmartWalletProfileEntity::getMonitorTier, tiers == null ? List.of() : tiers)
                .and(w -> w.isNull(SmartWalletProfileEntity::getClosedSyncLastFullAt)
                        .or().lt(SmartWalletProfileEntity::getClosedSyncLastFullAt, threshold))
                .orderByAsc(SmartWalletProfileEntity::getClosedSyncLastFullAt)
                .last("limit " + Math.max(1, batchSize));

        List<SmartWalletProfileEntity> rows = smartWalletProfileMapper.selectList(wrapper);
        List<String> wallets = rows.stream()
                .map(SmartWalletProfileEntity::getWalletAddress)
                .filter(s -> s != null && !s.isBlank())
                .toList();
        if (wallets.isEmpty()) {
            return DispatchResult.empty(tiers);
        }
        return runConcurrent(wallets, "full", incrementalSyncService::syncFull);
    }

    // ------------------------------------------------------------

    private List<String> loadDispatchTargets(List<String> tiers, int batchSize) {
        LambdaQueryWrapper<SmartWalletProfileEntity> wrapper = new LambdaQueryWrapper<SmartWalletProfileEntity>()
                .eq(SmartWalletProfileEntity::getSource, SOURCE)
                .in(tiers != null && !tiers.isEmpty(),
                        SmartWalletProfileEntity::getMonitorTier, tiers == null ? List.of() : tiers)
                .last("limit " + Math.max(1, batchSize));
        return smartWalletProfileMapper.selectList(wrapper).stream()
                .map(SmartWalletProfileEntity::getWalletAddress)
                .filter(s -> s != null && !s.isBlank())
                .toList();
    }

    private DispatchResult runConcurrent(
            List<String> wallets,
            String mode,
            java.util.function.Function<String, ClosedPositionIncrementalSyncService.SyncResult> action) {
        long started = System.currentTimeMillis();
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicInteger inserted = new AtomicInteger();
        AtomicInteger visited = new AtomicInteger();

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (String wallet : wallets) {
            try {
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        ClosedPositionIncrementalSyncService.SyncResult r = action.apply(wallet);
                        ok.incrementAndGet();
                        inserted.addAndGet(r.inserted());
                        visited.addAndGet(r.visited());
                        if (r.inserted() > 0) {
                            refreshBehaviorProfile(wallet, mode, r.inserted());
                        }
                    } catch (Exception ex) {
                        failed.incrementAndGet();
                        log.warn("[ClosedPositionSyncDispatcher] {} sync failed wallet={}: {}",
                                mode, wallet, ex.getMessage());
                    }
                }, closedSyncExecutor));
            } catch (RejectedExecutionException ex) {
                failed.incrementAndGet();
                log.warn("[ClosedPositionSyncDispatcher] {} sync rejected wallet={} reason=executor-saturated message={}",
                        mode, wallet, ex.getMessage());
            }
        }
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (Exception ex) {
            log.warn("[ClosedPositionSyncDispatcher] {} batch wait error: {}", mode, ex.getMessage());
        }
        long elapsedMs = System.currentTimeMillis() - started;
        log.info("[ClosedPositionSyncDispatcher] {} done total={} ok={} failed={} visited={} inserted={} elapsedMs={}",
                mode, wallets.size(), ok.get(), failed.get(), visited.get(), inserted.get(), elapsedMs);
        return new DispatchResult(wallets.size(), ok.get(), failed.get(),
                visited.get(), inserted.get(), elapsedMs);
    }

    private void refreshBehaviorProfile(String wallet, String mode, int inserted) {
        try {
            SmartWalletProfileEntity profile = smartWalletProfileMapper.selectOne(
                    new LambdaQueryWrapper<SmartWalletProfileEntity>()
                            .eq(SmartWalletProfileEntity::getWalletAddress, wallet)
                            .eq(SmartWalletProfileEntity::getSource, SOURCE)
                            .last("limit 1"));
            if (profile == null) {
                log.debug("[ClosedPositionSyncDispatcher] {} behavior refresh skipped wallet={} reason=profile-not-found",
                        mode, wallet);
                return;
            }
            walletBehaviorProfileService.evictProfileCache(wallet);
            walletBehaviorProfileService.buildProfile(profile);
            log.info("[ClosedPositionSyncDispatcher] {} behavior refreshed wallet={} insertedClosedPositions={}",
                    mode, wallet, inserted);
        } catch (Exception ex) {
            log.warn("[ClosedPositionSyncDispatcher] {} behavior refresh failed wallet={} insertedClosedPositions={} reason={}",
                    mode, wallet, inserted, ex.getMessage());
        }
    }

    private boolean isEnabled() {
        if (!smartWalletProperties.isEnabled() || !smartWalletProperties.getPolymarket().isEnabled()) {
            return false;
        }
        SmartWalletProperties.PolymarketMonitor.ClosedPositionSync cfg = config();
        return cfg != null && cfg.isEnabled();
    }

    private SmartWalletProperties.PolymarketMonitor.ClosedPositionSync config() {
        return smartWalletProperties.getPolymarket().getClosedPositionSync();
    }

    public record DispatchResult(int total, int ok, int failed,
                                 int visited, int inserted, long elapsedMs) {
        public static DispatchResult disabled() {
            return new DispatchResult(0, 0, 0, 0, 0, 0L);
        }
        public static DispatchResult empty(List<String> tiers) {
            return new DispatchResult(0, 0, 0, 0, 0, 0L);
        }
    }
}
