package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.engine.monitor.AlertType;
import com.metaverse.coin.strategy.job.smartwallet.audit.SmartWalletAuditAlertSink;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * R19（2026-06-03 全盘风险审计）：本地账 vs 链上余额对账（LIVE 专用，纯只读 + 告警）。
 *
 * <p>量化产品的日终底线之一：本地记账不能与链上真实状态长期漂移。本作业周期性地把
 * <b>执行钱包链上 USDC 余额</b>（{@code PolymarketApiClient.fetchBalance}）与
 * <b>策略本地可用资金</b>（{@link SmartWalletAccountingService#availableCapitalUsdc()}，
 * = 预算 + 已实现PnL − 手续费 − 已开仓成本 − 锁定认领额）做对比。</p>
 *
 * <p>设计为<b>低噪音、保守</b>：仅在配置了实盘执行钱包（即 LIVE 模式可用）时运行——PAPER /
 * 未配置钱包时直接跳过（paper-book 无链上余额可对账）。漂移仅在超过
 * {@code max(绝对阈值, 预算×相对阈值)} 时告警，避免"预算≠实际入金"等常态偏差刷屏。
 * 全程只读 + 发告警，不触碰任何下单/结算逻辑；异常吞掉不阻断。</p>
 *
 * <p>注：这是 v1 现金口径对账；持仓级（链上 CTF token 余额 vs 本地 OPEN 持仓）的对账
 * 见 R20，建议实盘启用前再补。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmartWalletReconciliationJob {

    private final SmartWalletProperties properties;
    private final ExecutionWalletContextLoader executionWalletContextLoader;
    private final PolymarketApiClient polymarketApiClient;
    private final SmartWalletAccountingService accountingService;
    private final SmartWalletAuditAlertSink alertSink;

    /** 漂移告警的绝对下限（USDC）：小于该值的偏差一律忽略。 */
    private static final BigDecimal RECONCILE_MIN_ABS_USDC = new BigDecimal("20");
    /** 漂移告警的相对阈值：偏差 > 预算 × 该比率 才告警（与绝对下限取大者）。 */
    private static final BigDecimal RECONCILE_REL_RATE = new BigDecimal("0.20");

    @Scheduled(fixedDelayString = "${smart-wallet.reconcile-fixed-delay-ms:3600000}")
    public void reconcile() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            ExecutionWalletContextLoader.ExecutionWalletContext ctx = executionWalletContextLoader.load();
            String address = ctx == null ? null : ctx.getAddress();
            if (!StringUtils.hasText(address)) {
                // PAPER / 未配置实盘钱包：无链上余额可对账，跳过（不告警）。
                return;
            }
            BigDecimal onChain = safe(polymarketApiClient.fetchBalance(address));
            BigDecimal localFree = safe(accountingService.availableCapitalUsdc());
            BigDecimal drift = onChain.subtract(localFree).abs();
            BigDecimal budget = safe(properties.getSignalRisk().getMaxTotalOpenCostUsdc());
            BigDecimal threshold = RECONCILE_MIN_ABS_USDC.max(budget.multiply(RECONCILE_REL_RATE));

            if (drift.compareTo(threshold) > 0) {
                log.warn("[Reconcile] DRIFT detected: onChainUsdc={} vs localFreeCapital={} drift={} > threshold={}. "
                                + "可能原因：预算与实际入金不一致 / 链上未被本地跟踪的花费或损失 / 记账漂移。请核对执行钱包与账本。",
                        onChain.setScale(2, RoundingMode.HALF_UP), localFree.setScale(2, RoundingMode.HALF_UP),
                        drift.setScale(2, RoundingMode.HALF_UP), threshold.setScale(2, RoundingMode.HALF_UP));
                safePublish(AlertType.AUDIT_RECONCILE_DRIFT, "本地账与链上余额对账漂移",
                        String.format("链上 USDC=%s, 本地可用资金=%s, 漂移=%s, 阈值=%s。请核对执行钱包与账本。",
                                onChain.setScale(2, RoundingMode.HALF_UP), localFree.setScale(2, RoundingMode.HALF_UP),
                                drift.setScale(2, RoundingMode.HALF_UP), threshold.setScale(2, RoundingMode.HALF_UP)),
                        drift, threshold);
            } else {
                log.info("[Reconcile] OK onChainUsdc={} localFreeCapital={} drift={} (threshold={})",
                        onChain.setScale(2, RoundingMode.HALF_UP), localFree.setScale(2, RoundingMode.HALF_UP),
                        drift.setScale(2, RoundingMode.HALF_UP), threshold.setScale(2, RoundingMode.HALF_UP));
            }
        } catch (Exception e) {
            log.warn("[Reconcile] reconciliation failed (swallowed): {}", e.toString());
        }
    }

    private void safePublish(AlertType type, String title, String message, BigDecimal value, BigDecimal threshold) {
        try {
            alertSink.publish(type, title, message, value, threshold);
        } catch (RuntimeException e) {
            log.warn("[Reconcile] alert publish failed (swallowed): {}", e.toString());
        }
    }

    private static BigDecimal safe(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
