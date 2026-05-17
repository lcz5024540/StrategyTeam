package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * W2-T6 后续补丁: Leader 行为漂移日级聚合 Job.
 *
 * <p>每日 (默认 08:00 Asia/Shanghai) 触发一次 {@link LeaderDriftAggregationService#runScheduled()},
 * 把窗口内活跃 leader 的漂移状态汇总, 仅在超阈值时单条 publish AUDIT_LEADER_DRIFT, 替代
 * 原 hot path 单 leader 单次 fire 的污染模式.</p>
 *
 * <p>纯只读 Job, 对开单 / 资金 / 胜率均无任何直接副作用; 调度开关
 * {@code smart-wallet.audit.leader-drift-aggregation-enabled} 默认 true, 一行配置关闭.</p>
 *
 * <p>详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第十节末
 * "下一轮" 第 5 项.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LeaderDriftAggregationJob {

    private final SmartWalletProperties properties;
    private final LeaderDriftAggregationService leaderDriftAggregationService;

    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(cron = "${smart-wallet.audit.leader-drift-aggregation-cron:0 0 8 * * *}",
            zone = "${smart-wallet.audit.leader-drift-aggregation-zone:Asia/Shanghai}")
    public void run() {
        if (!properties.isEnabled()) {
            return;
        }
        SmartWalletProperties.Audit audit = properties.getAudit();
        if (audit == null || !audit.isLeaderDriftAggregationEnabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.info("[LeaderDriftAggregationJob] previous run still in progress, skipping this tick");
            return;
        }
        try {
            leaderDriftAggregationService.runScheduled();
        } catch (RuntimeException e) {
            log.error("[LeaderDriftAggregationJob] unexpected failure: {}", e.toString(), e);
        } finally {
            running.set(false);
        }
    }
}
