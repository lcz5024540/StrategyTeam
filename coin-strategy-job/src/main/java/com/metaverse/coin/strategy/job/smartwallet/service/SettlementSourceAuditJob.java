package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * W1-T2.3 结算源对齐审计调度 (轻量化第一版).
 *
 * <p>每小时触发一次 {@link SettlementSourceAuditService#runScheduled()}, 跑一次结算源审计
 * 并输出日志: 当 parseFailure / resolutionDelayed / pendingSettlementOverdue / claimFailureMax
 * 任意维度异常时打 WARN.</p>
 *
 * <p>纯只读 Job (调用 SettlementSourceAuditService 内只读 + 内存计算), 对开单 / 资金 /
 * 胜率均无任何直接副作用; 调度开关 {@code smart-wallet.audit.settlement-source-audit-enabled}
 * 默认 true, 一行配置即可关闭.</p>
 *
 * <p>详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md (W1-T2).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementSourceAuditJob {

    private final SmartWalletProperties properties;
    private final SettlementSourceAuditService settlementSourceAuditService;

    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(cron = "${smart-wallet.audit.settlement-source-audit-cron:0 0 * * * *}",
            zone = "${smart-wallet.audit.settlement-source-audit-zone:Asia/Shanghai}")
    public void run() {
        if (!properties.isEnabled()) {
            return;
        }
        SmartWalletProperties.Audit audit = properties.getAudit();
        if (audit == null || !audit.isSettlementSourceAuditEnabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.info("[SettlementSourceAuditJob] previous run still in progress, skipping this tick");
            return;
        }
        try {
            settlementSourceAuditService.runScheduled();
        } catch (RuntimeException e) {
            log.error("[SettlementSourceAuditJob] unexpected failure: {}", e.toString(), e);
        } finally {
            running.set(false);
        }
    }
}
