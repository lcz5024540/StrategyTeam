package com.metaverse.coin.strategy.web.controller;

import com.metaverse.coin.strategy.web.model.StrategyDashboardOverviewView;
import com.metaverse.coin.strategy.web.model.StrategyDetailView;
import com.metaverse.coin.strategy.web.service.facade.StrategyReadFacade;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/dashboard/api")
public class StrategyDashboardController {

    private final StrategyReadFacade strategyDashboardService;

    public StrategyDashboardController(StrategyReadFacade strategyDashboardService) {
        this.strategyDashboardService = strategyDashboardService;
    }

    @GetMapping("/overview")
    public StrategyDashboardOverviewView overview(@RequestParam(required = false) String mode) {
        return strategyDashboardService.overview(mode);
    }

    @GetMapping("/strategies/{strategyCode}")
    public StrategyDetailView strategyDetail(@PathVariable String strategyCode,
                                             @RequestParam(required = false) String mode) {
        return strategyDashboardService.detail(strategyCode, mode);
    }
}
