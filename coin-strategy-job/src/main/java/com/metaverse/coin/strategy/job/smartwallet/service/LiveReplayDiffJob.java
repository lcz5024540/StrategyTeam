package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * W1-T1.3 Live↔Replay 一致性校验调度.
 *
 * <p>每日 02:00 (Asia/Shanghai) 触发一次, 跑前一天的 diff 报表 + 阈值告警.</p>
 *
 * <p>纯只读 Job (调用 {@link LiveReplayDiffReportService} 内只读 + 内存计算), 对开单 / 资金 /
 * 胜率均无任何直接副作用; 调度开关 {@code smart-wallet.audit.live-replay-diff-enabled}
 * 默认 true, 一行配置即可关闭.</p>
 *
 * <p>详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md (W1-T1).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LiveReplayDiffJob {

    private final SmartWalletProperties properties;
    private final LiveReplayDiffReportService liveReplayDiffReportService;

    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(cron = "${smart-wallet.audit.live-replay-diff-cron:0 0 2 * * *}",
            zone = "${smart-wallet.audit.live-replay-diff-zone:Asia/Shanghai}")
    public void run() {
        if (!properties.isEnabled()) {
            return;
        }
        SmartWalletProperties.Audit audit = properties.getAudit();
        if (audit == null || !audit.isLiveReplayDiffEnabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.info("[LiveReplayDiffJob] previous run still in progress, skipping this tick");
            return;
        }
        try {
            liveReplayDiffReportService.runDailyDiff();
        } catch (RuntimeException e) {
            log.error("[LiveReplayDiffJob] unexpected failure: {}", e.toString(), e);
        } finally {
            running.set(false);
        }
    }
}
