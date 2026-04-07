package com.metaverse.coin.strategy.job.strategy.controller;

import com.metaverse.coin.strategy.job.strategy.application.StrategyProductQueryService;
import com.metaverse.coin.strategy.job.strategy.model.StrategyDashboardOverviewView;
import com.metaverse.coin.strategy.job.strategy.model.StrategyDetailView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
    public StrategyDashboardOverviewView overview() {
        return strategyProductQueryService.overview();
    }

    @Operation(summary = "策略详情")
    @GetMapping("/{strategyCode}")
    public StrategyDetailView detail(@PathVariable String strategyCode) {
        return strategyProductQueryService.detail(strategyCode);
    }
}
