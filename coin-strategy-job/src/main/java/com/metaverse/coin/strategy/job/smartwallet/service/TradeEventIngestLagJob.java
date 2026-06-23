package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * T-B7：事件摄取时延监控调度。
 *
 * <p>默认每 30 分钟（HH:05 / HH:35，Asia/Shanghai）触发一次
 * {@link TradeEventIngestLagService#runScheduled()}，统计 {@code smart_wallet_trade_event}
 * 的摄取时延，P90 超阈值时 publish {@code HEALTH_INGEST_LAG_HIGH}。</p>
 *
 * <p>纯只读 Job（调用 {@link TradeEventIngestLagService} 内只读聚合 + 内存计算），对开单 /
 * 资金 / 胜率均无任何直接副作用；调度开关 {@code smart-wallet.audit.ingest-lag-monitor-enabled}
 * 默认 true，一行配置即可关闭。</p>
 *
 * <p>承接 docs/problem 排查修复任务拆解 T-B7（事件摄取延迟指标与告警）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TradeEventIngestLagJob {

    private final SmartWalletProperties properties;
    private final TradeEventIngestLagService tradeEventIngestLagService;

    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(cron = "${smart-wallet.audit.ingest-lag-monitor-cron:0 5/30 * * * *}",
            zone = "${smart-wallet.audit.ingest-lag-monitor-zone:Asia/Shanghai}")
    public void run() {
        if (!properties.isEnabled()) {
            return;
        }
        SmartWalletProperties.Audit audit = properties.getAudit();
        if (audit != null && !audit.isIngestLagMonitorEnabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.info("[TradeEventIngestLagJob] previous run still in progress, skipping this tick");
            return;
        }
        try {
            tradeEventIngestLagService.runScheduled();
        } catch (RuntimeException e) {
            log.error("[TradeEventIngestLagJob] unexpected failure: {}", e.toString(), e);
        } finally {
            running.set(false);
        }
    }
}
