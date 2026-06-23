package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * W2-T4.5 滑点事后统计调度.
 *
 * <p>每周一 07:00 (Asia/Shanghai, 默认值) 跑一次滑点事后统计报表, 紧跟
 * {@link ShadowVsLiveWeeklyReportService} (06:00) 之后, 以便两份周报可串联阅读:
 * 影子 vs 实盘的"哪一层风控拒贵了" + 真实成交的"滑点是否真的更差".</p>
 *
 * <p>纯只读 Job (调用 {@link SlippagePostFactReportService} 内只读 + 内存计算), 对开单 /
 * 资金 / 胜率均无任何直接副作用; 调度开关 {@code smart-wallet.audit.slippage-post-fact-enabled}
 * 默认 true, 一行配置即可关闭.</p>
 *
 * <p>详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md (W2-T4.5).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlippagePostFactReportJob {

    private final SmartWalletProperties properties;
    private final SlippagePostFactReportService slippagePostFactReportService;

    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(cron = "${smart-wallet.audit.slippage-post-fact-cron:0 0 7 * * MON}",
            zone = "${smart-wallet.audit.slippage-post-fact-zone:Asia/Shanghai}")
    public void run() {
        if (!properties.isEnabled()) {
            return;
        }
        SmartWalletProperties.Audit audit = properties.getAudit();
        if (audit == null || !audit.isSlippagePostFactEnabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.info("[SlippagePostFactReportJob] previous run still in progress, skipping this tick");
            return;
        }
        try {
            slippagePostFactReportService.runScheduled();
        } catch (RuntimeException e) {
            log.error("[SlippagePostFactReportJob] unexpected failure: {}", e.toString(), e);
        } finally {
            running.set(false);
        }
    }
}
