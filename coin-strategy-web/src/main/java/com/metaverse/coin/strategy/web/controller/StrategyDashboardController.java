package com.metaverse.coin.strategy.web.controller;

import com.metaverse.coin.strategy.web.model.StrategyDashboardOverviewView;
import com.metaverse.coin.strategy.web.model.StrategyDetailView;
import com.metaverse.coin.strategy.web.service.StrategyDashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/dashboard/api")
public class StrategyDashboardController {

    private final StrategyDashboardService strategyDashboardService;

    public StrategyDashboardController(StrategyDashboardService strategyDashboardService) {
        this.strategyDashboardService = strategyDashboardService;
    }

    @GetMapping("/overview")
    public StrategyDashboardOverviewView overview() {
        return strategyDashboardService.overview();
    }

    @GetMapping("/strategies/{strategyCode}")
    public StrategyDetailView strategyDetail(@PathVariable String strategyCode) {
        return strategyDashboardService.detail(strategyCode);
    }
}
