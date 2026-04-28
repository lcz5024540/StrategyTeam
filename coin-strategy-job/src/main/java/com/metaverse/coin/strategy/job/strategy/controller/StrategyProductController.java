package com.metaverse.coin.strategy.job.strategy.controller;

import com.metaverse.coin.strategy.job.strategy.application.StrategyProductQueryService;
import com.metaverse.coin.strategy.job.strategy.model.StrategyConflictRowView;
import com.metaverse.coin.strategy.job.strategy.model.StrategyDashboardOverviewView;
import com.metaverse.coin.strategy.job.strategy.model.StrategyDetailView;
import com.metaverse.coin.strategy.job.strategy.model.StrategyFactorView;
import com.metaverse.coin.strategy.job.strategy.model.StrategyOrderRowView;
import com.metaverse.coin.strategy.job.strategy.model.StrategyPositionRowView;
import com.metaverse.coin.strategy.job.strategy.model.StrategySummaryDetailView;
import com.metaverse.coin.strategy.job.strategy.model.StrategyTimelinePointView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/strategy-platform/strategy")
@Tag(name = "Strategy Product", description = "平台级策略产品查询接口，供 BFF 和页面层使用")
public class StrategyProductController {

    private final StrategyProductQueryService strategyProductQueryService;

    public StrategyProductController(StrategyProductQueryService strategyProductQueryService) {
        this.strategyProductQueryService = strategyProductQueryService;
    }

    @Operation(summary = "策略总览")
    @GetMapping("/overview")
    public StrategyDashboardOverviewView overview(@RequestParam(required = false) String mode) {
        return strategyProductQueryService.overview(mode);
    }

    /**
     * @deprecated 建议前端逐步迁移到下方拆分后的子接口，减少单次请求的数据量。
     */
    @Deprecated
    @Operation(summary = "策略详情（全量，建议使用拆分子接口）")
    @GetMapping("/{strategyCode}")
    public StrategyDetailView detail(@PathVariable String strategyCode,
                                     @RequestParam(required = false) String mode) {
        return strategyProductQueryService.detail(strategyCode, mode);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 拆分子接口 —— 前端按需请求，首屏只需 summary，其余延迟加载
    // ──────────────────────────────────────────────────────────────────────────

    @Operation(summary = "策略概要（summary + metrics + insights）",
            description = "轻量接口，仅返回概要、指标与运营提示，适合首屏快速渲染。")
    @GetMapping("/{strategyCode}/summary")
    public StrategySummaryDetailView summary(@PathVariable String strategyCode,
                                             @RequestParam(required = false) String mode) {
        return strategyProductQueryService.summaryDetail(strategyCode, mode);
    }

    @Operation(summary = "当前持仓列表")
    @GetMapping("/{strategyCode}/positions")
    public List<StrategyPositionRowView> positions(@PathVariable String strategyCode,
                                                    @RequestParam(required = false) String mode) {
        return strategyProductQueryService.positions(strategyCode, mode);
    }

    @Operation(summary = "待结算持仓列表")
    @GetMapping("/{strategyCode}/next-settlements")
    public List<StrategyPositionRowView> nextSettlements(@PathVariable String strategyCode,
                                                          @RequestParam(defaultValue = "8") Integer limit,
                                                          @RequestParam(required = false) String mode) {
        return strategyProductQueryService.nextSettlements(strategyCode, limit, mode);
    }

    @Operation(summary = "最近订单列表")
    @GetMapping("/{strategyCode}/orders")
    public List<StrategyOrderRowView> recentOrders(@PathVariable String strategyCode,
                                                    @RequestParam(defaultValue = "30") Integer limit,
                                                    @RequestParam(required = false) String mode) {
        return strategyProductQueryService.recentOrders(strategyCode, limit, mode);
    }

    @Operation(summary = "收益时间线")
    @GetMapping("/{strategyCode}/timeline")
    public List<StrategyTimelinePointView> timeline(@PathVariable String strategyCode,
                                                     @RequestParam(defaultValue = "30") Integer days,
                                                     @RequestParam(required = false) String mode) {
        return strategyProductQueryService.timeline(strategyCode, days, mode);
    }

    @Operation(summary = "冲突市场列表")
    @GetMapping("/{strategyCode}/conflicts")
    public List<StrategyConflictRowView> conflicts(@PathVariable String strategyCode,
                                                    @RequestParam(defaultValue = "10") Integer limit,
                                                    @RequestParam(required = false) String mode) {
        return strategyProductQueryService.conflicts(strategyCode, limit, mode);
    }

    @Operation(summary = "策略因子列表")
    @GetMapping("/{strategyCode}/factors")
    public List<StrategyFactorView> factors(@PathVariable String strategyCode,
                                            @RequestParam(required = false) String mode,
                                            @RequestParam(defaultValue = "30D") String window) {
        return strategyProductQueryService.factors(strategyCode, mode, window);
    }

    @Operation(summary = "策略因子详情")
    @GetMapping("/{strategyCode}/factors/{factorCode}")
    public StrategyFactorView factor(@PathVariable String strategyCode,
                                     @PathVariable String factorCode,
                                     @RequestParam(required = false) String mode,
                                     @RequestParam(defaultValue = "30D") String window) {
        return strategyProductQueryService.factor(strategyCode, factorCode, mode, window);
    }
}
