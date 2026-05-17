package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * W2-T2 灰度自动化 (第十八批): leader 滑动窗口胜率日级监控 Job.
 *
 * <p>每日 (默认 09:30 Asia/Shanghai) 触发 {@link LeaderRollingMonitorService#runScheduled()},
 * 把多 lookback 档 (默认 7d, 可配置 7+14) 的聚合结果 upsert 到
 * {@code leader_rolling_aggregation_daily}, 并在 health != OK 时 publish AUDIT_LEADER_ROLLING_HEALTH.</p>
 *
 * <p>纯只读聚合 Job, 对开单 / 资金 / 胜率均无任何直接副作用; 调度开关
 * {@code smart-wallet.audit.leader-rolling-aggregation-enabled} 默认 true.
 * 紧跟 LeaderDriftAggregationJob (08:00) 之后, 避开夜间增量与 MarketLiquidityPercentileSnapshot (02:30).</p>
 *
 * <p>详见 docs/polymarket/audit-observability/w2-t2-grayscale-sop.md.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LeaderRollingMonitorJob {

    private final SmartWalletProperties properties;
    private final LeaderRollingMonitorService leaderRollingMonitorService;

    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(cron = "${smart-wallet.audit.leader-rolling-aggregation-cron:0 30 9 * * *}",
            zone = "${smart-wallet.audit.leader-rolling-aggregation-zone:Asia/Shanghai}")
    public void run() {
        if (!properties.isEnabled()) {
            return;
        }
        SmartWalletProperties.Audit audit = properties.getAudit();
        if (audit == null || !audit.isLeaderRollingAggregationEnabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.info("[LeaderRollingMonitorJob] previous run still in progress, skipping this tick");
            return;
        }
        try {
            leaderRollingMonitorService.runScheduled();
        } catch (RuntimeException e) {
            log.error("[LeaderRollingMonitorJob] unexpected failure: {}", e.toString(), e);
        } finally {
            running.set(false);
        }
    }
}
