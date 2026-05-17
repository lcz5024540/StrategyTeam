package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 第十一批: Gamma / UMA / 我方 结算三方 diff Job.
 *
 * <p>每 N 小时 (默认每 4 小时) 触发一次 {@link ResolutionTriDiffService#runScheduled()},
 * 把窗口内 pm_resolution_audit 的不一致计数化, 超阈值 publish AUDIT_RESOLUTION_TRIDIFF.</p>
 *
 * <p>纯只读, 对开单 / 资金 / 胜率均无任何直接副作用; 调度开关
 * {@code smart-wallet.audit.resolution-tridiff-enabled} 默认 true.</p>
 *
 * <p>详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第十八节.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResolutionTriDiffJob {

    private final SmartWalletProperties properties;
    private final ResolutionTriDiffService resolutionTriDiffService;

    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(cron = "${smart-wallet.audit.resolution-tridiff-cron:0 15 */4 * * *}",
            zone = "${smart-wallet.audit.resolution-tridiff-zone:Asia/Shanghai}")
    public void run() {
        if (!properties.isEnabled()) {
            return;
        }
        SmartWalletProperties.Audit audit = properties.getAudit();
        if (audit != null && !audit.isResolutionTriDiffEnabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.info("[ResolutionTriDiffJob] previous run still in progress, skipping this tick");
            return;
        }
        try {
            resolutionTriDiffService.runScheduled();
        } catch (RuntimeException e) {
            log.error("[ResolutionTriDiffJob] unexpected failure: {}", e.toString(), e);
        } finally {
            running.set(false);
        }
    }
}
