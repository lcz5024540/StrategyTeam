package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * W1-T4.3 影子结算回填 Job.
 *
 * <p>定时触发 {@link ShadowSettlementBackfillService#runOnce()}, 把已结算市场上的影子记录
 * 回填出场价与 PnL, 长期未结算的影子记录标记 EXPIRED.</p>
 *
 * <p>默认间隔 10 分钟, 可通过 {@code smart-wallet.audit.shadow-backfill-fixed-delay-ms} 覆盖.
 * 调度开关 {@code smart-wallet.audit.shadow-backfill-enabled} 默认 true.</p>
 *
 * <p>纯回填型 Job, 不调用执行链路 → 对开单效率 / 资金使用率 / 实盘盈亏均无副作用.</p>
 *
 * <p>详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md (W1-T4).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShadowSettlementBackfillJob {

    private final SmartWalletProperties properties;
    private final ShadowSettlementBackfillService shadowSettlementBackfillService;

    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(fixedDelayString = "${smart-wallet.audit.shadow-backfill-fixed-delay-ms:600000}",
            initialDelayString = "${smart-wallet.audit.shadow-backfill-initial-delay-ms:60000}")
    public void run() {
        if (!properties.isEnabled()) {
            return;
        }
        SmartWalletProperties.Audit audit = properties.getAudit();
        if (audit == null || !audit.isShadowBackfillEnabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.info("[ShadowSettlementBackfillJob] previous run still in progress, skipping this tick");
            return;
        }
        try {
            shadowSettlementBackfillService.runOnce();
        } catch (RuntimeException e) {
            log.error("[ShadowSettlementBackfillJob] unexpected failure: {}", e.toString(), e);
        } finally {
            running.set(false);
        }
    }
}
