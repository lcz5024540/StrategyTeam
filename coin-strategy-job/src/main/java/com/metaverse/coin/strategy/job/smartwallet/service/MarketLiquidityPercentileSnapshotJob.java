package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * W2-T1.2 流动性分位日级快照 Job.
 *
 * <p>每天 02:30 (Asia/Shanghai, 紧跟 LiveReplayDiffJob 02:00) 触发一次
 * {@link MarketLiquidityPercentileSnapshotService#runScheduled()}, 把当日所有 active 市场
 * 的流动性按 category 分桶 → 算 P10/P30/P50/P70 → upsert 到
 * {@code pm_market_liquidity_percentile_daily}.</p>
 *
 * <p>纯写入 Job, 对开单 / 资金 / 胜率均无任何直接副作用; 调度开关
 * {@code smart-wallet.audit.liquidity-percentile-snapshot-enabled} 默认 true, 一行配置关闭.</p>
 *
 * <p>详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第十六节.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketLiquidityPercentileSnapshotJob {

    private final SmartWalletProperties properties;
    private final MarketLiquidityPercentileSnapshotService snapshotService;

    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(cron = "${smart-wallet.audit.liquidity-percentile-snapshot-cron:0 30 2 * * *}",
            zone = "${smart-wallet.audit.liquidity-percentile-snapshot-zone:Asia/Shanghai}")
    public void run() {
        if (!properties.isEnabled()) {
            return;
        }
        SmartWalletProperties.Audit audit = properties.getAudit();
        if (audit != null && !audit.isLiquidityPercentileSnapshotEnabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.info("[MarketLiquidityPercentileSnapshotJob] previous run still in progress, skipping this tick");
            return;
        }
        try {
            snapshotService.runScheduled();
        } catch (RuntimeException e) {
            log.error("[MarketLiquidityPercentileSnapshotJob] unexpected failure: {}", e.toString(), e);
        } finally {
            running.set(false);
        }
    }
}
