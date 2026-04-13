package com.metaverse.coin.strategy.job.hedge.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.HedgeArbitrageLogMapper;
import com.metaverse.coin.strategy.common.dao.mapper.HedgeOrderMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.HedgeArbitrageLogEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.HedgeOrderEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.HedgePositionEntity;
import com.metaverse.coin.strategy.job.hedge.config.HedgeProperties;
import com.metaverse.coin.strategy.job.hedge.model.HedgeDashboardView;
import com.metaverse.coin.strategy.job.hedge.model.HedgePositionView;
import com.metaverse.coin.strategy.job.hedge.service.GlobalRiskGuard;
import com.metaverse.coin.strategy.job.hedge.service.HedgePositionTracker;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 对冲策略管理接口
 *
 * 所有端点与跟单策略接口在独立的 /api/hedge/* 路径下，互不干扰。
 */
@Tag(name = "Hedge Strategy", description = "Polymarket 对冲套利策略管理")
@RestController
@RequestMapping("/api/hedge")
@RequiredArgsConstructor
public class HedgeController {

    private final HedgeProperties properties;
    private final HedgePositionTracker positionTracker;
    private final GlobalRiskGuard globalRiskGuard;
    private final HedgeArbitrageLogMapper logMapper;
    private final HedgeOrderMapper orderMapper;

    @Operation(summary = "对冲策略概览 Dashboard")
    @GetMapping("/dashboard")
    public HedgeDashboardView dashboard() {
        List<HedgePositionEntity> openPositions = positionTracker.getOpenPositions();
        BigDecimal totalOpenExposure = openPositions.stream()
                .map(p -> p.getTotalCostUsdc() != null ? p.getTotalCostUsdc() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal expectedProfit = openPositions.stream()
                .map(p -> p.getExpectedProfitUsdc() != null ? p.getExpectedProfitUsdc() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long totalSettled = positionTracker.getSettlementPendingPositions(0).size();  // settled count
        BigDecimal totalPnl = positionTracker.totalRealizedPnlUsdc();

        GlobalRiskGuard.GlobalExposureSummary exposure = globalRiskGuard.getSummary();

        return HedgeDashboardView.builder()
                .enabled(properties.isEnabled())
                .autoExecutionEnabled(properties.isAutoExecutionEnabled())
                .openPositions(openPositions.size())
                .totalOpenExposureUsdc(totalOpenExposure)
                .expectedTotalProfitUsdc(expectedProfit)
                .totalSettled((int) totalSettled)
                .totalRealizedPnlUsdc(totalPnl)
                .globalFollowExposureUsdc(exposure.followExposureUsdc())
                .globalHedgeExposureUsdc(exposure.hedgeExposureUsdc())
                .globalTotalExposureUsdc(exposure.totalExposureUsdc())
                .globalMaxExposureUsdc(exposure.globalMaxExposureUsdc())
                .openPositionList(openPositions.stream().map(this::toView).collect(Collectors.toList()))
                .build();
    }

    @Operation(summary = "OPEN 持仓列表")
    @GetMapping("/positions/open")
    public List<HedgePositionView> openPositions() {
        return positionTracker.getOpenPositions().stream()
                .map(this::toView)
                .collect(Collectors.toList());
    }

    @Operation(summary = "结算待处理持仓列表")
    @GetMapping("/positions/settling")
    public List<HedgePositionView> settlingPositions() {
        return positionTracker.getSettlementPendingPositions(100).stream()
                .map(this::toView)
                .collect(Collectors.toList());
    }

    @Operation(summary = "指定持仓的订单明细")
    @GetMapping("/positions/{positionId}/orders")
    public List<HedgeOrderEntity> positionOrders(@PathVariable Long positionId) {
        return orderMapper.selectList(new LambdaQueryWrapper<HedgeOrderEntity>()
                .eq(HedgeOrderEntity::getHedgePositionId, positionId)
                .orderByAsc(HedgeOrderEntity::getId));
    }

    @Operation(summary = "套利扫描日志（最近 N 条）")
    @GetMapping("/arbitrage-logs")
    public List<HedgeArbitrageLogEntity> arbitrageLogs(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String action) {
        LambdaQueryWrapper<HedgeArbitrageLogEntity> query = new LambdaQueryWrapper<HedgeArbitrageLogEntity>()
                .orderByDesc(HedgeArbitrageLogEntity::getScannedAt)
                .last("limit " + Math.min(limit, 200));
        if (action != null && !action.isBlank()) {
            query.eq(HedgeArbitrageLogEntity::getAction, action.toUpperCase());
        }
        return logMapper.selectList(query);
    }

    @Operation(summary = "全局风控概览（跟单 + 对冲合计敞口）")
    @GetMapping("/risk/global-exposure")
    public GlobalRiskGuard.GlobalExposureSummary globalExposure() {
        return globalRiskGuard.getSummary();
    }

    @Operation(summary = "当前对冲策略配置（只读）")
    @GetMapping("/config")
    public HedgeProperties config() {
        return properties;
    }

    private HedgePositionView toView(HedgePositionEntity e) {
        return HedgePositionView.builder()
                .id(e.getId())
                .marketId(e.getMarketId())
                .question(e.getQuestion())
                .status(e.getStatus())
                .yesEntryPrice(e.getYesEntryPrice())
                .noEntryPrice(e.getNoEntryPrice())
                .probDeviation(e.getProbDeviation())
                .grossEdgeRate(e.getGrossEdgeRate())
                .quantity(e.getQuantity())
                .totalCostUsdc(e.getTotalCostUsdc())
                .expectedProfitUsdc(e.getExpectedProfitUsdc())
                .realizedPnlUsdc(e.getRealizedPnlUsdc())
                .finalOutcome(e.getFinalOutcome())
                .openedAt(e.getOpenedAt())
                .settledAt(e.getSettledAt())
                .build();
    }
}
