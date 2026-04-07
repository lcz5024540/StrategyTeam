package com.metaverse.coin.strategy.web.controller.strategy;

import com.metaverse.coin.strategy.web.model.StrategyDashboardOverviewView;
import com.metaverse.coin.strategy.web.model.StrategyDetailView;
import com.metaverse.coin.strategy.web.service.facade.StrategyReadFacade;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/strategy/api")
public class StrategyController {

    private final StrategyReadFacade strategyReadFacade;

    public StrategyController(StrategyReadFacade strategyReadFacade) {
        this.strategyReadFacade = strategyReadFacade;
    }

    @GetMapping("/overview")
    public StrategyDashboardOverviewView overview() {
        return strategyReadFacade.overview();
    }

    @GetMapping("/{strategyCode}")
    public StrategyDetailView detail(@PathVariable String strategyCode) {
        return strategyReadFacade.detail(strategyCode);
    }
}
