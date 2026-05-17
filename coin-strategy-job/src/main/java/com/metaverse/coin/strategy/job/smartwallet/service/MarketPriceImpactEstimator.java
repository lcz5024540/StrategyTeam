package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketSnapshotEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;

/**
 * W2-T4 优化: 把 {@link ShadowFillSimulationService} 的 VWAP 逐档估算暴露成独立 estimator,
 * 让 {@link SmartWalletSignalRiskService} 的 priceImpact 三段式门控可以用"真实订单簿深度"
 * 替代现有"profitabilityEstimate.getEstimatedSlippageUsdc / stake * 10000"代理估算.
 *
 * <p><b>调用语义</b>: 输入 (side, stakeUsdc, event), 内部用最近一笔 snapshot 跑 VWAP,
 * 返回 {@link PriceImpactEstimate}; 估算失败 / 缺数据 → estimate.ok=false,
 * 调用方负责 fallback.</p>
 *
 * <p><b>开关</b>: {@code signal-risk.enable-vwap-slippage-estimator} 默认 false;
 * 启用后由 SignalRiskService 在 hot path 优先使用本 estimator. 失败兜底既有代理逻辑.</p>
 *
 * <p>详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第十九节.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketPriceImpactEstimator {

    private final SmartWalletProperties properties;
    private final ShadowFillSimulationService shadowFillSimulationService;
    private final MarketSnapshotCollectorService marketSnapshotCollectorService;

    /**
     * 估算给定意图下的预期滑点 bps.
     *
     * @param event     源 trade_event (提供 marketId / outcome / side / openedAt)
     * @param stakeUsdc 意图本金
     * @return PriceImpactEstimate; estimate.ok=false 表示无法估算, 调用方应 fallback
     */
    public PriceImpactEstimate estimate(SmartWalletTradeEventEntity event, BigDecimal stakeUsdc) {
        SmartWalletProperties.SignalRisk risk = properties.getSignalRisk();
        if (risk == null || !risk.isEnableVwapSlippageEstimator()) {
            return PriceImpactEstimate.disabled();
        }
        if (event == null || stakeUsdc == null || stakeUsdc.signum() <= 0) {
            return PriceImpactEstimate.disabled();
        }
        if (!StringUtils.hasText(event.getMarketId())) {
            return PriceImpactEstimate.disabled();
        }
        try {
            SmartWalletMarketSnapshotEntity snapshot = marketSnapshotCollectorService
                    .findLatestSnapshotAtOrBefore(event.getMarketId(), event.getOutcome(), event.getOpenedAt());
            if (snapshot == null) {
                return PriceImpactEstimate.builder()
                        .ok(false)
                        .status("MISSING_SNAPSHOT")
                        .message("no snapshot at or before event openedAt")
                        .build();
            }
            ShadowFillSimulationService.ShadowFill fill = shadowFillSimulationService.simulate(
                    event.getSide(), stakeUsdc, snapshot);
            if (fill == null) {
                return PriceImpactEstimate.builder()
                        .ok(false)
                        .status("NULL_FILL")
                        .build();
            }
            boolean usable = "OK".equals(fill.getStatus())
                    || "DRY_FALLBACK".equals(fill.getStatus());
            BigDecimal slippageBps = fill.getSlippageBps() == null
                    ? null : BigDecimal.valueOf(fill.getSlippageBps());
            return PriceImpactEstimate.builder()
                    .ok(usable && slippageBps != null)
                    .status(fill.getStatus())
                    .message(fill.getMessage())
                    .estimatedSlippageBps(slippageBps)
                    .estimatedEntryPrice(fill.getEntryPrice())
                    .estimatedEntrySize(fill.getEntrySize())
                    .estimatedEntryCostUsdc(fill.getEntryCostUsdc())
                    .build();
        } catch (RuntimeException ex) {
            log.warn("[MarketPriceImpactEstimator] failed marketId={} side={} stake={} reason={}",
                    event.getMarketId(), event.getSide(), stakeUsdc, ex.toString());
            return PriceImpactEstimate.builder().ok(false).status("ERROR").message(ex.toString()).build();
        }
    }

    /** 估算结果. */
    @Data
    @Builder
    public static class PriceImpactEstimate {
        /** true 表示 slippageBps 可用; false 表示需要 fallback 到旧逻辑. */
        private boolean ok;
        /** 详细 status: OK / DRY_FALLBACK / NO_DEPTH / MISSING_SNAPSHOT / NULL_FILL / ERROR / DISABLED. */
        private String status;
        private String message;
        private BigDecimal estimatedSlippageBps;
        private BigDecimal estimatedEntryPrice;
        private BigDecimal estimatedEntrySize;
        private BigDecimal estimatedEntryCostUsdc;

        public static PriceImpactEstimate disabled() {
            return PriceImpactEstimate.builder().ok(false).status("DISABLED").build();
        }
    }
}
